import { getLiveSupabaseClient, isSupabaseLive } from '../../config/supabase';
import { EventStore } from '../events/EventStore';
import {
  buildNormalizedMatchKey,
  coerceNonNegativePlayCount,
  normalizeSpotifyUri,
  resolveCanonicalTrackIdentity
} from '../identity/trackIdentity';
import {
  GraduatedVaultTrack,
  RestingVaultEngine,
  VaultTrackPayload
} from '../vault/RestingVaultEngine';

export interface SupabaseConnectionOverride {
  supabaseUrl?: string;
  supabaseKey?: string;
}

export interface LearnedTrackEntry {
  trackId: string;
  title: string;
  artist: string;
  spotifyUri?: string;
  playCount: number;
  source?: string;
  lastPlayedAt?: string;
}

export interface AgentInterventionRecord {
  user_id: string;
  trigger_transcript: string;
  computed_satiety: number;
  agent_tts_response: string;
  os_intent: string;
  target_search_query: string;
  action?: string;
  track_id?: string;
  track_title?: string;
  track_artist?: string;
  spotify_uri?: string;
  circadian_daypart?: string;
  created_at?: string;
}

export interface LongTermIntelligenceSummary {
  userId: string;
  isLiveSupabaseConnected: boolean;
  intelligenceMaturityLevel: 'SEEDING' | 'DEVELOPING' | 'CALIBRATED' | 'DEEP_NEURAL_SYNC';
  totalHistoricalInterventions: number;
  totalTracksLearned: number;
  approvedTracks: Array<{ trackId?: string; title: string; artist: string; spotifyUri?: string; approvedAt: string }>;
  rejectedTracks: Array<{ trackId?: string; title: string; artist: string; rejectedAt: string }>;
  topArtistsOverTime: Array<{ artist: string; totalPlays: number }>;
  topTracksOverTime: LearnedTrackEntry[];
  recentInterventions: Array<{
    userSaid: string;
    agentReplied: string;
    action: string;
    targetTrack: string;
    satiety: number;
    createdAt: string;
  }>;
  circadianPatternNotes: string[];
  allListenedTitles: string[];
  allListenedUris: string[];
  vaultTracks?: VaultTrackPayload[];
  graduatedVaultTracks?: GraduatedVaultTrack[];
}

export class SupabaseIntelligenceStore {
  private static inMemoryInterventions: AgentInterventionRecord[] = [];
  private static inMemoryApprovedTracks = new Map<string, { trackId?: string; title: string; artist: string; spotifyUri?: string; approvedAt: string }>();
  private static inMemoryRejectedTracks = new Map<string, { trackId?: string; title: string; artist: string; rejectedAt: string }>();
  private static inMemoryLearnedTracks = new Map<string, LearnedTrackEntry>();
  private static inMemoryVaultTracks = new Map<string, VaultTrackPayload[]>();
  private static inMemoryGraduatedVaultTracks = new Map<string, GraduatedVaultTrack[]>();
  private static inMemoryRestoredTrackTimestamps = new Map<string, Record<string, number>>();
  private static lastSupabaseHydrationMs = 0;

  public static clearInMemoryStateForTests(): void {
    this.inMemoryInterventions = [];
    this.inMemoryApprovedTracks.clear();
    this.inMemoryRejectedTracks.clear();
    this.inMemoryLearnedTracks.clear();
    this.inMemoryVaultTracks.clear();
    this.inMemoryGraduatedVaultTracks.clear();
    this.inMemoryRestoredTrackTimestamps.clear();
    this.lastSupabaseHydrationMs = 0;
  }

  public static normalizeUserId(rawUserId?: string, rawEmail?: string): string {
    const email = (rawEmail || '').trim().toLowerCase();
    if (email && email.includes('@')) return email;
    const uid = (rawUserId || '').trim();
    if (uid && uid !== 'anonymous' && uid !== 'null' && uid !== 'undefined') return uid;
    return 'tunemind-default-user';
  }

