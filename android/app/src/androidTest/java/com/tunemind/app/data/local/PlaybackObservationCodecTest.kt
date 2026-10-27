package com.tunemind.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.PlaybackObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackObservationCodecTest {
    @Test
    fun v1RoundTripPreservesRawValuesAndNullableFields() {
        val observation = PlaybackObservation(
            eventId = "event-1",
            observerSessionId = "observer-session-1",
            eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
            eventTimeMs = 1234L,
            savedAtMs = 1250L,
            sourcePackage = "com.example.player",
            trackTitle = "Track",
            trackArtist = null,
            trackAlbum = "Album",
            trackId = "provider-id",
            durationMs = 200_000L,
            playbackStateCode = 6,
            positionMs = 77_000L,
            playbackSpeed = 1.25f,
            positionUpdateElapsedRealtimeMs = 55_000L,
        )

        val decoded = PlaybackObservationCodec.decode(PlaybackObservationCodec.encode(observation))
        assertEquals(observation, decoded)
        assertNull(decoded.trackArtist)
    }

    @Test
    fun unversionedLegacyPayloadLoadsWithoutInventingRawState() {
        val legacy = """{
            "observationId":"old-id","sessionId":"old-session",
            "mediaSource":"com.example.player","eventType":"PLAYBACK_STATE_CHANGED",
            "observedAt":10,"elapsedRealtimeMs":9,"trackTitle":"Old track",
            "trackArtist":null,"trackAlbum":null,"positionMs":5,
            "isPlaying":true,"playbackSpeed":1.0
        }"""

        val decoded = PlaybackObservationCodec.decode(legacy, legacySavedAtMs = 12L)
        assertEquals("old-id", decoded.eventId)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(12L, decoded.savedAtMs)
        assertNull(decoded.playbackStateCode)
        assertNull(decoded.trackId)
        assertNull(decoded.durationMs)
    }
}
