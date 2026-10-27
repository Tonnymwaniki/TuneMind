import { AnalyticsService } from './AnalyticsService';
import { EventStore } from '../events/EventStore';
import path from 'path';
import fs from 'fs';

function createStore(): EventStore {
  const tmpFile = path.join(fs.realpathSync('.'), `__test_analytics_${Date.now()}_${Math.random().toString(36).slice(2)}.db`);
  return new EventStore(tmpFile);
}

function seedEvent(store: EventStore, overrides: Record<string, unknown> = {}): void {
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
    '{}',
  );
}

describe('AnalyticsService', () => {
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

  // --- Empty database ---
  test('empty database returns zero counts', () => {
    const summary = service.getSummary('tunemind-dev');
    expect(summary.totalEvents).toBe(0);
    expect(summary.bySource).toEqual({});
    expect(summary.byEventType).toEqual({});
    expect(summary.sources).toEqual([]);
    expect(summary.timeRange.earliest).toBeNull();
    expect(summary.timeRange.latest).toBeNull();
    expect(summary.sessionCreatedEventCount).toBe(0);
    expect(summary.distinctSessionCount).toBe(0);
    expect(summary.notices.length).toBeGreaterThan(0);
  });

  // --- Multiple sources and event types ---
  test('aggregates by source and event type', () => {
    seedEvent(store, { eventId: 'b1', mediaSource: 'com.spotify.music', eventType: 'PLAYBACK_STATE_CHANGED', occurredAt: '2026-10-18T06:00:00.000Z' });
    seedEvent(store, { eventId: 'b2', mediaSource: 'com.spotify.music', eventType: 'METADATA_CHANGED', occurredAt: '2026-10-18T06:01:00.000Z' });
    seedEvent(store, { eventId: 'b3', mediaSource: 'com.youtube.music', eventType: 'PLAYBACK_STATE_CHANGED', occurredAt: '2026-10-18T07:00:00.000Z' });
    seedEvent(store, { eventId: 'b4', mediaSource: 'com.spotify.music', eventType: 'SESSION_CREATED', occurredAt: '2026-10-18T06:00:00.000Z' });

    const summary = service.getSummary('tunemind-dev');
    expect(summary.totalEvents).toBe(4);
    expect(summary.bySource).toEqual({
      'com.spotify.music': 3,
      'com.youtube.music': 1,
    });
    expect(summary.byEventType).toEqual({
      'PLAYBACK_STATE_CHANGED': 2,
      'METADATA_CHANGED': 1,
      'SESSION_CREATED': 1,
    });
    expect(summary.sources).toEqual(['com.spotify.music', 'com.youtube.music']);
    expect(summary.sessionCreatedEventCount).toBe(1);
    expect(summary.distinctSessionCount).toBe(1);
  });

  // --- Correct total counts ---
  test('total count matches inserted events', () => {
    for (let i = 0; i < 50; i++) {
      seedEvent(store, { eventId: `c${String(i).padStart(3, '0')}` });
    }
    const summary = service.getSummary('tunemind-dev');
    expect(summary.totalEvents).toBe(50);
  });

  // --- Session counts ---
  test('session created count vs distinct session count', () => {
    seedEvent(store, { eventId: 'd1', sessionId: 'ses-a', eventType: 'SESSION_CREATED' });
    seedEvent(store, { eventId: 'd2', sessionId: 'ses-a', eventType: 'PLAYBACK_STATE_CHANGED' });
    seedEvent(store, { eventId: 'd3', sessionId: 'ses-b', eventType: 'SESSION_CREATED' });
    seedEvent(store, { eventId: 'd4', sessionId: 'ses-b', eventType: 'PLAYBACK_STATE_CHANGED' });
    seedEvent(store, { eventId: 'd5', sessionId: 'ses-c', eventType: 'SESSION_CREATED' });

    const summary = service.getSummary('tunemind-dev');
    expect(summary.sessionCreatedEventCount).toBe(3);
    expect(summary.distinctSessionCount).toBe(3);
    expect(summary.totalEvents).toBe(5);
  });

  // --- Filtering by user ID ---
  test('filters by user id', () => {
    seedEvent(store, { eventId: 'e1', userId: 'user-a', mediaSource: 'com.spotify.music' });
    seedEvent(store, { eventId: 'e2', userId: 'user-a', mediaSource: 'com.spotify.music' });
    seedEvent(store, { eventId: 'e3', userId: 'user-b', mediaSource: 'com.youtube.music' });

    const summaryA = service.getSummary('user-a');
    expect(summaryA.totalEvents).toBe(2);
    expect(summaryA.bySource).toEqual({ 'com.spotify.music': 2 });
    expect(summaryA.sources).toEqual(['com.spotify.music']);

    const summaryB = service.getSummary('user-b');
    expect(summaryB.totalEvents).toBe(1);
    expect(summaryB.bySource).toEqual({ 'com.youtube.music': 1 });

    const summaryAll = service.getSummary(null);
    expect(summaryAll.totalEvents).toBe(3);
    expect(summaryAll.userId).toBe('(all)');
  });

  // --- Missing or invalid timestamps ---
  test('invalid timestamps are excluded from time range', () => {
    seedEvent(store, { eventId: 'f1', occurredAt: 'not-a-date' });
    seedEvent(store, { eventId: 'f2', occurredAt: '' });
    seedEvent(store, { eventId: 'f3', occurredAt: '2026-10-18T06:00:00.000Z' });
    seedEvent(store, { eventId: 'f4', occurredAt: '2026-10-18T07:00:00.000Z' });

    const summary = service.getSummary('tunemind-dev');
    expect(summary.totalEvents).toBe(4);
    expect(summary.timeRange.earliest).toBe('2026-10-18T06:00:00.000Z');
    expect(summary.timeRange.latest).toBe('2026-10-18T07:00:00.000Z');
  });

  // --- SQL parameterization ---
  test('handles unusual userId values safely', () => {
    seedEvent(store, { eventId: 'g1', userId: "admin'--", mediaSource: 'com.spotify.music' });
    seedEvent(store, { eventId: 'g2', userId: 'tunemind-dev', mediaSource: 'com.spotify.music' });

    const summary = service.getSummary("admin'--");
    expect(summary.totalEvents).toBe(1);
    expect(summary.bySource).toEqual({ 'com.spotify.music': 1 });

    // Confirm the other user's events are not mixed in
    const summaryDev = service.getSummary('tunemind-dev');
    expect(summaryDev.totalEvents).toBe(1);
  });

  // --- Malformed records: empty session_id ---
  test('empty session_id is not counted as a distinct session', () => {
    seedEvent(store, { eventId: 'h1', sessionId: '' });
    seedEvent(store, { eventId: 'h2', sessionId: 'ses-valid' });

    const summary = service.getSummary('tunemind-dev');
    expect(summary.distinctSessionCount).toBe(1);
    expect(summary.totalEvents).toBe(2);
  });

  // --- bySource sum equals totalEvents ---
  test('sum of bySource values equals totalEvents', () => {
    seedEvent(store, { eventId: 'j1', mediaSource: 'com.spotify.music' });
    seedEvent(store, { eventId: 'j2', mediaSource: 'com.spotify.music' });
    seedEvent(store, { eventId: 'j3', mediaSource: 'com.youtube.music' });
    seedEvent(store, { eventId: 'j4', mediaSource: 'com.spotify.music' });

    const summary = service.getSummary('tunemind-dev');
    const sourceSum = Object.values(summary.bySource).reduce((a, b) => a + b, 0);
    expect(sourceSum).toBe(summary.totalEvents);
  });

  // --- byEventType sum equals totalEvents ---
  test('sum of byEventType values equals totalEvents', () => {
    seedEvent(store, { eventId: 'k1', eventType: 'PLAYBACK_STATE_CHANGED' });
    seedEvent(store, { eventId: 'k2', eventType: 'METADATA_CHANGED' });
    seedEvent(store, { eventId: 'k3', eventType: 'PLAYBACK_STATE_CHANGED' });
    seedEvent(store, { eventId: 'k4', eventType: 'SESSION_CREATED' });

    const summary = service.getSummary('tunemind-dev');
    const typeSum = Object.values(summary.byEventType).reduce((a, b) => a + b, 0);
    expect(typeSum).toBe(summary.totalEvents);
  });

  // --- Empty-string media_source is grouped consistently ---
  test('empty-string media_source is handled without count mismatch', () => {
    seedEvent(store, { eventId: 'l1', mediaSource: '' });
    seedEvent(store, { eventId: 'l2', mediaSource: '' });
    seedEvent(store, { eventId: 'l3', mediaSource: 'com.spotify.music' });

    const summary = service.getSummary('tunemind-dev');
    expect(summary.totalEvents).toBe(3);
    expect(summary.bySource).toEqual({ '': 2, 'com.spotify.music': 1 });
    expect(summary.sources).toEqual(['', 'com.spotify.music']);
    const sourceSum = Object.values(summary.bySource).reduce((a, b) => a + b, 0);
    expect(sourceSum).toBe(summary.totalEvents);
  });

  // --- Notices ---
  test('notices are present for development mode', () => {
    seedEvent(store, { eventId: 'i1' });
    const summary = service.getSummary('tunemind-dev');
    expect(summary.notices.length).toBeGreaterThanOrEqual(3);
    expect(summary.notices.some(n => n.includes('development placeholder'))).toBe(true);
    expect(summary.notices.some(n => n.includes('device wall clock'))).toBe(true);
    expect(summary.notices.some(n => n.includes('not actual listening duration'))).toBe(true);
  });
});
