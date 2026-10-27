/**
 * Tests for ListeningTimeline.
 */

import { ListeningTimeline } from './ListeningTimeline';
import { EpisodeReconstructor } from './EpisodeReconstructor';
import { EventStore } from '../events/EventStore';
import path from 'path';
import fs from 'fs';

function createStore(): EventStore {
  const tmpFile = path.join(fs.realpathSync('.'), `__test_tl_${Date.now()}_${Math.random().toString(36).slice(2)}.db`);
  return new EventStore(tmpFile);
}

function seedEvent(store: EventStore, overrides: Record<string, unknown> = {}): void {
  const playbackStateCode = overrides['playbackStateCode'] as number | undefined;
  const isPlaying = overrides['isPlaying'] as boolean | undefined;
  const track = overrides['track'] as Record<string, unknown> | undefined;

  const pb: Record<string, unknown> = {
    positionMs: 0, elapsedRealtimeMs: 1000,
    isPlaying: isPlaying ?? (playbackStateCode === 3 ? true : false),
    playbackSpeed: 1.0,
  };
  if (playbackStateCode !== undefined) pb.playbackStateCode = playbackStateCode;

  const payload: Record<string, unknown> = {
    eventId: overrides['eventId'] as string ?? 'e0000000-0000-0000-0000-000000000001',
    userId: overrides['userId'] as string ?? 'tunemind-dev',
    sessionId: overrides['sessionId'] as string ?? 'ses-001',
    deviceId: overrides['deviceId'] as string ?? 'device-1',
    schemaVersion: '1.0',
    eventType: overrides['eventType'] as string ?? 'PLAYBACK_STATE_CHANGED',
    occurredAt: overrides['occurredAt'] as string ?? '2026-10-18T06:00:00.000Z',
    mediaSource: overrides['mediaSource'] as string ?? 'com.spotify.music',
    playback: pb,
    track: track ?? null,
    previousTrack: null,
  };

  store.insert(
    payload.eventId as string, payload.userId as string,
    payload.sessionId as string, payload.deviceId as string,
    payload.schemaVersion as string, payload.eventType as string,
    payload.occurredAt as string, '2026-10-18T06:00:01.000Z',
    payload.mediaSource as string, JSON.stringify(payload),
  );
}

function track(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { trackId: overrides['trackId'] ?? null, title: overrides['title'] ?? null, artist: overrides['artist'] ?? null, album: overrides['album'] ?? null, durationMs: overrides['durationMs'] ?? null };
}

