package com.tunemind.app

import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.memory.ListeningMemoryBuilder
import com.tunemind.app.domain.model.BoundaryEvidence
import com.tunemind.app.domain.model.EpisodeStatus
import com.tunemind.app.domain.model.MemoryDataQualityNotice
import com.tunemind.app.domain.model.MemoryEvidenceQuality
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.ReconstructedPlaybackEpisode
import com.tunemind.app.domain.model.TrackAppearance
import com.tunemind.app.domain.model.TrackAppearanceEvidence
import com.tunemind.app.domain.model.TrackIdentityBasis
import org.junit.Assert.*
import org.junit.Test

class ListeningMemoryBuilderTest {
    private val builder = ListeningMemoryBuilder()

    @Test fun emptyInputsReturnEmptySummary() {
        val memory = builder.build(emptyList(), emptyList())
        assertEquals(0, memory.summary.identifiableTrackCount)
        assertEquals(0, memory.summary.distinctReconstructedAppearanceCount)
        assertTrue(memory.tracks.isEmpty()); assertTrue(memory.artists.isEmpty())
        assertTrue(memory.notices.contains(MemoryDataQualityNotice.NO_RAW_OBSERVATIONS))
    }

    @Test fun oneProviderTrackAppearanceIsIdentifiedWithObservedBounds() {
        val memory = build(listOf(provider("appearance-1", 1_000, "id-1", "Song", "Artist", "Album")))
        val track = memory.tracks.single()
        assertEquals(1, memory.summary.identifiableTrackCount)
        assertEquals(1, track.distinctAppearanceCount)
        assertEquals(1_000L, track.firstObservedAppearanceAtMs)
        assertEquals(2_000L, track.lastObservedAppearanceAtMs)
        assertEquals(TrackIdentityBasis.PROVIDER_TRACK_ID, track.identityBasis)
    }

    @Test fun multipleDistinctAppearancesMakeIdentityRecurring() {
        val memory = build(listOf(provider("appearance-1", 1_000, "id-1", "Song", "Artist", "Album"), provider("appearance-2", 5_000, "id-1", "Song", "Artist", "Album")))
        assertEquals(1, memory.tracks.size)
        assertEquals(2, memory.tracks.single().distinctAppearanceCount)
        assertEquals(1, memory.summary.recurringTrackIdentityCount)
    }

    @Test fun repeatedRawCallbacksWithinAppearanceDoNotInflateCount() {
        val appearance = provider("appearance-1", 1_000, "id-1", "Song", "Artist", "Album", listOf("raw-1", "raw-2", "raw-3"))
        val memory = builder.build(raw("raw-1", "raw-2", "raw-3"), listOf(episode(appearance)))
        assertEquals(1, memory.tracks.single().distinctAppearanceCount)
        assertEquals(1, memory.summary.distinctReconstructedAppearanceCount)
        assertEquals(3, memory.tracks.single().supportingObservationIds.size)
    }

    @Test fun duplicateReconstructionRecordWithSameAppearanceIdCountsOnce() {
        val appearance = provider("appearance-1", 1_000, "id-1", "Song", "Artist", "Album")
        val memory = builder.build(raw("raw-appearance-1"), listOf(episode(appearance), episode(appearance)))
        assertEquals(1, memory.summary.distinctReconstructedAppearanceCount)
        assertEquals(1, memory.tracks.single().distinctAppearanceCount)
    }

    @Test fun providerIdIsScopedToSourceApp() {
        val memory = build(listOf(
            provider("appearance-1", 1_000, "same-id", "Song", "Artist", "Album", source = "app.one"),
            provider("appearance-2", 2_000, "same-id", "Song", "Artist", "Album", source = "app.two"),
        ))
        assertEquals(2, memory.tracks.size)
        assertEquals(setOf("app.one", "app.two"), memory.tracks.map { it.sourcePackage }.toSet())
    }

    @Test fun sameTitleWithMissingAlbumIsNotMerged() {
        val memory = build(listOf(
            appearance("appearance-1", 1_000, title = "Same", artist = "Artist", album = null),
            appearance("appearance-2", 2_000, title = "Same", artist = "Artist", album = null),
        ))
        assertEquals(2, memory.tracks.size)
        assertEquals(0, memory.summary.identifiableTrackCount)
    }

