package com.tunemind.app.media

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.PlaybackObservation

/**
 * Abstraction for extracting raw [PlaybackObservation] records from a media source.
 *
 * Different media applications may expose different levels of metadata through
 * their MediaSession. This interface allows app-specific adapters to handle
 * non-standard behaviour, while [GenericMediaAdapter] covers the common case
 * using only standard MediaMetadata keys.
 *
 * GenericMediaAdapter handles the common standard MediaMetadata keys.
 */
interface MediaSourceAdapter {

    /**
     * The package name of the target media application this adapter handles.
     * e.g. "com.spotify.music" or "*" for a generic catch-all adapter.
     */
    val targetPackage: String

    /**
     * Returns true if this adapter can handle a session from the given package name.
     */
    fun canHandle(packageName: String): Boolean

    /**
     * Extracts a raw [PlaybackObservation] from MediaController callback data.
     *
     * MUST NOT perform any skip/completion/fatigue inference.
     * Records only what was directly observed from the Android APIs.
     *
     * Returns a raw observation even when metadata or playback state is absent;
     * unavailable values are represented as null rather than fabricated.
     *
     * @param eventType The type of observation being recorded.
     * @param metadata Current MediaMetadata from onMetadataChanged. May be null.
     * @param playbackState Current PlaybackState from onPlaybackStateChanged. May be null.
     * @param eventId Unique identifier assigned to this accepted observation.
     * @param observerSessionId Current TuneMind listener connection identifier.
     * @param sourcePackage Package name of the media application, if exposed.
     */
    fun extractObservation(
        eventId: String,
        eventType: ObservationEventType,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?,
        observerSessionId: String,
        sourcePackage: String?,
        eventTimeMs: Long = System.currentTimeMillis(),
    ): PlaybackObservation?
}
