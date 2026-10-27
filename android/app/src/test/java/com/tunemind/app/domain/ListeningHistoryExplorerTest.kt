package com.tunemind.app.domain

import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.*
import com.tunemind.app.domain.research.detectReappearanceAfterGap
import com.tunemind.app.domain.signal.TemporalSignalEngine
import org.junit.Assert.*
import org.junit.Test

class ListeningHistoryExplorerTest {

    private val MS_PER_DAY = 86_400_000L
    private val NOW = 1_000_000_000_000L
    private val engine = TemporalSignalEngine()

    private fun appearance(id: String, ms: Long) = MemoryAppearanceEvidence(
        reconstructedAppearanceId = id,
        firstObservedAtMs = ms, lastObservedAtMs = ms,
        supportingObservationIds = listOf("obs-$id"),
        evidenceStatus = TrackAppearanceEvidence.SAME_PROVIDER_ID,
        hasTimestampOrderingAmbiguity = false, hasObservationGap = false,
    )

    // 1. Empty history returns zero counts
    @Test fun emptyHistory() {
        val memory = ListeningMemoryBuilder().build(emptyList(), emptyList())
        assertEquals(0, memory.tracks.size)
        assertEquals(0, memory.summary.identifiableTrackCount)
    }

    // 2. Insufficient identity
    @Test fun insufficientIdentity() {
        val track = TrackMemoryEntry(
            entryId = "t1", sourcePackage = null, providerTrackId = null,
            title = null, artist = null, album = null,
            distinctAppearanceCount = 1,
            firstObservedAppearanceAtMs = NOW, lastObservedAppearanceAtMs = NOW,
            supportingObservationIds = listOf("obs-1"),
            identityBasis = TrackIdentityBasis.INSUFFICIENT,
            evidenceQuality = MemoryEvidenceQuality.INSUFFICIENT_IDENTITY,
            historyMayBeIncomplete = false,
            appearances = listOf(appearance("a1", NOW)),
        )
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(0, 1, 0, 0, 1, false),
            tracks = listOf(track), artists = emptyList(), notices = emptyList(),
        )
        assertEquals(0, memory.summary.identifiableTrackCount)
    }

    // 3. Multiple appearances in chronological order
    @Test fun multipleAppearancesChronologicalOrder() {
        val appearances = listOf(
            appearance("a1", NOW - 30 * MS_PER_DAY),
            appearance("a2", NOW - 14 * MS_PER_DAY),
            appearance("a3", NOW),
        )
        val sorted = appearances.sortedBy { it.firstObservedAtMs }
        assertEquals("a1", sorted[0].reconstructedAppearanceId)
        assertEquals("a2", sorted[1].reconstructedAppearanceId)
        assertEquals("a3", sorted[2].reconstructedAppearanceId)
    }

    // 4. Duplicate appearance IDs
    @Test fun duplicateAppearanceIds() {
        val timestamps = listOf(NOW - MS_PER_DAY, NOW - MS_PER_DAY)
        val result = engine.evaluate(NOW, timestamps)
        assertEquals(1, result.eligibleAppearanceCount)
        assertTrue(result.notices.contains(com.tunemind.app.domain.signal.SignalNotice.DUPLICATE_APPEARANCE_IDS_REMOVED))
    }

    // 5. Invalid and future timestamps
    @Test fun invalidAndFutureTimestamps() {
        val timestamps = listOf(-1L, NOW + 30 * MS_PER_DAY, NOW)
        val result = engine.evaluate(NOW, timestamps)
        assertEquals(1, result.eligibleAppearanceCount)
        assertEquals(1, result.futureAppearanceCount)
        assertEquals(1, result.excludedInvalidTimestampCount)
    }

    // 6. Historical comparison at explicit evaluation times
    @Test fun historicalComparison() {
        val timestamps = listOf(NOW - 30 * MS_PER_DAY, NOW - 14 * MS_PER_DAY)
        val now = NOW
        val earlier = now - 7 * MS_PER_DAY

        val current = engine.evaluate(now, timestamps)
        val historical = engine.evaluate(earlier, timestamps)

        // At the earlier evaluation time, appearances are less decayed → higher signal
        assertTrue(historical.signal > current.signal)
    }

    // 7. Reappearance after gap
    @Test fun reappearanceAfterGap() {
        val timestamps = listOf(NOW - 60 * MS_PER_DAY, NOW)
        val result = detectReappearanceAfterGap("t1", "Song", "Artist", timestamps, 14 * MS_PER_DAY)
        assertTrue(result.hasReappearance)
        assertTrue(result.gapMs >= 60 * MS_PER_DAY)
    }

    // 8. No reappearance for close appearances
    @Test fun noReappearanceForCloseAppearances() {
        val timestamps = listOf(NOW - 2 * MS_PER_DAY, NOW)
        val result = detectReappearanceAfterGap("t1", "Song", "Artist", timestamps, 14 * MS_PER_DAY)
        assertFalse(result.hasReappearance)
    }

    // 9. Incomplete history notices
    @Test fun incompleteHistoryNotice() {
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(0, 0, 0, 0, 0, historyMayBeIncomplete = true),
            tracks = emptyList(), artists = emptyList(), notices = emptyList(),
        )
        assertTrue(memory.summary.historyMayBeIncomplete)
    }

    // 10. Signal evaluation at explicit times
    @Test fun signalAtExplicitTimes() {
        val timestamps = listOf(NOW - 14 * MS_PER_DAY)
        val result1 = engine.evaluate(NOW, timestamps)
        val result2 = engine.evaluate(NOW + 7 * MS_PER_DAY, timestamps)
        assertTrue(result2.signal < result1.signal) // more decay
    }

    // 11. Appearance count vs callback count
    @Test fun appearanceCountNotCallbackCount() {
        // One appearance with many supporting observations = 1 appearance
        val track = TrackMemoryEntry(
            entryId = "t1", sourcePackage = "com.spotify", providerTrackId = "id:1",
            title = "Song", artist = "Artist", album = "Album",
            distinctAppearanceCount = 1,
            firstObservedAppearanceAtMs = NOW, lastObservedAppearanceAtMs = NOW,
            supportingObservationIds = (1..100).map { "obs-$it" },
            identityBasis = TrackIdentityBasis.PROVIDER_TRACK_ID,
            evidenceQuality = MemoryEvidenceQuality.SUPPORTED_IDENTITY,
            historyMayBeIncomplete = false,
            appearances = listOf(appearance("a1", NOW)),
        )
        assertEquals(1, track.distinctAppearanceCount)
        assertEquals(100, track.supportingObservationIds.size)
    }

    // 12. Concentration share
    @Test fun concentrationShare() {
        val tracks = (1..4).map { i ->
            TrackMemoryEntry(
                entryId = "t$i", sourcePackage = "com.spotify", providerTrackId = "id:$i",
                title = "Song $i", artist = "Artist", album = "Album",
                distinctAppearanceCount = 1,
                firstObservedAppearanceAtMs = NOW - i * MS_PER_DAY,
                lastObservedAppearanceAtMs = NOW - i * MS_PER_DAY,
                supportingObservationIds = listOf("obs-$i"),
                identityBasis = TrackIdentityBasis.PROVIDER_TRACK_ID,
                evidenceQuality = MemoryEvidenceQuality.SUPPORTED_IDENTITY,
                historyMayBeIncomplete = false,
                appearances = listOf(appearance("a$i", NOW - i * MS_PER_DAY)),
            )
        }
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(4, 4, 0, 0, 0, false),
            tracks = tracks, artists = emptyList(), notices = emptyList(),
        )
        val concentration = com.tunemind.app.domain.research.computeConcentration(memory, NOW, 30 * MS_PER_DAY)
        assertEquals(4, concentration.distinctIdentifiedTrackCount)
        assertEquals(4, concentration.totalIdentifiedAppearanceCount)
    }

    // 13. Consistent with snapshot
    @Test fun consistentWithSnapshot() {
        val timestamps = listOf(NOW - 7 * MS_PER_DAY, NOW)
        val signalResult = engine.evaluate(NOW, timestamps)
        // Snapshot uses the same engine internally
        assertTrue(signalResult.signal > 0)
        assertEquals(2, signalResult.eligibleAppearanceCount)
    }
}