  private static upsertLearnedTrack(
    userPrefix: string,
    raw: {
      title: string;
      artist?: string;
      trackId?: string;
      spotifyUri?: string;
      playCount?: unknown;
      source?: string;
      lastPlayedAt?: string;
    }
  ): void {
    const cleanTitle = raw.title.trim();
    if (!cleanTitle) return;
    const cleanArtist = (raw.artist || 'Unknown Artist').trim();
    const identity = resolveCanonicalTrackIdentity({
      trackId: raw.trackId,
      spotifyUri: raw.spotifyUri,
      title: cleanTitle,
      artist: cleanArtist
    });
    const normKey = buildNormalizedMatchKey(cleanTitle, cleanArtist);
    const key = `${userPrefix}${normKey || `${cleanTitle.toLowerCase()}::${cleanArtist.toLowerCase()}`}`;
    const existing = this.inMemoryLearnedTracks.get(key);
    const count = coerceNonNegativePlayCount(raw.playCount);
    if (!existing) {
      this.inMemoryLearnedTracks.set(key, {
        trackId: identity.trackId,
        title: cleanTitle,
        artist: cleanArtist,
        spotifyUri: identity.spotifyUri,
        playCount: count,
        source: raw.source || 'com.spotify.music',
        lastPlayedAt: raw.lastPlayedAt
      });
      return;
    }
    existing.playCount = Math.max(existing.playCount, count);
    if ((!existing.trackId || existing.trackId.startsWith('trk:')) && !identity.trackId.startsWith('trk:')) {
      existing.trackId = identity.trackId;
    }
    if (!existing.spotifyUri && identity.spotifyUri) {
      existing.spotifyUri = identity.spotifyUri;
    }
    if (raw.lastPlayedAt) {
      existing.lastPlayedAt = raw.lastPlayedAt;
    }
  }

