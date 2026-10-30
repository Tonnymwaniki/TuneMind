package com.tunemind.app.domain.moment

/**
 * Discovery method for musical moments.
 */
enum class MomentDiscoveryMethod {
    USER_MARKED,
    REPLAY_PATTERN,
    SEEK_BACK_PATTERN,
    INFERRED_PATTERN
}

/**
 * Domain representation of a preferred musical moment (a repeatedly enjoyed section of a song).
 */
data class MusicalMoment(
    val momentId: String,
    val trackTitle: String,
    val trackArtist: String,
    val trackAlbum: String? = null,
    val sourcePackage: String? = null,
    val providerTrackId: String? = null,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val preferredPlaybackDurationMs: Long,
    val discoveryMethod: MomentDiscoveryMethod = MomentDiscoveryMethod.USER_MARKED,
    val confidence: Float? = 1.0f,
    val userConfirmed: Boolean = true,
    val customName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null
)
