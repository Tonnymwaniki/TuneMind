package com.tunemind.app.data.local

import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.PlaybackObservation
import org.json.JSONObject

/** Canonical v1 local JSON encoding. Unknown future versions are rejected. */
object PlaybackObservationCodec {
    fun encode(observation: PlaybackObservation): String = JSONObject().apply {
        put("schemaVersion", observation.schemaVersion)
        put("eventId", observation.eventId)
        put("observerSessionId", observation.observerSessionId)
        put("eventType", observation.eventType.name)
        put("eventTimeMs", observation.eventTimeMs)
        putNullable("savedAtMs", observation.savedAtMs)
        putNullable("sourcePackage", observation.sourcePackage)
        putNullable("trackTitle", observation.trackTitle)
        putNullable("trackArtist", observation.trackArtist)
        putNullable("trackAlbum", observation.trackAlbum)
        putNullable("trackId", observation.trackId)
        putNullable("durationMs", observation.durationMs)
        putNullable("playbackStateCode", observation.playbackStateCode)
        putNullable("positionMs", observation.positionMs)
        putNullable("playbackSpeed", observation.playbackSpeed)
        putNullable("positionUpdateElapsedRealtimeMs", observation.positionUpdateElapsedRealtimeMs)
    }.toString()

    /** Decodes current v1 payloads and upgrades the pre-v1 local JSON shape in memory. */
    fun decode(json: String, legacySavedAtMs: Long? = null): PlaybackObservation {
        val value = JSONObject(json)
        return if (value.has("eventId")) decodeV1(value) else decodeLegacy(value, legacySavedAtMs)
    }

    private fun decodeV1(value: JSONObject): PlaybackObservation {
        val version = value.getInt("schemaVersion")
        require(version == PlaybackObservation.CURRENT_SCHEMA_VERSION) {
            "Unsupported raw observation schema version: $version"
        }
        return PlaybackObservation(
            schemaVersion = version,
            eventId = value.getString("eventId"),
            observerSessionId = value.getString("observerSessionId"),
            eventType = ObservationEventType.valueOf(value.getString("eventType")),
            eventTimeMs = value.getLong("eventTimeMs"),
            savedAtMs = value.nullableLong("savedAtMs"),
            sourcePackage = value.nullableString("sourcePackage"),
            trackTitle = value.nullableString("trackTitle"),
            trackArtist = value.nullableString("trackArtist"),
            trackAlbum = value.nullableString("trackAlbum"),
            trackId = value.nullableString("trackId"),
            durationMs = value.nullableLong("durationMs"),
            playbackStateCode = value.nullableInt("playbackStateCode"),
            positionMs = value.nullableLong("positionMs"),
            playbackSpeed = value.nullableFloat("playbackSpeed"),
            positionUpdateElapsedRealtimeMs = value.nullableLong("positionUpdateElapsedRealtimeMs"),
        )
    }

    private fun decodeLegacy(value: JSONObject, savedAtMs: Long?): PlaybackObservation = PlaybackObservation(
        eventId = value.getString("observationId"),
        observerSessionId = value.getString("sessionId"),
        eventType = ObservationEventType.valueOf(value.getString("eventType")),
        eventTimeMs = value.getLong("observedAt"),
        savedAtMs = savedAtMs,
        sourcePackage = value.nullableString("mediaSource"),
        trackTitle = value.nullableString("trackTitle"),
        trackArtist = value.nullableString("trackArtist"),
        trackAlbum = value.nullableString("trackAlbum"),
        // Legacy rows did not encode a track ID, duration, or raw state code.
        trackId = null,
        durationMs = null,
        playbackStateCode = null,
        positionMs = value.nullableLong("positionMs"),
        playbackSpeed = value.nullableFloat("playbackSpeed"),
        positionUpdateElapsedRealtimeMs = value.nullableLong("elapsedRealtimeMs"),
    )

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.nullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
    private fun JSONObject.nullableInt(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
    private fun JSONObject.nullableFloat(key: String): Float? = if (!has(key) || isNull(key)) null else getDouble(key).toFloat()
}
