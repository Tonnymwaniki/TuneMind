package com.tunemind.app.domain.moment

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for stored Musical Moments.
 */
@Entity(
    tableName = "musical_moments",
    indices = [
        Index("trackTitle", "trackArtist"),
        Index("userConfirmed"),
        Index("createdAt")
    ]
)
data class MusicalMomentEntity(
    @PrimaryKey val momentId: String,
    val trackTitle: String,
    val trackArtist: String,
    val trackAlbum: String? = null,
    val sourcePackage: String? = null,
    val providerTrackId: String? = null,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val preferredPlaybackDurationMs: Long,
    val discoveryMethod: String = "USER_MARKED",
    val confidence: Float? = 1.0f,
    val userConfirmed: Boolean = true,
    val customName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null
)
