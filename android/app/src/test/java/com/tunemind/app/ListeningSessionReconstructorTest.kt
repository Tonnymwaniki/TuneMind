package com.tunemind.app

import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.BoundaryEvidence
import com.tunemind.app.domain.model.EpisodeStatus
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.domain.model.TrackAppearanceEvidence
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.reconstruction.ListeningSessionReconstructor
import org.junit.Assert.*
import org.junit.Test

class ListeningSessionReconstructorTest {
    private val engine = ListeningSessionReconstructor(maxActiveSpanMs = 120_000, gapBoundaryMs = 300_000)

    @Test fun playPauseResumeStopCountsOnlyObservedPlayingSpans() {
        val result = engine.reconstruct(listOf(state("a", 0, 3), state("b", 60_000, 2), state("c", 120_000, 3), state("d", 180_000, 1)))
        assertEquals(1, result.size)
        assertEquals(120_000L, result.single().estimatedActivePlaybackMs)
        assertEquals(EpisodeStatus.ENDED_BY_OBSERVED_STOP, result.single().status)
        assertEquals(BoundaryEvidence.OBSERVED_STOP, result.single().endBoundary)
    }

    @Test fun distinctProviderIdsBecomeCandidateAppearancesWithoutSkipClassification() {
        val result = engine.reconstruct(listOf(track("a", 0, "id-1", "Song A"), track("b", 10_000, "id-2", "Song B"), state("c", 20_000, 1)))
        val tracks = result.single().trackAppearances
        assertEquals(2, tracks.size)
        assertEquals(TrackIdentityBasis.PROVIDER_TRACK_ID, tracks[0].identityBasis)
        assertEquals(TrackAppearanceEvidence.CANDIDATE_CHANGE, tracks[1].evidenceStatus)
    }

    @Test fun sameProviderIdMetadataUpdateDoesNotCreateAppearance() {
        val result = engine.reconstruct(listOf(track("a", 0, "id", "Song"), track("b", 1_000, "id", "Song (remastered)"), state("c", 2_000, 1)))
        assertEquals(1, result.single().trackAppearances.size)
        assertEquals(listOf("a", "b"), result.single().trackAppearances.single().rawObservationIds)
    }

    @Test fun missingProviderIdCanContinueSameAppearanceOnlyWithMatchingMetadataFingerprint() {
        val result = engine.reconstruct(listOf(track("a", 0, "id", "Song"), obs("b", 1_000, 3, title = "Song", artist = "Artist"), state("c", 2_000, 1)))
        assertEquals(1, result.single().trackAppearances.size)
        assertEquals(TrackAppearanceEvidence.SAME_METADATA_FINGERPRINT, result.single().trackAppearances.single().evidenceStatus)
    }

    @Test fun incompleteMetadataIsPreservedAsInsufficientIdentity() {
        val result = engine.reconstruct(listOf(obs("a", 0, 3, title = "Unknown title"), state("b", 1_000, 1)))
        assertEquals(TrackIdentityBasis.INSUFFICIENT, result.single().trackAppearances.single().identityBasis)
        assertEquals(TrackAppearanceEvidence.IDENTITY_INSUFFICIENT, result.single().trackAppearances.single().evidenceStatus)
    }

    @Test fun metadataFingerprintIsWeakerAndAmbiguousChangeIsOnlyCandidate() {
        val result = engine.reconstruct(listOf(obs("a", 0, 3, title = "One", artist = "Artist"), obs("b", 1_000, 3, title = "Two", artist = "Artist"), state("c", 2_000, 1)))
        assertEquals(TrackIdentityBasis.METADATA_FINGERPRINT, result.single().trackAppearances[0].identityBasis)
        assertEquals(TrackAppearanceEvidence.CANDIDATE_CHANGE, result.single().trackAppearances[1].evidenceStatus)
    }

    @Test fun longGapIsCensoredAndNotCounted() {
        val result = engine.reconstruct(listOf(state("a", 0, 3), state("b", 400_000, 2)))
        assertEquals(EpisodeStatus.CLOSED_BY_GAP, result.single().status)
        assertEquals(BoundaryEvidence.GAP_CENSORED, result.single().endBoundary)
        assertNull(result.single().estimatedActivePlaybackMs)
        assertTrue(result.single().hasObservationGap)
    }