  /**
   * Syncs a batch of Spotify / OS listening history tracks from the Android phone into:
   * 1. Live Supabase (`listening_events`, `user_taste_genome`, and `agent_interventions` snapshot)
   * 2. Local persistent `EventStore`
   */
  public static async syncTracksAndTelemetryToSupabase(
    userId: string,
    tracks: Array<{ title: string; artist: string; trackId?: string; spotifyUri?: string; playCount?: number; source?: string }>,
    options?: {
      hearingTelemetry?: {
        estimated_db?: number;
        safe_headroom_db?: number;
        daily_exposure_minutes?: number;
        output_device?: string;
      };
      vaultTracks?: Array<Record<string, any>>;
      restoredTrackTimestamps?: Record<string, number>;
      override?: SupabaseConnectionOverride;
    }
  ): Promise<{ syncedTracksCount: number; liveSupabaseStored: boolean; reconciledVaultCount: number; graduatedCount: number }> {
    const cleanUserId = this.normalizeUserId(userId);
    const validTracks = (tracks || []).filter(
      t =>
        t &&
        t.title &&
        t.title.trim().length > 0 &&
        t.title !== 'Standing By' &&
        t.title !== 'Active Session' &&
        t.title !== 'Deep Flow State'
    );

    const nowIso = new Date().toISOString();

    for (const t of validTracks) {
      this.upsertLearnedTrack(`${cleanUserId}::`, {
        title: t.title,
        artist: t.artist,
        trackId: t.trackId,
        spotifyUri: t.spotifyUri,
        playCount: t.playCount,
        source: t.source,
        lastPlayedAt: nowIso
      });
    }

    // Mirror into persistent EventStore
    try {
      const eventStore = new EventStore();
      eventStore.syncClientTracks(
        validTracks.map(t => {
          const identity = resolveCanonicalTrackIdentity({
            trackId: t.trackId,
            spotifyUri: t.spotifyUri,
            title: t.title,
            artist: t.artist
          });
          return {
            title: t.title,
            artist: t.artist,
            source: t.source || 'com.spotify.music',
            playCount: coerceNonNegativePlayCount(t.playCount),
            trackId: identity.trackId,
            spotifyUri: identity.spotifyUri
          };
        }),
        cleanUserId
      );
    } catch (e) {
      console.warn('[SupabaseIntelligenceStore] Local EventStore mirror notice:', e);
    }

    // Reconcile Vault mathematically on server using live recovery curve & graduation rules
    let reconciledVault: VaultTrackPayload[] = this.inMemoryVaultTracks.get(cleanUserId) || [];
    let graduatedVault: GraduatedVaultTrack[] = this.inMemoryGraduatedVaultTracks.get(cleanUserId) || [];
    if (options?.restoredTrackTimestamps && typeof options.restoredTrackTimestamps === 'object') {
      const mergedRestored = {
        ...(this.inMemoryRestoredTrackTimestamps.get(cleanUserId) || {}),
        ...options.restoredTrackTimestamps
      };
      this.inMemoryRestoredTrackTimestamps.set(cleanUserId, mergedRestored);
    }
    if (Array.isArray(options?.vaultTracks)) {
      const res = RestingVaultEngine.reconcileVaultTracks(
        options.vaultTracks,
        Date.now(),
        this.inMemoryRestoredTrackTimestamps.get(cleanUserId)
      );
      reconciledVault = res.activeVault;
      this.inMemoryVaultTracks.set(cleanUserId, reconciledVault);
      if (res.graduatedTracks.length > 0) {
        graduatedVault = [...res.graduatedTracks, ...graduatedVault].slice(0, 30);
        this.inMemoryGraduatedVaultTracks.set(cleanUserId, graduatedVault);
      }
    } else if (reconciledVault.length > 0) {
      const res = RestingVaultEngine.reconcileVaultTracks(
        reconciledVault,
        Date.now(),
        this.inMemoryRestoredTrackTimestamps.get(cleanUserId)
      );
      reconciledVault = res.activeVault;
      this.inMemoryVaultTracks.set(cleanUserId, reconciledVault);
      if (res.graduatedTracks.length > 0) {
        graduatedVault = [...res.graduatedTracks, ...graduatedVault].slice(0, 30);
        this.inMemoryGraduatedVaultTracks.set(cleanUserId, graduatedVault);
      }
    }

    const client = getLiveSupabaseClient(options?.override?.supabaseUrl, options?.override?.supabaseKey);
    if (!client) {
      return {
        syncedTracksCount: validTracks.length,
        liveSupabaseStored: false,
        reconciledVaultCount: reconciledVault.length,
        graduatedCount: graduatedVault.length
      };
    }

    let storedToSupabase = false;

    // 1. Try upserting into `listening_events` table if present in Supabase
    if (validTracks.length > 0) {
      try {
        const rows = validTracks.slice(0, 40).map((t, idx) => {
          const identity = resolveCanonicalTrackIdentity({
            trackId: t.trackId,
            spotifyUri: t.spotifyUri,
            title: t.title,
            artist: t.artist
          });
          return {
            event_id: `sync_${cleanUserId}_${t.title.toLowerCase().replace(/[^a-z0-9]/g, '_').slice(0, 32)}_${(t.artist || '').toLowerCase().replace(/[^a-z0-9]/g, '_').slice(0, 20)}`,
            user_id: cleanUserId,
            track_id: identity.trackId,
            track_title: t.title.trim(),
            track_artist: (t.artist || 'Unknown Artist').trim(),
            spotify_uri: identity.spotifyUri || null,
            play_count: coerceNonNegativePlayCount(t.playCount),
            media_source: t.source || 'com.spotify.music',
            observed_at: new Date(Date.now() - idx * 1000).toISOString()
          };
        });
        const { error } = await client.from('listening_events').upsert(rows, { onConflict: 'event_id' });
        if (!error) {
          storedToSupabase = true;
        }
      } catch {
        // Table may not exist yet; handled below via universal agent_interventions + user_taste_genome
      }
    }

    // 2. Try upserting aggregated Taste Genome into `user_taste_genome` table if present
    try {
      const userPrefix = `${cleanUserId}::`;
      const allLearned = Array.from(this.inMemoryLearnedTracks.entries())
        .filter(([k]) => k.startsWith(userPrefix))
        .map(([, v]) => v)
        .sort((a, b) => b.playCount - a.playCount);
      const topArtistsMap = new Map<string, number>();
      for (const trk of allLearned) {
        if (trk.artist && trk.artist !== 'Unknown Artist' && trk.playCount > 0) {
          topArtistsMap.set(trk.artist, (topArtistsMap.get(trk.artist) || 0) + trk.playCount);
        }
      }
      const topArtists = Array.from(topArtistsMap.entries())
        .sort((a, b) => b[1] - a[1])
        .slice(0, 15)
        .map(([artist, plays]) => ({ artist, plays }));

      const approvedForUser = Array.from(this.inMemoryApprovedTracks.entries())
        .filter(([k]) => k.startsWith(userPrefix))
        .map(([, v]) => v)
        .slice(-30);
      const rejectedForUser = Array.from(this.inMemoryRejectedTracks.entries())
        .filter(([k]) => k.startsWith(userPrefix))
        .map(([, v]) => v)
        .slice(-30);

      const { error } = await client.from('user_taste_genome').upsert(
        {
          user_id: cleanUserId,
          top_tracks_json: JSON.stringify(allLearned.slice(0, 40)),
          top_artists_json: JSON.stringify(topArtists),
          approved_tracks_json: JSON.stringify(approvedForUser),
          rejected_tracks_json: JSON.stringify(rejectedForUser),
          hearing_telemetry_json: options?.hearingTelemetry ? JSON.stringify(options.hearingTelemetry) : null,
          vault_tracks_json: JSON.stringify(reconciledVault),
          updated_at: nowIso
        },
        { onConflict: 'user_id' }
      );
      if (!error) {
        storedToSupabase = true;
      }
    } catch {
      // Optional table
    }

    // 3. Universal guarantee: also store a compact telemetry snapshot in `agent_interventions`
    // (only when new tracks arrive and at most once every 3 minutes for background syncs so we don't spam rows)
    const nowMs = Date.now();
    if (validTracks.length > 0 && nowMs - this.lastSupabaseHydrationMs > 180_000) {
      this.lastSupabaseHydrationMs = nowMs;
      try {
        const topSummary = validTracks
          .slice(0, 10)
          .map(t => {
            const identity = resolveCanonicalTrackIdentity({
              trackId: t.trackId,
              spotifyUri: t.spotifyUri,
              title: t.title,
              artist: t.artist
            });
            const plays = coerceNonNegativePlayCount(t.playCount);
            const idTag = ` [id:${identity.trackId}]`;
            const uriTag = identity.spotifyUri ? ` [${identity.spotifyUri}]` : '';
            return `${t.title} by ${t.artist} (${plays}x)${idTag}${uriTag}`;
          })
          .join(' | ');

        const { error } = await client.from('agent_interventions').insert({
          user_id: cleanUserId,
          trigger_transcript: 'SPOTIFY_HISTORY_TELEMETRY_SYNC',
          computed_satiety: 22,
          agent_tts_response: `Synced ${validTracks.length} Spotify tracks for long-term intelligence growth.`,
          os_intent: 'TELEMETRY_SYNC',
          target_search_query: topSummary.slice(0, 950)
        });
        if (!error) {
          storedToSupabase = true;
          console.log(`[SupabaseIntelligenceStore] ☁️ Persisted ${validTracks.length} Spotify tracks to live Supabase for ${cleanUserId}.`);
        }
      } catch (e) {
        console.warn('[SupabaseIntelligenceStore] Snapshot insert notice:', e);
      }
    }

    return {
      syncedTracksCount: validTracks.length,
      liveSupabaseStored: storedToSupabase,
      reconciledVaultCount: reconciledVault.length,
      graduatedCount: graduatedVault.length
    };
  }

