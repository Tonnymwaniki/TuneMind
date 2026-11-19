import { normalizeSpotifyUri, resolveCanonicalTrackIdentity } from '../identity/trackIdentity';

export const DAY_MS = 86_400_000;

export interface VaultTrackPayload {
  id: string;
  title: string;
  artist: string;
  trackId: string;
  spotifyUri?: string;
  sourcePackage: string;
  quarantinedAtMs: number;
  totalDays: number;
  cooldownDaysRemaining: number;
  recoveryProgress: number;
  satietyScore: number;
  freshnessPercent: number;
  entryReason: string;
  isAutoQuarantined: boolean;
  lastPenaltyPlayAtMs?: number;
  updatedAtMs: number;
}

export interface GraduatedVaultTrack {
  id: string;
  trackId: string;
  title: string;
  artist: string;
  spotifyUri?: string;
  graduatedAtMs: number;
  totalRestedDays: number;
  previousSatietyScore: number;
  entryReason: string;
}

export interface TrackFatigueMetrics {
  effectiveSatiety: number;
  freshnessPercent: number;
  recentPlays24h: number;
  recentPlays72h: number;
  recentPlays7d: number;
  shouldAutoQuarantine: boolean;
  recommendedCooldownDays: number;
  entryReason: string;
}

export type FreshCategory = 'COMEBACK' | 'UNPLAYED_GEM' | 'PEAK_FRESH' | 'WARMING_UP';

export interface FreshReadinessResult {
  readinessScore: number;
  baseFreshnessPercent: number;
  category: FreshCategory;
  freshReason: string;
}

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}

export function normalizeVaultKey(title: string, artist: string): string {
  return `${(title || '').trim().toLowerCase()}::${(artist || '').trim().toLowerCase()}`;
}

export class RestingVaultEngine {
  /**
   * Auto-quarantine entry satiety threshold based on user repetition sensitivity (0.20..1.0).
   * At default sensitivity (0.75) => threshold = 70% satiety (~30% freshness).
   */
  public static computeEntryThreshold(sensitivity = 0.75): number {
    const s = clamp(Number.isFinite(sensitivity) ? sensitivity : 0.75, 0.2, 1.0);
    return Math.round(86 - 22 * s);
  }

  /**
   * Adaptive cooldown duration in days [2.0, 7.0] scaled by overplay severity and repetition sensitivity.
   */
  public static computeCooldownDurationDays(params: {
    satietyScore: number;
    recentPlays7d?: number;
    recentPlays24h?: number;
    sensitivity?: number;
  }): number {
    const satiety = clamp(params.satietyScore || 75, 25, 100);
    const sensitivity = clamp(params.sensitivity ?? 0.75, 0.2, 1.0);
    const excess7d = Math.max(0, (params.recentPlays7d ?? 3) - 2);
    const bingeBonus = (params.recentPlays24h ?? 0) >= 3 ? 0.6 : 0;

    const rawDays =
      1.5 +
      (Math.max(0, satiety - 50) / 50) * 3.5 +
      Math.min(1.5, excess7d * 0.35) +
      (1.2 * sensitivity - 0.5) +
      bingeBonus;

    return Math.round(clamp(rawDays, 2.0, 7.0) * 10) / 10;
  }

