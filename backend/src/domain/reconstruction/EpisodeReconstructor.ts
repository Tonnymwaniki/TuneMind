/**
 * EpisodeReconstructor — deterministic, read-only reconstruction of listening
 * episodes from stored raw events.
 *
 * Reconstruction rules and thresholds:
 *
 * 1. Input events are deduplicated by eventId. Conflicting payloads sharing the
 *    same eventId are flagged but the canonical (lexicographically smallest JSON)
 *    representation is used.
 *
 * 2. Events are ordered by occurred_at (ISO string sort), then eventId, then
 *    eventType. This is deterministic but NOT immune to wall-clock changes.
 *
 * 3. A new episode begins when the prior observation has a gap > gapBoundaryMs
 *    or when the prior observation's playback state is a terminal state
 *    (STOPPED=1, NONE=0, or SESSION_DESTROYED).
 *
 * 4. An episode ends when:
 *    - A STOPPED (1) or NONE (0) state is observed.
 *    - A SESSION_DESTROYED event occurs for that source.
 *    - A gap > gapBoundaryMs is detected.
 *    - The observer session's last event is reached (OPEN or INFERRED_BOUNDARY).
 *
 * 5. PLAYING state (3) continues an episode. PAUSED (2) continues without
 *    accumulating active time.
 *
 * 6. Active playback duration is NOT computed because the backend has only
 *    wall-clock timestamps (occurred_at), not the monotonic elapsed-realtime
 *    clock used by the Android engine. The backend reports only the wall-clock
 *    span between episode boundaries.
 *
 * 7. Track appearances are detected from payload_json.track.* using the same
 *    identity rules as the Android engine: provider track ID within the same
 *    source package, then metadata fingerprint (title+artist+album).
 *
 * 8. When playbackStateCode is absent (legacy events) or null, the isPlaying
 *    boolean is used as a fallback. true → treated as PLAYING, false → treated
 *    as state unavailable (the episode continues but no duration is counted).
 *    The fallback is explicitly flagged in the episode's evidence notices.
 */

import { EventStore, StoredEvent } from '../events/EventStore';

// ─── Constants ────────────────────────────────────────────────────────────────

/** Maximum wall-clock gap (ms) before an episode is considered closed by gap. */
const GAP_BOUNDARY_MS = 300_000; // 5 minutes — matches Android DEFAULT_GAP_BOUNDARY_MS

/** Reconstruction schema version. Increment when breaking changes are made. */
export const RECONSTRUCTION_VERSION = 1;

/** Android PlaybackState constants. */
const STATE_NONE = 0;
const STATE_STOPPED = 1;
const STATE_PAUSED = 2;
const STATE_PLAYING = 3;

/** Valid ISO 8601 regex (same as AnalyticsService). */
const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/;

// ─── Types ────────────────────────────────────────────────────────────────────

export type EpisodeBoundaryType =
  | 'OBSERVED_PLAYING'
  | 'OBSERVED_STOP'
  | 'OBSERVED_NON_PLAYING_STATE'
  | 'OBSERVED_SESSION_DESTROYED'
  | 'INFERRED_OBSERVER_SESSION_BOUNDARY'
  | 'GAP_CENSORED'
  | 'UNKNOWN';

export type EpisodeStatus =
  | 'OPEN_NO_TERMINAL_STATE'
  | 'ENDED_BY_OBSERVED_STOP'
  | 'ENDED_BY_OBSERVED_NON_PLAYING_STATE'
  | 'ENDED_BY_SESSION_DESTRUCTION'
  | 'CLOSED_BY_OBSERVER_BOUNDARY'
  | 'CLOSED_BY_GAP';

export type TrackIdentityBasis = 'PROVIDER_TRACK_ID' | 'METADATA_FINGERPRINT' | 'INSUFFICIENT';
export type TrackAppearanceEvidence =
  | 'SNAPSHOT_ONLY'
  | 'SAME_PROVIDER_ID'
  | 'SAME_METADATA_FINGERPRINT'
  | 'CANDIDATE_CHANGE'
  | 'IDENTITY_INSUFFICIENT';

export interface TrackAppearance {
  appearanceId: string;
  sourcePackage: string | null;
  trackId: string | null;
  title: string | null;
  artist: string | null;
  album: string | null;
  durationMs: number | null;
  firstEventId: string;
  firstObservedAt: string | null;
  lastObservedAt: string | null;
  observationCount: number;
  identityBasis: TrackIdentityBasis;
  evidenceStatus: TrackAppearanceEvidence;
  rawEventIds: string[];
}

