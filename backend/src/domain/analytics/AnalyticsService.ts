/**
 * AnalyticsService — deterministic, read-only summaries over stored raw events.
 *
 * This is a development-phase service. It operates against the local SQLite
 * EventStore and does NOT authenticate users. The userId parameter is a
 * development filter, not a security boundary.
 *
 * All counts and aggregations come from the indexed columns of raw_events.
 * Payload-level fields (track metadata, playback state) are not parsed here.
 * That is the responsibility of a future AnalyticsV2 service.
 */

import { EventStore, StoredEvent } from '../events/EventStore';

export interface AnalyticsSummary {
  userId: string;
  totalEvents: number;
  bySource: Record<string, number>;
  byEventType: Record<string, number>;
  timeRange: {
    earliest: string | null;
    latest: string | null;
  };
  sources: string[];
  sessionCreatedEventCount: number;
  distinctSessionCount: number;
  notices: string[];
}

const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/;

export interface SourceSessionSummary {
  eventCount: number;
  eventCountsByType: Record<string, number>;
  sessionCreatedCount: number;
  sessionDestroyedCount: number;
  playingCallbackCount: number;
  nonPlayingCallbackCount: number;
  unknownPlaybackStateCount: number;
  firstEventAt: string | null;
  lastEventAt: string | null;
}

export interface ObserverSessionSummary {
  sessionId: string;
  deviceId: string;
  eventCount: number;
  observerConnectionSpanMs: number | null;
  firstEventAt: string | null;
  lastEventAt: string | null;
  hasObservationStop: boolean;
  sources: Record<string, SourceSessionSummary>;
}

export interface SessionsSummary {
  userId: string;
  observerSessions: ObserverSessionSummary[];
  totalObserverSessions: number;
  excludedEmptySessionIdCount: number;
  notices: string[];
}

export class AnalyticsService {
  private store: EventStore;

  constructor(store?: EventStore) {
    this.store = store ?? new EventStore();
  }

  /**
   * Produce a summary of stored events for the given userId.
   * Pass null or undefined to count all events regardless of userId.
   */
  getSummary(userId?: string | null): AnalyticsSummary {
    const store = this.store;

    // Build the WHERE clause and params.
    // Parameterized query prevents SQL injection even in dev.
    const filterByUser = typeof userId === 'string' && userId.length > 0;
    const clauses: string[] = [];
    const params: unknown[] = [];

    if (filterByUser) {
      clauses.push('user_id = ?');
      params.push(userId);
    }

    const whereClause = clauses.length > 0 ? 'WHERE ' + clauses.join(' AND ') : '';
    const andClause = (clause: string): string =>
      clauses.length > 0 ? ' AND ' + clause : 'WHERE ' + clause;

    // Total event count
    const countRow = store.queryRow<{ cnt: number }>(
      `SELECT COUNT(*) AS cnt FROM raw_events ${whereClause}`,
      params,
    );
    const totalEvents = countRow?.cnt ?? 0;

    // Group by media_source
    const bySourceRows = store.queryAll<{ media_source: string; cnt: number }>(
      `SELECT media_source, COUNT(*) AS cnt FROM raw_events ${whereClause} GROUP BY media_source ORDER BY cnt DESC`,
      params,
    );
    const bySource: Record<string, number> = {};
    for (const row of bySourceRows) {
      bySource[row.media_source] = row.cnt;
    }

    // Group by event_type
    const byTypeRows = store.queryAll<{ event_type: string; cnt: number }>(
      `SELECT event_type, COUNT(*) AS cnt FROM raw_events ${whereClause} GROUP BY event_type ORDER BY cnt DESC`,
      params,
    );
    const byEventType: Record<string, number> = {};
    for (const row of byTypeRows) {
      byEventType[row.event_type] = row.cnt;
    }

    // Distinct sources
    const sourceRows = store.queryAll<{ media_source: string }>(
      `SELECT DISTINCT media_source FROM raw_events ${whereClause} ORDER BY media_source`,
      params,
    );
    const sources = sourceRows.map(r => r.media_source);

    // SESSION_CREATED count
    const sessionCreatedRow = store.queryRow<{ cnt: number }>(
      `SELECT COUNT(*) AS cnt FROM raw_events ${whereClause}${andClause("event_type = 'SESSION_CREATED'")}`,
      params,
    );
    const sessionCreatedEventCount = sessionCreatedRow?.cnt ?? 0;

    // Distinct session IDs
    const distinctSessionRow = store.queryRow<{ cnt: number }>(
      `SELECT COUNT(DISTINCT session_id) AS cnt FROM raw_events ${whereClause}${andClause("session_id != ''")}`,
      params,
    );
    const distinctSessionCount = distinctSessionRow?.cnt ?? 0;

    // Time range — only valid ISO dates
    const allOccurredAt: string[] = [];
    const events = store.listAll();
    for (const event of events) {
      if (filterByUser && event.user_id !== userId) continue;
      if (ISO_DATE_RE.test(event.occurred_at)) {
        allOccurredAt.push(event.occurred_at);
      }
    }
    allOccurredAt.sort();
    const earliest = allOccurredAt.length > 0 ? allOccurredAt[0] : null;
    const latest = allOccurredAt.length > 0 ? allOccurredAt[allOccurredAt.length - 1] : null;

    const notices: string[] = [];
    if (filterByUser && userId === 'tunemind-dev') {
      notices.push('User and device identifiers are development placeholders — not production authentication.');
    }
    notices.push('occurredAt originates from the device wall clock (System.currentTimeMillis). Clock skew is not measured.');
    notices.push('This summary reports raw media-session callback counts, not actual listening duration, attention, or completion.');

    return {
      userId: filterByUser ? userId : '(all)',
      totalEvents,
      bySource,
      byEventType,
      timeRange: { earliest, latest },
      sources,
      sessionCreatedEventCount,
      distinctSessionCount,
      notices,
    };
  }