  /**
   * Temporal exponentially-decayed satiety kernel with 24h binge density & skip-burnout multiplier.
   */
  public static evaluateTrackFatigue(params: {
    playTimestampsMs: number[];
    listenedRatios?: number[];
    sensitivity?: number;
    lastRestoredAtMs?: number;
    nowMs?: number;
  }): TrackFatigueMetrics {
    const nowMs = params.nowMs ?? Date.now();
    const s = clamp(params.sensitivity ?? 0.75, 0.2, 1.0);
    const restoredCutoff = params.lastRestoredAtMs ?? 0;

    const halfLifeHours = 48 + 48 * s; // 57.6h at 0.20, 84h at 0.75, 96h at 1.00
    const weightPerPlay = 13 + 12 * s; // ~22 at default 0.75

    let decayedMass = 0;
    let recentPlays24h = 0;
    let recentPlays72h = 0;
    let recentPlays7d = 0;
    let earlySkips = 0;

    const timestamps = params.playTimestampsMs || [];
    const ratios = params.listenedRatios || [];

    for (let i = 0; i < timestamps.length; i++) {
      const ts = Number(timestamps[i] || 0);
      if (!Number.isFinite(ts) || ts <= 0) continue;
      if (restoredCutoff > 0 && ts <= restoredCutoff) {
        // Post-graduation / post-restore clean baseline for historical plays before restore
        continue;
      }

      const ageMs = Math.max(0, nowMs - ts);
      const ageHours = ageMs / 3_600_000;
      const decay = Math.pow(2, -ageHours / halfLifeHours);

      const ratio = ratios[i];
      const skipMult = typeof ratio === 'number' && ratio > 0 && ratio < 0.35 ? 1.25 : 1.0;
      if (skipMult > 1.0 && ageHours <= 72) earlySkips++;

      decayedMass += decay * skipMult;
      if (ageMs <= DAY_MS) recentPlays24h++;
      if (ageMs <= 3 * DAY_MS) recentPlays72h++;
      if (ageMs <= 7 * DAY_MS) recentPlays7d++;
    }

    const burstPenalty = recentPlays24h >= 2 ? Math.min(28, (recentPlays24h - 1) * (6 + 6 * s)) : 0;
    const effectiveSatiety = Math.round(clamp(decayedMass * weightPerPlay + burstPenalty, 0, 99));
    const freshnessPercent = clamp(100 - effectiveSatiety, 6, 99);

    const entryThreshold = this.computeEntryThreshold(s);
    const bingeCap = s >= 0.65 ? 3 : 4;
    const withinRestoreGrace = restoredCutoff > 0 && nowMs - restoredCutoff < 12 * 3_600_000;

    const hitSatietyGate = effectiveSatiety >= entryThreshold && (recentPlays72h >= 2 || recentPlays7d >= 3);
    const hitBingeGate = recentPlays24h >= bingeCap && effectiveSatiety >= 58;
    const shouldAutoQuarantine = !withinRestoreGrace && (hitSatietyGate || hitBingeGate);

    const entryReason = hitBingeGate
      ? `Binge guard · ${recentPlays24h} plays in 24h (${effectiveSatiety}% satiety)`
      : hitSatietyGate
        ? earlySkips >= 2
          ? `Fatigue + early skips (${effectiveSatiety}% satiety)`
          : `Auto-rested · ${recentPlays7d} recent plays (${effectiveSatiety}% satiety)`
        : 'Manual Vault Lock';

    const recommendedCooldownDays = this.computeCooldownDurationDays({
      satietyScore: Math.max(effectiveSatiety, 65),
      recentPlays7d,
      recentPlays24h,
      sensitivity: s
    });

    return {
      effectiveSatiety,
      freshnessPercent,
      recentPlays24h,
      recentPlays72h,
      recentPlays7d,
      shouldAutoQuarantine,
      recommendedCooldownDays,
      entryReason
    };
  }

  /**
   * Computes continuous recovery state from `quarantinedAtMs` and `totalDays`.
   * Ease-out curve R(u) = 1 - (1 - u)^1.25 reaches 1.0 when elapsed >= totalDays.
   */
  public static computeRecoveryState(
    quarantinedAtMs: number,
    totalDays: number,
    baseSatietyScore: number,
    nowMs = Date.now()
  ): {
    recoveryProgress: number;
    cooldownDaysRemaining: number;
    freshnessPercent: number;
    isGraduated: boolean;
  } {
    const safeTotalDays = clamp(Number.isFinite(totalDays) && totalDays > 0 ? totalDays : 3.5, 0.5, 14.0);
    const elapsedMs = Math.max(0, nowMs - quarantinedAtMs);
    const totalMs = safeTotalDays * DAY_MS;
    const u = clamp(elapsedMs / totalMs, 0, 1);

    const recoveryProgress = u >= 0.999 ? 1.0 : Math.round(clamp(1 - Math.pow(1 - u, 1.25), 0, 1) * 1000) / 1000;
    const remainingDaysRaw = Math.max(0, safeTotalDays * (1 - u));
    const cooldownDaysRemaining = Math.round(remainingDaysRaw * 10) / 10;

    const currentSatiety = Math.max(5, Math.round(clamp(baseSatietyScore, 40, 99) * Math.pow(Math.max(0, 1 - recoveryProgress), 0.85)));
    const freshnessPercent = clamp(100 - currentSatiety, 8, 99);

    return {
      recoveryProgress,
      cooldownDaysRemaining,
      freshnessPercent,
      isGraduated: u >= 1.0 || recoveryProgress >= 0.999
    };
  }

