package com.tunemind.app.domain.signal

import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TemporalDynamicsEngineTest {

    private val MS_PER_DAY = 86_400_000L
    private val engine = TemporalDynamicsEngine(
        comparisonWindowMs = MS_PER_DAY, // 1 day for convenient arithmetic
        tolerance = 0.05,
    )

    private fun appearance(
        appearanceId: String,
        firstObservedAtMs: Long,
    ): MemoryAppearanceEvidence = MemoryAppearanceEvidence(
        reconstructedAppearanceId = appearanceId,
        firstObservedAtMs = firstObservedAtMs,
        lastObservedAtMs = firstObservedAtMs,
        supportingObservationIds = listOf("obs-$appearanceId"),
        evidenceStatus = TrackAppearanceEvidence.SAME_PROVIDER_ID,
        hasTimestampOrderingAmbiguity = false,
        hasObservationGap = false,
    )

    // 1. Increasing signal
    @Test fun increasingSignal() {
        val now = 1_000_000_000_000L
        // One appearance 7 days ago, one appearance now
        val past = now - 7 * MS_PER_DAY
        val timestamps = listOf(past, now)
        val result = engine.evaluate(now, timestamps)
        // Previous (6 days ago): only `past` contributes, decayed by 6 days
        // Current (now): both past and now contribute
        assertEquals(TemporalDirection.INCREASING, result.direction)
        assertTrue(result.signalDelta > 0.05)
    }

    // 2. Decreasing signal
    @Test fun decreasingSignal() {
        val now = 1_000_000_000_000L
        // One appearance 14 days ago, one appearance 7 days ago
        val old = now - 14 * MS_PER_DAY
        val recent = now - 7 * MS_PER_DAY
        val timestamps = listOf(old, recent)
        val result = engine.evaluate(now, timestamps)
        // Current (now): both decayed
        // Previous (7 days ago): both decayed less → higher previous signal
        assertEquals(TemporalDirection.DECREASING, result.direction)
        assertTrue(result.signalDelta < -0.05)
    }

    // 3. Stable signal within tolerance
    @Test fun stableSignalWithinTolerance() {
        val now = 1_000_000_000_000L
        // One appearance 30 days ago → well decayed; delta between current and
        // previous (1 day ago) is negligible (within 0.05 tolerance).
        val past = now - 30 * MS_PER_DAY
        val timestamps = listOf(past)
        val result = engine.evaluate(now, timestamps)
        // Both windows have the same appearance; signal changes very little
        assertEquals(TemporalDirection.STABLE, result.direction)
    }

    // 4. Delta at positive tolerance boundary
    @Test fun deltaAtPositiveToleranceBoundary() {
        val now = 1_000_000_000_000L
        // Two appearances: one just inside the comparison window
        val justBeforeWindow = now - MS_PER_DAY - 1 // barely inside previous window
        val nowAppearance = now
        val timestamps = listOf(justBeforeWindow, nowAppearance)
        val result = engine.evaluate(now, timestamps)
        // Should be INCREASING (positive delta above tolerance)
        assertEquals(TemporalDirection.INCREASING, result.direction)
    }

    // 5. Insufficient evidence at earlier point
    @Test fun insufficientEvidenceAtEarlierPoint() {
        val now = 1_000_000_000_000L
        // Appearance at the current time → not in previous window (which is now - 1 day)
        // The previous evaluation has no eligible appearances (future timestamp).
        // The current evaluation has 1 eligible appearance.
        // Delta = 1.0 - 0.0 = 1.0 > tolerance → INCREASING
        // This is correct: the signal went from "no evidence" to "evidence present."
        val timestamps = listOf(now)
        val result = engine.evaluate(now, timestamps)
        assertEquals(TemporalDirection.INCREASING, result.direction)
        assertEquals(0, result.previousEligibleCount)
        assertEquals(1, result.currentEligibleCount)
    }

    // 6. Future appearances excluded from historical calculations
    @Test fun futureAppearancesExcludedFromHistorical() {
        val now = 1_000_000_000_000L
        val future = now + MS_PER_DAY
        val past = now - 14 * MS_PER_DAY
        val timestamps = listOf(past, future)
        val result = engine.evaluate(now, timestamps)
        // Both current and previous should exclude the future timestamp
        // Only `past` contributes to both → STABLE or close to it
        assertEquals(TemporalDirection.STABLE, result.direction)
    }

    // 7. Invalid timestamps excluded
    @Test fun invalidTimestampsExcluded() {
        val now = 1_000_000_000_000L
        val timestamps = listOf(-1L, 0L, now)
        val result = engine.evaluate(now, timestamps)
        // Only `now` is valid
        assertTrue(result.currentEligibleCount > 0)
    }

    // 8. Duplicate appearance IDs not double-counted
    @Test fun duplicateAppearanceIdsNotDoubleCounted() {
        val now = 1_000_000_000_000L
        val timestamps = listOf(now, now)
        val result = engine.evaluate(now, timestamps)
        assertEquals(1, result.currentEligibleCount)
    }

    // 9. Insufficient track identity via evaluator
    @Test fun insufficientIdentityTrackExcludedByEvaluator() {
        val track = TrackMemoryEntry(
            entryId = "insufficient",
            sourcePackage = null, providerTrackId = null,
            title = null, artist = null, album = null,
            distinctAppearanceCount = 1,
            firstObservedAppearanceAtMs = 1_000_000_000_000L,
            lastObservedAppearanceAtMs = 1_000_000_000_000L,
            supportingObservationIds = listOf("obs-1"),
            identityBasis = TrackIdentityBasis.INSUFFICIENT,
            evidenceQuality = MemoryEvidenceQuality.INSUFFICIENT_IDENTITY,
            historyMayBeIncomplete = false,
            appearances = listOf(appearance("a1", 1_000_000_000_000L)),
        )
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(1, 1, 0, 0, 1, false),
            tracks = listOf(track),
            artists = emptyList(),
            notices = emptyList(),
        )
        val summary = TemporalDynamicsEvaluator.evaluate(memory, evaluationTimeMs = 1_000_000_000_000L)
        val trackResult = summary.trackDynamics.find { it.entryId == "insufficient" }!!
        assertEquals(0, trackResult.currentEligibleCount)
    }

    // 10. Invalid comparison window rejected
    @Test fun invalidComparisonWindowRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TemporalDynamicsEngine(comparisonWindowMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemporalDynamicsEngine(comparisonWindowMs = -1L)
        }
    }

    // 11. Invalid tolerance rejected
    @Test fun invalidToleranceRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TemporalDynamicsEngine(tolerance = -0.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemporalDynamicsEngine(tolerance = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemporalDynamicsEngine(tolerance = Double.POSITIVE_INFINITY)
        }
    }

    // 12. Changing evaluation time changes results deterministically
    @Test fun changingEvaluationTimeChangesResults() {
        val now = 1_000_000_000_000L
        val past = now - 14 * MS_PER_DAY
        val timestamps = listOf(past)

        // Evaluate at 'now' (past is 14 days ago, well decayed)
        val result1 = engine.evaluate(now, timestamps)
        // Evaluate at 'now - 7 days' (past is only 7 days ago, stronger)
        val result2 = engine.evaluate(now - 7 * MS_PER_DAY, timestamps)

        // Both should have the same eligible count
        assertEquals(result1.currentEligibleCount, result2.currentEligibleCount)
        // Current signal at later time should be lower (more decay)
        assertTrue(result1.currentSignal < result2.currentSignal)
    }

    // 13. Source memory unchanged
    @Test fun sourceMemoryUnchanged() {
        val track = TrackMemoryEntry(
            entryId = "track-1", sourcePackage = "com.spotify", providerTrackId = "id:1",
            title = "Song", artist = "Artist", album = "Album",
            distinctAppearanceCount = 1,
            firstObservedAppearanceAtMs = 1_000_000_000_000L,
            lastObservedAppearanceAtMs = 1_000_000_000_000L,
            supportingObservationIds = listOf("obs-1"),
            identityBasis = TrackIdentityBasis.PROVIDER_TRACK_ID,
            evidenceQuality = MemoryEvidenceQuality.SUPPORTED_IDENTITY,
            historyMayBeIncomplete = false,
            appearances = listOf(appearance("a1", 1_000_000_000_000L)),
        )
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(1, 1, 0, 0, 0, false),
            tracks = listOf(track),
            artists = emptyList(),
            notices = emptyList(),
        )
        val originalId = memory.tracks[0].entryId
        TemporalDynamicsEvaluator.evaluate(memory, evaluationTimeMs = 1_000_000_000_000L)
        assertEquals(originalId, memory.tracks[0].entryId)
    }

    // 14. Empty memory handled correctly
    @Test fun emptyMemoryHandledCorrectly() {
        val memory = ListeningMemoryBuilder().build(emptyList(), emptyList())
        val summary = TemporalDynamicsEvaluator.evaluate(memory)
        assertTrue(summary.trackDynamics.isEmpty())
        assertEquals(0, summary.totalTracksEvaluated)
    }

    // 15. No NaN or infinity for valid inputs
    @Test fun noNanOrInfinityForValidInputs() {
        val now = 1_000_000_000_000L
        val past = now - 30 * MS_PER_DAY
        val timestamps = listOf(past, now)
        val result = engine.evaluate(now, timestamps)
        assertFalse(result.currentSignal.isNaN())
        assertFalse(result.currentSignal.isInfinite())
        assertFalse(result.previousSignal.isNaN())
        assertFalse(result.previousSignal.isInfinite())
        assertFalse(result.signalDelta.isNaN())
        assertFalse(result.signalDelta.isInfinite())
        assertNotNull(result.changeRatePerDay)
        assertFalse(result.changeRatePerDay!!.isNaN())
        assertFalse(result.changeRatePerDay!!.isInfinite())
    }

    // 16. Default comparison window is 7 days
    @Test fun defaultComparisonWindowIsSevenDays() {
        val engine = TemporalDynamicsEngine()
        assertEquals(7 * 24 * 60 * 60 * 1000L, engine.comparisonWindowMs)
    }

    // 17. Default tolerance is 0.05
    @Test fun defaultToleranceIs005() {
        val engine = TemporalDynamicsEngine()
        assertEquals(0.05, engine.tolerance, 1e-9)
    }

    // 18. Both points insufficient → INSUFFICIENT_DATA
    @Test fun bothPointsInsufficientData() {
        val now = 1_000_000_000_000L
        val future = now + MS_PER_DAY
        val result = engine.evaluate(now, listOf(future))
        assertEquals(TemporalDirection.INSUFFICIENT_DATA, result.direction)
    }

    // 19. No appearances → INSUFFICIENT_DATA
    @Test fun noAppearancesInsufficientData() {
        val now = 1_000_000_000_000L
        val result = engine.evaluate(now, emptyList())
        assertEquals(TemporalDirection.INSUFFICIENT_DATA, result.direction)
    }
}