  /**
   * Logs every voice conversation turn, track proposal, track approval, or track rejection
   * to the live Supabase database (`agent_interventions` + `user_taste_genome`) so the AI grows smarter over time.
   */
  public static async recordAgentIntervention(
    record: AgentInterventionRecord,
    override?: SupabaseConnectionOverride
  ): Promise<boolean> {
    const cleanUserId = this.normalizeUserId(record.user_id);
    const nowIso = new Date().toISOString();

    const cleanTitle = (record.track_title || '').trim();
    const cleanArtist = (record.track_artist || '').trim();
    const identity = cleanTitle
      ? resolveCanonicalTrackIdentity({
          trackId: record.track_id,
          spotifyUri: record.spotify_uri,
          title: cleanTitle,
          artist: cleanArtist
        })
      : null;

    const enrichedRecord: AgentInterventionRecord = {
      ...record,
      user_id: cleanUserId,
      track_id: identity?.trackId || record.track_id,
      spotify_uri: identity?.spotifyUri || normalizeSpotifyUri(record.spotify_uri),
      created_at: nowIso
    };

    this.inMemoryInterventions.unshift(enrichedRecord);
    if (this.inMemoryInterventions.length > 100) {
      this.inMemoryInterventions = this.inMemoryInterventions.slice(0, 100);
    }

    // Track approvals vs rejections for long-term reinforcement learning
    if (cleanTitle && cleanTitle !== 'Standing By') {
      const trackKey = `${cleanUserId}::${cleanTitle.toLowerCase()}::${cleanArtist.toLowerCase()}`;
      if (record.action === 'PLAY_TRACK_NOW' || record.os_intent === 'OS_MEDIA_NEXT_AND_DEEP_LINK' || record.action === 'APPROVED_TRACK') {
        this.inMemoryApprovedTracks.set(trackKey, {
          trackId: identity?.trackId,
          title: cleanTitle,
          artist: cleanArtist,
          spotifyUri: identity?.spotifyUri,
          approvedAt: nowIso
        });
        this.inMemoryRejectedTracks.delete(trackKey);
      } else if (record.action === 'REJECTED_TRACK') {
        this.inMemoryRejectedTracks.set(trackKey, {
          trackId: identity?.trackId,
          title: cleanTitle,
          artist: cleanArtist,
          rejectedAt: nowIso
        });
      }
    }

    const client = getLiveSupabaseClient(override?.supabaseUrl, override?.supabaseKey);
    if (!client) {
      return false;
    }

    // Encode structured action, track_id & URI metadata inside target_search_query so even basic `agent_interventions` schemas preserve 100% of intelligence!
    const structuredTargetQuery = [
      record.target_search_query || `${cleanTitle} ${cleanArtist}`.trim(),
      record.action ? `action=${record.action}` : '',
      identity?.trackId ? `track_id=${identity.trackId}` : '',
      identity?.spotifyUri ? `uri=${identity.spotifyUri}` : '',
      record.circadian_daypart ? `daypart=${record.circadian_daypart}` : ''
    ]
      .filter(Boolean)
      .join(' | ')
      .slice(0, 500);

    try {
      const { error } = await client.from('agent_interventions').insert({
        user_id: cleanUserId,
        trigger_transcript: (record.trigger_transcript || 'voice_interaction').slice(0, 500),
        computed_satiety: Math.round(Number(record.computed_satiety || 25)),
        agent_tts_response: (record.agent_tts_response || '').slice(0, 1000),
        os_intent: record.os_intent || record.action || 'NONE',
        target_search_query: structuredTargetQuery,
        track_id: identity?.trackId || null,
        spotify_uri: identity?.spotifyUri || null
      });

      if (error) {
        console.warn('[SupabaseIntelligenceStore] Supabase agent_interventions insert warning:', error.message);
        return false;
      }

      console.log(
        `[SupabaseIntelligenceStore] ✅ Logged intervention to live Supabase Taste Genome (user=${cleanUserId}, action=${record.action || record.os_intent})`
      );
      return true;
    } catch (err) {
      console.warn('[SupabaseIntelligenceStore] Failed to write intervention to Supabase:', err);
      return false;
    }
  }

