package com.tunemind.app.domain.coverage

/**
 * Observation coverage and evidence integrity for a listening-data evaluation.
 *
 * Defines what is known about the completeness and limitations of the
 * local observation history. Distinguishes directly observed boundaries
 * from unknown periods.
 */

/** Known status of observation coverage for a specific time window. */
enum class CoverageStatus {
    /** Known events exist at timestamps within or bounding this range. */
    EVENTS_KNOWN_WITHIN_BOUNDS,
    /** Events exist but the range extends beyond the known event timeline. */
    RANGE_EXTENDS_BEYOND_KNOWN_BOUNDS,
    /** No known events fall within or near this range. */
    UNOBSERVED,
    /** Coverage cannot be established. */
    UNKNOWN,
}

/** A single known observation session boundary. */
data class ObservationBoundary(
    /** Timestamp (ms since epoch). */
    val timestampMs: Long,
    /** Type of boundary. */
    val type: BoundaryType,
) {
    enum class BoundaryType {
        /** OBSERVATION_STOPPED event — observer session ended. Best-effort. */
        OBSERVATION_STOPPED,
        /** SESSION_CREATED event — media controller discovered. */
        SESSION_CREATED,
        /** SESSION_DESTROYED event — media controller ended. */
        SESSION_DESTROYED,
        /** First observed event timestamp for this session. */
        FIRST_OBSERVED,
        /** Last observed event timestamp for this session. */
        LAST_OBSERVED,
    }
}

/** Coverage evidence for a single observer session. */
data class SessionCoverage(
    /** Observer session ID (nonblank). */
    val sessionId: String,
    /** Total events in this session (including those with invalid timestamps). */
    val totalEventCount: Int,
    /** Events with valid (> 0) timestamps. */
    val validTimestampCount: Int,
    /** Earliest valid event timestamp, or null. */
    val earliestMs: Long?,
    /** Latest valid event timestamp, or null. */
    val latestMs: Long?,
    /** Whether OBSERVATION_STOPPED was recorded for this session. */
    val hasObservationStop: Boolean,
    /** Number of SESSION_CREATED events. */
    val sessionCreatedCount: Int,
    /** Number of SESSION_DESTROYED events. */
    val sessionDestroyedCount: Int,
    /** Notices specific to this session. */
    val notices: List<String>,
)

/** Evidence-integrity assessment for a listening-intelligence evaluation. */
data class CoverageAssessment(
    /** Known observation boundaries, sorted chronologically. */
    val boundaries: List<ObservationBoundary>,
    /** Earliest known observation timestamp, or null. */
    val earliestObservedMs: Long?,
    /** Latest known observation timestamp, or null. */
    val latestObservedMs: Long?,
    /** Whether the 500-record retention limit may have pruned older events. */
    val retentionMayHavePruned: Boolean,
    /** Whether OBSERVATION_STOPPED events were found in the record. */
    val hasObservationStopEvents: Boolean,
    /** Number of distinct observer sessions with evidence. */
    val distinctObserverSessionCount: Int,
    /** Number of events excluded due to invalid timestamps. */
    val invalidTimestampCount: Int,
    /** Per-session coverage details, for sessions with nonblank IDs. */
    val sessionCoverages: List<SessionCoverage>,
    /** Number of events with blank session IDs. */
    val unassignedEventCount: Int,
    /** Data-quality notices. */
    val notices: List<String>,
) {
    /** Whether the earliest observation time is a known boundary or just the oldest retained record. */
    val earliestIsTruncatedByRetention: Boolean
        get() = retentionMayHavePruned || boundaries.none { it.type == ObservationBoundary.BoundaryType.FIRST_OBSERVED }
}

/** Builds a [CoverageAssessment] from available evidence. */
object CoverageAssessor {

