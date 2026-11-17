package com.tunemind.app.domain.recommendation

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tunemind.app.domain.model.TrackIdentityResolver

/**
 * Persistent AI Recommendation stored in user's local Room database.
 * Supports daily auto-refresh drops, per-genre + global wildcard tagging,
 * and passive listen-duration + volume outcome tracking for self-learning ML updates.
 */
@Entity(
    tableName = "ai_recommendation_crate",
    indices = [
        Index(value = ["title", "artist"], unique = true),
        Index("timeSlotKey"),
        Index("recommendedAtMs"),
        Index("isSaved"),
        Index("isDismissed"),
        Index("dropDateKey")
    ]
)
data class AiRecommendationEntity(
    @PrimaryKey
    val id: String = createDeterministicId(title = "track", artist = "artist"),
    val title: String,
    val artist: String,
    val album: String? = null,
    val category: String = "DOPAMINE_FLOW", // DOPAMINE_FLOW, PALATE_CLEANSER, CHRONO_RESTORE, NOVEL_EXPLORATION
    val subTier: String = "GENRE_PICK", // GENRE_PICK, GLOBAL_WILDCARD, CORE_DOPAMINE, DEEP_CUT, etc.
    val reason: String,
    val confidence: Float = 0.88f,
    val timeSlotKey: String = "MORNING",
    val timeSlotLabel: String = "Morning Focus",
    val recommendedAtMs: Long = System.currentTimeMillis(),
    val suggestedProviderId: String = "spotify",
    val isSaved: Boolean = false,
    val wasPlayed: Boolean = false,
    val isDismissed: Boolean = false,
    val playCount: Int = 0,
    val completedListenCount: Int = 0,
    val lastInteractedAtMs: Long? = null,
    val telemetryBasisSummary: String = "Based on recent listening patterns",
    val genreTag: String = "Discovery",
    val dropDateKey: String = "",
    val matchScorePercent: Int = 92,
    val avgVolumeUsedRatio: Float = 0f,
    val listenedDurationMs: Long = 0L,
    val trackDurationMs: Long = 0L,
    val isWildcard: Boolean = false,
    val spotifyUri: String? = null,
    val previewUrl: String? = null,
    val trackId: String = ""
) {
    fun resolvedCanonicalTrackId(): String = TrackIdentityResolver.resolveCanonicalTrackId(
        trackId = trackId.ifBlank { null },
        spotifyUri = spotifyUri,
        title = title,
        artist = artist
    )

    companion object {
        fun createDeterministicId(title: String, artist: String): String {
            val cleanTitle = title.trim().lowercase().replace(Regex("[^a-z0-9]"), "_")
            val cleanArtist = artist.trim().lowercase().replace(Regex("[^a-z0-9]"), "_")
            return "rec_${cleanArtist}_${cleanTitle}".take(64)
        }
    }
}
