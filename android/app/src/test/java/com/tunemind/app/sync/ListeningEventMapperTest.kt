package com.tunemind.app.sync

import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.PlaybackObservation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningEventMapperTest {

    @Test
    fun `maps minimal observation to valid backend payload`() {
        val obs = PlaybackObservation(
            eventId = "550e8400-e29b-41d4-a716-446655440000",
            observerSessionId = "obs-session-1",
            eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
            eventTimeMs = 1_000_000_000_000L,
            sourcePackage = "com.example.player",
            playbackStateCode = 3,
            positionMs = 5000,
            playbackSpeed = 1.0f,
            positionUpdateElapsedRealtimeMs = 100_000L,
        )

        val body = ListeningEventMapper.toRequestBody(listOf(obs), userId = "u1", deviceId = "d1")
        val json = JSONObject(body)
        val events = json.getJSONArray("events")
        assertEquals(1, events.length())

        val event = events.getJSONObject(0)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", event.getString("eventId"))
        assertEquals("u1", event.getString("userId"))
        assertEquals("obs-session-1", event.getString("sessionId"))
        assertEquals("d1", event.getString("deviceId"))
        assertEquals("1.0", event.getString("schemaVersion"))
        assertEquals("PLAYBACK_STATE_CHANGED", event.getString("eventType"))
        assertEquals("com.example.player", event.getString("mediaSource"))
        assertNotNull(event.getString("occurredAt"))

        val playback = event.getJSONObject("playback")
        assertEquals(5000, playback.getInt("positionMs"))
        assertEquals(100_000, playback.getInt("elapsedRealtimeMs"))
        assertTrue(playback.getBoolean("isPlaying"))
        assertEquals(3, playback.getInt("playbackStateCode"))
        assertEquals(1.0, playback.getDouble("playbackSpeed"), 0.001)

        assertTrue(event.isNull("track"))
        assertTrue(event.isNull("previousTrack"))
    }

    @Test
    fun `maps observation with full metadata to backend payload`() {
        val obs = PlaybackObservation(
            eventId = "660e8400-e29b-41d4-a716-446655440001",
            observerSessionId = "obs-session-2",
            eventType = ObservationEventType.METADATA_CHANGED,
            eventTimeMs = 2_000_000_000_000L,
            sourcePackage = "com.example.player",
            trackTitle = "Song Title",
            trackArtist = "Artist Name",
            trackAlbum = "Album Name",
            trackId = "provider-track-123",
            durationMs = 200_000,
            playbackStateCode = 3,
            positionMs = 15000,
            playbackSpeed = 1.0f,
            positionUpdateElapsedRealtimeMs = 200_000L,
        )

        val body = ListeningEventMapper.toRequestBody(listOf(obs), userId = "u1", deviceId = "d1")
        val event = JSONObject(body).getJSONArray("events").getJSONObject(0)

        val track = event.getJSONObject("track")
        assertEquals("Song Title", track.getString("title"))
        assertEquals("Artist Name", track.getString("artist"))
        assertEquals("Album Name", track.getString("album"))
        assertEquals("provider-track-123", track.getString("trackId"))
        assertEquals(200_000, track.getInt("durationMs"))
    }

    @Test
    fun `non-playing state maps isPlaying to false`() {
        val obs = PlaybackObservation(
            eventId = "770e8400-e29b-41d4-a716-446655440002",
            observerSessionId = "obs-session-3",
            eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
            eventTimeMs = 3_000_000_000_000L,
            sourcePackage = "com.example.player",
            playbackStateCode = 2,
            positionMs = 0,
            playbackSpeed = 0.0f,
            positionUpdateElapsedRealtimeMs = 0L,
        )

        val event = JSONObject(ListeningEventMapper.toRequestBody(listOf(obs)))
            .getJSONArray("events").getJSONObject(0)
        val playback = event.getJSONObject("playback")

        assertEquals(false, playback.getBoolean("isPlaying"))
        assertEquals(2, playback.getInt("playbackStateCode"))
    }

    @Test
    fun `STOPPED state 1 preserves raw code`() {
        val obs = PlaybackObservation(
            eventId = "990e8400-e29b-41d4-a716-446655440004",
            observerSessionId = "obs-session-5",
            eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
            eventTimeMs = 5_000_000_000_000L,
            sourcePackage = "com.example.player",
            playbackStateCode = 1,
            positionMs = 10000,
            playbackSpeed = 0.0f,
            positionUpdateElapsedRealtimeMs = 0L,
        )
        val event = JSONObject(ListeningEventMapper.toRequestBody(listOf(obs)))
            .getJSONArray("events").getJSONObject(0)
        val playback = event.getJSONObject("playback")
        assertEquals(false, playback.getBoolean("isPlaying"))
        assertEquals(1, playback.getInt("playbackStateCode"))
    }

    @Test
    fun `NONE state 0 preserves raw code`() {
        val obs = PlaybackObservation(
            eventId = "aa0e8400-e29b-41d4-a716-446655440005",
            observerSessionId = "obs-session-6",
            eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
            eventTimeMs = 6_000_000_000_000L,
            sourcePackage = "com.example.player",
            playbackStateCode = 0,
            positionMs = 0,
            playbackSpeed = 0.0f,
            positionUpdateElapsedRealtimeMs = 0L,
        )
        val event = JSONObject(ListeningEventMapper.toRequestBody(listOf(obs)))
            .getJSONArray("events").getJSONObject(0)
        val playback = event.getJSONObject("playback")
        assertEquals(false, playback.getBoolean("isPlaying"))
        assertEquals(0, playback.getInt("playbackStateCode"))
    }

    @Test
    fun `null playback fields produce nulls not defaults`() {
        val obs = PlaybackObservation(
            eventId = "880e8400-e29b-41d4-a716-446655440003",
            observerSessionId = "obs-session-4",
            eventType = ObservationEventType.SESSION_CREATED,
            eventTimeMs = 4_000_000_000_000L,
            sourcePackage = null,
            playbackStateCode = null,
            positionMs = null,
            playbackSpeed = null,
            positionUpdateElapsedRealtimeMs = null,
        )

        val event = JSONObject(ListeningEventMapper.toRequestBody(listOf(obs)))
            .getJSONArray("events").getJSONObject(0)

        assertEquals("", event.getString("mediaSource"))

        val playback = event.getJSONObject("playback")
        assertTrue("positionMs should be null", playback.isNull("positionMs"))
        assertTrue("elapsedRealtimeMs should be null", playback.isNull("elapsedRealtimeMs"))
        assertEquals(false, playback.getBoolean("isPlaying"))
        assertTrue("playbackSpeed should be null", playback.isNull("playbackSpeed"))
        assertTrue(playback.isNull("playbackStateCode"))

        assertTrue(event.isNull("track"))
    }

    @Test
    fun `genuine zero position remains distinguishable from null`() {
        val obs = PlaybackObservation(
            eventId = "bb0e8400-e29b-41d4-a716-446655440006",
            observerSessionId = "obs-session-7",
            eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
            eventTimeMs = 7_000_000_000_000L,
            sourcePackage = "com.example.player",
            playbackStateCode = 3,
            positionMs = 0L,
            playbackSpeed = 1.0f,
            positionUpdateElapsedRealtimeMs = 5000L,
        )

        val event = JSONObject(ListeningEventMapper.toRequestBody(listOf(obs)))
            .getJSONArray("events").getJSONObject(0)
        val playback = event.getJSONObject("playback")
        assertEquals(0, playback.getInt("positionMs"))
        assertEquals(1.0, playback.getDouble("playbackSpeed"), 0.001)
        assertEquals(5000, playback.getInt("elapsedRealtimeMs"))
    }

    @Test
    fun `batch of multiple observations produces matching array`() {
        val obs1 = PlaybackObservation(
            eventId = "event-1", observerSessionId = "s1",
            eventType = ObservationEventType.PLAYBACK_STATE_CHANGED,
            eventTimeMs = 1000, sourcePackage = "pkg",
            playbackStateCode = 3, positionMs = 0, playbackSpeed = 1.0f,
        )
        val obs2 = PlaybackObservation(
            eventId = "event-2", observerSessionId = "s1",
            eventType = ObservationEventType.METADATA_CHANGED,
            eventTimeMs = 2000, sourcePackage = "pkg",
            trackTitle = "Song", trackArtist = "Artist",
            playbackStateCode = 3, positionMs = 10000, playbackSpeed = 1.0f,
        )

        val body = ListeningEventMapper.toRequestBody(listOf(obs1, obs2))
        val events = JSONObject(body).getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("event-1", events.getJSONObject(0).getString("eventId"))
        assertEquals("event-2", events.getJSONObject(1).getString("eventId"))
    }
}