export interface ReconstructedEpisode {
  episodeId: string;
  reconstructionVersion: number;
  userId: string;
  deviceId: string;
  observerSessionId: string;
  sourcePackage: string | null;
  firstObservedAt: string | null;
  lastObservedAt: string | null;
  wallClockSpanMs: number | null;
  startBoundary: EpisodeBoundaryType;
  endBoundary: EpisodeBoundaryType;
  status: EpisodeStatus;
  eventCount: number;
  rawEventIds: string[];
  trackAppearances: TrackAppearance[];
  hasObservationGap: boolean;
  hasTimestampAmbiguity: boolean;
  usedIsPlayingFallback: boolean;
  notices: string[];
}

export interface ReconstructionResult {
  userId: string;
  reconstructionVersion: number;
  episodes: ReconstructedEpisode[];
  totalEpisodes: number;
  totalEventsProcessed: number;
  totalEventsExcluded: number;
  excludedEmptySessionIdCount: number;
  excludedReason: string[];
  notices: string[];
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Resolve the raw playback state code from a stored event's payload. */
function resolveStateCode(event: StoredEvent): { code: number | null; usedFallback: boolean } {
  try {
    const parsed = JSON.parse(event.payload_json);
    const sc = parsed?.playback?.playbackStateCode;
    if (typeof sc === 'number' && Number.isInteger(sc) && sc >= 0) {
      return { code: sc, usedFallback: false };
    }
    // Fallback: isPlaying boolean (legacy events)
    const isPlaying = parsed?.playback?.isPlaying;
    if (typeof isPlaying === 'boolean') {
      return { code: isPlaying ? STATE_PLAYING : null, usedFallback: true };
    }
    return { code: null, usedFallback: false };
  } catch {
    return { code: null, usedFallback: false };
  }
}

/** Resolve track metadata from a stored event's payload. */
function resolveTrack(event: StoredEvent): {
  trackId: string | null;
  title: string | null;
  artist: string | null;
  album: string | null;
  durationMs: number | null;
  hasMetadata: boolean;
} {
  try {
    const parsed = JSON.parse(event.payload_json);
    const t = parsed?.track;
    if (!t || typeof t !== 'object') {
      return { trackId: null, title: null, artist: null, album: null, durationMs: null, hasMetadata: false };
    }
    const trackId = typeof t.trackId === 'string' && t.trackId.length > 0 ? t.trackId : null;
    const title = typeof t.title === 'string' && t.title.length > 0 ? t.title.trim() : null;
    const artist = typeof t.artist === 'string' && t.artist.length > 0 ? t.artist.trim() : null;
    const album = typeof t.album === 'string' && t.album.length > 0 ? t.album.trim() : null;
    const durationMs = typeof t.durationMs === 'number' && t.durationMs >= 0 ? t.durationMs : null;
    return {
      trackId, title, artist, album, durationMs,
      hasMetadata: trackId != null || title != null || artist != null || album != null || durationMs != null,
    };
  } catch {
    return { trackId: null, title: null, artist: null, album: null, durationMs: null, hasMetadata: false };
  }
}

/** Compute a deterministic track identity key matching Android rules. */
function trackIdentityKey(track: {
  trackId: string | null;
  title: string | null;
  artist: string | null;
  album: string | null;
  sourcePackage: string | null;
}): { key: string | null; basis: TrackIdentityBasis } {
  const norm = (s: string | null): string | null =>
    s != null ? s.trim().toLowerCase() : null;
  const tid = norm(track.trackId);
  const title = norm(track.title);
  const artist = norm(track.artist);
  const album = norm(track.album);
  const src = norm(track.sourcePackage);

  if (src != null && tid != null) {
    return { key: `id:${src}|${tid}`, basis: 'PROVIDER_TRACK_ID' };
  }
  if (title != null && artist != null) {
    return { key: `meta:${title}|${artist}|${album ?? ''}`, basis: 'METADATA_FINGERPRINT' };
  }
  return { key: null, basis: 'INSUFFICIENT' };
}

/** Normalize a string for comparison. */
function norm(s: string | null): string | null {
  return s != null ? s.trim().toLowerCase() : null;
}

// ─── Service ──────────────────────────────────────────────────────────────────

export class EpisodeReconstructor {
  private store: EventStore;

