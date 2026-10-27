package com.tunemind.app.domain.model

/**
 * Legacy metadata helper; Raw Listening Observation v1 uses nullable flat fields
 * directly on [PlaybackObservation] and this type is not serialized by Room.
 *
 * All fields are nullable because not all media sources expose all metadata.
 * The GenericMediaAdapter must handle partial or absent metadata gracefully.
 */
data class TrackSnapshot(
    /** Source-app-specific track identifier, if exposed via MediaMetadata. */
    val trackId: String? = null,

    /** Track title from MediaMetadata.METADATA_KEY_TITLE. */
    val title: String? = null,

    /** Artist name from MediaMetadata.METADATA_KEY_ARTIST. */
    val artist: String? = null,

    /** Album name from MediaMetadata.METADATA_KEY_ALBUM. */
    val album: String? = null,

    /**
     * Reported track duration in milliseconds from MediaMetadata.METADATA_KEY_DURATION.
     * May be null or 0 if not exposed by the media source.
     */
    val durationMs: Long? = null,
)