    @Test fun outOfOrderInputAndDuplicateRecordsProduceStableOutput() {
        val input = listOf(state("b", 10_000, 1), state("a", 0, 3), state("a", 0, 3))
        val first = engine.reconstruct(input)
        val second = engine.reconstruct(input.reversed())
        assertEquals(first, second)
        assertEquals(listOf("a", "b"), first.single().rawObservationIds)
    }

    @Test fun conflictingDuplicateIdIsFlagged() {
        val result = engine.reconstruct(listOf(state("a", 0, 3), state("a", 1, 2), state("b", 2, 1)))
        assertTrue(result.single().hasTimestampOrderingAmbiguity)
    }

    @Test fun observerSessionAndSourceAreSeparateScopes() {
        val rows = listOf(state("a", 0, 3, source = "music.one", observer = "obs1"), state("b", 10, 1, source = "music.one", observer = "obs1"), state("c", 20, 3, source = "music.two", observer = "obs1"), state("d", 30, 1, source = "music.two", observer = "obs1"), state("e", 40, 3, source = "music.one", observer = "obs2"))
        val result = engine.reconstruct(rows)
        assertEquals(3, result.size)
        assertEquals(setOf("music.one", "music.two"), result.map { it.sourcePackage }.toSet())
        assertEquals(setOf("obs1", "obs2"), result.map { it.observerSessionId }.toSet())
        assertEquals(BoundaryEvidence.INFERRED_OBSERVER_SESSION_BOUNDARY, result.single { it.observerSessionId == "obs2" }.startBoundary)
    }

    @Test fun unknownStateDoesNotStartAnEpisode() {
        assertTrue(engine.reconstruct(listOf(state("a", 0, null), state("b", 1, 2))).isEmpty())
    }

    @Test fun missingPlaybackStateBreaksAnActiveDurationSpan() {
        val episode = engine.reconstruct(listOf(state("a", 0, 3), state("b", 30_000, null), state("c", 60_000, 2))).single()
        assertNull(episode.estimatedActivePlaybackMs)
        assertTrue(episode.hasObservationGap)
    }

    @Test fun openPlayingIntervalHasNoExtrapolatedDuration() {
        val episode = engine.reconstruct(listOf(state("a", 10_000, 3))).single()
        assertNull(episode.estimatedActivePlaybackMs)
        assertEquals(EpisodeStatus.OPEN_NO_TERMINAL_STATE, episode.status)
        assertEquals(BoundaryEvidence.UNKNOWN, episode.endBoundary)
    }

    @Test fun cappedRetainedWindowMarksStartAndEndTruncated() {
        val episode = engine.reconstruct(listOf(state("a", 0, 3)), retainedWindowMayBeTruncated = true).single()
        assertEquals(BoundaryEvidence.RETENTION_WINDOW_TRUNCATED, episode.startBoundary)
        assertEquals(BoundaryEvidence.RETENTION_WINDOW_TRUNCATED, episode.endBoundary)
        assertEquals(EpisodeStatus.RETENTION_WINDOW_TRUNCATED, episode.status)
    }

    private fun state(id: String, time: Long, code: Int?, source: String = "music.app", observer: String = "observer") = obs(id, time, code, source = source, observer = observer, type = ObservationEventType.PLAYBACK_STATE_CHANGED)
    private fun track(id: String, time: Long, trackId: String, title: String) = obs(id, time, 3, trackId = trackId, title = title, artist = "Artist", type = ObservationEventType.METADATA_CHANGED)
    private fun obs(id: String, time: Long, code: Int?, source: String = "music.app", observer: String = "observer", type: ObservationEventType = ObservationEventType.PLAYBACK_STATE_CHANGED, trackId: String? = null, title: String? = null, artist: String? = null) = PlaybackObservation(eventId = id, observerSessionId = observer, eventType = type, eventTimeMs = time, sourcePackage = source, playbackStateCode = code, trackId = trackId, trackTitle = title, trackArtist = artist)
}