    /**
     * Assess observation coverage from available evidence.
     *
     * @param sessionTimestampsMs All valid event timestamps in the record (ms since epoch).
     * @param hasObservationStop Whether any OBSERVATION_STOPPED events exist.
     * @param historyMayBeIncomplete Whether the memory builder flagged incomplete history.
     * @param retentionLimitReached Whether the 500-record retention limit was reached.
     * @param distinctSessionIdsCount Number of distinct observer session IDs.
     * @param invalidTimestampCount Number of events with invalid (<= 0) timestamps.
     * @param boundaries Known observation boundaries (from session analytics or event types).
     */
    fun assess(
        sessionTimestampsMs: List<Long>,
        hasObservationStop: Boolean,
        historyMayBeIncomplete: Boolean,
        retentionLimitReached: Boolean,
        distinctSessionIdsCount: Int,
        invalidTimestampCount: Int,
        boundaries: List<ObservationBoundary> = emptyList(),
    ): CoverageAssessment {
        val validTimestamps = sessionTimestampsMs.filter { it > 0L }
        val earliest = validTimestamps.minOrNull()
        val latest = validTimestamps.maxOrNull()
        val retentionMayHavePruned = retentionLimitReached || historyMayBeIncomplete

        val notices = mutableListOf<String>()
        if (retentionMayHavePruned) {
            notices.add("Observation history is limited to 500 events. Older events may have been pruned.")
        }
        if (!hasObservationStop) {
            notices.add("No OBSERVATION_STOPPED events found. Observer session end times are unknown.")
        }
        if (distinctSessionIdsCount == 0 && validTimestamps.isNotEmpty()) {
            notices.add("Events exist but have empty session IDs. Session boundaries are unknown.")
        }
        if (invalidTimestampCount > 0) {
            notices.add("$invalidTimestampCount event(s) excluded due to invalid timestamps.")
        }
        if (boundaries.isEmpty() && validTimestamps.isNotEmpty()) {
            notices.add("No explicit observation boundaries available. Coverage inferred from event timestamps only.")
        }

        val hasStop = hasObservationStop || boundaries.any { it.type == ObservationBoundary.BoundaryType.OBSERVATION_STOPPED }

        return CoverageAssessment(
            boundaries = boundaries.sortedBy { it.timestampMs },
            earliestObservedMs = earliest,
            latestObservedMs = latest,
            retentionMayHavePruned = retentionMayHavePruned,
            hasObservationStopEvents = hasStop,
            distinctObserverSessionCount = distinctSessionIdsCount,
            invalidTimestampCount = invalidTimestampCount,
            sessionCoverages = emptyList(),
            unassignedEventCount = 0,
            notices = notices,
        )
    }