    @Test fun metadataFallbackRequiresCompleteFingerprintAndNormalizesHarmlessFormatting() {
        val memory = build(listOf(
            appearance("appearance-1", 1_000, " Song ", "ARTIST", "Record"),
            appearance("appearance-2", 2_000, "song", "Artist", " record "),
            appearance("appearance-3", 3_000, "song", "Artist", "Other Record"),
        ))
        assertEquals(2, memory.tracks.size)
        assertEquals(TrackIdentityBasis.METADATA_FINGERPRINT, memory.tracks.first { it.distinctAppearanceCount == 2 }.identityBasis)
        assertEquals(1, memory.summary.recurringTrackIdentityCount)
    }

    @Test fun missingTitleArtistAlbumAndProviderIdRemainNullAndUnidentified() {
        val memory = build(listOf(appearance("appearance-1", 1_000, null, null, null)))
        val track = memory.tracks.single()
        assertNull(track.title); assertNull(track.artist); assertNull(track.album); assertNull(track.providerTrackId)
        assertEquals(TrackIdentityBasis.INSUFFICIENT, track.identityBasis)
        assertTrue(memory.notices.contains(MemoryDataQualityNotice.MISSING_TRACK_METADATA))
    }

    @Test fun conflictingMetadataForProviderIdentityIsNotChosenArbitrarily() {
        val memory = build(listOf(
            provider("appearance-1", 1_000, "id-1", "First title", "Artist", "Album"),
            provider("appearance-2", 2_000, "id-1", "Second title", "Artist", "Album"),
        ))
        assertEquals(1, memory.tracks.size)
        assertNull(memory.tracks.single().title)
        assertEquals(MemoryEvidenceQuality.CONFLICTING_METADATA, memory.tracks.single().evidenceQuality)
        assertEquals(0, memory.summary.recurringTrackIdentityCount)
        assertTrue(memory.notices.contains(MemoryDataQualityNotice.CONFLICTING_METADATA))
    }

    @Test fun artistSummaryCountsIdentifiedTracksAndAppearancesWithoutRawCallbackInflation() {
        val memory = build(listOf(
            provider("appearance-1", 1_000, "id-1", "Song A", "Artist", "Album A", listOf("raw-a", "raw-b")),
            provider("appearance-2", 2_000, "id-1", "Song A", "Artist", "Album A"),
            provider("appearance-3", 3_000, "id-2", "Song B", "Artist", "Album B"),
        ))
        val artist = memory.artists.single()
        assertEquals(2, artist.distinctIdentifiedTrackCount)
        assertEquals(3, artist.distinctReconstructedAppearanceCount)
    }

    @Test fun duplicateTimestampsAndReorderedInputsProduceEquivalentOutput() {
        val appearances = listOf(provider("appearance-a", 1_000, "id-1", "Song", "Artist", "Album"), provider("appearance-b", 1_000, "id-1", "Song", "Artist", "Album"))
        assertEquals(build(appearances), build(appearances.reversed()))
    }

    @Test fun derivedEntryIdIsStableForUnchangedIdentityEvidence() {
        val input = listOf(provider("appearance-1", 1_000, "id-1", "Song", "Artist", "Album"))
        assertEquals(build(input).tracks.single().entryId, build(input).tracks.single().entryId)
    }

    @Test fun missingSourcePreventsCrossAppProviderIdAssumption() {
        val memory = build(listOf(
            appearance("appearance-1", 1_000, "Song", "Artist", "Album", "shared-id", source = null),
            appearance("appearance-2", 2_000, "Song", "Artist", "Album", "shared-id", source = null),
        ))
        assertEquals(2, memory.tracks.size)
        assertEquals(0, memory.summary.identifiableTrackCount)
        assertTrue(memory.notices.contains(MemoryDataQualityNotice.MISSING_SOURCE_PACKAGE))
    }