  /**
   * Fetches the user's complete long-term memory & evolving taste intelligence from the live Supabase database,
   * combining `agent_interventions`, `listening_events`, and `user_taste_genome` with local memory.
   */
  public static async fetchUserLongTermMemory(
    userId?: string,
    override?: SupabaseConnectionOverride
  ): Promise<LongTermIntelligenceSummary> {
    const cleanUserId = this.normalizeUserId(userId);
    const userPrefix = `${cleanUserId}::`;
    const client = getLiveSupabaseClient(override?.supabaseUrl, override?.supabaseKey);
    const liveConnected = Boolean(client && isSupabaseLive(override?.supabaseUrl, override?.supabaseKey));

    let supabaseInterventions: any[] = [];

    if (client) {
      try {
        // Strictly scope Supabase queries to the effective userId (no cross-user leak into authenticated accounts)
        const { data, error } = await client
          .from('agent_interventions')
          .select('user_id, trigger_transcript, computed_satiety, agent_tts_response, os_intent, target_search_query, track_id, spotify_uri, created_at')
          .eq('user_id', cleanUserId)
          .order('created_at', { ascending: false })
          .limit(80);

        if (!error && Array.isArray(data)) {
          supabaseInterventions = data;
        }
      } catch (e) {
        console.warn('[SupabaseIntelligenceStore] Live Supabase query notice:', e);
      }

      // Also check `listening_events` table in Supabase if it exists (up to 500 daily/historical events)
      try {
        const { data: eventRows, error: eventErr } = await client
          .from('listening_events')
          .select('track_id, track_title, track_artist, spotify_uri, play_count, media_source, observed_at')
          .eq('user_id', cleanUserId)
          .order('observed_at', { ascending: false })
          .limit(500);

        if (!eventErr && Array.isArray(eventRows)) {
          for (const row of eventRows) {
            if (row.track_title) {
              this.upsertLearnedTrack(userPrefix, {
                title: String(row.track_title),
                artist: String(row.track_artist || 'Unknown Artist'),
                trackId: row.track_id,
                spotifyUri: row.spotify_uri,
                playCount: row.play_count,
                source: row.media_source,
                lastPlayedAt: row.observed_at
              });
            }
          }
        }
      } catch {
        // Optional table
      }

      // Also hydrate `user_taste_genome` if present (top_tracks_json, approved_tracks_json, rejected_tracks_json, vault_tracks_json)
      try {
        const { data: genomeRows, error: genomeErr } = await client
          .from('user_taste_genome')
          .select('top_tracks_json, approved_tracks_json, rejected_tracks_json, vault_tracks_json')
          .eq('user_id', cleanUserId)
          .limit(1);

        if (!genomeErr && Array.isArray(genomeRows) && genomeRows[0]) {
          const row = genomeRows[0] as any;
          if (row.vault_tracks_json && !(this.inMemoryVaultTracks.get(cleanUserId)?.length)) {
            try {
              const parsedVault = JSON.parse(String(row.vault_tracks_json));
              if (Array.isArray(parsedVault)) {
                const reconciled = RestingVaultEngine.reconcileVaultTracks(
                  parsedVault,
                  Date.now(),
                  this.inMemoryRestoredTrackTimestamps.get(cleanUserId)
                );
                this.inMemoryVaultTracks.set(cleanUserId, reconciled.activeVault);
                if (reconciled.graduatedTracks.length > 0) {
                  const prevGrad = this.inMemoryGraduatedVaultTracks.get(cleanUserId) || [];
                  this.inMemoryGraduatedVaultTracks.set(
                    cleanUserId,
                    [...reconciled.graduatedTracks, ...prevGrad].slice(0, 30)
                  );
                }
              }
            } catch {
              // Ignore malformed JSON
            }
          }
          if (row.top_tracks_json) {
            const topTracks = JSON.parse(String(row.top_tracks_json));
            if (Array.isArray(topTracks)) {
              for (const t of topTracks) {
                if (!t?.title) continue;
                this.upsertLearnedTrack(userPrefix, {
                  title: String(t.title),
                  artist: String(t.artist || 'Unknown Artist'),
                  trackId: t.trackId,
                  spotifyUri: t.spotifyUri,
                  playCount: t.playCount,
                  source: t.source,
                  lastPlayedAt: t.lastPlayedAt
                });
              }
            }
          }
          if (row.approved_tracks_json) {
            const approved = JSON.parse(String(row.approved_tracks_json));
            if (Array.isArray(approved)) {
              for (const a of approved) {
                if (!a?.title) continue;
                const key = `${userPrefix}${String(a.title).toLowerCase().trim()}::${String(a.artist || '').toLowerCase().trim()}`;
                if (!this.inMemoryApprovedTracks.has(key)) {
                  this.inMemoryApprovedTracks.set(key, a);
                }
              }
            }
          }
          if (row.rejected_tracks_json) {
            const rejected = JSON.parse(String(row.rejected_tracks_json));
            if (Array.isArray(rejected)) {
              for (const r of rejected) {
                if (!r?.title) continue;
                const key = `${userPrefix}${String(r.title).toLowerCase().trim()}::${String(r.artist || '').toLowerCase().trim()}`;
                if (!this.inMemoryRejectedTracks.has(key)) {
                  this.inMemoryRejectedTracks.set(key, r);
                }
              }
            }
          }
        }
      } catch {
        // Optional table
      }
    }

    // Parse historical interventions from Supabase to reconstruct approved tracks, rejected tracks, and synced tracks
    const recentInterventionsFormatted: LongTermIntelligenceSummary['recentInterventions'] = [];
    const circadianCounts: Record<string, Map<string, number>> = {};

    const combinedInterventions = [
      ...supabaseInterventions,
      ...this.inMemoryInterventions.filter(i => i.user_id === cleanUserId).slice(0, 15)
    ];

    const seenTimestamps = new Set<string>();
    for (const row of combinedInterventions) {
      const sig = `${row.trigger_transcript}::${row.agent_tts_response}::${String(row.created_at || '').slice(0, 16)}`;
      if (seenTimestamps.has(sig)) continue;
      seenTimestamps.add(sig);

      const trigger = String(row.trigger_transcript || '');
      const targetRaw = String(row.target_search_query || '');
      const intentRaw = String(row.os_intent || 'NONE');

      // If this row was a SPOTIFY_HISTORY_TELEMETRY_SYNC snapshot, parse any tracks embedded in target_search_query
      if (trigger === 'SPOTIFY_HISTORY_TELEMETRY_SYNC' || intentRaw === 'TELEMETRY_SYNC') {
        const parts = targetRaw.split('|').map(s => s.trim()).filter(Boolean);
        for (const p of parts) {
          const match = p.match(/^(.+?)\s+by\s+(.+?)\s+\((\d+)x\)(?:\s+\[id:([^\]]+)\])?(?:\s+\[(spotify:track:[^\]]+)\])?$/i);
          if (match) {
            const title = match[1].trim();
            const artist = match[2].trim();
            const plays = coerceNonNegativePlayCount(match[3]);
            const identity = resolveCanonicalTrackIdentity({
              trackId: match[4],
              spotifyUri: match[5],
              title,
              artist
            });
            const key = `${userPrefix}${title.toLowerCase()}::${artist.toLowerCase()}`;
            if (!this.inMemoryLearnedTracks.has(key)) {
              this.inMemoryLearnedTracks.set(key, {
                trackId: identity.trackId,
                title,
                artist,
                spotifyUri: identity.spotifyUri,
                playCount: plays,
                source: 'com.spotify.music'
              });
            }
          }
        }
        continue;
      }

