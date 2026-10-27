import { EventIngestionService } from './EventIngestionService';
import { EventStore } from './EventStore';
import { validateEvent } from './EventValidator';
import path from 'path';
import fs from 'fs';

// Helper: valid minimal event
function validEvent(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    eventId: '550e8400-e29b-41d4-a716-446655440000',
    userId: 'user-1',
    sessionId: 'ses-abc',
    deviceId: 'dev-xyz',
    schemaVersion: '1.0',
    eventType: 'PLAYBACK_STATE_CHANGED',
    occurredAt: '2026-10-17T09:00:00.000Z',
    mediaSource: 'com.example.player',
    playback: {
      positionMs: 0,
      elapsedRealtimeMs: 1000,
      isPlaying: true,
      playbackSpeed: 1.0,
    },
    ...overrides,
  };
}

function createStore(): EventStore {
  const tmpFile = path.join(fs.realpathSync('.'), `__test_${Date.now()}_${Math.random().toString(36).slice(2)}.db`);
  return new EventStore(tmpFile);
}

describe('EventIngestionService', () => {
  let store: EventStore;
  let service: EventIngestionService;
  let dbPaths: string[];

  beforeEach(() => {
    dbPaths = [];
    store = createStore();
    dbPaths.push(store.getDbPath());
    service = new EventIngestionService(store);
  });

  afterEach(() => {
    store.close();
    for (const dbPath of dbPaths) {
      try { if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath); } catch { /* best effort */ }
      try { const wal = dbPath + '-wal'; if (fs.existsSync(wal)) fs.unlinkSync(wal); } catch { /* best effort */ }
      try { const shm = dbPath + '-shm'; if (fs.existsSync(shm)) fs.unlinkSync(shm); } catch { /* best effort */ }
    }
  });

  // --- Valid events are accepted and persisted ---
  test('accepts and persists a valid event', async () => {
    const result = await service.ingestBatch([validEvent()]);
    expect(result.accepted).toEqual(['550e8400-e29b-41d4-a716-446655440000']);
    expect(result.rejected).toEqual([]);
    expect(store.count()).toBe(1);
  });

  test('accepts and persists multiple valid events', async () => {
    const events = [
      validEvent({ eventId: 'a0000000-e29b-41d4-a716-446655440001' }),
      validEvent({ eventId: 'a0000000-e29b-41d4-a716-446655440002', eventType: 'METADATA_CHANGED' }),
      validEvent({ eventId: 'a0000000-e29b-41d4-a716-446655440003', eventType: 'SESSION_CREATED' }),
    ];
    const result = await service.ingestBatch(events);
    expect(result.accepted).toHaveLength(3);
    expect(result.rejected).toEqual([]);
    expect(store.count()).toBe(3);
  });

  test('persisted event stores the full payload JSON', async () => {
    await service.ingestBatch([validEvent()]);
    const stored = store.listAll();
    expect(stored).toHaveLength(1);
    expect(stored[0].event_id).toBe('550e8400-e29b-41d4-a716-446655440000');
    const parsed = JSON.parse(stored[0].payload_json);
    expect(parsed.playback.isPlaying).toBe(true);
  });

  // --- Invalid events are rejected ---
  test('rejects event with missing eventId', async () => {
    const event = validEvent();
    delete event.eventId;
    const result = await service.ingestBatch([event]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toHaveLength(1);
    expect(result.rejected[0].errors.length).toBeGreaterThan(0);
    expect(store.count()).toBe(0);
  });

  test('rejects event with empty eventId', async () => {
    const result = await service.ingestBatch([validEvent({ eventId: '' })]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toHaveLength(1);
    expect(store.count()).toBe(0);
  });

  test('rejects event with invalid UUID format', async () => {
    const result = await service.ingestBatch([validEvent({ eventId: 'not-a-uuid' })]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toHaveLength(1);
    expect(store.count()).toBe(0);
  });

  test('rejects event with invalid schemaVersion', async () => {
    const result = await service.ingestBatch([validEvent({ schemaVersion: '2.0' })]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toHaveLength(1);
    expect(store.count()).toBe(0);
  });

  test('rejects event with unknown eventType', async () => {
    const result = await service.ingestBatch([validEvent({ eventType: 'SKIP' })]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toHaveLength(1);
    expect(store.count()).toBe(0);
  });

  test('rejects event with invalid occurredAt format', async () => {
    const result = await service.ingestBatch([validEvent({ occurredAt: 'yesterday' })]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toHaveLength(1);
    expect(store.count()).toBe(0);
  });

  test('rejects event with missing required string fields', async () => {
    const event = validEvent();
    delete event.userId;
    delete event.mediaSource;
    const result = await service.ingestBatch([event]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toHaveLength(1);
    expect(store.count()).toBe(0);
  });

  test('rejects event with missing playback object', async () => {
    const event = validEvent();
    delete event.playback;
    const result = await service.ingestBatch([event]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toHaveLength(1);
    expect(store.count()).toBe(0);
  });

  test('rejects event with invalid playback.positionMs (negative)', async () => {
    const result = await service.ingestBatch([validEvent({ playback: { positionMs: -1, elapsedRealtimeMs: 100, isPlaying: true, playbackSpeed: 1.0 } })]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toHaveLength(1);
    expect(store.count()).toBe(0);
  });

  test('rejects event with invalid playback.isPlaying (non-boolean)', async () => {
    const result = await service.ingestBatch([validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: 1, playbackSpeed: 1.0 } })]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toHaveLength(1);
    expect(store.count()).toBe(0);
  });

  // --- Idempotency (duplicates are acknowledged) ---
  test('replaying the same eventId acknowledges it again (idempotent)', async () => {
    const event = validEvent();
    const first = await service.ingestBatch([event]);
    expect(first.accepted).toHaveLength(1);
    expect(store.count()).toBe(1);

    const second = await service.ingestBatch([event]);
    expect(second.accepted).toEqual(['550e8400-e29b-41d4-a716-446655440000']);  // acknowledged
    expect(second.rejected).toEqual([]);
    expect(store.count()).toBe(1);  // still 1 stored row
  });

  test('replaying same eventId in the same batch acknowledges it once', async () => {
    const event = validEvent();
    const result = await service.ingestBatch([event, event]);
    // Both are acknowledged; only one row stored
    expect(result.accepted).toHaveLength(2);
    expect(result.rejected).toEqual([]);
    expect(store.count()).toBe(1);
  });

  // --- Mixed valid/invalid batch ---
  test('mixed batch accepts valid events and rejects invalid ones', async () => {
    const events = [
      validEvent({ eventId: 'b0000000-e29b-41d4-a716-446655440001' }),
      validEvent({ eventId: 'b0000000-e29b-41d4-a716-446655440002', eventType: 'INVALID_TYPE' }),
      validEvent({ eventId: 'b0000000-e29b-41d4-a716-446655440003' }),
    ];
    const result = await service.ingestBatch(events);
    expect(result.accepted).toEqual(['b0000000-e29b-41d4-a716-446655440001', 'b0000000-e29b-41d4-a716-446655440003']);
    expect(result.rejected).toHaveLength(1);
    expect(result.rejected[0].eventId).toBe('b0000000-e29b-41d4-a716-446655440002');
    expect(store.count()).toBe(2);
  });

  // --- Empty batch ---
  test('empty batch returns empty result', async () => {
    const result = await service.ingestBatch([]);
    expect(result.accepted).toEqual([]);
    expect(result.rejected).toEqual([]);
    expect(store.count()).toBe(0);
  });

  // --- All valid event types are accepted ---
  test('all six valid event types are accepted', async () => {
    const types = ['PLAYBACK_STATE_CHANGED', 'METADATA_CHANGED', 'SESSION_CREATED', 'SESSION_DESTROYED', 'OBSERVATION_STARTED', 'OBSERVATION_STOPPED'];
    const events = types.map((et, i) => validEvent({
      eventId: `c0000000-e29b-41d4-a716-44665544${String(i).padStart(4, '0')}`,
      eventType: et,
    }));
    const result = await service.ingestBatch(events);
    expect(result.accepted).toHaveLength(6);
    expect(result.rejected).toEqual([]);
    expect(store.count()).toBe(6);
  });

  // --- Event with all optional fields populated is accepted ---
  test('event with full optional fields is accepted', async () => {
    const event = validEvent({
      eventType: 'METADATA_CHANGED',
      track: {
        trackId: 'track-abc',
        title: 'Song',
        artist: 'Artist',
        album: 'Album',
        durationMs: 200000,
      },
      previousTrack: {
        title: 'Previous',
        artist: 'Prev Artist',
        durationMs: 180000,
        positionMs: 120000,
        elapsedRealtimeMs: 50000,
      },
    });
    const result = await service.ingestBatch([event]);
    expect(result.accepted).toHaveLength(1);
    expect(result.rejected).toEqual([]);
    expect(store.count()).toBe(1);
  });

  // --- Valid event types ---
  test('SESSION_CREATED is accepted', async () => {
    const result = await service.ingestBatch([validEvent({ eventType: 'SESSION_CREATED' })]);
    expect(result.accepted).toHaveLength(1);
  });

  test('SESSION_DESTROYED is accepted', async () => {
    const result = await service.ingestBatch([validEvent({ eventType: 'SESSION_DESTROYED' })]);
    expect(result.accepted).toHaveLength(1);
  });

  test('OBSERVATION_STARTED is accepted', async () => {
    const result = await service.ingestBatch([validEvent({ eventType: 'OBSERVATION_STARTED' })]);
    expect(result.accepted).toHaveLength(1);
  });

  test('OBSERVATION_STOPPED is accepted', async () => {
    const result = await service.ingestBatch([validEvent({ eventType: 'OBSERVATION_STOPPED' })]);
    expect(result.accepted).toHaveLength(1);
  });

  // --- playbackStateCode persistence ---
  test('playbackStateCode survives ingestion and persistence', async () => {
    const event = validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: true, playbackSpeed: 1.0, playbackStateCode: 3 } });
    const result = await service.ingestBatch([event]);
    expect(result.accepted).toHaveLength(1);
    const stored = store.listAll();
    expect(stored).toHaveLength(1);
    const parsed = JSON.parse(stored[0].payload_json);
    expect(parsed.playback.playbackStateCode).toBe(3);
    expect(parsed.playback.isPlaying).toBe(true);
  });

  // --- Nullable field persistence ---
  test('null playback fields survive ingestion and persistence', async () => {
    const event = validEvent({ playback: { positionMs: null, elapsedRealtimeMs: null, isPlaying: true, playbackSpeed: null } });
    const result = await service.ingestBatch([event]);
    expect(result.accepted).toHaveLength(1);
    const stored = store.listAll();
    expect(stored).toHaveLength(1);
    const parsed = JSON.parse(stored[0].payload_json);
    expect(parsed.playback.positionMs).toBeNull();
    expect(parsed.playback.elapsedRealtimeMs).toBeNull();
    expect(parsed.playback.playbackSpeed).toBeNull();
    expect(parsed.playback.isPlaying).toBe(true);
  });
});

describe('EventValidator (unit)', () => {
  test('rejects null input', () => {
    const result = validateEvent(null as unknown as Record<string, unknown>);
    expect(result.valid).toBe(false);
  });

  test('rejects non-object input', () => {
    const result = validateEvent('hello' as unknown as Record<string, unknown>);
    expect(result.valid).toBe(false);
  });

  test('rejects event with track as array instead of object', () => {
    const event = validEvent({ track: ['invalid'] });
    const result = validateEvent(event);
    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.startsWith('track:'))).toBe(true);
  });

  test('rejects event with playbackSpeed NaN', () => {
    const event = validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: true, playbackSpeed: NaN } });
    const result = validateEvent(event);
    expect(result.valid).toBe(false);
  });

  test('accepts event with track: null', () => {
    const event = validEvent({ track: null });
    const result = validateEvent(event);
    expect(result.valid).toBe(true);
  });

  test('accepts event with previousTrack: null', () => {
    const event = validEvent({ previousTrack: null });
    const result = validateEvent(event);
    expect(result.valid).toBe(true);
  });

  // --- playbackStateCode preservation ---
  test('legacy event without playbackStateCode is accepted', () => {
    const result = validateEvent(validEvent());
    expect(result.valid).toBe(true);
  });

  test('explicit null playbackStateCode is accepted', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: true, playbackSpeed: 1.0, playbackStateCode: null } }));
    expect(result.valid).toBe(true);
  });

  test('valid playbackStateCode values (0, 1, 2, 3) are accepted', () => {
    for (const code of [0, 1, 2, 3]) {
      const result = validateEvent(validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: code === 3, playbackSpeed: 1.0, playbackStateCode: code } }));
      expect(result.valid).toBe(true);
    }
  });

  test('playbackStateCode greater than 3 is accepted', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: false, playbackSpeed: 1.0, playbackStateCode: 7 } }));
    expect(result.valid).toBe(true);
  });

  test('negative playbackStateCode is rejected', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: false, playbackSpeed: 1.0, playbackStateCode: -1 } }));
    expect(result.valid).toBe(false);
  });

  test('fractional playbackStateCode is rejected', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: false, playbackSpeed: 1.0, playbackStateCode: 2.5 } }));
    expect(result.valid).toBe(false);
  });

  test('string playbackStateCode is rejected', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: false, playbackSpeed: 1.0, playbackStateCode: '3' } }));
    expect(result.valid).toBe(false);
  });

  test('boolean playbackStateCode is rejected', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: false, playbackSpeed: 1.0, playbackStateCode: true } }));
    expect(result.valid).toBe(false);
  });

  // --- Nullable playback measurements ---
  test('null positionMs is accepted', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: null, elapsedRealtimeMs: 100, isPlaying: true, playbackSpeed: 1.0 } }));
    expect(result.valid).toBe(true);
  });

  test('null elapsedRealtimeMs is accepted', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: null, isPlaying: true, playbackSpeed: 1.0 } }));
    expect(result.valid).toBe(true);
  });

  test('null playbackSpeed is accepted', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: 0, elapsedRealtimeMs: 100, isPlaying: true, playbackSpeed: null } }));
    expect(result.valid).toBe(true);
  });

  test('all three nullable fields null is accepted', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: null, elapsedRealtimeMs: null, isPlaying: true, playbackSpeed: null } }));
    expect(result.valid).toBe(true);
  });

  test('absent positionMs is accepted (legacy compat)', () => {
    const result = validateEvent(validEvent({ playback: { elapsedRealtimeMs: 100, isPlaying: true, playbackSpeed: 1.0 } }));
    expect(result.valid).toBe(true);
  });

  test('negative positionMs is rejected', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: -1, elapsedRealtimeMs: 100, isPlaying: true, playbackSpeed: 1.0 } }));
    expect(result.valid).toBe(false);
  });

  test('string positionMs is rejected', () => {
    const result = validateEvent(validEvent({ playback: { positionMs: '0', elapsedRealtimeMs: 100, isPlaying: true, playbackSpeed: 1.0 } }));
    expect(result.valid).toBe(false);
  });

});