  constructor(store?: EventStore) {
    this.store = store ?? new EventStore();
  }

  /**
   * Reconstruct listening episodes from stored events for the given userId.
   * Pass null/undefined for userId to include all events.
   */
  reconstruct(userId?: string | null): ReconstructionResult {
    const allEvents = this.store.listAll();
    const filterByUser = typeof userId === 'string' && userId.length > 0;
    const events = filterByUser ? allEvents.filter(e => e.user_id === userId) : allEvents;

    const excludedReason: string[] = [];

    // Separate empty session_ids
    const validEvents = events.filter(e => e.session_id !== '');
    const excludedEmptySessionIdCount = events.length - validEvents.length;
    if (excludedEmptySessionIdCount > 0) {
      excludedReason.push(`${excludedEmptySessionIdCount} event(s) excluded: empty session_id`);
    }

    if (validEvents.length === 0) {
      return {
        userId: filterByUser ? userId : '(all)',
        reconstructionVersion: RECONSTRUCTION_VERSION,
        episodes: [],
        totalEpisodes: 0,
        totalEventsProcessed: 0,
        totalEventsExcluded: events.length,
        excludedEmptySessionIdCount,
        excludedReason,
        notices: ['No events to reconstruct.'],
      };
    }

    // Deduplicate by eventId
    const eventMap = new Map<string, StoredEvent[]>();
    for (const event of validEvents) {
      const existing = eventMap.get(event.event_id);
      if (existing) existing.push(event);
      else eventMap.set(event.event_id, [event]);
    }

    const conflictEventIds: string[] = [];
    const deduped: StoredEvent[] = [];

    for (const [eid, rows] of eventMap) {
      const distinctPayloads = new Set(rows.map(r => r.payload_json));
      if (distinctPayloads.size > 1) {
        conflictEventIds.push(eid);
      }
      // Canonical: lexicographically smallest payload_json
      const canonical = rows.reduce((a, b) => a.payload_json < b.payload_json ? a : b);
      deduped.push(canonical);
    }

    // Sort by occurred_at, then eventId, then event_type
    deduped.sort((a, b) => {
      const aTime = ISO_DATE_RE.test(a.occurred_at) ? a.occurred_at : '';
      const bTime = ISO_DATE_RE.test(b.occurred_at) ? b.occurred_at : '';
      if (aTime < bTime) return -1;
      if (aTime > bTime) return 1;
      if (a.event_id < b.event_id) return -1;
      if (a.event_id > b.event_id) return 1;
      return a.event_type.localeCompare(b.event_type);
    });

    // Group by (user_id, device_id, session_id, media_source)
    const scopeGroups = new Map<string, StoredEvent[]>();
    const scopeKey = (e: StoredEvent): string =>
      `${e.user_id}|${e.device_id}|${e.session_id}|${e.media_source}`;

    for (const event of deduped) {
      const key = scopeKey(event);
      const group = scopeGroups.get(key);
      if (group) group.push(event);
      else scopeGroups.set(key, [event]);
    }

    const episodes: ReconstructedEpisode[] = [];
    let usedFallbackGlobally = false;

    // Collect OBSERVATION_STOPPED timestamps per session for session-level closure
    const sessionStopMap = new Map<string, string[]>();
    for (const event of deduped) {
      if (event.event_type === 'OBSERVATION_STOPPED') {
        const sk = `${event.user_id}|${event.device_id}|${event.session_id}`;
        const list = sessionStopMap.get(sk) ?? [];
        if (ISO_DATE_RE.test(event.occurred_at)) list.push(event.occurred_at);
        sessionStopMap.set(sk, list);
      }
    }

    for (const [key, scopeEvents] of scopeGroups) {
      const parts = key.split('|');
      const sUserId = parts[0];
      const sDeviceId = parts[1];
      const sSessionId = parts[2];
      const sSource = parts[3];

      // Determine if this session has an OBSERVATION_STOPPED event
      const sessionKey = `${sUserId}|${sDeviceId}|${sSessionId}`;
      const sessionStopTimes = sessionStopMap.get(sessionKey) ?? [];
      const hasObservationStop = sessionStopTimes.length > 0;
      const latestObservationStop = sessionStopTimes.length > 0 ? sessionStopTimes[sessionStopTimes.length - 1] : null;

      let builder: EpisodeBuilder | null = null;
      let previous: StoredEvent | null = null;
      let scopeUsedFallback = false;

      for (const event of scopeEvents) {
        const { code, usedFallback } = resolveStateCode(event);
        if (usedFallback) scopeUsedFallback = true;
        const eventUsedFallback = usedFallback;

        const eventTime = ISO_DATE_RE.test(event.occurred_at) ? event.occurred_at : null;
        const previousTime = previous != null && ISO_DATE_RE.test(previous.occurred_at) ? previous.occurred_at : null;
        const isGap = previousTime != null && eventTime != null &&
          new Date(eventTime).getTime() - new Date(previousTime).getTime() > GAP_BOUNDARY_MS;

        // Close previous episode if gap detected
        if (builder != null && isGap) {
          closeEpisode(builder, event, 'GAP_CENSORED', 'CLOSED_BY_GAP', episodes, conflictEventIds);
          builder = null;
        }

        if (builder != null) {
          // Add event to current episode
          const track = resolveTrack(event);
          builder.addEvent(event, track, conflictEventIds.includes(event.event_id), eventTime);
          builder.lastTime = eventTime;
          if (eventUsedFallback) builder.usedFallback = true;

          // Check for terminal states
          if (event.event_type === 'SESSION_DESTROYED') {
            closeEpisode(builder, event, 'OBSERVED_SESSION_DESTROYED', 'ENDED_BY_SESSION_DESTRUCTION', episodes, conflictEventIds);
            builder = null;
          } else if (code === STATE_STOPPED) {
            closeEpisode(builder, event, 'OBSERVED_STOP', 'ENDED_BY_OBSERVED_STOP', episodes, conflictEventIds);
            builder = null;
          } else if (code === STATE_NONE) {
            closeEpisode(builder, event, 'OBSERVED_NON_PLAYING_STATE', 'ENDED_BY_OBSERVED_NON_PLAYING_STATE', episodes, conflictEventIds);
            builder = null;
          } else if (code === STATE_PLAYING) {
            builder.lastPlayingCode = STATE_PLAYING;
          } else if (code != null) {
            builder.lastPlayingCode = code; // PAUSED or other non-terminal
          }
        }

        // Start a new episode on PLAYING state or SESSION_CREATED with PLAYING
        if (builder == null && code === STATE_PLAYING && event.event_type !== 'SESSION_DESTROYED') {
          builder = new EpisodeBuilder(sUserId, sDeviceId, sSessionId, sSource, event, eventTime, 'OBSERVED_PLAYING', eventUsedFallback);
        } else if (builder == null && event.event_type === 'SESSION_CREATED' && code === STATE_PLAYING) {
          builder = new EpisodeBuilder(sUserId, sDeviceId, sSessionId, sSource, event, eventTime, 'OBSERVED_PLAYING', eventUsedFallback);
        }

        previous = event;
        if (usedFallback) usedFallbackGlobally = true;
      }

      // Close the last episode if the builder is still open
      if (builder != null) {
        if (hasObservationStop && latestObservationStop != null && builder.firstTime != null &&
            latestObservationStop >= builder.firstTime) {
          // The observer session has a stop event that occurred during or after this episode.
          // Use OBSERVATION_STOPPED as the end boundary.
          closeEpisode(builder, scopeEvents[scopeEvents.length - 1], 'OBSERVED_SESSION_DESTROYED', 'ENDED_BY_SESSION_DESTRUCTION', episodes, conflictEventIds);
        } else {
          const endBoundary: EpisodeBoundaryType = 'UNKNOWN';
          const status: EpisodeStatus = 'OPEN_NO_TERMINAL_STATE';
          closeEpisode(builder, scopeEvents[scopeEvents.length - 1], endBoundary, status, episodes, conflictEventIds);
        }
      }

      if (scopeUsedFallback) usedFallbackGlobally = true;
    }

    // Sort episodes by time, then episodeId
    episodes.sort((a, b) => {
      const aTime = a.firstObservedAt ?? '';
      const bTime = b.firstObservedAt ?? '';
      if (aTime < bTime) return -1;
      if (aTime > bTime) return 1;
      return a.episodeId.localeCompare(b.episodeId);
    });

    const notices: string[] = [];
    if (filterByUser && userId === 'tunemind-dev') {
      notices.push('User and device identifiers are development placeholders — not production authentication.');
    }
    notices.push('Wall-clock span (firstEventAt to lastEventAt) is NOT active listening duration. The backend cannot compute reliable active playback time because it lacks the monotonic elapsed-realtime clock.');
    notices.push('Episode boundaries are inferred from playback state codes and gap thresholds. They do not confirm the user heard or paid attention to any audio.');
    notices.push('A track metadata change does not indicate a skip. It records only that the media source reported different metadata.');
    notices.push('Timestamps originate from the device wall clock (System.currentTimeMillis). Clock skew is not measured.');
    if (usedFallbackGlobally) {
      notices.push('Some events used the isPlaying boolean as a playback-state fallback because playbackStateCode was absent or null. Fallback events cannot distinguish STOPPED from PAUSED from NONE.');
    }
    if (conflictEventIds.length > 0) {
      notices.push(`${conflictEventIds.length} event ID(s) had conflicting payloads. The lexicographically smallest representation was used.`);
    }

    return {
      userId: filterByUser ? userId : '(all)',
      reconstructionVersion: RECONSTRUCTION_VERSION,
      episodes,
      totalEpisodes: episodes.length,
      totalEventsProcessed: deduped.length,
      totalEventsExcluded: events.length - deduped.length,
      excludedEmptySessionIdCount,
      excludedReason,
      notices,
    };
  }
}

// ─── Episode Builder ──────────────────────────────────────────────────────────

class EpisodeBuilder {
  readonly episodeId: string;
  readonly userId: string;
  readonly deviceId: string;
  readonly observerSessionId: string;
  readonly sourcePackage: string | null;
  firstTime: string | null;
  lastTime: string | null;
  firstEventId: string;
  readonly startBoundary: EpisodeBoundaryType;
  eventIds: string[];
  lastPlayingCode: number | null;
  hasConflict: boolean;
  usedFallback: boolean;
  trackBuilders: TrackBuilder[];