      const cleanTarget = targetRaw.split('|')[0]?.trim() || '';
      const actionMatch = targetRaw.match(/action=([A-Z_]+)/);
      const trackIdMatch = targetRaw.match(/track_id=([^\s|]+)/);
      const uriMatch = targetRaw.match(/uri=(spotify:track:[A-Za-z0-9]+)/);
      const daypartMatch = targetRaw.match(/daypart=([A-Za-z\s]+)/);
      const effectiveAction = actionMatch?.[1] || intentRaw;
      const effectiveTrackId = row.track_id || trackIdMatch?.[1];
      const effectiveUri = normalizeSpotifyUri(row.spotify_uri || uriMatch?.[1]);

      if (cleanTarget && (effectiveAction === 'PLAY_TRACK_NOW' || intentRaw === 'OS_MEDIA_NEXT_AND_DEEP_LINK' || effectiveAction === 'APPROVED_TRACK')) {
        const key = `${userPrefix}${cleanTarget.toLowerCase()}`;
        if (!this.inMemoryApprovedTracks.has(key)) {
          this.inMemoryApprovedTracks.set(key, {
            trackId: effectiveTrackId,
            title: cleanTarget,
            artist: '',
            spotifyUri: effectiveUri,
            approvedAt: row.created_at || new Date().toISOString()
          });
        }
      } else if (cleanTarget && effectiveAction === 'REJECTED_TRACK') {
        const key = `${userPrefix}${cleanTarget.toLowerCase()}`;
        if (!this.inMemoryRejectedTracks.has(key)) {
          this.inMemoryRejectedTracks.set(key, {
            trackId: effectiveTrackId,
            title: cleanTarget,
            artist: '',
            rejectedAt: row.created_at || new Date().toISOString()
          });
        }
      }

