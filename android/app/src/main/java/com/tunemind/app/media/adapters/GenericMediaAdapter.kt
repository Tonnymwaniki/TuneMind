package com.tunemind.app.media.adapters

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.tunemind.app.domain.event.ObservationEventType
import com.tunemind.app.domain.model.PlaybackObservation
import com.tunemind.app.media.MediaSourceAdapter

/** Maps only values directly exposed by Android media-session APIs. */
class GenericMediaAdapter : MediaSourceAdapter {
    override val targetPackage: String = "*"
    override fun canHandle(packageName: String): Boolean = true

    override fun extractObservation(
        eventId: String,
        eventType: ObservationEventType,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?,
        observerSessionId: String,
        sourcePackage: String?,
        eventTimeMs: Long,
    ): PlaybackObservation {
        val position = playbackState?.position?.takeIf { it >= 0L }
        val speed = playbackState?.playbackSpeed?.takeIf { it.isFinite() }
        val positionUpdateTime = playbackState?.lastPositionUpdateTime
            ?.takeIf { it > 0L && position != null }
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.trim()?.takeIf(String::isNotEmpty)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.trim()?.takeIf(String::isNotEmpty)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?.trim()?.takeIf(String::isNotEmpty)
        val trackId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.trim()?.takeIf(String::isNotEmpty)
        val duration = metadata?.takeIf { it.containsKey(MediaMetadata.METADATA_KEY_DURATION) }
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.takeIf { it >= 0L }

        return PlaybackObservation(
            eventId = eventId,
            observerSessionId = observerSessionId,
            eventType = eventType,
            eventTimeMs = eventTimeMs,
            sourcePackage = sourcePackage,
            trackTitle = title,
            trackArtist = artist,
            trackAlbum = album,
            trackId = trackId,
            durationMs = duration,
            playbackStateCode = playbackState?.state,
            positionMs = position,
            playbackSpeed = speed,
            positionUpdateElapsedRealtimeMs = positionUpdateTime,
        )
    }
}
