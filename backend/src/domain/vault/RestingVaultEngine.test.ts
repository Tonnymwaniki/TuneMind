import { DAY_MS, RestingVaultEngine } from './RestingVaultEngine';
import { SupabaseIntelligenceStore } from '../storage/SupabaseIntelligenceStore';

describe('RestingVaultEngine mathematical model', () => {
  const now = 1_760_000_000_000;

  beforeEach(() => {
    SupabaseIntelligenceStore.clearInMemoryStateForTests();
  });

  test('spaced historical plays decay over time and stay fresh without entering Vault', () => {
    // 5 plays spaced across 10..22 days ago
    const oldPlays = [10, 13, 16, 19, 22].map(d => now - d * DAY_MS);
    const metrics = RestingVaultEngine.evaluateTrackFatigue({
      playTimestampsMs: oldPlays,
      sensitivity: 0.75,
      nowMs: now
    });

    expect(metrics.recentPlays24h).toBe(0);
    expect(metrics.recentPlays7d).toBe(0);
    expect(metrics.effectiveSatiety).toBeLessThan(35);
    expect(metrics.freshnessPercent).toBeGreaterThan(65);
    expect(metrics.shouldAutoQuarantine).toBe(false);
  });

  test('24h binge or high recent repetition triggers auto-entry & adaptive cooldown duration', () => {
    // 3 rapid plays within last 6 hours + 1 yesterday
    const recentPlays = [
      now - 1 * 3_600_000,
      now - 3 * 3_600_000,
      now - 5 * 3_600_000,
      now - 28 * 3_600_000
    ];
    const metrics = RestingVaultEngine.evaluateTrackFatigue({
      playTimestampsMs: recentPlays,
      sensitivity: 0.75,
      nowMs: now
    });

    expect(metrics.recentPlays24h).toBe(3);
    expect(metrics.effectiveSatiety).toBeGreaterThanOrEqual(RestingVaultEngine.computeEntryThreshold(0.75));
    expect(metrics.shouldAutoQuarantine).toBe(true);
    expect(metrics.recommendedCooldownDays).toBeGreaterThanOrEqual(3.5);
    expect(metrics.recommendedCooldownDays).toBeLessThanOrEqual(7.0);
  });

  test('early-skip fatigue amplifies effective satiety more than full listens', () => {
    const timestamps = [now - 2 * 3_600_000, now - 10 * 3_600_000];
    const fullListens = RestingVaultEngine.evaluateTrackFatigue({
      playTimestampsMs: timestamps,
      listenedRatios: [0.95, 0.92],
      sensitivity: 0.75,
      nowMs: now
    });
    const earlySkips = RestingVaultEngine.evaluateTrackFatigue({
      playTimestampsMs: timestamps,
      listenedRatios: [0.18, 0.22],
      sensitivity: 0.75,
      nowMs: now
    });

    expect(earlySkips.effectiveSatiety).toBeGreaterThan(fullListens.effectiveSatiety);
  });

  test('post-restore cutoff ignores historical pre-restore plays so graduated songs stay fresh', () => {
    const restoredAt = now - 2 * 3_600_000;
    const preRestorePlays = [now - 10 * 3_600_000, now - 12 * 3_600_000, now - 14 * 3_600_000];
    const metrics = RestingVaultEngine.evaluateTrackFatigue({
      playTimestampsMs: preRestorePlays,
      sensitivity: 0.9,
      lastRestoredAtMs: restoredAt,
      nowMs: now
    });

    expect(metrics.effectiveSatiety).toBe(0);
    expect(metrics.freshnessPercent).toBe(99);
    expect(metrics.shouldAutoQuarantine).toBe(false);
  });

  test('recovery curve progresses monotonically and auto-graduates once elapsed >= totalDays', () => {
    const qTime = now - 2 * DAY_MS; // 2 days elapsed of 4-day rest
    const mid = RestingVaultEngine.computeRecoveryState(qTime, 4.0, 88, now);
    expect(mid.isGraduated).toBe(false);
    expect(mid.cooldownDaysRemaining).toBeCloseTo(2.0, 1);
    expect(mid.recoveryProgress).toBeGreaterThan(0.5);
    expect(mid.freshnessPercent).toBeGreaterThan(45);

    const end = RestingVaultEngine.computeRecoveryState(now - 4.1 * DAY_MS, 4.0, 88, now);
    expect(end.isGraduated).toBe(true);
    expect(end.recoveryProgress).toBe(1.0);
    expect(end.cooldownDaysRemaining).toBe(0);
    expect(end.freshnessPercent).toBe(95);
  });

  test('SupabaseIntelligenceStore syncs and reconciles active vs graduated Vault entries', async () => {
    const syncRes = await SupabaseIntelligenceStore.syncTracksAndTelemetryToSupabase(
      'vault-test-user',
      [],
      {
        vaultTracks: [
          {
            id: 'v-active',
            title: 'Still Resting Track',
            artist: 'Artist A',
            quarantinedAtMs: Date.now() - 1 * DAY_MS,
            totalDays: 4.0,
            satietyScore: 86,
            isAutoQuarantined: true,
            entryReason: 'Binge guard · 3 plays in 24h'
          },
          {
            id: 'v-done',
            title: 'Graduated Comeback',
            artist: 'Artist B',
            quarantinedAtMs: Date.now() - 5 * DAY_MS,
            totalDays: 3.0,
            satietyScore: 80
          }
        ]
      }
    );

    expect(syncRes.reconciledVaultCount).toBe(1);
    expect(syncRes.graduatedCount).toBe(1);

    const memory = await SupabaseIntelligenceStore.fetchUserLongTermMemory('vault-test-user');
    expect(memory.vaultTracks?.length).toBe(1);
    expect(memory.vaultTracks?.[0].title).toBe('Still Resting Track');
    expect(memory.graduatedVaultTracks?.length).toBe(1);
    expect(memory.graduatedVaultTracks?.[0].title).toBe('Graduated Comeback');
  });

  test('computeFreshReadiness rewards graduated comebacks and untouched liked gems cleanly', () => {
    const comeback = RestingVaultEngine.computeFreshReadiness({
      baseFreshnessPercent: 94,
      playCount: 3,
      isGraduatedComeback: true,
      restedDays: 4.0,
      nowMs: now
    });
    const gem = RestingVaultEngine.computeFreshReadiness({
      baseFreshnessPercent: 99,
      playCount: 0,
      isLikedOrSaved: true,
      sourceLabel: 'Liked Song',
      nowMs: now
    });
    const warming = RestingVaultEngine.computeFreshReadiness({
      baseFreshnessPercent: 48,
      playCount: 3,
      lastPlayedAtMs: now - 3_600_000,
      nowMs: now
    });

    expect(comeback.category).toBe('COMEBACK');
    expect(comeback.readinessScore).toBe(100);
    expect(gem.category).toBe('UNPLAYED_GEM');
    expect(gem.readinessScore).toBe(100);
    expect(warming.category).toBe('WARMING_UP');
    expect(warming.readinessScore).toBeLessThan(comeback.readinessScore);
  });
});