      if (daypartMatch && cleanTarget) {
        const dp = daypartMatch[1].trim();
        if (!circadianCounts[dp]) circadianCounts[dp] = new Map<string, number>();
        circadianCounts[dp].set(cleanTarget, (circadianCounts[dp].get(cleanTarget) || 0) + 1);
      }

      if (recentInterventionsFormatted.length < 12) {
        recentInterventionsFormatted.push({
          userSaid: trigger,
          agentReplied: String(row.agent_tts_response || ''),
          action: effectiveAction,
          targetTrack: cleanTarget,
          satiety: Number(row.computed_satiety || 25),
          createdAt: String(row.created_at || '')
        });
      }
    }

    const allLearnedTracks = Array.from(this.inMemoryLearnedTracks.entries())
      .filter(([k]) => k.startsWith(userPrefix))
      .map(([, v]) => v)
      .sort((a, b) => b.playCount - a.playCount);
    const artistTotals = new Map<string, number>();
    for (const t of allLearnedTracks) {
      if (t.artist && t.artist !== 'Unknown Artist' && t.playCount > 0) {
        artistTotals.set(t.artist, (artistTotals.get(t.artist) || 0) + t.playCount);
      }
    }

    const topArtistsOverTime = Array.from(artistTotals.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 12)
      .map(([artist, totalPlays]) => ({ artist, totalPlays }));

    const circadianPatternNotes: string[] = [];
    for (const [dp, map] of Object.entries(circadianCounts)) {
      const topForDp = Array.from(map.entries())
        .sort((a, b) => b[1] - a[1])
        .slice(0, 3)
        .map(([trk]) => trk);
      if (topForDp.length > 0) {
        circadianPatternNotes.push(`${dp}: user frequently gravitates toward ${topForDp.join(', ')}`);
      }
    }

    const userApproved = Array.from(this.inMemoryApprovedTracks.entries())
      .filter(([k]) => k.startsWith(userPrefix))
      .map(([, v]) => v);
    const userRejected = Array.from(this.inMemoryRejectedTracks.entries())
      .filter(([k]) => k.startsWith(userPrefix))
      .map(([, v]) => v);

    const totalInterventions = Math.max(supabaseInterventions.length, recentInterventionsFormatted.length);
    const totalTracks = allLearnedTracks.length;
    const maturity: LongTermIntelligenceSummary['intelligenceMaturityLevel'] =
      totalInterventions >= 20 || totalTracks >= 35
        ? 'DEEP_NEURAL_SYNC'
        : totalInterventions >= 8 || totalTracks >= 15
        ? 'CALIBRATED'
        : totalInterventions >= 2 || totalTracks >= 5
        ? 'DEVELOPING'
        : 'SEEDING';

    const allListenedTitlesSet = new Set<string>();
    const allListenedUrisSet = new Set<string>();
    for (const trk of allLearnedTracks) {
      if (trk.playCount > 0 && trk.title) allListenedTitlesSet.add(trk.title.toLowerCase().trim());
      if (trk.playCount > 0 && trk.spotifyUri && trk.spotifyUri.startsWith('spotify:track:')) {
        allListenedUrisSet.add(trk.spotifyUri.trim());
      }
    }
    for (const app of userApproved) {
      if (app.title) allListenedTitlesSet.add(app.title.toLowerCase().trim());
      if (app.spotifyUri && app.spotifyUri.startsWith('spotify:track:')) {
        allListenedUrisSet.add(app.spotifyUri.trim());
      }
    }

    const currentVaultRaw = this.inMemoryVaultTracks.get(cleanUserId) || [];
    const currentVaultReconciled = RestingVaultEngine.reconcileVaultTracks(
      currentVaultRaw,
      Date.now(),
      this.inMemoryRestoredTrackTimestamps.get(cleanUserId)
    );
    this.inMemoryVaultTracks.set(cleanUserId, currentVaultReconciled.activeVault);
    if (currentVaultReconciled.graduatedTracks.length > 0) {
      const prevGrad = this.inMemoryGraduatedVaultTracks.get(cleanUserId) || [];
      this.inMemoryGraduatedVaultTracks.set(
        cleanUserId,
        [...currentVaultReconciled.graduatedTracks, ...prevGrad].slice(0, 30)
      );
    }

    return {
      userId: cleanUserId,
      isLiveSupabaseConnected: liveConnected,
      intelligenceMaturityLevel: maturity,
      totalHistoricalInterventions: totalInterventions,
      totalTracksLearned: totalTracks,
      approvedTracks: userApproved.slice(-15),
      rejectedTracks: userRejected.slice(-15),
      topArtistsOverTime,
      topTracksOverTime: allLearnedTracks.slice(0, 20),
      recentInterventions: recentInterventionsFormatted,
      circadianPatternNotes,
      allListenedTitles: Array.from(allListenedTitlesSet),
      allListenedUris: Array.from(allListenedUrisSet),
      vaultTracks: currentVaultReconciled.activeVault,
      graduatedVaultTracks: this.inMemoryGraduatedVaultTracks.get(cleanUserId) || []
    };
  }
}
