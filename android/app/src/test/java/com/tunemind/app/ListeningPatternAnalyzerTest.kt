package com.tunemind.app

import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.AppearanceGapStatus
import com.tunemind.app.domain.model.BoundaryEvidence
import com.tunemind.app.domain.model.ComparisonStatus
import com.tunemind.app.domain.model.DailyDurationStatus
import com.tunemind.app.domain.model.EpisodeStatus
import com.tunemind.app.domain.model.ListeningMemory
import com.tunemind.app.domain.model.ListeningMemorySummary
import com.tunemind.app.domain.model.MemoryAppearanceEvidence
import com.tunemind.app.domain.model.MemoryEvidenceQuality
import com.tunemind.app.domain.model.PatternDataNotice
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.ReconstructedPlaybackEpisode
import com.tunemind.app.domain.model.TrackAppearanceEvidence
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.patterns.ListeningPatternAnalyzer
import com.tunemind.app.domain.reconstruction.ListeningSessionReconstructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ListeningPatternAnalyzerTest {
    private val analyzer = ListeningPatternAnalyzer()
    private val utc = ZoneId.of("UTC")

    @Test fun emptyInputsHaveNoWindowAndExplicitEmptyStatus() {
        val result = analyze(emptyList())
        assertNull(result.coverage.windowStartMs)
        assertNull(result.coverage.windowEndMs)
        assertEquals(0, result.coverage.rawObservationCount)
        assertTrue(result.routineByDay.isEmpty())
        assertTrue(result.trackPatterns.isEmpty())
        assertTrue(result.notices.contains(PatternDataNotice.NO_RAW_OBSERVATIONS))
    }

    @Test fun oneObservationAndAppearanceAreReportedWithoutRecurrenceClaim() {
        val result = analyze(listOf(trackState("one", 1_000, "id-1", source = "player")))
        assertEquals(1, result.coverage.rawObservationCount)
        assertEquals(1, result.coverage.usableReconstructedAppearanceCount)
        assertEquals(1, result.trackPatterns.single().distinctAppearanceCount)
        assertFalse(result.trackPatterns.single().recurrenceEligible)
        assertTrue(result.trackPatterns.single().intervals.isEmpty())
    }

    @Test fun localDateAggregationSplitsEventsAcrossMidnight() {
        val zone = ZoneId.of("Africa/Nairobi")
        val midnight = LocalDate.of(2025, 4, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val result = analyze(listOf(raw("before", midnight - 1), raw("after", midnight)), zone = zone)
        assertEquals(listOf(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 2)), result.routineByDay.map { it.date })
        assertEquals(listOf(1, 1), result.routineByDay.map { it.observedMediaSessionEventCount })
    }

    @Test fun explicitlySuppliedTimezoneControlsDateAndHourBuckets() {
        val instant = Instant.parse("2025-01-01T00:30:00Z").toEpochMilli()
        val result = analyze(listOf(raw("event", instant)), zone = ZoneId.of("America/Los_Angeles"))
        assertEquals("America/Los_Angeles", result.coverage.timeZoneId)
        assertEquals(LocalDate.of(2024, 12, 31), result.routineByDay.single().date)
        assertEquals(16, result.routineByHour.single().localHour)
        assertEquals(LocalDate.of(2025, 1, 1), analyze(listOf(raw("event", instant)), zone = utc).routineByDay.single().date)
    }

    @Test fun springForwardSkipsMissingLocalHour() {
        val zone = ZoneId.of("America/New_York")
        val oneThirty = ZonedDateTime.of(2025, 3, 9, 1, 30, 0, 0, zone).toInstant().toEpochMilli()
        val threeThirty = ZonedDateTime.of(2025, 3, 9, 3, 30, 0, 0, zone).toInstant().toEpochMilli()
        val result = analyze(listOf(raw("one", oneThirty), raw("three", threeThirty)), zone = zone)
        assertEquals(listOf(1, 3), result.routineByHour.map { it.localHour })
    }

    @Test fun repeatedFallBackHourIsAggregatedIntoOneLocalHourBucket() {
        val zone = ZoneId.of("America/New_York")
        val local = java.time.LocalDateTime.of(2025, 11, 2, 1, 30)
        val first = ZonedDateTime.ofLocal(local, zone, ZoneOffset.ofHours(-4)).toInstant().toEpochMilli()
        val second = ZonedDateTime.ofLocal(local, zone, ZoneOffset.ofHours(-5)).toInstant().toEpochMilli()
        val result = analyze(listOf(raw("first", first), raw("second", second)), zone = zone)
        assertEquals(1, result.routineByHour.size)
        assertEquals(1, result.routineByHour.single().localHour)
        assertEquals(2, result.routineByHour.single().observedMediaSessionEventCount)
    }

    @Test fun distinctReconstructedTrackAppearancesCreateObservedGap() {
        val result = analyze(listOf(
            trackState("play-a", 1_000, "provider-1"), state("stop-a", 11_000, 1),
            trackState("play-b", 61_000, "provider-1"), state("stop-b", 71_000, 1),
        ))
        val pattern = result.trackPatterns.single()
        assertEquals(2, pattern.distinctAppearanceCount)
        assertTrue(pattern.recurrenceEligible)
        assertEquals(60_000L, pattern.intervals.single().gapMs)
        assertEquals(AppearanceGapStatus.VALID_OBSERVED_GAP, pattern.intervals.single().status)
    }

    @Test fun duplicateRawIdsAndRepeatedAppearanceIdsDoNotInflateRecurrence() {
        val observations = listOf(trackState("play-a", 1_000, "id-1"), state("stop-a", 11_000, 1))
        val episode = ListeningSessionReconstructor().reconstruct(observations).single()
        val memory = ListeningMemoryBuilder().build(observations, listOf(episode, episode))
        val result = analyzer.analyze(observations + observations.first(), listOf(episode, episode), memory, utc)
        assertEquals(observations.size, result.coverage.rawObservationCount)
        assertEquals(1, result.trackPatterns.single().distinctAppearanceCount)
        assertTrue(result.trackPatterns.single().recurrenceEligible)
        assertTrue(result.trackPatterns.single().intervals.isEmpty())
        assertFalse(result.notices.contains(PatternDataNotice.AMBIGUOUS_RECONSTRUCTION))
    }

    @Test fun sameProviderIdFromDifferentAppsRemainsTwoIdentityPatterns() {
        val result = analyze(listOf(
            trackState("a-play", 1_000, "shared", source = "app.one"), state("a-stop", 2_000, 1, source = "app.one"),
            trackState("b-play", 3_000, "shared", source = "app.two"), state("b-stop", 4_000, 1, source = "app.two"),
        ))
        assertEquals(2, result.trackPatterns.size)
        assertEquals(setOf("app.one", "app.two"), result.trackPatterns.map { it.sourcePackage }.toSet())
    }

    @Test fun unidentifiedAndAmbiguousAppearancesAreExcludedFromRecurrence() {
        val unidentified = memoryTrack("unknown", TrackIdentityBasis.INSUFFICIENT, MemoryEvidenceQuality.INSUFFICIENT_IDENTITY, listOf(evidence("a", 1_000), evidence("b", 2_000)))
        val ambiguous = memoryTrack("ambiguous", TrackIdentityBasis.PROVIDER_TRACK_ID, MemoryEvidenceQuality.AMBIGUOUS_RECONSTRUCTION, listOf(evidence("c", 3_000), evidence("d", 4_000)))
        val result = analyze(listOf(raw("r1", 1_000), raw("r2", 2_000)), suppliedMemory = memory(unidentified, ambiguous))
        assertTrue(result.trackPatterns.none { it.recurrenceEligible })
        assertTrue(result.notices.contains(PatternDataNotice.INSUFFICIENT_TRACK_IDENTITY))
    }

    @Test fun conflictingIdentityEvidenceIsPreservedButCannotProduceRecurrence() {
        val conflict = memoryTrack("conflict", TrackIdentityBasis.PROVIDER_TRACK_ID, MemoryEvidenceQuality.CONFLICTING_METADATA, listOf(evidence("a", 1_000), evidence("b", 2_000)))
        val result = analyze(listOf(raw("raw-a", 1_000), raw("raw-b", 2_000)), suppliedMemory = memory(conflict))
        assertEquals(2, result.trackPatterns.single().distinctAppearanceCount)
        assertFalse(result.trackPatterns.single().recurrenceEligible)
        assertTrue(result.trackPatterns.single().intervals.isEmpty())
    }

    @Test fun missingMetadataRemainsNullableAndIsNotInvented() {
        val incomplete = memoryTrack("memory", TrackIdentityBasis.INSUFFICIENT, MemoryEvidenceQuality.INSUFFICIENT_IDENTITY, listOf(evidence("a", 1_000))).copy(title = null, artist = null, album = null, providerTrackId = null)
        val result = analyze(listOf(raw("raw-a", 1_000)), suppliedMemory = memory(incomplete))
        val pattern = result.trackPatterns.single()
        assertNull(pattern.title)
        assertNull(pattern.artist)
        assertNull(pattern.album)
        assertNull(pattern.providerTrackId)
        assertTrue(result.notices.contains(PatternDataNotice.MISSING_TRACK_METADATA))
    }

    @Test fun unknownSourceIsExposedAndCannotSupportCrossAppIdentity() {
        val unknownSource = memoryTrack("unknown-source", TrackIdentityBasis.INSUFFICIENT, MemoryEvidenceQuality.INSUFFICIENT_IDENTITY, listOf(evidence("a", 1_000)))
            .copy(sourcePackage = null, providerTrackId = null)
        val result = analyze(listOf(raw("raw-a", 1_000)), suppliedMemory = memory(unknownSource))
        assertNull(result.trackPatterns.single().sourcePackage)
        assertTrue(result.notices.contains(PatternDataNotice.MISSING_SOURCE_PACKAGE))
    }

    @Test fun negativeObservationAndAppearanceTimestampsAreExcluded() {
        val invalid = raw("negative", -1)
        val track = memoryTrack("memory", TrackIdentityBasis.PROVIDER_TRACK_ID, MemoryEvidenceQuality.SUPPORTED_IDENTITY, listOf(evidence("a", -1), evidence("b", 2_000)))
        val result = analyze(listOf(invalid, raw("valid", 2_000)), suppliedMemory = memory(track))
        assertEquals(1, result.routineByDay.sumOf { it.observedMediaSessionEventCount })
        assertEquals(2_000L, result.trackPatterns.single().firstObservedAppearanceAtMs)
        assertTrue(result.notices.contains(PatternDataNotice.INVALID_OBSERVATION_TIMESTAMP))
        assertTrue(result.notices.contains(PatternDataNotice.UNORDERABLE_APPEARANCE_TIMES))
    }

    @Test fun duplicateAppearanceTimestampsHaveExplicitZeroGapStatus() {
        val track = memoryTrack("memory", TrackIdentityBasis.PROVIDER_TRACK_ID, MemoryEvidenceQuality.SUPPORTED_IDENTITY, listOf(evidence("a", 1_000), evidence("b", 1_000)))
        val result = analyze(listOf(raw("ra", 1_000), raw("rb", 1_000)), suppliedMemory = memory(track))
        assertNull(result.trackPatterns.single().intervals.single().gapMs)
        assertEquals(AppearanceGapStatus.IDENTICAL_TIMESTAMPS, result.trackPatterns.single().intervals.single().status)
    }

    @Test fun veryLargePositiveGapIsCalculatedWithoutOverflow() {
        val track = memoryTrack("memory", TrackIdentityBasis.PROVIDER_TRACK_ID, MemoryEvidenceQuality.SUPPORTED_IDENTITY, listOf(evidence("a", 0), evidence("b", Long.MAX_VALUE)))
        val result = analyze(listOf(raw("ra", 0), raw("rb", Long.MAX_VALUE)), suppliedMemory = memory(track))
        assertEquals(Long.MAX_VALUE, result.trackPatterns.single().intervals.single().gapMs)
        assertEquals(AppearanceGapStatus.VALID_OBSERVED_GAP, result.trackPatterns.single().intervals.single().status)
    }

    @Test fun invalidUnboundedAndOpenDurationsAreUnavailable() {
        val result = analyze(listOf(trackState("playing", 1_000, "id-1")))
        assertEquals(DailyDurationStatus.UNAVAILABLE, result.routineByDay.single().durationStatus)
        assertNull(result.routineByDay.single().estimatedActivePlaybackMs)
        assertTrue(result.notices.contains(PatternDataNotice.INVALID_OR_UNBOUNDED_DURATION))
    }

    @Test fun pauseBoundsKnownActiveDurationAndPausedTimeIsNotAdded() {
        val result = analyze(listOf(trackState("play", 1_000, "id-1"), state("pause", 31_000, 2), state("still-paused", 91_000, 2)))
        assertEquals(30_000L, result.routineByDay.single().estimatedActivePlaybackMs)
        assertEquals(DailyDurationStatus.PARTIAL_ESTIMATE, result.routineByDay.single().durationStatus)
    }

    @Test fun overlappingSourceEpisodeBoundsDoNotDoubleCountDuration() {
        val rows = listOf(
            trackState("one-play", 1_000, "id-1", source = "app.one"), state("one-pause", 31_000, 2, source = "app.one"),
            trackState("two-play", 1_000, "id-2", source = "app.two"), state("two-pause", 31_000, 2, source = "app.two"),
        )
        val result = analyze(rows)
        assertNull(result.routineByDay.single().estimatedActivePlaybackMs)
        assertEquals(DailyDurationStatus.UNAVAILABLE, result.routineByDay.single().durationStatus)
        assertTrue(result.notices.contains(PatternDataNotice.OVERLAPPING_EPISODE_BOUNDS))
    }

    @Test fun crossDayEpisodeDurationIsNotAssignedToOneDate() {
        val start = LocalDate.of(2025, 2, 2).atStartOfDay(utc).toInstant().toEpochMilli()
        val end = start + 86_400_000L + 10_000
        val episode = episode("cross", start, end, duration = 10_000)
        val result = analyze(listOf(raw("raw-cross", start), raw("raw-cross-end", end)), episodes = listOf(episode))
        assertTrue(result.routineByDay.all { it.estimatedActivePlaybackMs == null })
        assertTrue(result.notices.contains(PatternDataNotice.CROSS_DAY_DURATION_NOT_APPORTIONED))
    }

    @Test fun durationSumOverflowIsUnavailableInsteadOfThrowing() {
        val first = episode("a", 1_000, 1_000, Long.MAX_VALUE)
        val second = episode("b", 2_000, 2_000, 1)
        val result = analyze(listOf(raw("raw-a", 1_000), raw("raw-b", 2_000)), episodes = listOf(first, second))
        assertNull(result.routineByDay.single().estimatedActivePlaybackMs)
        assertTrue(result.notices.contains(PatternDataNotice.INVALID_OR_UNBOUNDED_DURATION))
    }

    @Test fun negativeDurationEstimateIsExcluded() {
        val invalid = episode("negative-duration", 1_000, 1_000, -1)
        val result = analyze(listOf(raw("raw-negative-duration", 1_000)), episodes = listOf(invalid))
        assertNull(result.routineByDay.single().estimatedActivePlaybackMs)
        assertEquals(DailyDurationStatus.UNAVAILABLE, result.routineByDay.single().durationStatus)
        assertTrue(result.notices.contains(PatternDataNotice.INVALID_OR_UNBOUNDED_DURATION))
    }

    @Test fun incompleteRetainedHistoryIsExplicit() {
        val result = analyze(listOf(raw("a", 1_000)), retained = true)
        assertEquals(com.tunemind.app.domain.model.PatternHistoryStatus.RETENTION_LIMIT_REACHED, result.coverage.historyStatus)
        assertTrue(result.notices.contains(PatternDataNotice.RETENTION_LIMIT_REACHED))
    }

    @Test fun periodComparisonIsWithheldEvenWhenSeveralDatesExistBecauseCoverageIsUnknown() {
        val start = LocalDate.of(2025, 1, 1).atStartOfDay(utc).toInstant().toEpochMilli()
        val result = analyze((0..2).map { raw("day-$it", start + it * 86_400_000L) })
        assertEquals(ComparisonStatus.COVERAGE_UNKNOWN, result.comparisonAvailability.status)
        assertTrue(result.notices.contains(PatternDataNotice.COMPARISONS_UNAVAILABLE))
    }

    @Test fun insufficientDatesReturnInsufficientHistoryInsteadOfAZeroComparison() {
        val result = analyze(listOf(raw("one", 1_000)))
        assertEquals(ComparisonStatus.INSUFFICIENT_HISTORY, result.comparisonAvailability.status)
    }

    @Test fun reorderingEquivalentInputsProducesIdenticalAnalysisAndStableIds() {
        val rows = listOf(
            trackState("a-play", 1_000, "id-1"), state("a-stop", 2_000, 1),
            trackState("b-play", 61_000, "id-1"), state("b-stop", 62_000, 1),
        )
        assertEquals(analyze(rows), analyze(rows.reversed()))
    }

    @Test fun metricsDoNotRepresentPreferencesSkipsAttentionOrCompletePlays() {
        val result = analyze(listOf(trackState("one", 1_000, "id-1")))
        assertEquals(1, result.trackPatterns.single().distinctAppearanceCount)
        assertFalse(result.trackPatterns.single().recurrenceEligible)
        assertFalse(result::class.java.declaredFields.any { field ->
            field.name.contains("favorite", true) || field.name.contains("fatigue", true) || field.name.contains("mood", true)
        })
    }

    private fun analyze(
        observations: List<PlaybackObservation>,
        zone: ZoneId = utc,
        episodes: List<ReconstructedPlaybackEpisode>? = null,
        retained: Boolean = false,
        suppliedMemory: ListeningMemory? = null,
    ) : com.tunemind.app.domain.model.ListeningPatternAnalysis {
        val reconstructed = episodes ?: ListeningSessionReconstructor().reconstruct(observations, retainedWindowMayBeTruncated = retained)
        val memory = suppliedMemory ?: ListeningMemoryBuilder().build(observations, reconstructed, retainedHistoryMayBeIncomplete = retained)
        return analyzer.analyze(observations, reconstructed, memory, zone, retained)
    }

    private fun raw(id: String, time: Long) = PlaybackObservation(
        eventId = id, observerSessionId = "observer", eventType = ObservationEventType.METADATA_CHANGED,
        eventTimeMs = time, sourcePackage = "player",
    )

    private fun state(id: String, time: Long, state: Int, source: String = "player") = PlaybackObservation(
        eventId = id, observerSessionId = "observer", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
        eventTimeMs = time, sourcePackage = source, playbackStateCode = state,
    )

    private fun trackState(id: String, time: Long, trackId: String, source: String = "player") = PlaybackObservation(
        eventId = id, observerSessionId = "observer", eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
        eventTimeMs = time, sourcePackage = source, trackTitle = "Track $trackId", trackArtist = "Artist",
        trackAlbum = "Album", trackId = trackId, playbackStateCode = 3,
    )

    private fun episode(id: String, first: Long, last: Long, duration: Long) = ReconstructedPlaybackEpisode(
        episodeId = id, reconstructionVersion = 1, sourcePackage = "player", observerSessionId = "observer",
        firstObservedAtMs = first, lastObservedAtMs = last, estimatedActivePlaybackMs = duration,
        startBoundary = BoundaryEvidence.OBSERVED_PLAYING, endBoundary = BoundaryEvidence.OBSERVED_STOP,
        status = EpisodeStatus.ENDED_BY_OBSERVED_STOP, rawObservationIds = listOf("raw-$id"), trackAppearances = emptyList(),
        hasObservationGap = false, hasTimestampOrderingAmbiguity = false,
    )

    private fun evidence(id: String, time: Long) = MemoryAppearanceEvidence(
        reconstructedAppearanceId = id, firstObservedAtMs = time, lastObservedAtMs = time,
        supportingObservationIds = listOf("raw-$id"), evidenceStatus = TrackAppearanceEvidence.SNAPSHOT_ONLY,
        hasTimestampOrderingAmbiguity = false, hasObservationGap = false,
    )

    private fun memoryTrack(
        id: String,
        basis: TrackIdentityBasis,
        quality: MemoryEvidenceQuality,
        appearances: List<MemoryAppearanceEvidence>,
    ) = TrackMemoryEntry(
        entryId = id, sourcePackage = "player", providerTrackId = if (basis == TrackIdentityBasis.PROVIDER_TRACK_ID) "track-id" else null,
        title = "Song", artist = "Artist", album = "Album", distinctAppearanceCount = appearances.size,
        firstObservedAppearanceAtMs = appearances.minOfOrNull { it.firstObservedAtMs },
        lastObservedAppearanceAtMs = appearances.maxOfOrNull { it.lastObservedAtMs },
        supportingObservationIds = appearances.flatMap { it.supportingObservationIds }, identityBasis = basis,
        evidenceQuality = quality, historyMayBeIncomplete = true, appearances = appearances,
    )

    private fun memory(vararg tracks: TrackMemoryEntry) = ListeningMemory(
        summary = ListeningMemorySummary(tracks.size, tracks.sumOf { it.distinctAppearanceCount }, 0, 0, 0, true),
        tracks = tracks.toList(), artists = emptyList(), notices = emptyList(),
    )
}
