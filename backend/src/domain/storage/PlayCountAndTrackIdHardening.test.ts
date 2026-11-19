import fs from 'fs';
import os from 'os';
import path from 'path';
import { EventStore } from '../events/EventStore';
import { SupabaseIntelligenceStore } from './SupabaseIntelligenceStore';
import { SelfLearningDiscoveryEngine } from '../recommendation/SelfLearningDiscoveryEngine';
import { SatietyExecutionEngine } from '../reasoning/SatietyExecutionEngine';
import { TelemetryReasoningPoller } from '../reasoning/TelemetryReasoningPoller';
import { ClaudeMusicReasoningProvider } from '../reasoning/ClaudeMusicReasoningProvider';
import { resolveCanonicalTrackIdentity, coerceNonNegativePlayCount } from '../identity/trackIdentity';

describe('PlayCount & Canonical TrackId Hardening', () => {
  const tempFiles: string[] = [];

  afterEach(() => {
    SupabaseIntelligenceStore.clearInMemoryStateForTests();
    new EventStore().deleteByUserIds(['user-a@tunemind.app', 'tunemind-default-user']);
    for (const f of tempFiles.splice(0, tempFiles.length)) {
      try {
        if (fs.existsSync(f)) fs.unlinkSync(f);
      } catch {
        // ignore cleanup error
      }
    }
  });

  function makeTempStorePath(label: string): string {
    const p = path.join(os.tmpdir(), `tunemind_${label}_${Date.now()}_${Math.random().toString(36).slice(2)}.json`);
    tempFiles.push(p);
    return p;
  }

  it('starts fresh EventStore with zero seeded events, preserves playCount=0, and keeps trackId separate from spotifyUri', () => {
    const storePath = makeTempStorePath('eventstore');
    const store = new EventStore(storePath);

    expect(store.count()).toBe(0);
    expect(store.getRecentObservedTracks()).toHaveLength(0);

    store.syncClientTracks([
      {
        title: 'Local Acoustic Demo',
        artist: 'Bedroom Producer',
        source: 'com.google.android.apps.youtube.music',
        playCount: 0,
        trackId: 'hist_0_fake_ui_key'
      },
      {
        title: 'Kese (Dance)',
        artist: 'Wizkid',
        source: 'com.spotify.music',
        playCount: 0,
        spotifyUri: 'spotify:track:4iV5W9uYEdYUVa79Axb7Rh'
      }
    ]);

    const summary = store.getRecentObservedTracks();
    expect(summary).toHaveLength(2);

    const youtubeTrack = summary.find((t: { title: string }) => t.title === 'Local Acoustic Demo');
    expect(youtubeTrack).toBeDefined();
    expect(youtubeTrack!.playCount).toBe(0);
    expect(youtubeTrack!.trackId).not.toBe('hist_0_fake_ui_key');
    expect(youtubeTrack!.trackId.startsWith('trk:')).toBe(true);
    expect(youtubeTrack!.spotifyUri).toBeUndefined();

    const spotifyTrack = summary.find((t: { title: string }) => t.title === 'Kese (Dance)');
    expect(spotifyTrack).toBeDefined();
    expect(spotifyTrack!.playCount).toBe(0);
    expect(spotifyTrack!.trackId).toBe('4iV5W9uYEdYUVa79Axb7Rh');
    expect(spotifyTrack!.spotifyUri).toBe('spotify:track:4iV5W9uYEdYUVa79Axb7Rh');
  });

  it('preserves 0 play counts, separates track_id vs spotify_uri, and isolates authenticated users in SupabaseIntelligenceStore', async () => {
    await SupabaseIntelligenceStore.syncTracksAndTelemetryToSupabase('user-a@tunemind.app', [
      {
        title: 'Unplayed Saved Track',
        artist: 'Artist A',
        playCount: 0
      }
    ]);
    await SupabaseIntelligenceStore.syncTracksAndTelemetryToSupabase('tunemind-default-user', [
      {
        title: 'Default Leak Track',
        artist: 'Leak Artist',
        playCount: 9
      }
    ]);

    const memA = await SupabaseIntelligenceStore.fetchUserLongTermMemory('user-a@tunemind.app');
    expect(memA.topTracksOverTime.map(t => t.title)).toContain('Unplayed Saved Track');
    expect(memA.topTracksOverTime.map(t => t.title)).not.toContain('Default Leak Track');

    const savedTrack = memA.topTracksOverTime.find(t => t.title === 'Unplayed Saved Track')!;
    expect(savedTrack.playCount).toBe(0);
    expect(savedTrack.trackId.startsWith('trk:')).toBe(true);
    expect(savedTrack.spotifyUri).toBeUndefined();

    // Unplayed tracks (playCount === 0) must not pollute topArtistsOverTime or allListenedTitles
    expect(memA.topArtistsOverTime).toHaveLength(0);
    expect(memA.allListenedTitles).not.toContain('Unplayed Saved Track');
  });

  it('attaches canonical trackId and playCount=0 on discovery tracks and ignores unplayed signals in weight training', async () => {
    const engine = new SelfLearningDiscoveryEngine();
    const userId = `hardening-${Date.now()}`;

    const drop = await engine.generateDailyForYouDrop({
      userId,
      dropDate: '2026-11-17@12PM',
      timezone: 'Africa/Nairobi',
      timeSlot: 'Evening',
      avgVolumeRatio: 0.7,
      recentTracks: [
        { title: 'Real Played Song', artist: 'Sauti Sol', genre: 'Afrobeats', playCount: 3, volumeRatio: 0.8 },
        { title: 'Zero Play Saved Song', artist: 'Nviiri', genre: 'Bongo Flava', playCount: 0, volumeRatio: 0.8 }
      ]
    });

    expect(drop.recommendations.length).toBeGreaterThan(0);
    for (const rec of drop.recommendations) {
      expect(typeof rec.trackId).toBe('string');
      expect(rec.trackId.trim().length).toBeGreaterThan(0);
      expect(rec.playCount ?? 0).toBe(0);
      if (rec.spotifyUri) {
        expect(rec.spotifyUri.startsWith('spotify:track:')).toBe(true);
      }
    }

    // Verify applyCrossAppSignals ignores tracks with playCount <= 0
    const freshUser = `crossapp-${Date.now()}`;
    const model = await engine.getOrCreateModel(freshUser);
    const beforePlays = model.genreWeights['Afrobeats']?.plays ?? 0;
    engine.applyCrossAppSignals(model, [
      { title: 'Saved Never Played', artist: 'Wizkid', genre: 'Afrobeats', playCount: 0, volumeRatio: 0.9 }
    ]);
    const afterPlays = model.genreWeights['Afrobeats']?.plays ?? 0;
    expect(afterPlays).toBe(beforePlays);
  });

  it('never fabricates spotify:track: URIs from non-Spotify trackIds or forces min-1 track/play counts in reasoning engines', async () => {
    const storePath = makeTempStorePath('poller');
    const store = new EventStore(storePath);
    const poller = new TelemetryReasoningPoller(store);
    const status = await poller.getStatus();
    expect(status.lastAnalyzedEventCount).toBe(0);

    // SatietyExecutionEngine should preserve canonical non-Spotify identity without fabricating spotify:track: URI
    const satietyEngine = new SatietyExecutionEngine();
    const decision = await satietyEngine.evaluateLast50Tracks({
      user_id: 'test-user',
      tracks: [
        {
          track_id: 'trk:yt_12345',
          track_title: 'Fresh YT Track',
          artist_name: 'Indie Band',
          duration_ms: 180_000,
          played_at_epoch_ms: Date.now()
        },
        {
          track_id: '1111111111111111111111',
          track_title: 'Repeated Track',
          artist_name: 'Indie Band',
          duration_ms: 180_000,
          played_at_epoch_ms: Date.now() - 10_000,
          was_skipped: true,
          skip_duration_ms: 8_000
        }
      ]
    });
    expect(decision.telemetry_metrics.evaluated_track_count).toBe(2);

    // Track identity helper checks
    expect(coerceNonNegativePlayCount(0)).toBe(0);
    expect(coerceNonNegativePlayCount(-4)).toBe(0);
    const nonSpotify = resolveCanonicalTrackIdentity({
      trackId: 'sp_like_12',
      title: 'Night Drive',
      artist: 'Kavinsky'
    });
    expect(nonSpotify.spotifyUri).toBeUndefined();
    expect(nonSpotify.trackId.startsWith('trk:')).toBe(true);
  });

  it('keeps playCount=0 and returns canonical track_id separately from spotify_uri in ClaudeMusicReasoningProvider fallbacks', async () => {
    const originalKey = process.env.ANTHROPIC_API_KEY;
    delete process.env.ANTHROPIC_API_KEY;
    try {
      const provider = new ClaudeMusicReasoningProvider();
      const response = await provider.conversationalMusicAgent(
        'what is my most played song',
        [
          {
            title: 'Library Only Track',
            artist: 'Local Artist',
            playCount: 0,
            trackId: 'trk:local_only_1'
          }
        ],
        undefined,
        `test-fallback-${Date.now()}`
      );

      expect(response.matchedTrackId).toBeTruthy();
      expect(response.matchedTrackId.startsWith('trk:')).toBe(true);
      expect(response.recommendedTrack?.trackId).toBe(response.matchedTrackId);
      // Either null or a genuine Spotify track URI — never 'trk:...'
      if (response.matchedSpotifyUri) {
        expect(response.matchedSpotifyUri.startsWith('spotify:track:')).toBe(true);
      }
      expect(response.reply).toContain("don't have any verified play counts yet");
    } finally {
      if (originalKey !== undefined) {
        process.env.ANTHROPIC_API_KEY = originalKey;
      }
    }
  });

  it('preserves default player on temporary YouTube in-app playback and allows breaking out of pending never-leave lock on explicit external open', () => {
    // User with Spotify default temporarily allows YouTube inside TuneMind -> keep Spotify as default
    expect(
      ClaudeMusicReasoningProvider.shouldKeepExistingDefaultPlayer({
        hasExplicitDefaultPlayer: true,
        defaultPlayerPackage: 'com.spotify.music',
        targetPlayerPackage: 'com.google.android.apps.youtube.music',
        userCommandRaw: 'use youtube inside tunemind for this song'
      })
    ).toBe(true);

    // User explicitly asks to make YouTube Music their permanent default -> allow update
    expect(
      ClaudeMusicReasoningProvider.shouldKeepExistingDefaultPlayer({
        hasExplicitDefaultPlayer: true,
        defaultPlayerPackage: 'com.spotify.music',
        targetPlayerPackage: 'com.google.android.apps.youtube.music',
        userCommandRaw: 'make youtube music my default player'
      })
    ).toBe(false);

    // Follow-up turn breaking out of strict never-open lock when user says "open Spotify" or "open Boomplay"
    expect(
      ClaudeMusicReasoningProvider.resolveRequestedNeverOpenExternal({
        userCommandRaw: 'actually open spotify',
        pendingNeverOpenExternal: true
      })
    ).toBe(false);

    expect(
      ClaudeMusicReasoningProvider.resolveRequestedNeverOpenExternal({
        userCommandRaw: 'open boomplay',
        decisionNeverOpenExternal: false,
        pendingNeverOpenExternal: true
      })
    ).toBe(false);

    // Saying "yes use YouTube" keeps the in-app never-open flag active cleanly
    expect(
      ClaudeMusicReasoningProvider.resolveRequestedNeverOpenExternal({
        userCommandRaw: 'yes use youtube inside tunemind',
        pendingNeverOpenExternal: true
      })
    ).toBe(true);
  });
});