  /**
   * Produce a per-observer-session summary of stored events.
   * Observer sessions are grouped by (user_id, device_id, session_id).
   */
  getSessions(userId?: string | null): SessionsSummary {
    const store = this.store;

    const filterByUser = typeof userId === 'string' && userId.length > 0;
    const allEvents = store.listAll();

    // Filter by user if needed
    const events = filterByUser ? allEvents.filter(e => e.user_id === userId) : allEvents;

    // Separate empty-session_id events
    const validEvents = events.filter(e => e.session_id !== '');
    const excludedEmptySessionIdCount = events.length - validEvents.length;

    // Group by (user_id, device_id, session_id)
    const sessionGroups = new Map<string, StoredEvent[]>();
    const sessionKey = (e: StoredEvent): string => `${e.user_id}|${e.device_id}|${e.session_id}`;

    for (const event of validEvents) {
      const key = sessionKey(event);
      const group = sessionGroups.get(key);
      if (group) group.push(event);
      else sessionGroups.set(key, [event]);
    }

    const observerSessions: ObserverSessionSummary[] = [];

    for (const [key, sessionEvents] of sessionGroups) {
      // Parse the key back to components
      const pipeIndex = key.indexOf('|');
      const secondPipe = key.indexOf('|', pipeIndex + 1);
      // const sUserId = key.substring(0, pipeIndex);  // unused in this loop
      const sDeviceId = key.substring(pipeIndex + 1, secondPipe);
      const sSessionId = key.substring(secondPipe + 1);

      // Group by media_source within this observer session
      const sourceMap = new Map<string, StoredEvent[]>();
      for (const event of sessionEvents) {
        const src = sourceMap.get(event.media_source);
        if (src) src.push(event);
        else sourceMap.set(event.media_source, [event]);
      }

      // Compute timestamps for the observer session
      const allValidTimestamps: string[] = [];
      for (const event of sessionEvents) {
        if (ISO_DATE_RE.test(event.occurred_at)) {
          allValidTimestamps.push(event.occurred_at);
        }
      }
      allValidTimestamps.sort();
      const sessionFirst = allValidTimestamps.length > 0 ? allValidTimestamps[0] : null;
      const sessionLast = allValidTimestamps.length > 0 ? allValidTimestamps[allValidTimestamps.length - 1] : null;
      const observerConnectionSpanMs = (sessionFirst != null && sessionLast != null)
        ? new Date(sessionLast).getTime() - new Date(sessionFirst).getTime()
        : null;

      // Build source summaries
      const sources: Record<string, SourceSessionSummary> = {};

      for (const [sourceName, sourceEvents] of sourceMap) {
        const sourceTimestamps: string[] = [];
        let sessionCreatedCount = 0;
        let sessionDestroyedCount = 0;
        let playingCallbackCount = 0;
        let nonPlayingCallbackCount = 0;
        let unknownPlaybackStateCount = 0;
        const typeCounts: Record<string, number> = {};

        for (const event of sourceEvents) {
          // Timestamps
          if (ISO_DATE_RE.test(event.occurred_at)) {
            sourceTimestamps.push(event.occurred_at);
          }

          // Event type
          typeCounts[event.event_type] = (typeCounts[event.event_type] ?? 0) + 1;

          // Session lifecycle
          if (event.event_type === 'SESSION_CREATED') sessionCreatedCount++;
          if (event.event_type === 'SESSION_DESTROYED') sessionDestroyedCount++;

          // Playback state from payload_json
          try {
            const parsed = JSON.parse(event.payload_json);
            const isPlaying = parsed?.playback?.isPlaying;
            if (typeof isPlaying === 'boolean') {
              if (isPlaying) playingCallbackCount++;
              else nonPlayingCallbackCount++;
            } else {
              unknownPlaybackStateCount++;
            }
          } catch {
            unknownPlaybackStateCount++;
          }
        }

        sourceTimestamps.sort();
        const srcFirst = sourceTimestamps.length > 0 ? sourceTimestamps[0] : null;
        const srcLast = sourceTimestamps.length > 0 ? sourceTimestamps[sourceTimestamps.length - 1] : null;

        sources[sourceName] = {
          eventCount: sourceEvents.length,
          eventCountsByType: typeCounts,
          sessionCreatedCount,
          sessionDestroyedCount,
          playingCallbackCount,
          nonPlayingCallbackCount,
          unknownPlaybackStateCount,
          firstEventAt: srcFirst,
          lastEventAt: srcLast,
        };
      }

      // Detect OBSERVATION_STOPPED event in this session
      const hasObservationStop = sessionEvents.some(e => e.event_type === 'OBSERVATION_STOPPED');

      observerSessions.push({
        sessionId: sSessionId,
        deviceId: sDeviceId,
        eventCount: sessionEvents.length,
        observerConnectionSpanMs,
        firstEventAt: sessionFirst,
        lastEventAt: sessionLast,
        hasObservationStop,
        sources,
      });
    }

    // Sort observer sessions by first event time, then session ID
    observerSessions.sort((a, b) => {
      const aTime = a.firstEventAt ?? '';
      const bTime = b.firstEventAt ?? '';
      if (aTime < bTime) return -1;
      if (aTime > bTime) return 1;
      return a.sessionId.localeCompare(b.sessionId);
    });

    const notices: string[] = [];
    if (filterByUser && userId === 'tunemind-dev') {
      notices.push('User and device identifiers are development placeholders — not production authentication.');
    }
    notices.push('observerConnectionSpanMs is wall-clock duration between first and last valid event timestamp in this observer session. It is NOT active listening duration or confirmed observer uptime.');
    notices.push('Playing counts describe stored callback observations, not proof that a human was listening.');
    notices.push('Non-playing counts combine PAUSED, STOPPED, NONE, and null states — the backend cannot currently distinguish them.');
    notices.push('Unknown playback states were excluded from both playing and non-playing counts.');
    if (!events.some(e => e.event_type === 'OBSERVATION_STOPPED')) {
      notices.push('No OBSERVATION_STOPPED events found in the database. Sessions without a stop event may still be active.');
    }
    notices.push('occurredAt originates from the device wall clock (System.currentTimeMillis). Clock skew is not measured.');
    if (excludedEmptySessionIdCount > 0) {
      notices.push(`${excludedEmptySessionIdCount} event(s) with empty session_id were excluded from observer session summaries.`);
    }

    return {
      userId: filterByUser ? userId : '(all)',
      observerSessions,
      totalObserverSessions: observerSessions.length,
      excludedEmptySessionIdCount,
      notices,
    };
  }
}