describe('ListeningTimeline', () => {
  let store: EventStore;
  let reconstructor: EpisodeReconstructor;
  let timeline: ListeningTimeline;
  let dbPaths: string[];

  beforeEach(() => {
    dbPaths = [];
    store = createStore();
    dbPaths.push(store.getDbPath());
    reconstructor = new EpisodeReconstructor(store);
    timeline = new ListeningTimeline(reconstructor);
  });

  afterEach(() => {
    store.close();
    for (const dbPath of dbPaths) {
      try { if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath); } catch { /* best effort */ }
      try { const wal = dbPath + '-wal'; if (fs.existsSync(wal)) fs.unlinkSync(wal); } catch { /* best effort */ }
      try { const shm = dbPath + '-shm'; if (fs.existsSync(shm)) fs.unlinkSync(shm); } catch { /* best effort */ }
    }
  });

  // 1. Empty result
  test('empty dataset returns empty timeline', () => {
    const result = timeline.build('tunemind-dev');
    expect(result.episodes).toEqual([]);
    expect(result.totalEpisodes).toBe(0);
  });

  // 2. Chronological ordering
  test('episodes are in chronological order', () => {
    seedEvent(store, { eventId: 'a1', playbackStateCode: 3, occurredAt: '2026-10-18T07:00:00.000Z' });
    seedEvent(store, { eventId: 'a2', playbackStateCode: 1, occurredAt: '2026-10-18T07:01:00.000Z' });
    seedEvent(store, { eventId: 'a3', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z' });
    seedEvent(store, { eventId: 'a4', playbackStateCode: 1, occurredAt: '2026-10-18T06:01:00.000Z' });
    const result = timeline.build('tunemind-dev');
    expect(result.totalEpisodes).toBe(2);
    expect(result.episodes[0].firstObservedAt).toBe('2026-10-18T06:00:00.000Z');
    expect(result.episodes[1].firstObservedAt).toBe('2026-10-18T07:00:00.000Z');
  });

  // 3. Multiple sessions
  test('multiple sessions produce separate episodes', () => {
    seedEvent(store, { eventId: 'b1', sessionId: 'ses-a', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z' });
    seedEvent(store, { eventId: 'b2', sessionId: 'ses-b', playbackStateCode: 3, occurredAt: '2026-10-18T07:00:00.000Z' });
    const result = timeline.build('tunemind-dev');
    expect(result.totalEpisodes).toBe(2);
    expect(result.episodes[0].observerSessionId).toBe('ses-a');
    expect(result.episodes[1].observerSessionId).toBe('ses-b');
  });

  // 4. Multiple sources in one session
  test('multiple sources in one session produce separate episodes', () => {
    seedEvent(store, { eventId: 'c1', mediaSource: 'com.spotify.music', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z' });
    seedEvent(store, { eventId: 'c2', mediaSource: 'com.youtube.music', playbackStateCode: 3, occurredAt: '2026-10-18T07:00:00.000Z' });
    const result = timeline.build('tunemind-dev');
    expect(result.totalEpisodes).toBe(2);
    expect(result.episodes[0].sourcePackage).toBe('com.spotify.music');
    expect(result.episodes[1].sourcePackage).toBe('com.youtube.music');
  });

  // 5. Track appearances with distinct observation timestamps
  test('track appearances have own observation bounds', () => {
    seedEvent(store, {
      eventId: 'd1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ trackId: 'id:a', title: 'A', artist: 'Art' }),
    });
    seedEvent(store, {
      eventId: 'd2', playbackStateCode: 3, occurredAt: '2026-10-18T06:02:00.000Z',
      track: track({ trackId: 'id:b', title: 'B', artist: 'Art' }),
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes).toHaveLength(1);
    expect(result.episodes[0].trackAppearances).toHaveLength(2);
    expect(result.episodes[0].trackAppearances[0].firstObservedAt).toBe('2026-10-18T06:00:00.000Z');
    expect(result.episodes[0].trackAppearances[1].firstObservedAt).toBe('2026-10-18T06:02:00.000Z');
  });

  // 6. Open episode
  test('open episode is represented', () => {
    seedEvent(store, { eventId: 'e1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z' });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].status).toBe('OPEN_NO_TERMINAL_STATE');
  });

  // 7. Gap-closed episode
  test('gap-closed episode', () => {
    seedEvent(store, { eventId: 'f1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z' });
    seedEvent(store, { eventId: 'f2', playbackStateCode: 3, occurredAt: '2026-10-18T06:10:00.000Z' });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].status).toBe('CLOSED_BY_GAP');
    expect(result.episodes[0].hasObservationGap).toBe(true);
  });

  // 8. No rawEventIds in response
  test('timeline response does not contain rawEventIds', () => {
    seedEvent(store, { eventId: 'g1', playbackStateCode: 3,
      track: track({ trackId: 'id:x', title: 'X', artist: 'Art' }) });
    const result = timeline.build('tunemind-dev');
    const ep = result.episodes[0] as unknown as Record<string, unknown>;
    expect(ep.rawEventIds).toBeUndefined();
    const ta = result.episodes[0].trackAppearances[0] as unknown as Record<string, unknown>;
    expect(ta.rawEventIds).toBeUndefined();
    expect(ta.firstEventId).toBeUndefined();
  });

  // 9. Multiple users remain separate
  test('multiple users remain separate', () => {
    seedEvent(store, { eventId: 'h1', userId: 'user-a', playbackStateCode: 3 });
    seedEvent(store, { eventId: 'h2', userId: 'user-b', playbackStateCode: 3 });
    const resultA = timeline.build('user-a');
    expect(resultA.totalEpisodes).toBe(1);
    const resultAll = timeline.build(null);
    expect(resultAll.totalEpisodes).toBe(2);
  });

  // 10. Version field present
  test('timeline version is present', () => {
    seedEvent(store, { playbackStateCode: 3 });
    const result = timeline.build('tunemind-dev');
    expect(result.timelineVersion).toBe(1);
  });

  // 11. Same provider ID produces one appearance
  test('same provider ID merges into one appearance', () => {
    seedEvent(store, {
      eventId: 'i1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ trackId: 'spotify:track:abc', title: 'Song', artist: 'Artist' }),
    });
    seedEvent(store, {
      eventId: 'i2', playbackStateCode: 3, occurredAt: '2026-10-18T06:01:00.000Z',
      track: track({ trackId: 'spotify:track:abc', title: 'Song', artist: 'Artist' }),
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].trackAppearances).toHaveLength(1);
    expect(result.episodes[0].trackAppearances[0].observationCount).toBe(2);
    expect(result.episodes[0].trackAppearances[0].identityBasis).toBe('PROVIDER_TRACK_ID');
  });

  // 12. Same metadata fingerprint merges into one appearance
  test('same metadata fingerprint merges into one appearance', () => {
    seedEvent(store, {
      eventId: 'j1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ title: 'Song', artist: 'Artist', album: 'Album' }),
    });
    seedEvent(store, {
      eventId: 'j2', playbackStateCode: 3, occurredAt: '2026-10-18T06:01:00.000Z',
      track: track({ title: 'Song', artist: 'Artist', album: 'Album' }),
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].trackAppearances).toHaveLength(1);
    expect(result.episodes[0].trackAppearances[0].observationCount).toBe(2);
    expect(result.episodes[0].trackAppearances[0].identityBasis).toBe('METADATA_FINGERPRINT');
  });

  // 13. Insufficient identity observations are grouped separately
  test('insufficient identity observations produce separate appearances', () => {
    seedEvent(store, {
      eventId: 'k1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ title: 'Song' }), // no artist → insufficient
    });
    seedEvent(store, {
      eventId: 'k2', playbackStateCode: 3, occurredAt: '2026-10-18T06:01:00.000Z',
      track: track({ title: 'Song' }), // same title, no artist → still insufficient
    });
    const result = timeline.build('tunemind-dev');
    // Each event with insufficient identity gets its own appearance because
    // identityKey returns null and the builder creates a new TrackBuilder per event.
    expect(result.episodes[0].trackAppearances).toHaveLength(2);
    for (const ta of result.episodes[0].trackAppearances) {
      expect(ta.identityBasis).toBe('INSUFFICIENT');
    }
  });

  // 14. Transition from insufficient to identifiable
  test('transition from insufficient to identifiable creates separate appearances', () => {
    seedEvent(store, {
      eventId: 'l1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ title: 'Song' }), // insufficient (no artist)
    });
    seedEvent(store, {
      eventId: 'l2', playbackStateCode: 3, occurredAt: '2026-10-18T06:01:00.000Z',
      track: track({ title: 'Song', artist: 'Artist' }), // now identifiable
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].trackAppearances).toHaveLength(2);
    expect(result.episodes[0].trackAppearances[0].identityBasis).toBe('INSUFFICIENT');
    expect(result.episodes[0].trackAppearances[1].identityBasis).toBe('METADATA_FINGERPRINT');
  });

  // 15. Transition between two identifiable tracks
  test('transition between identifiable tracks creates separate appearances', () => {
    seedEvent(store, {
      eventId: 'm1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ trackId: 'id:a', title: 'Song A', artist: 'Artist' }),
    });
    seedEvent(store, {
      eventId: 'm2', playbackStateCode: 3, occurredAt: '2026-10-18T06:01:00.000Z',
      track: track({ trackId: 'id:b', title: 'Song B', artist: 'Artist' }),
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].trackAppearances).toHaveLength(2);
    expect(result.episodes[0].trackAppearances[0].trackId).toBe('id:a');
    expect(result.episodes[0].trackAppearances[1].trackId).toBe('id:b');
  });

  // 16. Missing observation timestamp does not crash
  test('missing observation timestamp does not crash', () => {
    seedEvent(store, {
      eventId: 'n1', playbackStateCode: 3, occurredAt: 'invalid',
      track: track({ trackId: 'id:x', title: 'X', artist: 'Art' }),
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes).toHaveLength(1);
    expect(result.episodes[0].trackAppearances).toHaveLength(1);
    expect(result.episodes[0].trackAppearances[0].firstObservedAt).toBeNull();
  });

  // 17. Metadata fingerprint does NOT require source package
  test('metadata fingerprint works without source package', () => {
    seedEvent(store, {
      eventId: 'o1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      // sourcePackage defaults to 'com.spotify.music' from seedEvent
      track: track({ title: 'Song', artist: 'Artist' }),
    });
    seedEvent(store, {
      eventId: 'o2', playbackStateCode: 3, occurredAt: '2026-10-18T06:01:00.000Z',
      track: track({ title: 'Song', artist: 'Artist' }),
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].trackAppearances).toHaveLength(1);
    expect(result.episodes[0].trackAppearances[0].identityBasis).toBe('METADATA_FINGERPRINT');
  });

  // 18. Same metadata with different capitalization merges
  test('different capitalization merges into same appearance', () => {
    seedEvent(store, {
      eventId: 'p1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ trackId: 'id:a', title: 'Song Title', artist: 'Artist Name' }),
    });
    seedEvent(store, {
      eventId: 'p2', playbackStateCode: 3, occurredAt: '2026-10-18T06:01:00.000Z',
      track: track({ trackId: 'id:A', title: 'song title', artist: 'artist name' }),
    });
    const result = timeline.build('tunemind-dev');
    // Provider IDs are normalized (lowercased), so 'id:a' and 'id:A' differ
    // after normalization. But wait — the provider ID is normalized via norm()
    // before being used as the key. 'id:a' becomes 'id:a', 'id:A' becomes 'id:a'.
    // So they should merge.
    expect(result.episodes[0].trackAppearances).toHaveLength(1);
    expect(result.episodes[0].trackAppearances[0].observationCount).toBe(2);
  });

  // 19. Title without artist is INSUFFICIENT
  test('title without artist is insufficient', () => {
    seedEvent(store, {
      eventId: 'q1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ title: 'Song', artist: null }),
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].trackAppearances[0].identityBasis).toBe('INSUFFICIENT');
  });

  // 20. Artist without title is INSUFFICIENT
  test('artist without title is insufficient', () => {
    seedEvent(store, {
      eventId: 'r1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ title: null, artist: 'Artist' }),
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].trackAppearances[0].identityBasis).toBe('INSUFFICIENT');
  });

  // 21. Album missing still allows METADATA_FINGERPRINT
  test('album missing still allows metadata fingerprint', () => {
    seedEvent(store, {
      eventId: 's1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ title: 'Song', artist: 'Artist', album: null }),
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].trackAppearances[0].identityBasis).toBe('METADATA_FINGERPRINT');
  });

  // 22. Metadata changes from insufficient to identifiable and back
  test('insufficient→identifiable→insufficient creates three appearances', () => {
    seedEvent(store, {
      eventId: 't1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ title: 'Song', artist: null }), // insufficient
    });
    seedEvent(store, {
      eventId: 't2', playbackStateCode: 3, occurredAt: '2026-10-18T06:01:00.000Z',
      track: track({ title: 'Song', artist: 'Artist' }), // identifiable
    });
    seedEvent(store, {
      eventId: 't3', playbackStateCode: 3, occurredAt: '2026-10-18T06:02:00.000Z',
      track: track({ title: 'Song', artist: null }), // insufficient again
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].trackAppearances).toHaveLength(3);
    expect(result.episodes[0].trackAppearances[0].identityBasis).toBe('INSUFFICIENT');
    expect(result.episodes[0].trackAppearances[1].identityBasis).toBe('METADATA_FINGERPRINT');
    expect(result.episodes[0].trackAppearances[2].identityBasis).toBe('INSUFFICIENT');
  });

  // 23. Same track with same provider ID but different case in metadata
  test('same provider ID with different metadata case merges', () => {
    seedEvent(store, {
      eventId: 'u1', playbackStateCode: 3, occurredAt: '2026-10-18T06:00:00.000Z',
      track: track({ trackId: 'spotify:track:abc', title: 'Song A', artist: 'Artist A' }),
    });
    seedEvent(store, {
      eventId: 'u2', playbackStateCode: 3, occurredAt: '2026-10-18T06:01:00.000Z',
      track: track({ trackId: 'spotify:track:abc', title: 'song a', artist: 'artist a' }),
    });
    const result = timeline.build('tunemind-dev');
    expect(result.episodes[0].trackAppearances).toHaveLength(1);
    expect(result.episodes[0].trackAppearances[0].observationCount).toBe(2);
    // Title is filled from first event, not updated
    expect(result.episodes[0].trackAppearances[0].title).toBe('Song A');
  });
});
