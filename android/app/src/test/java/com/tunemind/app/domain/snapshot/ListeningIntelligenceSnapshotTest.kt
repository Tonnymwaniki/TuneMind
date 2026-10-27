package com.tunemind.app.domain.snapshot

import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.*
import com.tunemind.app.domain.signal.TemporalDirection
import com.tunemind.app.domain.signal.TemporalSignalEngine
import org.junit.Assert.*
import org.junit.Test

class ListeningIntelligenceSnapshotTest {

    private val MS_PER_DAY = 86_400_000L
    private val NOW = 1_000_000_000_000L

    private fun appearance(id: String, ms: Long) = MemoryAppearanceEvidence(
        reconstructedAppearanceId = id,
        firstObservedAtMs = ms, lastObservedAtMs = ms,
        supportingObservationIds = listOf("obs-$id"),
        evidenceStatus = TrackAppearanceEvidence.SAME_PROVIDER_ID,
        hasTimestampOrderingAmbiguity = false, hasObservationGap = false,
    )

    private fun trackEntry(
        entryId: String,
        appearances: List<MemoryAppearanceEvidence>,
        identityBasis: TrackIdentityBasis = TrackIdentityBasis.PROVIDER_TRACK_ID,
        evidenceQuality: MemoryEvidenceQuality = MemoryEvidenceQuality.SUPPORTED_IDENTITY,
        title: String? = "Song",
        artist: String? = "Artist",
    ) = TrackMemoryEntry(
        entryId = entryId, sourcePackage = "com.spotify", providerTrackId = if (identityBasis == TrackIdentityBasis.PROVIDER_TRACK_ID) "id:1" else null,
        title = title, artist = artist, album = "Album",
        distinctAppearanceCount = appearances.size,
        firstObservedAppearanceAtMs = appearances.minOfOrNull { it.firstObservedAtMs },
        lastObservedAppearanceAtMs = appearances.maxOfOrNull { it.firstObservedAtMs },
        supportingObservationIds = appearances.flatMap { it.supportingObservationIds },
        identityBasis = identityBasis, evidenceQuality = evidenceQuality,
        historyMayBeIncomplete = false, appearances = appearances,
    )

    private fun memory(tracks: List<TrackMemoryEntry>) = ListeningMemory(
        summary = ListeningMemorySummary(
            identifiableTrackCount = tracks.count { it.identityBasis != TrackIdentityBasis.INSUFFICIENT },
            distinctReconstructedAppearanceCount = tracks.sumOf { it.appearances.size },
            recurringTrackIdentityCount = tracks.count { it.distinctAppearanceCount >= 2 },
            identifiableArtistSummaryCount = tracks.mapNotNull { it.artist }.distinct().size,
            unidentifiedAppearanceCount = tracks.filter { it.identityBasis == TrackIdentityBasis.INSUFFICIENT }.sumOf { it.appearances.size },
            historyMayBeIncomplete = false,
        ),
        tracks = tracks, artists = emptyList(), notices = emptyList(),
    )

    // 1. One track appearing regularly
    @Test fun regularAppearancesProduceExpectedSignal() {
        val track = trackEntry("t1", listOf(
            appearance("a1", NOW - 14 * MS_PER_DAY),
            appearance("a2", NOW - 7 * MS_PER_DAY),
            appearance("a3", NOW),
        ))
        val snap = ListeningIntelligenceBuilder.build(memory(listOf(track)), NOW)
        assertEquals(1, snap.identifiedTrackCount)
        val ts = snap.tracks[0]
        assertTrue(ts.recurrenceSignal > 0)
        assertTrue(ts.eligibleAppearanceCount >= 3)
    }

    // 2. Short burst then quiet period → decreasing
    @Test fun burstThenQuietIsDecreasing() {
        // Appearances: burst at -30d and -28d, then quiet (no recent appearances)
        // The most recent appearance is at -28d, which is well before the
        // 7-day comparison window. Both evaluation points see the same
        // appearances, and the signal decays → DECREASING.
        val track = trackEntry("t1", listOf(
            appearance("a1", NOW - 30 * MS_PER_DAY),
            appearance("a2", NOW - 28 * MS_PER_DAY),
        ))
        val snap = ListeningIntelligenceBuilder.build(memory(listOf(track)), NOW)
        assertEquals(TemporalDirection.DECREASING, snap.tracks[0].temporalDirection)
    }

