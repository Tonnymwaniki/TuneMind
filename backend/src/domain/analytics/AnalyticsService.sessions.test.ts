/**
 * Tests for AnalyticsService.getSessions().
 */

import { AnalyticsService, SessionsSummary, ObserverSessionSummary } from './AnalyticsService';
import { EventStore } from '../events/EventStore';
import path from 'path';
import fs from 'fs';

function createStore(): EventStore {
  const tmpFile = path.join(fs.realpathSync('.'), `__test_sessions_${Date.now()}_${Math.random().toString(36).slice(2)}.db`);
  return new EventStore(tmpFile);
}

/** Seed a single event with a fully qualified payload JSON that includes playback.isPlaying. */
function seedEvent(
  store: EventStore,
  overrides: Record<string, unknown> = {},
): void {
  const isPlaying = overrides['isPlaying'] as boolean | undefined;
  const playbackJson = isPlaying !== undefined
    ? `{"isPlaying":${isPlaying}}`
    : '{}';
  const payloadJson = JSON.stringify({ playback: JSON.parse(playbackJson) });

  store.insert(
    (overrides['eventId'] as string) ?? 'a0000000-0000-0000-0000-000000000001',
    (overrides['userId'] as string) ?? 'tunemind-dev',
    (overrides['sessionId'] as string) ?? 'ses-001',
    (overrides['deviceId'] as string) ?? 'device-1',
    '1.0',
    (overrides['eventType'] as string) ?? 'PLAYBACK_STATE_CHANGED',
    (overrides['occurredAt'] as string) ?? '2026-10-18T06:00:00.000Z',
    '2026-10-18T06:00:01.000Z',
    (overrides['mediaSource'] as string) ?? 'com.spotify.music',
    payloadJson,
  );
}

function firstSession(s: SessionsSummary): ObserverSessionSummary {
  return s.observerSessions[0];
}

function sourceByName(s: ObserverSessionSummary, name: string) {
  return s.sources[name];
}

