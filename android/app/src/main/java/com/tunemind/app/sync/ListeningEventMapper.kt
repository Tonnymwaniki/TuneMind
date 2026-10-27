package com.tunemind.app.sync

import com.tunemind.app.domain.model.PlaybackObservation
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Maps a local [PlaybackObservation] to the backend ListeningEvent JSON payload
 * defined in schema/listening-event.schema.json.
 *
 * The backend schema nests track metadata under a "track" object and playback
 * state under a "playback" object, while the local schema uses flat nullable fields.
 */
object ListeningEventMapper {

    /** Thread-safe ISO 8601 formatter using java.time. */
    private val isoFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /**
     * Serializes a batch of observations into the JSON body for POST /api/v1/events.
     *
     * userId and deviceId are placeholders until authentication is implemented.
     * The backend requires non-empty strings for these fields.
     */
    fun toRequestBody(observations: List<PlaybackObservation>, userId: String = "tunemind-dev", deviceId: String = "device-1"): String {
        val events = JSONArray()
        for (obs in observations) {
            events.put(toEventJson(obs, userId, deviceId))
        }
        return JSONObject().put("events", events).toString()
    }

    private fun toEventJson(obs: PlaybackObservation, userId: String, deviceId: String): JSONObject {
        val playback = JSONObject().apply {
            put("positionMs", obs.positionMs ?: JSONObject.NULL)
            put("elapsedRealtimeMs", obs.positionUpdateElapsedRealtimeMs ?: JSONObject.NULL)
            put("isPlaying", obs.playbackStateCode == 3)
            put("playbackStateCode", obs.playbackStateCode ?: JSONObject.NULL)
            put("playbackSpeed", obs.playbackSpeed?.toDouble() ?: JSONObject.NULL)
        }

        val track: JSONObject? = if (obs.trackId != null || obs.trackTitle != null || obs.trackArtist != null || obs.trackAlbum != null || obs.durationMs != null) {
            JSONObject().apply {
                put("trackId", obs.trackId ?: JSONObject.NULL)
                put("title", obs.trackTitle ?: JSONObject.NULL)
                put("artist", obs.trackArtist ?: JSONObject.NULL)
                put("album", obs.trackAlbum ?: JSONObject.NULL)
                put("durationMs", obs.durationMs ?: JSONObject.NULL)
            }
        } else null

        val occurredAt = isoFormat.format(Instant.ofEpochMilli(obs.eventTimeMs))

        return JSONObject().apply {
            put("eventId", obs.eventId)
            put("userId", userId)
            put("sessionId", obs.observerSessionId)
            put("deviceId", deviceId)
            put("schemaVersion", "1.0")
            put("eventType", obs.eventType.name)
            put("occurredAt", occurredAt)
            put("mediaSource", obs.sourcePackage ?: "")
            put("playback", playback)
            put("track", track ?: JSONObject.NULL)
            put("previousTrack", JSONObject.NULL)
        }
    }
}
