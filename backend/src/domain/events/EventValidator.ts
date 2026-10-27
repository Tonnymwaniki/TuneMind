/**
 * EventValidator — validates incoming ListeningEvent payloads.
 *
 * Phase 2: Full validation against the contract in schema/listening-event.schema.json.
 * Rejects malformed, missing-required-field, and unrecognized-enum events.
 * Clock-skew detection is deferred (requires a known-good reference time).
 */

const VALID_EVENT_TYPES: ReadonlySet<string> = new Set([
  'PLAYBACK_STATE_CHANGED',
  'METADATA_CHANGED',
  'SESSION_CREATED',
  'SESSION_DESTROYED',
  'OBSERVATION_STARTED',
  'OBSERVATION_STOPPED',
]);

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/;

export type ListeningEventType =
  | 'PLAYBACK_STATE_CHANGED'
  | 'METADATA_CHANGED'
  | 'SESSION_CREATED'
  | 'SESSION_DESTROYED'
  | 'OBSERVATION_STARTED'
  | 'OBSERVATION_STOPPED';

export interface TrackData {
  trackId?: string | null;
  title?: string | null;
  artist?: string | null;
  album?: string | null;
  durationMs?: number | null;
}

export interface PlaybackData {
  positionMs: number | null;
  elapsedRealtimeMs: number | null;
  isPlaying: boolean;
  playbackSpeed: number | null;
  playbackStateCode?: number | null;
}

export interface PreviousTrackData {
  title?: string | null;
  artist?: string | null;
  durationMs?: number | null;
  positionMs: number;
  elapsedRealtimeMs: number;
}

/** Shape of a single raw ListeningEvent as received from the Android client. */
export interface ListeningEvent {
  eventId: string;
  userId: string;
  sessionId: string;
  deviceId: string;
  schemaVersion: '1.0';
  eventType: ListeningEventType;
  occurredAt: string;
  receivedAt?: string | null;
  mediaSource: string;
  track?: TrackData | null;
  playback: PlaybackData;
  previousTrack?: PreviousTrackData | null;
}

export interface ValidationResult {
  valid: boolean;
  errors: string[];
  clockSkewDetected: boolean;
  skewMs?: number;
}

/**
 * Validates a single ListeningEvent against the schema contract.
 */
