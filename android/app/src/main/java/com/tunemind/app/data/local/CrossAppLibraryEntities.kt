package com.tunemind.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Permanent, unpruned cross-app music library entry.
 *
 * Every verified track the user listens to across ANY Android music player
 * (YouTube Music, Boomplay, Audiomack, Spotify passive, Deezer, SoundCloud,
 * local audio, TuneMind In-App) is merged cleanly by [canonicalTrackKey] and
 * stored permanently alongside all source app package names where it was played.
 */
@Entity(
    tableName = "cross_app_track_library",
    indices = [
        Index(value = ["last_played_at_ms"], orders = [Index.Order.DESC]),
        Index(value = ["play_count"], orders = [Index.Order.DESC]),
        Index(value = ["primary_source_package"])
    ]
)
data class CrossAppTrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "canonical_track_key")
    val canonicalTrackKey: String,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "album")
    val album: String = "",

    @ColumnInfo(name = "genre")
    val genre: String = "Music",

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "primary_source_package")
    val primarySourcePackage: String = "unknown_player",

    @ColumnInfo(name = "source_packages_csv")
    val sourcePackagesCsv: String = "",

    @ColumnInfo(name = "play_count")
    val playCount: Int = 0,

    @ColumnInfo(name = "total_listened_ms")
    val totalListenedMs: Long = 0L,

    @ColumnInfo(name = "first_played_at_ms")
    val firstPlayedAtMs: Long = 0L,

    @ColumnInfo(name = "last_played_at_ms")
    val lastPlayedAtMs: Long = 0L,

    @ColumnInfo(name = "is_liked")
    val isLiked: Boolean = false,

    @ColumnInfo(name = "is_in_vault")
    val isInVault: Boolean = false,

    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,

    @ColumnInfo(name = "preview_url")
    val previewUrl: String? = null,

    @ColumnInfo(name = "audius_stream_url")
    val audiusStreamUrl: String? = null,

    @ColumnInfo(name = "ytm_video_id")
    val ytmVideoId: String? = null,

    @ColumnInfo(name = "deezer_url")
    val deezerUrl: String? = null,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Permanent chronological play session log across every music app on the device.
 */
@Entity(
    tableName = "cross_app_play_log",
    indices = [
        Index(value = ["played_at_ms"], orders = [Index.Order.DESC]),
        Index(value = ["source_package"]),
        Index(value = ["canonical_track_key"])
    ]
)
data class CrossAppPlayLogEntity(
    @PrimaryKey
    @ColumnInfo(name = "play_log_id")
    val playLogId: String,

    @ColumnInfo(name = "canonical_track_key")
    val canonicalTrackKey: String,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "album")
    val album: String = "",

    @ColumnInfo(name = "source_package")
    val sourcePackage: String,

    @ColumnInfo(name = "source_app_name")
    val sourceAppName: String,

    @ColumnInfo(name = "played_at_ms")
    val playedAtMs: Long,

    @ColumnInfo(name = "listened_ms")
    val listenedMs: Long = 0L,

    @ColumnInfo(name = "track_duration_ms")
    val trackDurationMs: Long = 0L,

    @ColumnInfo(name = "completion_ratio")
    val completionRatio: Float = 0f,

    @ColumnInfo(name = "was_skipped")
    val wasSkipped: Boolean = false,

    @ColumnInfo(name = "synced_to_cloud")
    val syncedToCloud: Boolean = false
)

/**
 * Cached artwork & open-catalog playable URLs resolved via Deezer / iTunes / Audius / YouTube.
 */
@Entity(
    tableName = "universal_track_visual_cache",
    indices = [
        Index(value = ["track_id"]),
        Index(value = ["updated_at_ms"])
    ]
)
data class TrackVisualCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "track_key")
    val trackKey: String,

    @ColumnInfo(name = "track_id")
    val trackId: String? = null,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "artist_id")
    val artistId: String? = null,

    @ColumnInfo(name = "album_name")
    val albumName: String? = null,

    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,

    @ColumnInfo(name = "artist_image_url")
    val artistImageUrl: String? = null,

    @ColumnInfo(name = "genres_csv")
    val genresCsv: String = "",

    @ColumnInfo(name = "popularity")
    val popularity: Int = 0,

    @ColumnInfo(name = "preview_url")
    val previewUrl: String? = null,

    @ColumnInfo(name = "audius_stream_url")
    val audiusStreamUrl: String? = null,

    @ColumnInfo(name = "ytm_video_id")
    val ytmVideoId: String? = null,

    @ColumnInfo(name = "deezer_url")
    val deezerUrl: String? = null,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "universal_artist_profile_cache",
    indices = [
        Index(value = ["artist_id"]),
        Index(value = ["updated_at_ms"])
    ]
)
data class ArtistProfileCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_key")
    val artistKey: String,

    @ColumnInfo(name = "artist_id")
    val artistId: String? = null,

    @ColumnInfo(name = "artist_name")
    val artistName: String,

    @ColumnInfo(name = "image_url")
    val imageUrl: String? = null,

    @ColumnInfo(name = "genres_csv")
    val genresCsv: String = "",

    @ColumnInfo(name = "popularity")
    val popularity: Int = 0,

    @ColumnInfo(name = "followers_total")
    val followersTotal: Long = 0L,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class ResolvedTrackVisuals(
    val trackKey: String = "",
    val trackId: String? = null,
    val albumName: String? = null,
    val albumArtUrl: String? = null,
    val artistImageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val popularity: Int = 0,
    val previewUrl: String? = null,
    val audiusStreamUrl: String? = null,
    val ytmVideoId: String? = null,
    val deezerUrl: String? = null
)

data class CrossAppArtistInsight(
    val artistId: String = "",
    val name: String = "",
    val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val popularity: Int = 0,
    val totalPlays: Int = 0,
    val primaryPlayerPackage: String = "unknown_player"
)

data class CrossAppGenreWeight(
    val genre: String,
    val count: Int,
    val sharePercent: Int
)

data class CrossAppTasteShiftSummary(
    val topGenres: List<CrossAppGenreWeight> = emptyList(),
    val risingArtists: List<CrossAppArtistInsight> = emptyList(),
    val consistentArtists: List<CrossAppArtistInsight> = emptyList(),
    val emergingGenres: List<String> = emptyList()
)

/**
 * Represents a dynamic player tab discovered from the user's actual listening history
 * (e.g. YouTube Music, Boomplay, Spotify Passive, Audiomack, Phone Audio, TuneMind).
 */
data class DetectedPlayerTab(
    val packageName: String,
    val displayName: String,
    val trackCount: Int,
    val totalPlays: Int,
    val totalListenedMs: Long,
    val lastPlayedAtMs: Long
)
