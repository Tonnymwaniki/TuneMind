package com.tunemind.app.domain.provider

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Observed music application summary (Spotify, YouTube Music, Tidal, etc.).
 */
@Entity(tableName = "music_provider_summaries")
data class MusicProviderEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val firstObservedAt: Long = System.currentTimeMillis(),
    val lastObservedAt: Long = System.currentTimeMillis(),
    val totalObservedEpisodes: Int = 1,
    val totalObservedPlaybackMs: Long = 0L,
    val integrationStatus: String = "ACTIVE_MONITORING",
    val capabilitiesJson: String = "{\"canOpen\":true,\"canObserve\":true}"
)