  constructor(
    userId: string,
    deviceId: string,
    sessionId: string,
    sourcePackage: string,
    firstEvent: StoredEvent,
    firstTime: string | null,
    startBoundary: EpisodeBoundaryType,
    firstEventUsedFallback: boolean = false,
  ) {
    this.episodeId = `episode:${sessionId}:${firstEvent.event_id}`;
    this.userId = userId;
    this.deviceId = deviceId;
    this.observerSessionId = sessionId;
    this.sourcePackage = sourcePackage;
    this.firstTime = firstTime;
    this.lastTime = firstTime;
    this.firstEventId = firstEvent.event_id;
    this.startBoundary = startBoundary;
    this.eventIds = [firstEvent.event_id];
    this.lastPlayingCode = null;
    this.hasConflict = false;
    this.usedFallback = firstEventUsedFallback;
    this.trackBuilders = [];

    const track = resolveTrack(firstEvent);
    if (track.hasMetadata) {
      const { key, basis } = trackIdentityKey({ ...track, sourcePackage });
      this.trackBuilders.push(new TrackBuilder(firstEvent.event_id, track, key, basis, 'SNAPSHOT_ONLY', firstTime));
    }
  }

  addEvent(event: StoredEvent, track: ReturnType<typeof resolveTrack>, isConflict: boolean, eventTimestamp: string | null): void {
    this.eventIds.push(event.event_id);
    if (isConflict) this.hasConflict = true;

    if (!track.hasMetadata) return;

    const { key, basis } = trackIdentityKey({ ...track, sourcePackage: this.sourcePackage });
    const current = this.trackBuilders[this.trackBuilders.length - 1];

    // Same identity key → update current appearance
    if (current != null && key != null && current.key === key) {
      current.update(event.event_id, track, current.basis === 'PROVIDER_TRACK_ID' ? 'SAME_PROVIDER_ID' : 'SAME_METADATA_FINGERPRINT', eventTimestamp);
      return;
    }

    // Different identity key → new appearance
    if (key != null) {
      const evidence: TrackAppearanceEvidence = current == null ? 'SNAPSHOT_ONLY' : 'CANDIDATE_CHANGE';
      this.trackBuilders.push(new TrackBuilder(event.event_id, track, key, basis, evidence, eventTimestamp));
    } else {
      // Insufficient identity but has some metadata
      this.trackBuilders.push(new TrackBuilder(event.event_id, track, null, 'INSUFFICIENT', 'IDENTITY_INSUFFICIENT', eventTimestamp));
    }
  }
}

class TrackBuilder {
  readonly firstEventId: string;
  readonly key: string | null;
  readonly basis: TrackIdentityBasis;
  evidence: TrackAppearanceEvidence;
  trackId: string | null;
  title: string | null;
  artist: string | null;
  album: string | null;
  durationMs: number | null;
  eventIds: string[];
  /** Timestamps of events assigned to this appearance, for computing own bounds. */
  eventTimestamps: (string | null)[];