    // 3. Gradually decreasing recurrence → decreasing
    @Test fun graduallyDecreasingRecurrenceIsDecreasing() {
        val track = trackEntry("t1", listOf(
            appearance("a1", NOW - 30 * MS_PER_DAY),
            appearance("a2", NOW - 20 * MS_PER_DAY),
            appearance("a3", NOW - 10 * MS_PER_DAY),
        ))
        val snap = ListeningIntelligenceBuilder.build(memory(listOf(track)), NOW)
        // Each appearance is 10 days apart, well decayed
        assertEquals(TemporalDirection.DECREASING, snap.tracks[0].temporalDirection)
    }

    // 4. Track reappearing after long gap
    @Test fun reappearanceAfterLongGap() {
        val track = trackEntry("t1", listOf(
            appearance("a1", NOW - 60 * MS_PER_DAY),
            appearance("a2", NOW),
        ))
        val snap = ListeningIntelligenceBuilder.build(memory(listOf(track)), NOW)
        assertTrue(snap.tracks[0].hasReappearanceAfterGap)
        assertTrue(snap.reappearanceAfterGapCount >= 1)
    }

    // 5. Multiple tracks with equal representation
    @Test fun equalRepresentationConcentration() {
        val tracks = (1..3).map { i ->
            trackEntry("t$i", listOf(appearance("a${i}1", NOW - MS_PER_DAY)))
        }
        val snap = ListeningIntelligenceBuilder.build(memory(tracks), NOW)
        assertEquals(3, snap.concentrationDistinctTrackCount)
        snap.normalizedConcentration?.let { assertEquals(1.0 / 3, it, 0.01) }
    }

    // 6. Insufficient-identity track
    @Test fun insufficientIdentityTrack() {
        val track = trackEntry("t1", listOf(appearance("a1", NOW)), identityBasis = TrackIdentityBasis.INSUFFICIENT)
        val snap = ListeningIntelligenceBuilder.build(memory(listOf(track)), NOW)
        assertEquals(0, snap.identifiedTrackCount)
        assertEquals(1, snap.totalTracksInMemory)
    }

    // 7. Duplicate appearance IDs
    @Test fun duplicateAppearanceIds() {
        val track = trackEntry("t1", listOf(appearance("a1", NOW - MS_PER_DAY), appearance("a1", NOW - MS_PER_DAY)))
        val snap = ListeningIntelligenceBuilder.build(memory(listOf(track)), NOW)
        val ts = snap.tracks[0]
        assertEquals(1, ts.eligibleAppearanceCount) // deduplicated
    }

    // 8. Future-dated and invalid timestamps
    @Test fun futureAndInvalidTimestamps() {
        val track = trackEntry("t1", listOf(
            appearance("a1", -1L),
            appearance("a2", NOW + 10 * MS_PER_DAY),
            appearance("a3", NOW),
        ))
        val snap = ListeningIntelligenceBuilder.build(memory(listOf(track)), NOW)
        val ts = snap.tracks[0]
        assertEquals(1, ts.eligibleAppearanceCount) // only NOW is eligible
        assertEquals(1, ts.futureAppearanceCount)   // future timestamp
    }

    // 9. Conflicting metadata
    @Test fun conflictingMetadataQuality() {
        val track = trackEntry("t1", listOf(appearance("a1", NOW)), evidenceQuality = MemoryEvidenceQuality.CONFLICTING_METADATA)
        val snap = ListeningIntelligenceBuilder.build(memory(listOf(track)), NOW)
        assertEquals(1, snap.evidenceQuality.conflictingMetadataCount)
    }

    // 10. Incomplete history
    @Test fun incompleteHistory() {
        val mem = memory(emptyList()).copy(
            summary = ListeningMemorySummary(0, 0, 0, 0, 0, historyMayBeIncomplete = true)
        )
        val snap = ListeningIntelligenceBuilder.build(mem, NOW)
        assertTrue(snap.historyMayBeIncomplete)
    }