export function validateEvent(event: Record<string, unknown>): ValidationResult {
  const errors: string[] = [];

  if (typeof event !== 'object' || event === null) {
    return { valid: false, errors: ['event must be an object'], clockSkewDetected: false };
  }

  // --- Required string fields ---
  const stringRequired = ['eventId', 'userId', 'sessionId', 'deviceId', 'schemaVersion', 'eventType', 'occurredAt', 'mediaSource'] as const;
  for (const field of stringRequired) {
    const val = event[field];
    if (typeof val !== 'string' || val.length === 0) {
      errors.push(`${field}: required non-empty string`);
    }
  }

  if (errors.length > 0) {
    return { valid: false, errors, clockSkewDetected: false };
  }

  // --- eventId: UUID format ---
  if (!UUID_RE.test(event.eventId as string)) {
    errors.push('eventId: must be a valid UUID');
  }

  // --- schemaVersion: must be exactly "1.0" ---
  if (event.schemaVersion !== '1.0') {
    errors.push('schemaVersion: must be "1.0"');
  }

  // --- eventType: must be a known enum value ---
  if (!VALID_EVENT_TYPES.has(event.eventType as string)) {
    errors.push(`eventType: must be one of ${[...VALID_EVENT_TYPES].join(', ')}`);
  }

  // --- occurredAt: ISO 8601 date-time ---
  if (!ISO_DATE_RE.test(event.occurredAt as string)) {
    errors.push('occurredAt: must be a valid ISO 8601 date-time string');
  }

  // --- mediaSource: non-empty (already checked above) ---

  // --- playback object ---
  const playback = event.playback;
  if (typeof playback !== 'object' || playback === null) {
    errors.push('playback: required object');
  } else {
    const pb = playback as Record<string, unknown>;
    // positionMs — optional, nullable, non-negative integer
    if (pb.positionMs !== undefined && pb.positionMs !== null) {
      if (typeof pb.positionMs !== 'number' || !Number.isInteger(pb.positionMs) || (pb.positionMs as number) < 0) {
        errors.push('playback.positionMs: must be a non-negative integer or null');
      }
    }
    // elapsedRealtimeMs — optional, nullable, non-negative integer
    if (pb.elapsedRealtimeMs !== undefined && pb.elapsedRealtimeMs !== null) {
      if (typeof pb.elapsedRealtimeMs !== 'number' || !Number.isInteger(pb.elapsedRealtimeMs) || (pb.elapsedRealtimeMs as number) < 0) {
        errors.push('playback.elapsedRealtimeMs: must be a non-negative integer or null');
      }
    }
    if (typeof pb.isPlaying !== 'boolean') {
      errors.push('playback.isPlaying: required boolean');
    }
    // playbackSpeed — optional, nullable, non-negative finite number
    if (pb.playbackSpeed !== undefined && pb.playbackSpeed !== null) {
      if (typeof pb.playbackSpeed !== 'number' || !Number.isFinite(pb.playbackSpeed as number) || (pb.playbackSpeed as number) < 0) {
        errors.push('playback.playbackSpeed: must be a non-negative finite number or null');
      }
    }
    // playbackStateCode — optional, nullable, non-negative integer
    if (pb.playbackStateCode !== undefined && pb.playbackStateCode !== null) {
      if (typeof pb.playbackStateCode !== 'number' || !Number.isInteger(pb.playbackStateCode) || (pb.playbackStateCode as number) < 0) {
        errors.push('playback.playbackStateCode: must be a non-negative integer or null');
      }
    }
  }

  // --- optional track object ---
  if (event.track !== null && event.track !== undefined) {
    if (typeof event.track !== 'object' || Array.isArray(event.track)) {
      errors.push('track: must be an object or null');
    } else {
      const t = event.track as Record<string, unknown>;
      if (t.trackId !== undefined && t.trackId !== null && typeof t.trackId !== 'string') {
        errors.push('track.trackId: must be a string or null');
      }
      if (t.title !== undefined && t.title !== null && typeof t.title !== 'string') {
        errors.push('track.title: must be a string or null');
      }
      if (t.artist !== undefined && t.artist !== null && typeof t.artist !== 'string') {
        errors.push('track.artist: must be a string or null');
      }
      if (t.album !== undefined && t.album !== null && typeof t.album !== 'string') {
        errors.push('track.album: must be a string or null');
      }
      if (t.durationMs !== undefined && t.durationMs !== null) {
        if (typeof t.durationMs !== 'number' || !Number.isInteger(t.durationMs) || (t.durationMs as number) < 0) {
          errors.push('track.durationMs: must be a non-negative integer or null');
        }
      }
    }
  }

  // --- optional previousTrack object ---
  if (event.previousTrack !== null && event.previousTrack !== undefined) {
    if (typeof event.previousTrack !== 'object') {
      errors.push('previousTrack: must be an object or null');
    } else {
      const pt = event.previousTrack as Record<string, unknown>;
      if (pt.title !== undefined && pt.title !== null && typeof pt.title !== 'string') {
        errors.push('previousTrack.title: must be a string or null');
      }
      if (pt.artist !== undefined && pt.artist !== null && typeof pt.artist !== 'string') {
        errors.push('previousTrack.artist: must be a string or null');
      }
      if (pt.durationMs !== undefined && pt.durationMs !== null) {
        if (typeof pt.durationMs !== 'number' || !Number.isInteger(pt.durationMs) || (pt.durationMs as number) < 0) {
          errors.push('previousTrack.durationMs: must be a non-negative integer or null');
        }
      }
      if (typeof pt.positionMs !== 'number' || !Number.isInteger(pt.positionMs) || (pt.positionMs as number) < 0) {
        errors.push('previousTrack.positionMs: required non-negative integer');
      }
      if (typeof pt.elapsedRealtimeMs !== 'number' || !Number.isInteger(pt.elapsedRealtimeMs) || (pt.elapsedRealtimeMs as number) < 0) {
        errors.push('previousTrack.elapsedRealtimeMs: required non-negative integer');
      }
    }
  }

  if (errors.length > 0) {
    return { valid: false, errors, clockSkewDetected: false };
  }

  // Clock-skew detection is deferred (requires a server-side clock reference).
  return { valid: true, errors: [], clockSkewDetected: false };
}