  constructor(firstEventId: string, track: ReturnType<typeof resolveTrack>, key: string | null, basis: TrackIdentityBasis, evidence: TrackAppearanceEvidence, firstTimestamp: string | null = null) {
    this.firstEventId = firstEventId;
    this.key = key;
    this.basis = basis;
    this.evidence = evidence;
    this.trackId = track.trackId;
    this.title = track.title;
    this.artist = track.artist;
    this.album = track.album;
    this.durationMs = track.durationMs;
    this.eventIds = [firstEventId];
    this.eventTimestamps = [firstTimestamp];
  }

  update(eventId: string, track: ReturnType<typeof resolveTrack>, evidence: TrackAppearanceEvidence, timestamp: string | null = null): void {
    this.eventIds.push(eventId);
    this.eventTimestamps.push(timestamp);
    this.evidence = evidence;
    // Fill in missing metadata from later observations
    if (this.trackId == null) this.trackId = track.trackId;
    if (this.title == null) this.title = track.title;
    if (this.artist == null) this.artist = track.artist;
    if (this.album == null) this.album = track.album;
    if (this.durationMs == null) this.durationMs = track.durationMs;
  }

  toModel(sourcePackage: string | null): TrackAppearance {
    const appearanceId = `track:${this.firstEventId}`;
    // Compute own bounds from assigned timestamps
    const validTimestamps = this.eventTimestamps.filter((t): t is string => t != null && ISO_DATE_RE.test(t)).sort();
    const firstObservedAt = validTimestamps.length > 0 ? validTimestamps[0] : null;
    const lastObservedAt = validTimestamps.length > 0 ? validTimestamps[validTimestamps.length - 1] : null;
    return {
      appearanceId,
      sourcePackage,
      trackId: this.trackId,
      title: this.title,
      artist: this.artist,
      album: this.album,
      durationMs: this.durationMs,
      firstEventId: this.firstEventId,
      firstObservedAt,
      lastObservedAt,
      observationCount: this.eventIds.length,
      identityBasis: this.basis,
      evidenceStatus: this.evidence,
      rawEventIds: [...this.eventIds],
    };
  }
}

function closeEpisode(
  builder: EpisodeBuilder,
  lastEvent: StoredEvent,
  endBoundary: EpisodeBoundaryType,
  status: EpisodeStatus,
  output: ReconstructedEpisode[],
  conflictEventIds: string[],
): void {
  const lastTime = builder.lastTime;
  const firstTime = builder.firstTime;

  const wallClockSpanMs = (firstTime != null && lastTime != null)
    ? new Date(lastTime).getTime() - new Date(firstTime).getTime()
    : null;

  const notices: string[] = [];
  if (builder.usedFallback) {
    notices.push('Some events in this episode used the isPlaying boolean as a playback-state fallback. Fallback events cannot distinguish STOPPED from PAUSED from NONE.');
  }
  if (status === 'OPEN_NO_TERMINAL_STATE') {
    notices.push('This episode has no terminal boundary — it may still be active or the observer session may have ended without a terminal state event.');
  }
  if (endBoundary === 'UNKNOWN') {
    notices.push('The episode end boundary could not be determined from available evidence.');
  }

  const trackAppearances = builder.trackBuilders.map(tb =>
    tb.toModel(builder.sourcePackage),
  );

  output.push({
    episodeId: builder.episodeId,
    reconstructionVersion: RECONSTRUCTION_VERSION,
    userId: builder.userId,
    deviceId: builder.deviceId,
    observerSessionId: builder.observerSessionId,
    sourcePackage: builder.sourcePackage,
    firstObservedAt: firstTime,
    lastObservedAt: lastTime,
    wallClockSpanMs,
    startBoundary: builder.startBoundary,
    endBoundary,
    status,
    eventCount: builder.eventIds.length,
    rawEventIds: [...builder.eventIds],
    trackAppearances,
    hasObservationGap: status === 'CLOSED_BY_GAP',
    hasTimestampAmbiguity: builder.hasConflict || conflictEventIds.some(id => builder.eventIds.includes(id)),
    usedIsPlayingFallback: builder.usedFallback,
    notices,
  });
}