    @Test fun cappedOrCensoredHistoryIsRepresentedAsIncomplete() {
        val a = provider("appearance-1", 1_000, "id-1", "Song", "Artist", "Album")
        val limited = episode(a).copy(status = EpisodeStatus.RETENTION_WINDOW_TRUNCATED, startBoundary = BoundaryEvidence.RETENTION_WINDOW_TRUNCATED)
        val memory = builder.build(raw("raw-appearance-1"), listOf(limited), retainedHistoryMayBeIncomplete = true)
        assertTrue(memory.summary.historyMayBeIncomplete)
        assertTrue(memory.tracks.single().historyMayBeIncomplete)
        assertTrue(memory.notices.contains(MemoryDataQualityNotice.RETENTION_LIMIT_REACHED))
    }

    @Test fun missingRawSupportingObservationIsExplicit() {
        val appearance = provider("appearance-1", 1_000, "id-1", "Song", "Artist", "Album", rawIds = listOf("raw-missing"))
        val memory = builder.build(emptyList(), listOf(episode(appearance)))
        assertEquals(MemoryEvidenceQuality.RAW_EVIDENCE_UNAVAILABLE, memory.tracks.single().evidenceQuality)
        assertTrue(memory.notices.contains(MemoryDataQualityNotice.RAW_EVIDENCE_UNAVAILABLE))
    }

    @Test fun memoryDoesNotInventPlaysOrExactTrackStartTimes() {
        val track = build(listOf(provider("appearance-1", 1_000, "id-1", "Song", "Artist", "Album"))).tracks.single()
        assertEquals(1_000L, track.firstObservedAppearanceAtMs)
        assertFalse(track::class.java.declaredFields.any { it.name.contains("playCount", ignoreCase = true) || it.name.contains("fatigue", ignoreCase = true) || it.name.contains("preference", ignoreCase = true) })
    }

    private fun build(appearances: List<TrackAppearance>) = builder.build(
        appearances.flatMap { appearance -> appearance.rawObservationIds.map { rawObservation(it, appearance.sourcePackage) } },
        appearances.map(::episode),
    )
    private fun episode(a: TrackAppearance) = ReconstructedPlaybackEpisode(
        episodeId = "episode-${a.appearanceId}", reconstructionVersion = 1, sourcePackage = a.sourcePackage,
        observerSessionId = "observer", firstObservedAtMs = a.firstObservedAtMs, lastObservedAtMs = a.lastObservedAtMs,
        estimatedActivePlaybackMs = null, startBoundary = BoundaryEvidence.OBSERVED_PLAYING, endBoundary = BoundaryEvidence.OBSERVED_STOP,
        status = EpisodeStatus.ENDED_BY_OBSERVED_STOP, rawObservationIds = a.rawObservationIds,
        trackAppearances = listOf(a), hasObservationGap = false, hasTimestampOrderingAmbiguity = false,
    )
    private fun provider(id: String, time: Long, trackId: String, title: String, artist: String, album: String, rawIds: List<String> = listOf("raw-$id"), source: String = "app.player") = appearance(id, time, title, artist, album, trackId, source, TrackIdentityBasis.PROVIDER_TRACK_ID, rawIds)
    private fun appearance(id: String, time: Long, title: String?, artist: String?, album: String?, trackId: String? = null, source: String? = "app.player", basis: TrackIdentityBasis = if (trackId == null) TrackIdentityBasis.METADATA_FINGERPRINT else TrackIdentityBasis.PROVIDER_TRACK_ID, rawIds: List<String> = listOf("raw-$id")) = TrackAppearance(
        appearanceId = id, sourcePackage = source, trackId = trackId, title = title, artist = artist, album = album,
        durationMs = null, firstObservedAtMs = time, lastObservedAtMs = time + 1_000, identityBasis = basis,
        evidenceStatus = TrackAppearanceEvidence.SNAPSHOT_ONLY, rawObservationIds = rawIds,
    )
    private fun raw(vararg ids: String) = ids.mapIndexed { index, id -> rawObservation(id, "app.player", index.toLong()) }
    private fun rawObservation(id: String, source: String?, time: Long = 0) = PlaybackObservation(
        eventId = id, observerSessionId = "observer", eventType = ObservationEventType.METADATA_CHANGED,
        eventTimeMs = time, sourcePackage = source,
    )
}