  /**
   * Normalizes, updates real-time recovery curves, applies any restored exclusions,
   * and separates still-resting tracks from newly graduated ones.
   */
  public static reconcileVaultTracks(
    rawItems: Array<Record<string, any>>,
    nowMs = Date.now(),
    restoredTrackTimestamps?: Record<string, number>
  ): {
    activeVault: VaultTrackPayload[];
    graduatedTracks: GraduatedVaultTrack[];
  } {
    const seenKeys = new Set<string>();
    const activeVault: VaultTrackPayload[] = [];
    const graduatedTracks: GraduatedVaultTrack[] = [];

    for (let i = 0; i < (rawItems || []).length; i++) {
      const raw = rawItems[i];
      if (!raw || !raw.title) continue;

      const cleanTitle = String(raw.title).trim();
      const cleanArtist = String(raw.artist || 'Unknown Artist').trim();
      if (!cleanTitle) continue;

      const dedupeKey = normalizeVaultKey(cleanTitle, cleanArtist);
      if (seenKeys.has(dedupeKey)) continue;
      seenKeys.add(dedupeKey);

      const identity = resolveCanonicalTrackIdentity({
        trackId: raw.trackId || raw.track_id,
        spotifyUri: raw.spotifyUri || raw.spotify_uri,
        title: cleanTitle,
        artist: cleanArtist
      });

      const rawTotalDays = Number(raw.totalDays ?? raw.total_days ?? 3.5);
      const totalDays = clamp(Number.isFinite(rawTotalDays) && rawTotalDays > 0 ? rawTotalDays : 3.5, 0.5, 14.0);

      let quarantinedAtMs = Number(raw.quarantinedAtMs ?? raw.quarantined_at_ms ?? 0);
      if (!Number.isFinite(quarantinedAtMs) || quarantinedAtMs <= 0) {
        const remDays = Number(raw.cooldownDaysRemaining ?? raw.cooldown_days_remaining);
        const pct = Number(raw.recoveryProgress ?? raw.recovery_percent);
        if (Number.isFinite(remDays) && remDays >= 0) {
          const elapsedDays = Math.max(0, totalDays - remDays);
          quarantinedAtMs = Math.round(nowMs - elapsedDays * DAY_MS);
        } else if (Number.isFinite(pct) && pct >= 0) {
          const frac = pct > 1 ? pct / 100 : pct;
          quarantinedAtMs = Math.round(nowMs - clamp(frac, 0, 1) * totalDays * DAY_MS);
        } else {
          quarantinedAtMs = nowMs;
        }
      }

      // Skip tracks explicitly restored more recently than their quarantine timestamp
      const restoredAt =
        restoredTrackTimestamps?.[identity.trackId] ??
        restoredTrackTimestamps?.[dedupeKey] ??
        0;
      if (restoredAt > quarantinedAtMs) {
        continue;
      }

      const satietyScore = Math.round(clamp(Number(raw.satietyScore ?? raw.satiety_score ?? 82), 40, 99));
      const entryReason = String(raw.entryReason || raw.entry_reason || (raw.isAutoQuarantined ? 'Overplay fatigue' : 'Manual Vault Lock'));
      const isAutoQuarantined = Boolean(raw.isAutoQuarantined ?? raw.is_auto_quarantined ?? false);
      const sourcePackage = String(raw.sourcePackage || raw.source_package || 'unknown_player');
      const id = String(raw.id || `vault-${identity.trackId}`);

      const rec = this.computeRecoveryState(quarantinedAtMs, totalDays, satietyScore, nowMs);
      if (rec.isGraduated) {
        graduatedTracks.push({
          id,
          trackId: identity.trackId,
          title: cleanTitle,
          artist: cleanArtist,
          spotifyUri: normalizeSpotifyUri(identity.spotifyUri),
          graduatedAtMs: nowMs,
          totalRestedDays: totalDays,
          previousSatietyScore: satietyScore,
          entryReason
        });
        continue;
      }

      activeVault.push({
        id,
        title: cleanTitle,
        artist: cleanArtist,
        trackId: identity.trackId,
        spotifyUri: normalizeSpotifyUri(identity.spotifyUri),
        sourcePackage,
        quarantinedAtMs,
        totalDays,
        cooldownDaysRemaining: rec.cooldownDaysRemaining,
        recoveryProgress: rec.recoveryProgress,
        satietyScore,
        freshnessPercent: rec.freshnessPercent,
        entryReason,
        isAutoQuarantined,
        lastPenaltyPlayAtMs: Number(raw.lastPenaltyPlayAtMs ?? raw.last_penalty_play_at_ms ?? 0) || undefined,
        updatedAtMs: nowMs
      });
    }

    return { activeVault, graduatedTracks };
  }