    /**
     * Assess coverage from raw PlaybackObservation objects.
     * Builds per-session coverage and aggregates across sessions.
     *
     * @param observations Raw persisted observations.
     * @param retentionLimitMayBeActive Whether the 500-record retention limit may have pruned older events.
     */
    fun assessFromRawEvents(
        observations: List<com.tunemind.app.domain.model.PlaybackObservation>,
        retentionLimitMayBeActive: Boolean = false,
    ): CoverageAssessment {
        val validTimestamps = observations.map { it.eventTimeMs }.filter { it > 0L }
        val invalidTimestampCount = observations.size - validTimestamps.size
        val hasObservationStop = observations.any { it.eventType == com.tunemind.app.domain.event.ObservationEventType.OBSERVATION_STOPPED }
        val hasObservationStart = observations.any { it.eventType == com.tunemind.app.domain.event.ObservationEventType.OBSERVATION_STARTED }

        val boundaries = mutableListOf<ObservationBoundary>()

        // Per-session grouping
        val sessionGroups = observations.groupBy { it.observerSessionId }
        val namedSessions = sessionGroups.filterKeys { it.isNotEmpty() }
        val unassigned = sessionGroups[""]?.size ?: 0

        val sessionCoverages = namedSessions.map { (sessionId, events) ->
            val validTs = events.map { it.eventTimeMs }.filter { it > 0L }
            val hasStop = events.any { it.eventType == com.tunemind.app.domain.event.ObservationEventType.OBSERVATION_STOPPED }
            val createdCount = events.count { it.eventType == com.tunemind.app.domain.event.ObservationEventType.SESSION_CREATED }
            val destroyedCount = events.count { it.eventType == com.tunemind.app.domain.event.ObservationEventType.SESSION_DESTROYED }

            val sessionNotices = mutableListOf<String>()
            if (!hasStop) sessionNotices.add("No stop event — end time unknown.")
            if (createdCount == 0) sessionNotices.add("No SESSION_CREATED events.")

            SessionCoverage(
                sessionId = sessionId,
                totalEventCount = events.size,
                validTimestampCount = validTs.size,
                earliestMs = validTs.minOrNull(),
                latestMs = validTs.maxOrNull(),
                hasObservationStop = hasStop,
                sessionCreatedCount = createdCount,
                sessionDestroyedCount = destroyedCount,
                notices = sessionNotices,
            )
        }

        // Build aggregate boundaries
        observations.filter { it.eventType == com.tunemind.app.domain.event.ObservationEventType.OBSERVATION_STOPPED && it.eventTimeMs > 0L }
            .forEach { boundaries.add(ObservationBoundary(it.eventTimeMs, ObservationBoundary.BoundaryType.OBSERVATION_STOPPED)) }
        observations.filter { it.eventType == com.tunemind.app.domain.event.ObservationEventType.SESSION_CREATED && it.eventTimeMs > 0L }
            .forEach { boundaries.add(ObservationBoundary(it.eventTimeMs, ObservationBoundary.BoundaryType.SESSION_CREATED)) }
        observations.filter { it.eventType == com.tunemind.app.domain.event.ObservationEventType.SESSION_DESTROYED && it.eventTimeMs > 0L }
            .forEach { boundaries.add(ObservationBoundary(it.eventTimeMs, ObservationBoundary.BoundaryType.SESSION_DESTROYED)) }

        val notices = mutableListOf<String>()
        if (!hasObservationStart) {
            notices.add("No OBSERVATION_STARTED events found. The observer start time is inferred from the earliest event.")
        }
        if (!hasObservationStop) {
            notices.add("No OBSERVATION_STOPPED events found in any session. Observer session end times are unknown.")
        }
        if (observations.size >= 500) {
            notices.add("Event count is at or near the 500-record retention limit. Older events may have been pruned.")
        }
        if (invalidTimestampCount > 0) {
            notices.add("$invalidTimestampCount event(s) excluded due to invalid (<= 0) timestamps.")
        }
        if (unassigned > 0) {
            notices.add("$unassigned event(s) have blank session IDs and are not assigned to any session.")
        }

        return CoverageAssessment(
            boundaries = boundaries.sortedBy { it.timestampMs },
            earliestObservedMs = validTimestamps.minOrNull(),
            latestObservedMs = validTimestamps.maxOrNull(),
            retentionMayHavePruned = retentionLimitMayBeActive || observations.size >= 500,
            hasObservationStopEvents = hasObservationStop,
            distinctObserverSessionCount = namedSessions.size,
            invalidTimestampCount = invalidTimestampCount,
            sessionCoverages = sessionCoverages.sortedBy { it.earliestMs ?: Long.MAX_VALUE },
            unassignedEventCount = unassigned,
            notices = notices,
        )
    }

    /**
     * Convenience: assess coverage from a ListeningMemory and its builder inputs.
     */
    fun assessFromMemory(
        memory: com.tunemind.app.domain.model.ListeningMemory,
        eventTimestampsMs: List<Long>,
        hasObservationStop: Boolean,
        distinctSessionIdsCount: Int,
        invalidTimestampCount: Int,
        boundaries: List<ObservationBoundary> = emptyList(),
    ): CoverageAssessment {
        return assess(
            sessionTimestampsMs = eventTimestampsMs,
            hasObservationStop = hasObservationStop,
            historyMayBeIncomplete = memory.summary.historyMayBeIncomplete,
            retentionLimitReached = memory.notices.contains(
                com.tunemind.app.domain.model.MemoryDataQualityNotice.RETENTION_LIMIT_REACHED
            ),
            distinctSessionIdsCount = distinctSessionIdsCount,
            invalidTimestampCount = invalidTimestampCount,
            boundaries = boundaries,
        )
    }

    /**
     * Determine whether a specific time range has coverage evidence.
     *
     * @param rangeStartMs Start of the query range (ms since epoch).
     * @param rangeEndMs End of the query range (ms since epoch).
     * @param coverage The coverage assessment.
     * @return [CoverageStatus] for the requested range.
     */
    fun coverageAt(
        rangeStartMs: Long,
        rangeEndMs: Long,
        coverage: CoverageAssessment,
    ): CoverageStatus {
        val earliest = coverage.earliestObservedMs
        val latest = coverage.latestObservedMs

        if (earliest == null || latest == null) return CoverageStatus.UNKNOWN

        return when {
            rangeEndMs < earliest || rangeStartMs > latest -> CoverageStatus.UNOBSERVED
            rangeStartMs >= earliest && rangeEndMs <= latest -> CoverageStatus.EVENTS_KNOWN_WITHIN_BOUNDS
            else -> CoverageStatus.RANGE_EXTENDS_BEYOND_KNOWN_BOUNDS
        }
    }
}
