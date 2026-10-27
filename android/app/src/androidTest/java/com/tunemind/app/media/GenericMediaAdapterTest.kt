package com.tunemind.app.media

import android.media.MediaMetadata
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.media.adapters.GenericMediaAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenericMediaAdapterTest {
    private val adapter = GenericMediaAdapter()

    @Test
    fun mapsRawStateMetadataAndUnavailableFieldsWithoutInference() {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "provider:track:123")
            .putString(MediaMetadata.METADATA_KEY_TITLE, "  Song title  ")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 180_000L)
            .build()
        val state = PlaybackState.Builder()
            .setState(PlaybackState.STATE_BUFFERING, 12_345L, 1.25f, 10_000L)
            .build()

        val event = adapter.extractObservation(
            eventId = "event-id",
            eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
            metadata = metadata,
            playbackState = state,
            observerSessionId = "session-id",
            sourcePackage = "com.example.player",
            eventTimeMs = 20_000L,
        )

        assertEquals("event-id", event.eventId)
        assertEquals("session-id", event.observerSessionId)
        assertEquals(1, event.schemaVersion)
        assertEquals("Song title", event.trackTitle)
        assertEquals("Artist", event.trackArtist)
        assertEquals("provider:track:123", event.trackId)
        assertEquals(180_000L, event.durationMs)
        assertEquals("com.example.player", event.sourcePackage)
        assertEquals(PlaybackState.STATE_BUFFERING, event.playbackStateCode)
        assertEquals(12_345L, event.positionMs)
        assertEquals(10_000L, event.positionUpdateElapsedRealtimeMs)
        assertEquals(1.25f, event.playbackSpeed)
        assertEquals(20_000L, event.eventTimeMs)
        assertNull(event.savedAtMs)
        assertNull(event.trackAlbum)
    }

    @Test
    fun absentMetadataAndPlaybackValuesRemainNull() {
        val event = adapter.extractObservation(
            eventId = "event-id",
            eventType = ObservationEventType.SESSION_CREATED,
            metadata = null,
            playbackState = null,
            observerSessionId = "session-id",
            sourcePackage = null,
            eventTimeMs = 1L,
        )
        assertNull(event.trackTitle)
        assertNull(event.trackArtist)
        assertNull(event.trackId)
        assertNull(event.durationMs)
        assertNull(event.positionMs)
        assertNull(event.playbackStateCode)
        assertNull(event.playbackSpeed)
        assertNull(event.positionUpdateElapsedRealtimeMs)
        assertNull(event.sourcePackage)
    }

    @Test
    fun unknownPositionStaysNullButRawPlaybackCodeIsPreserved() {
        val state = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
            .build()
        val event = adapter.extractObservation(
            eventId = "event-id",
            eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
            metadata = null,
            playbackState = state,
            observerSessionId = "session-id",
            sourcePackage = "com.example.player",
            eventTimeMs = 1L,
        )
        assertEquals(PlaybackState.STATE_PAUSED, event.playbackStateCode)
        assertNull(event.positionMs)
    }

    @Test
    fun adStateRemainsSuppressedAcrossMissingMetadataUntilExplicitNonAdSignal() {
        val gate = AdvertisementFilterState()
        val ad = MediaMetadata.Builder()
            .putLong("android.media.metadata.ADVERTISEMENT", 1L)
            .build()
        val missing = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Some title")
            .build()
        val ordinaryNamedAd = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Advertisement")
            .build()
        val explicitlyNonAd = MediaMetadata.Builder()
            .putLong("android.media.metadata.ADVERTISEMENT", 0L)
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Next track")
            .build()

        assertTrue(gate.shouldSuppress(ad))
        assertTrue(gate.shouldSuppress(null))
        assertTrue(gate.shouldSuppress(missing))
        assertTrue(gate.shouldSuppress(ordinaryNamedAd))
        assertFalse(gate.shouldSuppress(explicitlyNonAd))
        assertFalse(gate.shouldSuppress(ordinaryNamedAd))
    }

    @Test
    fun adMarkerIsExplicitAndTitleTextAloneIsNotAClassification() {
        val marked = MediaMetadata.Builder()
            .putLong("android.media.metadata.ADVERTISEMENT", 1L)
            .build()
        val normal = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Advertisement")
            .build()
        assertEquals(true, MediaAdvertisementFilter.advertisementMarker(marked))
        assertNull(MediaAdvertisementFilter.advertisementMarker(normal))
        assertFalse(AdvertisementFilterState().shouldSuppress(normal))
    }
}
