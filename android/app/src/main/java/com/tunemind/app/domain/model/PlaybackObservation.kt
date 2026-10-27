package com.tunemind.app.domain.model

import com.tunemind.app.domain.event.ObservationEventType

/**
 * Version 1 raw observation of values reported by an Android media session.
 * Nullable fields remain null when Android/the source does not provide them.
 * This model contains no skip, intent, preference, or fatigue interpretation.
 */
data class PlaybackObservation(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** Random UUID for this accepted record; independent of mutable playback state. */
    val eventId: String,
    /** Random UUID shared by observations from this listener-service connection. */
    val observerSessionId: String,
    val eventType: ObservationEventType,
    /** Wall-clock milliseconds sampled when the callback is accepted. */
    val eventTimeMs: Long,
    /** Wall-clock milliseconds when the repository begins saving; null before persistence. */
    val savedAtMs: Long? = null,
    /** Package name reported by MediaController, when available. */
    val sourcePackage: String?,
    /** Values directly obtained from MediaMetadata, if present. */
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val trackAlbum: String? = null,
    val trackId: String? = null,
    val durationMs: Long? = null,
    /** Raw PlaybackState.STATE_* integer, not collapsed into a playing boolean. */
    val playbackStateCode: Int? = null,
    /** Last reported PlaybackState position; this is not a continuously updated position. */
    val positionMs: Long? = null,
    val playbackSpeed: Float? = null,
    /** PlaybackState's source-reported last-position-update time on the elapsed-realtime clock. */
    val positionUpdateElapsedRealtimeMs: Long? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
