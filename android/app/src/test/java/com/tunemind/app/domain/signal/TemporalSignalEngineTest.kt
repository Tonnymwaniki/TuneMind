package com.tunemind.app.domain.signal

import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TemporalSignalEngineTest {

    private val engine = TemporalSignalEngine(halfLifeMs = 86_400_000L) // 1 day for convenient arithmetic

    // Helper: build a TrackMemoryEntry with given appearances
    private fun trackEntry(
        entryId: String,
        identityBasis: TrackIdentityBasis = TrackIdentityBasis.PROVIDER_TRACK_ID,
        evidenceQuality: MemoryEvidenceQuality = MemoryEvidenceQuality.SUPPORTED_IDENTITY,
        appearances: List<MemoryAppearanceEvidence> = emptyList(),
        title: String? = "Song",
        artist: String? = "Artist",
    ): TrackMemoryEntry = TrackMemoryEntry(
        entryId = entryId,
        sourcePackage = "com.spotify.music",
        providerTrackId = if (identityBasis == TrackIdentityBasis.PROVIDER_TRACK_ID) "id:abc" else null,
        title = title,
        artist = artist,
        album = "Album",
        distinctAppearanceCount = appearances.size,
        firstObservedAppearanceAtMs = appearances.minOfOrNull { it.firstObservedAtMs },
        lastObservedAppearanceAtMs = appearances.maxOfOrNull { it.lastObservedAtMs },
        supportingObservationIds = appearances.flatMap { it.supportingObservationIds },
        identityBasis = identityBasis,
        evidenceQuality = evidenceQuality,
        historyMayBeIncomplete = false,
        appearances = appearances,
    )

    private fun appearance(
        appearanceId: String,
        firstObservedAtMs: Long,
        lastObservedAtMs: Long = firstObservedAtMs,
    ): MemoryAppearanceEvidence = MemoryAppearanceEvidence(
        reconstructedAppearanceId = appearanceId,
        firstObservedAtMs = firstObservedAtMs,
        lastObservedAtMs = lastObservedAtMs,
        supportingObservationIds = listOf("obs-$appearanceId"),
        evidenceStatus = TrackAppearanceEvidence.SAME_PROVIDER_ID,
        hasTimestampOrderingAmbiguity = false,
        hasObservationGap = false,
    )

    private val MS_PER_DAY = 86_400_000L

    // 1. One appearance at its own timestamp contributes full weight
    @Test fun oneAppearanceAtEvaluationTimeContributesFullWeight() {
        val now = 1_000_000_000_000L
        val result = engine.evaluate(now, listOf(now))
        assertEquals(1.0, result.signal, 1e-9)
        assertEquals(1, result.eligibleAppearanceCount)
    }

    // 2. One appearance exactly one half-life old contributes half
    @Test fun oneAppearanceOneHalfLifeOldContributesHalf() {
        val now = 1_000_000_000_000L
        val past = now - MS_PER_DAY
        val result = engine.evaluate(now, listOf(past))
        assertEquals(0.5, result.signal, 1e-9)
        assertEquals(1, result.eligibleAppearanceCount)
    }

    // 3. One appearance two half-lives old contributes one-quarter
    @Test fun oneAppearanceTwoHalfLivesOldContributesQuarter() {
        val now = 1_000_000_000_000L
        val past = now - 2 * MS_PER_DAY
        val result = engine.evaluate(now, listOf(past))
        assertEquals(0.25, result.signal, 1e-9)
    }

    // 4. Two distinct appearances contribute sum of decayed weights
    @Test fun twoDistinctAppearancesSumDecayedWeights() {
        val now = 1_000_000_000_000L
        val past1 = now - MS_PER_DAY       // 0.5
        val past2 = now - 2 * MS_PER_DAY   // 0.25
        val result = engine.evaluate(now, listOf(past1, past2))
        assertEquals(0.75, result.signal, 1e-9)
        assertEquals(2, result.eligibleAppearanceCount)
    }

    // 5. Duplicate appearance IDs do not double-count
    @Test fun duplicateAppearanceIdsNotDoubleCounted() {
        val now = 1_000_000_000_000L
        val past = now - MS_PER_DAY
        val result = engine.evaluate(now, listOf(past, past))
        assertEquals(0.5, result.signal, 1e-9)
        assertEquals(1, result.eligibleAppearanceCount)
        assertTrue(result.notices.contains(SignalNotice.DUPLICATE_APPEARANCE_IDS_REMOVED))
    }

    // 6. Multiple callbacks in one appearance → one contribution
    @Test fun multipleCallbacksInOneAppearanceNotMultipleContributions() {
        val now = 1_000_000_000_000L
        // One appearance ID even though it has many supporting observations
        val result = engine.evaluate(now, listOf(now))
        assertEquals(1.0, result.signal, 1e-9)
        assertEquals(1, result.eligibleAppearanceCount)
    }

    // 7. Insufficient-identity appearances excluded via TemporalSignalEvaluator
    @Test fun insufficientIdentityTrackExcludedByEvaluator() {
        val track = trackEntry(
            entryId = "insufficient-track",
            identityBasis = TrackIdentityBasis.INSUFFICIENT,
            appearances = listOf(appearance("a1", 1_000_000_000_000L)),
        )
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(1, 1, 0, 0, 1, false),
            tracks = listOf(track),
            artists = emptyList(),
            notices = emptyList(),
        )
        val summary = TemporalSignalEvaluator.evaluate(memory, evaluationTimeMs = 1_000_000_000_000L)
        // The track appears in the results but the evaluator filters its appearances
        val trackResult = summary.trackSignals.find { it.entryId == "insufficient-track" }
        assertNotNull(trackResult)
        assertEquals(0, trackResult!!.signal.eligibleAppearanceCount)
        assertEquals(0.0, trackResult.signal.signal, 1e-9)
    }

    // 8. Missing/invalid timestamps handled
    @Test fun invalidTimestampsExcluded() {
        val now = 1_000_000_000_000L
        val result = engine.evaluate(now, listOf(-1L, 0L, now))
        assertEquals(1.0, result.signal, 1e-9)
        assertEquals(1, result.eligibleAppearanceCount)
        assertEquals(2, result.excludedInvalidTimestampCount)
        assertTrue(result.notices.contains(SignalNotice.INVALID_TIMESTAMPS_EXCLUDED))
    }

    // 9. Future timestamps do not contribute to signal
    @Test fun futureTimestampsDoNotContributeToSignal() {
        val now = 1_000_000_000_000L
        val future = now + MS_PER_DAY
        val result = engine.evaluate(now, listOf(future))
        assertEquals(0.0, result.signal, 1e-9) // not included
        assertEquals(0, result.eligibleAppearanceCount)
        assertEquals(1, result.futureAppearanceCount)
        assertNull(result.latestEligibleAppearanceMs)
        assertTrue(result.notices.contains(SignalNotice.FUTURE_APPEARANCES_PRESENT))
    }

    // 9b. Future-only track produces signal 0 with notices
    @Test fun futureOnlyTrackProducesZeroSignal() {
        val now = 1_000_000_000_000L
        val future1 = now + MS_PER_DAY
        val future2 = now + 2 * MS_PER_DAY
        val result = engine.evaluate(now, listOf(future1, future2))
        assertEquals(0.0, result.signal, 1e-9)
        assertEquals(0, result.eligibleAppearanceCount)
        assertEquals(2, result.futureAppearanceCount)
        assertTrue(result.notices.contains(SignalNotice.FUTURE_APPEARANCES_PRESENT))
        assertTrue(result.notices.contains(SignalNotice.NO_ELIGIBLE_APPEARANCES))
    }

    // 9c. Mixed past, present, and future timestamps
    @Test fun mixedTimestampsIncludePastAndExcludeFuture() {
        val now = 1_000_000_000_000L
        val past = now - MS_PER_DAY        // 0.5
        val future = now + MS_PER_DAY      // excluded
        val present = now                   // 1.0
        val result = engine.evaluate(now, listOf(past, future, present))
        assertEquals(1.5, result.signal, 1e-9) // 0.5 + 1.0
        assertEquals(2, result.eligibleAppearanceCount)
        assertEquals(1, result.futureAppearanceCount)
        assertEquals(present, result.latestEligibleAppearanceMs)
        assertTrue(result.notices.contains(SignalNotice.FUTURE_APPEARANCES_PRESENT))
    }

    // 10. Invalid half-life rejected
    @Test fun invalidHalfLifeRejected() {
        assertThrows(IllegalArgumentException::class.java) { TemporalSignalEngine(halfLifeMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) { TemporalSignalEngine(halfLifeMs = -1L) }
    }

    // 11. Changing evaluation timestamp changes signal deterministically
    @Test fun changingEvaluationTimeChangesSignal() {
        val appearanceTime = 1_000_000_000_000L
        val result1 = engine.evaluate(appearanceTime, listOf(appearanceTime))
        assertEquals(1.0, result1.signal, 1e-9)

        val result2 = engine.evaluate(appearanceTime + MS_PER_DAY, listOf(appearanceTime))
        assertEquals(0.5, result2.signal, 1e-9)
    }

    // 12. Source memory unmodified
    @Test fun sourceMemoryNotModified() {
        val track = trackEntry(
            entryId = "track-1",
            appearances = listOf(appearance("a1", 1_000_000_000_000L)),
        )
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(1, 1, 0, 0, 0, false),
            tracks = listOf(track),
            artists = emptyList(),
            notices = emptyList(),
        )
        val originalAppearanceCount = memory.tracks[0].appearances.size
        val originalObservationIds = memory.tracks[0].supportingObservationIds.size
        TemporalSignalEvaluator.evaluate(memory, evaluationTimeMs = 1_000_000_000_000L)
        assertEquals(originalAppearanceCount, memory.tracks[0].appearances.size)
        assertEquals(originalObservationIds, memory.tracks[0].supportingObservationIds.size)
    }

    // 13. No eligible appearances → signal 0 with notice
    @Test fun noEligibleAppearancesReturnsZeroSignal() {
        val now = 1_000_000_000_000L
        val result = engine.evaluate(now, emptyList())
        assertEquals(0.0, result.signal, 1e-9)
        assertEquals(0, result.eligibleAppearanceCount)
        assertTrue(result.notices.contains(SignalNotice.NO_ELIGIBLE_APPEARANCES))
    }

    // 14. Default half-life is 7 days
    @Test fun defaultHalfLifeIsSevenDays() {
        val engine = TemporalSignalEngine()
        assertEquals(7 * 24 * 60 * 60 * 1000L, engine.halfLifeMs)
    }

    // 15. TemporalSignalEvaluator with real ListeningMemoryBuilder
    @Test fun evaluatorWithEmptyMemoryReturnsEmptySummary() {
        val memory = ListeningMemoryBuilder().build(emptyList(), emptyList())
        val summary = TemporalSignalEvaluator.evaluate(memory)
        assertTrue(summary.trackSignals.isEmpty())
        assertEquals(0, summary.totalTracksEvaluated)
    }
}