  /**
   * Computes mathematical Fresh Readiness [0, 100] & category for tracks outside the active Vault.
   * Combines temporal base freshness, dormancy/rediscovery spacing, Vault graduation comeback boost,
   * and library affinity / completion signals.
   */
  public static computeFreshReadiness(params: {
    baseFreshnessPercent: number;
    playCount: number;
    lastPlayedAtMs?: number;
    isLikedOrSaved?: boolean;
    isGraduatedComeback?: boolean;
    restedDays?: number;
    avgCompletionRatio?: number;
    sourceLabel?: string;
    nowMs?: number;
  }): FreshReadinessResult {
    const nowMs = params.nowMs ?? Date.now();
    const plays = Math.max(0, Math.round(params.playCount || 0));
    const baseFresh = clamp(Math.round(params.baseFreshnessPercent ?? 96), 6, 99);
    const lastPlayed = params.lastPlayedAtMs || 0;
    const daysSinceLast = lastPlayed > 0 ? Math.max(0, (nowMs - lastPlayed) / DAY_MS) : 0;

    const dormancyBonus = plays === 0
      ? 6
      : clamp(Math.log1p(daysSinceLast) * 4.2, 0, 10);
    const comebackBonus = params.isGraduatedComeback ? 10 : 0;
    const affinityBonus = params.isLikedOrSaved ? 4 : 0;
    const completionAdj =
      typeof params.avgCompletionRatio === 'number' && params.avgCompletionRatio > 0
        ? params.avgCompletionRatio >= 0.75
          ? 3
          : params.avgCompletionRatio < 0.35
            ? -6
            : 0
        : 0;

    const readinessScore = Math.round(
      clamp(baseFresh + dormancyBonus + comebackBonus + affinityBonus + completionAdj, 6, 100)
    );

    let category: FreshCategory;
    let freshReason: string;
    if (params.isGraduatedComeback) {
      category = 'COMEBACK';
      const restedTxt = params.restedDays && params.restedDays > 0 ? `${params.restedDays.toFixed(1)}d` : 'fully';
      freshReason = `Graduated comeback · Rested ${restedTxt}`;
    } else if (plays === 0) {
      category = 'UNPLAYED_GEM';
      const prefix = params.sourceLabel || (params.isLikedOrSaved ? 'Liked Song' : 'Library Gem');
      freshReason = `${prefix} · Untouched`;
    } else if (baseFresh >= 75) {
      category = 'PEAK_FRESH';
      if (daysSinceLast >= 2) {
        freshReason = `Crisp · ${Math.floor(daysSinceLast)}d since last play`;
      } else {
        freshReason = `Low rotation · ${plays} ${plays === 1 ? 'play' : 'plays'}`;
      }
    } else {
      category = 'WARMING_UP';
      freshReason = `Approaching Vault · ${baseFresh}% fresh`;
    }

    return {
      readinessScore,
      baseFreshnessPercent: baseFresh,
      category,
      freshReason
    };
  }
}