    // 11. Deterministic for identical inputs
    @Test fun deterministicForIdenticalInputs() {
        val track = trackEntry("t1", listOf(appearance("a1", NOW - 7 * MS_PER_DAY), appearance("a2", NOW)))
        val mem = memory(listOf(track))
        val snap1 = ListeningIntelligenceBuilder.build(mem, NOW)
        val snap2 = ListeningIntelligenceBuilder.build(mem, NOW)
        assertEquals(snap1.tracks[0].recurrenceSignal, snap2.tracks[0].recurrenceSignal, 1e-9)
        assertEquals(snap1.tracks[0].temporalDirection, snap2.tracks[0].temporalDirection)
    }

    // 12. Different evaluation times produce different results
    @Test fun differentEvaluationTimesProduceDifferentResults() {
        val track = trackEntry("t1", listOf(appearance("a1", NOW - 7 * MS_PER_DAY)))
        val mem = memory(listOf(track))
        val snap1 = ListeningIntelligenceBuilder.build(mem, NOW)
        val snap2 = ListeningIntelligenceBuilder.build(mem, NOW + 30 * MS_PER_DAY)
        assertTrue(snap1.tracks[0].recurrenceSignal > snap2.tracks[0].recurrenceSignal)
    }

    // 13. Empty memory
    @Test fun emptyMemory() {
        val snap = ListeningIntelligenceBuilder.build(memory(emptyList()), NOW)
        assertEquals(0, snap.totalTracksInMemory)
        assertEquals(0, snap.identifiedTrackCount)
    }

    // 14. No NaN or infinity
    @Test fun noNanOrInfinity() {
        val track = trackEntry("t1", listOf(appearance("a1", NOW - 7 * MS_PER_DAY)))
        val snap = ListeningIntelligenceBuilder.build(memory(listOf(track)), NOW)
        for (ts in snap.tracks) {
            assertFalse(ts.recurrenceSignal.isNaN())
            assertFalse(ts.recurrenceSignal.isInfinite())
            assertFalse(ts.signalDelta.isNaN())
            assertFalse(ts.signalDelta.isInfinite())
        }
    }

    // 15. Default configuration values
    @Test fun defaultConfigValues() {
        assertEquals(TemporalSignalEngine.DEFAULT_HALF_LIFE_MS, ListeningIntelligenceBuilder.DEFAULT_HALF_LIFE_MS)
        assertEquals(7 * 24 * 60 * 60 * 1000L, ListeningIntelligenceBuilder.DEFAULT_REAPPEARANCE_MIN_GAP_MS)
    }

    // 16. Coverage assessment present in snapshot
    @Test fun coveragePresentInSnapshot() {
        val track = trackEntry("t1", listOf(appearance("a1", NOW)))
        val snap = ListeningIntelligenceBuilder.build(
            memory = memory(listOf(track)),
            evaluationTimeMs = NOW,
            allEventTimestampsMs = listOf(NOW),
            hasObservationStopEvents = false,
            distinctSessionCount = 1,
        )
        assertNotNull(snap.coverage)
        assertEquals(1, snap.coverage.distinctObserverSessionCount)
        assertEquals(NOW, snap.coverage.earliestObservedMs)
        assertEquals(NOW, snap.coverage.latestObservedMs)
    }

    // 17. Coverage notices propagated
    @Test fun coverageNoticesPropagated() {
        val track = trackEntry("t1", listOf(appearance("a1", NOW)))
        val mem = memory(listOf(track)).copy(
            summary = ListeningMemorySummary(1, 1, 0, 0, 0, historyMayBeIncomplete = true),
        )
        val snap = ListeningIntelligenceBuilder.build(
            memory = mem,
            evaluationTimeMs = NOW,
            allEventTimestampsMs = listOf(NOW),
        )
        assertTrue(snap.coverage.retentionMayHavePruned)
        assertTrue(snap.coverage.notices.any { it.contains("pruned") })
    }

    // 18. Coverage with observation stop
    @Test fun coverageWithObservationStop() {
        val track = trackEntry("t1", listOf(appearance("a1", NOW)))
        val snap = ListeningIntelligenceBuilder.build(
            memory = memory(listOf(track)),
            evaluationTimeMs = NOW,
            allEventTimestampsMs = listOf(NOW),
            hasObservationStopEvents = true,
        )
        assertTrue(snap.coverage.hasObservationStopEvents)
    }
}
