package com.tunemind.app.domain.coverage

import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ObservationCoverageTest {

    private val MS_PER_DAY = 86_400_000L
    private val NOW = 1_000_000_000_000L

    // 1. Known lifecycle boundaries
    @Test fun knownBoundaries() {
        val boundaries = listOf(
            ObservationBoundary(NOW - 30 * MS_PER_DAY, ObservationBoundary.BoundaryType.FIRST_OBSERVED),
            ObservationBoundary(NOW, ObservationBoundary.BoundaryType.OBSERVATION_STOPPED),
        )
        val assessment = CoverageAssessor.assess(
            sessionTimestampsMs = listOf(NOW - 30 * MS_PER_DAY, NOW),
            hasObservationStop = true,
            historyMayBeIncomplete = false,
            retentionLimitReached = false,
            distinctSessionIdsCount = 1,
            invalidTimestampCount = 0,
            boundaries = boundaries,
        )
        assertEquals(2, assessment.boundaries.size)
        assertEquals(NOW - 30 * MS_PER_DAY, assessment.earliestObservedMs)
        assertEquals(NOW, assessment.latestObservedMs)
    }

    // 2. Missing start or stop evidence
    @Test fun missingStartStopEvidence() {
        val assessment = CoverageAssessor.assess(
            sessionTimestampsMs = listOf(NOW - 10 * MS_PER_DAY, NOW),
            hasObservationStop = false,
            historyMayBeIncomplete = false,
            retentionLimitReached = false,
            distinctSessionIdsCount = 1,
            invalidTimestampCount = 0,
        )
        assertFalse(assessment.hasObservationStopEvents)
        assertTrue(assessment.notices.any { it.contains("OBSERVATION_STOPPED") })
    }

    // 3. Unknown periods between callbacks
    @Test fun unknownPeriodsBetweenCallbacks() {
        val timestamps = listOf(NOW - 60 * MS_PER_DAY, NOW)
        val assessment = CoverageAssessor.assess(
            sessionTimestampsMs = timestamps,
            hasObservationStop = false,
            historyMayBeIncomplete = false,
            retentionLimitReached = false,
            distinctSessionIdsCount = 1,
            invalidTimestampCount = 0,
        )
        assertEquals(NOW - 60 * MS_PER_DAY, assessment.earliestObservedMs)
        assertEquals(NOW, assessment.latestObservedMs)
        // The gap is a temporal gap, not proof of non-listening
        assertTrue(assessment.notices.any { it.contains("OBSERVATION_STOPPED") })
    }

    // 4. Invalid timestamps
    @Test fun invalidTimestamps() {
        val assessment = CoverageAssessor.assess(
            sessionTimestampsMs = listOf(-1L, 0L, NOW),
            hasObservationStop = false,
            historyMayBeIncomplete = false,
            retentionLimitReached = false,
            distinctSessionIdsCount = 1,
            invalidTimestampCount = 2,
        )
        assertEquals(2, assessment.invalidTimestampCount)
        assertTrue(assessment.notices.any { it.contains("invalid") })
    }

    // 5. Incomplete history due to retention
    @Test fun incompleteHistory() {
        val assessment = CoverageAssessor.assess(
            sessionTimestampsMs = listOf(NOW),
            hasObservationStop = false,
            historyMayBeIncomplete = true,
            retentionLimitReached = true,
            distinctSessionIdsCount = 1,
            invalidTimestampCount = 0,
        )
        assertTrue(assessment.retentionMayHavePruned)
        assertTrue(assessment.earliestIsTruncatedByRetention)
    }

    // 6. Long appearance gaps (not inferring non-listening)
    @Test fun longGapDoesNotProveNonListening() {
        val assessment = CoverageAssessor.assess(
            sessionTimestampsMs = listOf(NOW - 90 * MS_PER_DAY, NOW),
            hasObservationStop = false,
            historyMayBeIncomplete = false,
            retentionLimitReached = false,
            distinctSessionIdsCount = 1,
            invalidTimestampCount = 0,
        )
        assertEquals(NOW - 90 * MS_PER_DAY, assessment.earliestObservedMs)
        assertEquals(NOW, assessment.latestObservedMs)
        // No claim about what happened during the gap
    }

    // 7. Identical inputs produce identical results
    @Test fun deterministicForIdenticalInputs() {
        val timestamps = listOf(NOW - 14 * MS_PER_DAY, NOW)
        val boundaries = listOf(ObservationBoundary(NOW, ObservationBoundary.BoundaryType.OBSERVATION_STOPPED))
        val a1 = CoverageAssessor.assess(timestamps, true, false, false, 1, 0, boundaries)
        val a2 = CoverageAssessor.assess(timestamps, true, false, false, 1, 0, boundaries)
        assertEquals(a1.earliestObservedMs, a2.earliestObservedMs)
        assertEquals(a1.notices, a2.notices)
    }

    // 8. Empty history
    @Test fun emptyHistory() {
        val assessment = CoverageAssessor.assess(emptyList(), false, false, false, 0, 0)
        assertNull(assessment.earliestObservedMs)
        assertNull(assessment.latestObservedMs)
    }

    // 9. Coverage status for known range
    @Test fun coverageStatusEventsKnownWithinBounds() {
        val assessment = CoverageAssessor.assess(
            listOf(NOW - 10 * MS_PER_DAY, NOW), true, false, false, 1, 0,
        )
        val status = CoverageAssessor.coverageAt(NOW - 5 * MS_PER_DAY, NOW, assessment)
        assertEquals(CoverageStatus.EVENTS_KNOWN_WITHIN_BOUNDS, status)
    }

    // 10. Coverage status for range before earliest
    @Test fun coverageStatusRangeExtendsBeyond() {
        val assessment = CoverageAssessor.assess(
            listOf(NOW), true, false, false, 1, 0,
        )
        val status = CoverageAssessor.coverageAt(NOW - 10 * MS_PER_DAY, NOW - 5 * MS_PER_DAY, assessment)
        assertEquals(CoverageStatus.UNOBSERVED, status)
    }

    // 11. Coverage status for unknown range
    @Test fun coverageStatusUnknown() {
        val assessment = CoverageAssessor.assess(emptyList(), false, false, false, 0, 0)
        val status = CoverageAssessor.coverageAt(NOW, NOW + MS_PER_DAY, assessment)
        assertEquals(CoverageStatus.UNKNOWN, status)
    }

    // 12. Coverage status for range that extends beyond bounds
    @Test fun coverageStatusRangeExtendsBeyondKnownBounds() {
        val assessment = CoverageAssessor.assess(
            listOf(NOW), true, false, false, 1, 0,
        )
        val status = CoverageAssessor.coverageAt(NOW, NOW + 10 * MS_PER_DAY, assessment)
        assertEquals(CoverageStatus.RANGE_EXTENDS_BEYOND_KNOWN_BOUNDS, status)
    }

    // 13. Raw event assessment detects OBSERVATION_STOPPED
    @Test fun rawEventAssessmentDetectsStopEvents() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "ses-1", eventType = com.tunemind.app.domain.event.ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW - MS_PER_DAY, sourcePackage = "com.spotify"),
            PlaybackObservation(eventId = "e2", observerSessionId = "ses-1", eventType = com.tunemind.app.domain.event.ObservationEventType.OBSERVATION_STOPPED, eventTimeMs = NOW, sourcePackage = null),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        assertTrue(assessment.hasObservationStopEvents)
        assertEquals(1, assessment.distinctObserverSessionCount)
        assertEquals(1, assessment.boundaries.count { it.type == ObservationBoundary.BoundaryType.OBSERVATION_STOPPED })
    }

    // 14. Raw event assessment: multiple sessions
    @Test fun rawEventAssessmentMultipleSessions() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "ses-a", eventType = com.tunemind.app.domain.event.ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW - 10 * MS_PER_DAY, sourcePackage = "com.spotify"),
            PlaybackObservation(eventId = "e2", observerSessionId = "ses-b", eventType = com.tunemind.app.domain.event.ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW, sourcePackage = "com.spotify"),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        assertEquals(2, assessment.distinctObserverSessionCount)
    }

    // 15. Raw event assessment: invalid timestamps
    @Test fun rawEventAssessmentInvalidTimestamps() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "ses-1", eventType = com.tunemind.app.domain.event.ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = -1L, sourcePackage = "com.spotify"),
            PlaybackObservation(eventId = "e2", observerSessionId = "ses-1", eventType = com.tunemind.app.domain.event.ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW, sourcePackage = "com.spotify"),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        assertEquals(1, assessment.invalidTimestampCount)
    }

    // 16. SESSION_DESTROYED not mistaken for OBSERVATION_STOPPED
    @Test fun sessionDestroyedNotObservationStop() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "ses-1", eventType = com.tunemind.app.domain.event.ObservationEventType.SESSION_DESTROYED, eventTimeMs = NOW, sourcePackage = "com.spotify"),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        assertFalse(assessment.hasObservationStopEvents)
        assertEquals(1, assessment.boundaries.count { it.type == ObservationBoundary.BoundaryType.SESSION_DESTROYED })
        assertEquals(0, assessment.boundaries.count { it.type == ObservationBoundary.BoundaryType.OBSERVATION_STOPPED })
    }

    // 17. Missing stop evidence not treated as continuous
    @Test fun missingStopNotContinuous() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "ses-1", eventType = com.tunemind.app.domain.event.ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW, sourcePackage = "com.spotify"),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        assertFalse(assessment.hasObservationStopEvents)
        assertTrue(assessment.notices.any { it.contains("OBSERVATION_STOPPED") })
    }

    // 18. Two sessions with separate time ranges
    @Test fun twoSessionsSeparateTimeRanges() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "ses-a", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW - 20 * MS_PER_DAY, sourcePackage = "com.spotify"),
            PlaybackObservation(eventId = "e2", observerSessionId = "ses-b", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW, sourcePackage = "com.spotify"),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        assertEquals(2, assessment.distinctObserverSessionCount)
        assertEquals(2, assessment.sessionCoverages.size)
        val sesA = assessment.sessionCoverages.find { it.sessionId == "ses-a" }!!
        val sesB = assessment.sessionCoverages.find { it.sessionId == "ses-b" }!!
        assertEquals(NOW - 20 * MS_PER_DAY, sesA.earliestMs)
        assertEquals(NOW, sesB.earliestMs)
    }

    // 19. Stop event in session A does not affect session B
    @Test fun stopEventInSessionAOnly() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "ses-a", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW - MS_PER_DAY, sourcePackage = "com.spotify"),
            PlaybackObservation(eventId = "e2", observerSessionId = "ses-a", eventType = ObservationEventType.OBSERVATION_STOPPED, eventTimeMs = NOW, sourcePackage = null),
            PlaybackObservation(eventId = "e3", observerSessionId = "ses-b", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW, sourcePackage = "com.spotify"),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        assertTrue(assessment.sessionCoverages.find { it.sessionId == "ses-a" }!!.hasObservationStop)
        assertFalse(assessment.sessionCoverages.find { it.sessionId == "ses-b" }!!.hasObservationStop)
    }

    // 20. Multiple stop events in one session
    @Test fun multipleStopEventsOneSession() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "ses-1", eventType = ObservationEventType.OBSERVATION_STOPPED, eventTimeMs = NOW - MS_PER_DAY, sourcePackage = null),
            PlaybackObservation(eventId = "e2", observerSessionId = "ses-1", eventType = ObservationEventType.OBSERVATION_STOPPED, eventTimeMs = NOW, sourcePackage = null),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        val ses = assessment.sessionCoverages.find { it.sessionId == "ses-1" }!!
        assertTrue(ses.hasObservationStop)
    }

    // 21. Missing and blank session IDs
    @Test fun blankSessionIdsUnassigned() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW, sourcePackage = "com.spotify"),
            PlaybackObservation(eventId = "e2", observerSessionId = "ses-1", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW, sourcePackage = "com.spotify"),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        assertEquals(1, assessment.unassignedEventCount)
        assertEquals(1, assessment.distinctObserverSessionCount)
        assertTrue(assessment.notices.any { it.contains("blank") })
    }

    // 22. Session with no start or stop evidence
    @Test fun sessionNoStartOrStop() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "ses-1", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW, sourcePackage = "com.spotify"),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        val ses = assessment.sessionCoverages.find { it.sessionId == "ses-1" }!!
        assertFalse(ses.hasObservationStop)
        assertEquals(0, ses.sessionCreatedCount)
    }

    // 23. Aggregate bounds from sessions
    @Test fun aggregateBoundsFromSessions() {
        val obs = listOf(
            PlaybackObservation(eventId = "e1", observerSessionId = "ses-a", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW - 10 * MS_PER_DAY, sourcePackage = "com.spotify"),
            PlaybackObservation(eventId = "e2", observerSessionId = "ses-b", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW, sourcePackage = "com.spotify"),
        )
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        assertEquals(NOW - 10 * MS_PER_DAY, assessment.earliestObservedMs)
        assertEquals(NOW, assessment.latestObservedMs)
    }

    // 24. Retention notices preserved
    @Test fun retentionNoticesPreserved() {
        val obs = (1..501).map { i ->
            PlaybackObservation(eventId = "e$i", observerSessionId = "ses-1", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED, eventTimeMs = NOW - (501 - i).toLong(), sourcePackage = "com.spotify")
        }
        val assessment = CoverageAssessor.assessFromRawEvents(obs)
        assertTrue(assessment.retentionMayHavePruned)
        assertTrue(assessment.notices.any { it.contains("retention") })
    }

    // 25. assessFromMemory convenience
    @Test fun assessFromMemory() {
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(0, 0, 0, 0, 0, historyMayBeIncomplete = false),
            tracks = emptyList(), artists = emptyList(), notices = emptyList(),
        )
        val assessment = CoverageAssessor.assessFromMemory(
            memory = memory,
            eventTimestampsMs = listOf(NOW),
            hasObservationStop = true,
            distinctSessionIdsCount = 1,
            invalidTimestampCount = 0,
        )
        assertEquals(NOW, assessment.earliestObservedMs)
    }
}
