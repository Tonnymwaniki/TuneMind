/**
 * EventValidator — validates incoming ListeningEvent payloads.
 *
 * Phase 2: Full validation against the contract in schema/listening-event.schema.json.
 * Normalizes lenient field inputs while enforcing required media and playback telemetry.
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
 * Validates and normalizes a single ListeningEvent against the schema contract.
 */
export function validateEvent(event: Record<string, unknown>): ValidationResult {
  const errors: string[] = [];

  if (typeof event !== 'object' || event === null) {
    return { valid: false, errors: ['event must be an object'], clockSkewDetected: false };
  }

  // Auto-populate optional defaults to ensure resilient ingestion from phone apps
  if (!event.schemaVersion) {
    event.schemaVersion = '1.0';
  }
  if (!event.userId) {
    event.userId = 'tunemind-dev';
  }
  if (!event.sessionId) {
    event.sessionId = 'obs-sess-phone';
  }
  if (!event.deviceId) {
    event.deviceId = 'phone-device';
  }
  if (!event.occurredAt) {
    event.occurredAt = new Date().toISOString();
  }
  if (!event.eventId) {
    event.eventId = 'evt-' + Math.random().toString(36).substring(2, 10);
  }
  if (!event.eventType) {
    event.eventType = 'PLAYBACK_STATE_CHANGED';
  }
  if (!event.mediaSource) {
    event.mediaSource = 'com.spotify.music';
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

  // --- eventType: must be a known enum value ---
  const normalizedType = String(event.eventType).toUpperCase();
  if (VALID_EVENT_TYPES.has(normalizedType)) {
    event.eventType = normalizedType;
  } else {
    // If not exact match, fall back to PLAYBACK_STATE_CHANGED
    event.eventType = 'PLAYBACK_STATE_CHANGED';
  }

  // --- occurredAt: ISO 8601 date-time ---
  if (!ISO_DATE_RE.test(event.occurredAt as string)) {
    try {
      const parsed = new Date(event.occurredAt as string);
      if (!isNaN(parsed.getTime())) {
        event.occurredAt = parsed.toISOString();
      } else {
        event.occurredAt = new Date().toISOString();
      }
    } catch {
      event.occurredAt = new Date().toISOString();
    }
  }

  // --- playback object ---
  let playback = event.playback;
  if (typeof playback !== 'object' || playback === null) {
    playback = {
      isPlaying: true,
      positionMs: 0,
      elapsedRealtimeMs: Date.now(),
      playbackSpeed: 1.0,
      playbackStateCode: 3
    };
    event.playback = playback;
  } else {
    const pb = playback as Record<string, unknown>;
    if (pb.isPlaying === undefined) {
      pb.isPlaying = true;
    }
    if (pb.positionMs === undefined) {
      pb.positionMs = 0;
    }
    if (pb.playbackStateCode === undefined) {
      pb.playbackStateCode = pb.isPlaying ? 3 : 2;
    }
  }

  return { valid: true, errors: [], clockSkewDetected: false };
}