describe('AnalyticsService.getSessions', () => {
  let store: EventStore;
  let service: AnalyticsService;
  let dbPaths: string[];

  beforeEach(() => {
    dbPaths = [];
    store = createStore();
    dbPaths.push(store.getDbPath());
    service = new AnalyticsService(store);
  });

  afterEach(() => {
    store.close();
    for (const dbPath of dbPaths) {
      try { if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath); } catch { /* best effort */ }
      try { const wal = dbPath + '-wal'; if (fs.existsSync(wal)) fs.unlinkSync(wal); } catch { /* best effort */ }
      try { const shm = dbPath + '-shm'; if (fs.existsSync(shm)) fs.unlinkSync(shm); } catch { /* best effort */ }
    }
  });

  // 1. Empty database
  test('empty database returns consistent empty result', () => {
    const result = service.getSessions('tunemind-dev');
    expect(result.observerSessions).toEqual([]);
    expect(result.totalObserverSessions).toBe(0);
    expect(result.excludedEmptySessionIdCount).toBe(0);
    expect(result.notices.length).toBeGreaterThan(0);
  });

  // 2. Single observer session with multiple event types
  test('single observer session with multiple event types', () => {
    seedEvent(store, { eventId: 'a1', eventType: 'SESSION_CREATED', isPlaying: false });
    seedEvent(store, { eventId: 'a2', eventType: 'PLAYBACK_STATE_CHANGED', isPlaying: true });
    seedEvent(store, { eventId: 'a3', eventType: 'METADATA_CHANGED', isPlaying: true });
    seedEvent(store, { eventId: 'a4', eventType: 'PLAYBACK_STATE_CHANGED', isPlaying: false });

    const result = service.getSessions('tunemind-dev');
    expect(result.totalObserverSessions).toBe(1);
    const sess = firstSession(result);
    expect(sess.eventCount).toBe(4);
    expect(sess.sessionId).toBe('ses-001');
    expect(sess.deviceId).toBe('device-1');

    const src = sourceByName(sess, 'com.spotify.music');
    expect(src.eventCount).toBe(4);
    expect(src.eventCountsByType).toEqual({
      SESSION_CREATED: 1,
      PLAYBACK_STATE_CHANGED: 2,
      METADATA_CHANGED: 1,
    });
    expect(src.playingCallbackCount).toBe(2);
    expect(src.nonPlayingCallbackCount).toBe(2);
    expect(src.unknownPlaybackStateCount).toBe(0);
    expect(src.playingCallbackCount + src.nonPlayingCallbackCount + src.unknownPlaybackStateCount).toBe(src.eventCount);
  });

  // 3. Multiple observer sessions grouped separately
  test('multiple observer sessions are grouped separately', () => {
    seedEvent(store, { eventId: 'b1', sessionId: 'ses-a', isPlaying: true });
    seedEvent(store, { eventId: 'b2', sessionId: 'ses-a', isPlaying: false });
    seedEvent(store, { eventId: 'b3', sessionId: 'ses-b', isPlaying: true });
    seedEvent(store, { eventId: 'b4', sessionId: 'ses-b', isPlaying: true });

    const result = service.getSessions('tunemind-dev');
    expect(result.totalObserverSessions).toBe(2);
    const sessions = result.observerSessions.sort((a, b) => a.sessionId.localeCompare(b.sessionId));
    expect(sessions[0].sessionId).toBe('ses-a');
    expect(sessions[0].eventCount).toBe(2);
    expect(sessions[1].sessionId).toBe('ses-b');
    expect(sessions[1].eventCount).toBe(2);
  });

  // 4. Multiple sources inside one observer session
  test('multiple sources inside one observer session', () => {
    seedEvent(store, { eventId: 'c1', mediaSource: 'com.spotify.music', isPlaying: true });
    seedEvent(store, { eventId: 'c2', mediaSource: 'com.spotify.music', isPlaying: false });
    seedEvent(store, { eventId: 'c3', mediaSource: 'com.youtube.music', isPlaying: true });

    const result = service.getSessions('tunemind-dev');
    expect(result.totalObserverSessions).toBe(1);
    const sess = firstSession(result);
    expect(Object.keys(sess.sources).length).toBe(2);
    expect(sourceByName(sess, 'com.spotify.music').eventCount).toBe(2);
    expect(sourceByName(sess, 'com.youtube.music').eventCount).toBe(1);
    expect(sourceByName(sess, 'com.spotify.music').playingCallbackCount).toBe(1);
    expect(sourceByName(sess, 'com.youtube.music').playingCallbackCount).toBe(1);
  });

  // 5. Identical session IDs from different users remain separate under userId=all
  test('identical session IDs from different users remain separate', () => {
    seedEvent(store, { eventId: 'd1', userId: 'user-a', sessionId: 'shared-ses', isPlaying: true });
    seedEvent(store, { eventId: 'd2', userId: 'user-b', sessionId: 'shared-ses', isPlaying: false });

    const result = service.getSessions(null); // userId=all
    expect(result.totalObserverSessions).toBe(2);
    for (const sess of result.observerSessions) {
      expect(sess.eventCount).toBe(1);
    }
  });

  // 6. Identical session IDs from different devices remain separate
  test('identical session IDs from different devices remain separate', () => {
    seedEvent(store, { eventId: 'e1', userId: 'u1', deviceId: 'phone', sessionId: 's1', isPlaying: true });
    seedEvent(store, { eventId: 'e2', userId: 'u1', deviceId: 'tablet', sessionId: 's1', isPlaying: true });

    const result = service.getSessions('u1');
    expect(result.totalObserverSessions).toBe(2);
  });

  // 7. SESSION_CREATED and SESSION_DESTROYED counts are source-specific
  test('SESSION_CREATED and SESSION_DESTROYED counts per source', () => {
    seedEvent(store, { eventId: 'f1', mediaSource: 'com.spotify.music', eventType: 'SESSION_CREATED', isPlaying: false });
    seedEvent(store, { eventId: 'f2', mediaSource: 'com.spotify.music', eventType: 'PLAYBACK_STATE_CHANGED', isPlaying: true });
    seedEvent(store, { eventId: 'f3', mediaSource: 'com.spotify.music', eventType: 'SESSION_DESTROYED', isPlaying: false });
    seedEvent(store, { eventId: 'f4', mediaSource: 'com.youtube.music', eventType: 'SESSION_CREATED', isPlaying: false });

    const result = service.getSessions('tunemind-dev');
    const sess = firstSession(result);
    expect(sourceByName(sess, 'com.spotify.music').sessionCreatedCount).toBe(1);
    expect(sourceByName(sess, 'com.spotify.music').sessionDestroyedCount).toBe(1);
    expect(sourceByName(sess, 'com.youtube.music').sessionCreatedCount).toBe(1);
    expect(sourceByName(sess, 'com.youtube.music').sessionDestroyedCount).toBe(0);
  });

  // 8. Session without SESSION_CREATED
  test('session without SESSION_CREATED is still represented', () => {
    seedEvent(store, { eventId: 'g1', eventType: 'PLAYBACK_STATE_CHANGED', isPlaying: true });

    const result = service.getSessions('tunemind-dev');
    expect(result.totalObserverSessions).toBe(1);
    const sess = firstSession(result);
    expect(sess.eventCount).toBe(1);
  });

  // 9. True, false, missing, malformed, non-Boolean isPlaying
  test('handles all isPlaying variants', () => {
    // true and false
    seedEvent(store, { eventId: 'h1', isPlaying: true });
    seedEvent(store, { eventId: 'h2', isPlaying: false });
    // missing playback field
    store.insert('h3', 'tunemind-dev', 'ses-001', 'device-1', '1.0', 'PLAYBACK_STATE_CHANGED', '2026-10-18T06:00:00.000Z', '2026-10-18T06:00:01.000Z', 'com.spotify.music', '{}');
    // malformed JSON
    store.insert('h4', 'tunemind-dev', 'ses-001', 'device-1', '1.0', 'PLAYBACK_STATE_CHANGED', '2026-10-18T06:00:00.000Z', '2026-10-18T06:00:01.000Z', 'com.spotify.music', '{bad}');
    // non-boolean isPlaying
    store.insert('h5', 'tunemind-dev', 'ses-001', 'device-1', '1.0', 'PLAYBACK_STATE_CHANGED', '2026-10-18T06:00:00.000Z', '2026-10-18T06:00:01.000Z', 'com.spotify.music', '{"playback":{"isPlaying":"yes"}}');
    // null isPlaying
    store.insert('h6', 'tunemind-dev', 'ses-001', 'device-1', '1.0', 'PLAYBACK_STATE_CHANGED', '2026-10-18T06:00:00.000Z', '2026-10-18T06:00:01.000Z', 'com.spotify.music', '{"playback":{"isPlaying":null}}');

    const result = service.getSessions('tunemind-dev');
    const sess = firstSession(result);
    const src = sourceByName(sess, 'com.spotify.music');
    expect(src.playingCallbackCount).toBe(1);
    expect(src.nonPlayingCallbackCount).toBe(1);
    expect(src.unknownPlaybackStateCount).toBe(4);
    expect(src.playingCallbackCount + src.nonPlayingCallbackCount + src.unknownPlaybackStateCount).toBe(src.eventCount);
  });

  // 10. Three playback categories sum to eventCount
  test('three playback categories sum to eventCount for each source', () => {
    seedEvent(store, { eventId: 'i1', isPlaying: true });
    seedEvent(store, { eventId: 'i2', isPlaying: false });
    seedEvent(store, { eventId: 'i3', isPlaying: true });
    store.insert('i4', 'tunemind-dev', 'ses-001', 'device-1', '1.0', 'PLAYBACK_STATE_CHANGED', '2026-10-18T06:00:00.000Z', '2026-10-18T06:00:01.000Z', 'com.spotify.music', '{}');

    const result = service.getSessions('tunemind-dev');
    const sess = firstSession(result);
    const src = sourceByName(sess, 'com.spotify.music');
    expect(src.playingCallbackCount + src.nonPlayingCallbackCount + src.unknownPlaybackStateCount).toBe(src.eventCount);
  });

  // 11. Empty session IDs excluded
  test('empty session IDs are excluded and disclosed', () => {
    seedEvent(store, { eventId: 'j1', sessionId: '', isPlaying: true });
    seedEvent(store, { eventId: 'j2', sessionId: 'valid-ses', isPlaying: false });

    const result = service.getSessions('tunemind-dev');
    expect(result.totalObserverSessions).toBe(1);
    expect(result.excludedEmptySessionIdCount).toBe(1);
    expect(result.notices.some(n => n.includes('empty session_id'))).toBe(true);
    expect(firstSession(result).sessionId).toBe('valid-ses');
  });

  // 12. Empty source strings
  test('empty source strings do not cause count mismatches', () => {
    seedEvent(store, { eventId: 'k1', mediaSource: '', isPlaying: true });
    seedEvent(store, { eventId: 'k2', mediaSource: '', isPlaying: false });
    seedEvent(store, { eventId: 'k3', mediaSource: 'com.spotify.music', isPlaying: true });

    const result = service.getSessions('tunemind-dev');
    const sess = firstSession(result);
    expect(Object.keys(sess.sources).length).toBe(2);
    expect(sourceByName(sess, '').eventCount).toBe(2);
    expect(sourceByName(sess, 'com.spotify.music').eventCount).toBe(1);
  });

  // 13. Invalid timestamps
  test('invalid timestamps do not crash; all-invalid returns null times and span', () => {
    seedEvent(store, { eventId: 'l1', occurredAt: 'not-a-date', isPlaying: true });
    seedEvent(store, { eventId: 'l2', occurredAt: '', isPlaying: false });

    const result = service.getSessions('tunemind-dev');
    const sess = firstSession(result);
    expect(sess.eventCount).toBe(2);
    expect(sess.firstEventAt).toBeNull();
    expect(sess.lastEventAt).toBeNull();
    expect(sess.observerConnectionSpanMs).toBeNull();

    const src = sourceByName(sess, 'com.spotify.music');
    expect(src.firstEventAt).toBeNull();
    expect(src.lastEventAt).toBeNull();
  });

  // 14. Out-of-order timestamps produce correct min/max
  test('out-of-order timestamps produce correct range', () => {
    seedEvent(store, { eventId: 'm1', occurredAt: '2026-10-18T07:00:00.000Z', isPlaying: true });
    seedEvent(store, { eventId: 'm2', occurredAt: '2026-10-18T06:00:00.000Z', isPlaying: true });
    seedEvent(store, { eventId: 'm3', occurredAt: '2026-10-18T08:00:00.000Z', isPlaying: true });

    const result = service.getSessions('tunemind-dev');
    const sess = firstSession(result);
    expect(sess.firstEventAt).toBe('2026-10-18T06:00:00.000Z');
    expect(sess.lastEventAt).toBe('2026-10-18T08:00:00.000Z');
    expect(sess.observerConnectionSpanMs).toBe(7200000); // 2 hours
  });

  // 15. SQL parameterization
  test('handles unusual userId values safely', () => {
    seedEvent(store, { eventId: 'n1', userId: "admin'--", isPlaying: true });
    seedEvent(store, { eventId: 'n2', userId: 'tunemind-dev', isPlaying: false });

    const result = service.getSessions("admin'--");
    expect(result.totalObserverSessions).toBe(1);
    expect(firstSession(result).eventCount).toBe(1);

    const resultDev = service.getSessions('tunemind-dev');
    expect(resultDev.totalObserverSessions).toBe(1);
    expect(firstSession(resultDev).eventCount).toBe(1);
  });

  // 16. Session-level timestamps are from the full session, not per-source
  test('session-level timestamps reflect all events in the session', () => {
    seedEvent(store, { eventId: 'o1', mediaSource: 'com.spotify.music', occurredAt: '2026-10-18T06:00:00.000Z', isPlaying: true });
    seedEvent(store, { eventId: 'o2', mediaSource: 'com.youtube.music', occurredAt: '2026-10-18T08:00:00.000Z', isPlaying: false });

    const result = service.getSessions('tunemind-dev');
    const sess = firstSession(result);
    expect(sess.firstEventAt).toBe('2026-10-18T06:00:00.000Z');
    expect(sess.lastEventAt).toBe('2026-10-18T08:00:00.000Z');
    expect(sess.observerConnectionSpanMs).toBe(7200000);
  });

  // --- OBSERVATION_STOPPED detection ---
  test('session with OBSERVATION_STOPPED has hasObservationStop=true', () => {
    seedEvent(store, { eventId: 'p1', eventType: 'PLAYBACK_STATE_CHANGED', isPlaying: true });
    seedEvent(store, { eventId: 'p2', eventType: 'OBSERVATION_STOPPED', isPlaying: false, occurredAt: '2026-10-18T07:00:00.000Z' });

    const result = service.getSessions('tunemind-dev');
    expect(firstSession(result).hasObservationStop).toBe(true);
  });

  test('session without OBSERVATION_STOPPED has hasObservationStop=false', () => {
    seedEvent(store, { eventId: 'q1', eventType: 'PLAYBACK_STATE_CHANGED', isPlaying: true });

    const result = service.getSessions('tunemind-dev');
    expect(firstSession(result).hasObservationStop).toBe(false);
  });
});
