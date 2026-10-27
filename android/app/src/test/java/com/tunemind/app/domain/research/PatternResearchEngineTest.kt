package com.tunemind.app.domain.research

import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class PatternResearchEngineTest {

    private val MS_PER_DAY = 86_400_000L

    private fun appearance(
        id: String, firstMs: Long,
    ): MemoryAppearanceEvidence = MemoryAppearanceEvidence(
        reconstructedAppearanceId = id,
        firstObservedAtMs = firstMs,
        lastObservedAtMs = firstMs,
        supportingObservationIds = listOf("obs-$id"),
        evidenceStatus = TrackAppearanceEvidence.SAME_PROVIDER_ID,
        hasTimestampOrderingAmbiguity = false,
        hasObservationGap = false,
    )

    private fun trackEntry(
        entryId: String,
        identityBasis: TrackIdentityBasis = TrackIdentityBasis.PROVIDER_TRACK_ID,
        evidenceQuality: MemoryEvidenceQuality = MemoryEvidenceQuality.SUPPORTED_IDENTITY,
        appearances: List<MemoryAppearanceEvidence> = emptyList(),
        title: String? = "Song",
        artist: String? = "Artist",
    ): TrackMemoryEntry = TrackMemoryEntry(
        entryId = entryId, sourcePackage = "com.spotify", providerTrackId = if (identityBasis == TrackIdentityBasis.PROVIDER_TRACK_ID) "id:1" else null,
        title = title, artist = artist, album = "Album",
        distinctAppearanceCount = appearances.size,
        firstObservedAppearanceAtMs = appearances.minOfOrNull { it.firstObservedAtMs },
        lastObservedAppearanceAtMs = appearances.maxOfOrNull { it.firstObservedAtMs },
        supportingObservationIds = appearances.flatMap { it.supportingObservationIds },
        identityBasis = identityBasis, evidenceQuality = evidenceQuality,
        historyMayBeIncomplete = false, appearances = appearances,
    )

    // ─── 1. Recurrence Acceleration ───────────────────────────────────────

    @Test fun accelerationEqualWindows() {
        val now = 1_000_000_000_000L
        // 3 appearances in earlier window, 4 in later (now is excluded by until)
        val timestamps = listOf(
            now - 12 * MS_PER_DAY, now - 11 * MS_PER_DAY, now - 10 * MS_PER_DAY, // earlier
            now - 5 * MS_PER_DAY, now - 3 * MS_PER_DAY, now - 2 * MS_PER_DAY, now - MS_PER_DAY, // later
        )
        val result = computeRecurrenceAcceleration(timestamps, now, 7 * MS_PER_DAY)
        assertEquals(3, result.earlierWindow.distinctAppearanceCount)
        assertEquals(4, result.laterWindow.distinctAppearanceCount)
        assertEquals(1, result.absoluteChange)
    }

    @Test fun accelerationDuplicateIdsRemoved() {
        val now = 1_000_000_000_000L
        val timestamps = listOf(now - 10 * MS_PER_DAY, now - 10 * MS_PER_DAY, now)
        val result = computeRecurrenceAcceleration(timestamps, now, 7 * MS_PER_DAY)
        assertTrue(result.notices.any { it.contains("Duplicate") })
    }

    @Test fun accelerationInvalidTimestampsExcluded() {
        val now = 1_000_000_000_000L
        val timestamps = listOf(-1L, 0L, now)
        val result = computeRecurrenceAcceleration(timestamps, now, 7 * MS_PER_DAY)
        assertTrue(result.notices.any { it.contains("excluded") })
    }

    @Test fun accelerationRejectsInvalidWindow() {
        assertThrows(IllegalArgumentException::class.java) {
            computeRecurrenceAcceleration(emptyList(), 1_000L, 0L)
        }
    }

    // ─── 2. Reappearance After Absence ────────────────────────────────────

    @Test fun reappearanceDetected() {
        val now = 1_000_000_000_000L
        val result = detectReappearanceAfterGap(
            "track-1", "Song", "Artist",
            listOf(now - 30 * MS_PER_DAY, now),
            minGapMs = 14 * MS_PER_DAY,
        )
        assertTrue(result.hasReappearance)
        assertTrue(result.gapMs >= 30 * MS_PER_DAY)
    }

    @Test fun reappearanceNotDetectedForCloseAppearances() {
        val now = 1_000_000_000_000L
        val result = detectReappearanceAfterGap(
            "track-1", "Song", "Artist",
            listOf(now - 2 * MS_PER_DAY, now),
            minGapMs = 14 * MS_PER_DAY,
        )
        assertFalse(result.hasReappearance)
    }

    @Test fun reappearanceSingleAppearanceReturnsNoReappearance() {
        val now = 1_000_000_000_000L
        val result = detectReappearanceAfterGap(
            "track-1", "Song", "Artist", listOf(now),
            minGapMs = 7 * MS_PER_DAY,
        )
        assertFalse(result.hasReappearance)
    }

    @Test fun reappearanceRejectsInvalidMinGap() {
        assertThrows(IllegalArgumentException::class.java) {
            detectReappearanceAfterGap("t", null, null, emptyList(), 0L)
        }
    }

    @Test fun reappearanceInvalidTimestampsExcluded() {
        val now = 1_000_000_000_000L
        val result = detectReappearanceAfterGap(
            "track-1", null, null,
            listOf(-1L, now - 20 * MS_PER_DAY, now),
            minGapMs = 7 * MS_PER_DAY,
        )
        assertTrue(result.hasReappearance)
        assertTrue(result.notices.any { it.contains("Invalid") })
    }

    // ─── 3. Listening Concentration ───────────────────────────────────────

    @Test fun concentrationSingleTrackFullShare() {
        val now = 1_000_000_000_000L
        val track = trackEntry("t1", appearances = listOf(appearance("a1", now)))
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(1, 1, 0, 0, 0, false),
            tracks = listOf(track), artists = emptyList(), notices = emptyList(),
        )
        val result = computeConcentration(memory, now, 7 * MS_PER_DAY)
        assertEquals(1, result.totalIdentifiedAppearanceCount)
        assertEquals(1, result.distinctIdentifiedTrackCount)
        assertEquals(1.0, result.tracks[0].share, 1e-9)
        assertNull(result.normalizedConcentration)
    }

    @Test fun concentrationMultipleTracks() {
        val now = 1_000_000_000_000L
        val track1 = trackEntry("t1", appearances = listOf(appearance("a1", now)))
        val track2 = trackEntry("t2", appearances = listOf(appearance("a2", now - MS_PER_DAY)))
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(2, 2, 0, 0, 0, false),
            tracks = listOf(track1, track2), artists = emptyList(), notices = emptyList(),
        )
        val result = computeConcentration(memory, now, 7 * MS_PER_DAY)
        assertEquals(2, result.totalIdentifiedAppearanceCount)
        assertEquals(2, result.distinctIdentifiedTrackCount)
        assertEquals(0.5, result.tracks[0].share, 1e-9)
        assertNotNull(result.normalizedConcentration)
        assertEquals(0.5, result.normalizedConcentration!!, 1e-9)
    }

    @Test fun concentrationExcludesUnidentifiedTracks() {
        val now = 1_000_000_000_000L
        val identified = trackEntry("t1", appearances = listOf(appearance("a1", now)))
        val unidentified = trackEntry("t2", identityBasis = TrackIdentityBasis.INSUFFICIENT, appearances = listOf(appearance("a2", now)))
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(1, 1, 0, 0, 1, false),
            tracks = listOf(identified, unidentified), artists = emptyList(), notices = emptyList(),
        )
        val result = computeConcentration(memory, now, 7 * MS_PER_DAY)
        assertEquals(1, result.totalIdentifiedAppearanceCount)
        assertEquals(1, result.distinctIdentifiedTrackCount)
        assertTrue(result.notices.any { it.contains("Unidentified") })
    }

    @Test fun concentrationRejectsInvalidWindow() {
        assertThrows(IllegalArgumentException::class.java) {
            val memory = ListeningMemoryBuilder().build(emptyList(), emptyList())
            computeConcentration(memory, 1_000L, 0L)
        }
    }

    // ─── 4. Evidence Quality ──────────────────────────────────────────────

    @Test fun evidenceQualityEmptyMemory() {
        val memory = ListeningMemoryBuilder().build(emptyList(), emptyList())
        val result = assessEvidenceQuality(memory)
        assertEquals(0, result.totalTracksInMemory)
        assertEquals(0, result.identifiedTrackCount)
    }

    @Test fun evidenceQualityIdentifiesCategories() {
        val t1 = trackEntry("t1", identityBasis = TrackIdentityBasis.PROVIDER_TRACK_ID)
        val t2 = trackEntry("t2", identityBasis = TrackIdentityBasis.INSUFFICIENT)
        val t3 = trackEntry("t3", evidenceQuality = MemoryEvidenceQuality.CONFLICTING_METADATA)
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(2, 2, 0, 0, 1, true),
            tracks = listOf(t1, t2, t3), artists = emptyList(), notices = emptyList(),
        )
        val result = assessEvidenceQuality(memory)
        assertEquals(3, result.totalTracksInMemory)
        assertEquals(2, result.identifiedTrackCount)
        assertEquals(1, result.insufficientIdentityCount)
        assertEquals(1, result.conflictingMetadataCount)
        assertTrue(result.historyMayBeIncomplete)
    }

    // ─── Full evaluator ───────────────────────────────────────────────────

    @Test fun evaluatorWithEmptyMemory() {
        val memory = ListeningMemoryBuilder().build(emptyList(), emptyList())
        val summary = PatternResearchEvaluator.evaluate(memory)
        assertEquals(0, summary.concentration.totalIdentifiedAppearanceCount)
        assertTrue(summary.reappearances.isEmpty())
    }

    @Test fun evaluatorWithTracks() {
        val now = 1_000_000_000_000L
        val track = trackEntry("t1", appearances = listOf(appearance("a1", now - 20 * MS_PER_DAY), appearance("a2", now)))
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(1, 2, 1, 0, 0, false),
            tracks = listOf(track), artists = emptyList(), notices = emptyList(),
        )
        val summary = PatternResearchEvaluator.evaluate(memory, evaluationTimeMs = now)
        assertEquals(1, summary.reappearances.size)
        assertTrue(summary.reappearances[0].hasReappearance)
    }

    @Test fun sourceMemoryUnmodified() {
        val now = 1_000_000_000_000L
        val track = trackEntry("t1", appearances = listOf(appearance("a1", now)))
        val memory = ListeningMemory(
            summary = ListeningMemorySummary(1, 1, 0, 0, 0, false),
            tracks = listOf(track), artists = emptyList(), notices = emptyList(),
        )
        val originalId = memory.tracks[0].entryId
        PatternResearchEvaluator.evaluate(memory, evaluationTimeMs = now)
        assertEquals(originalId, memory.tracks[0].entryId)
    }
}
