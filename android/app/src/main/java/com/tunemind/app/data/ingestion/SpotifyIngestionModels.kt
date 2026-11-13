package com.tunemind.app.data.ingestion

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import com.tunemind.app.domain.model.MemoryEvidenceQuality
import com.tunemind.app.domain.model.TrackIdentityBasis
import com.tunemind.app.domain.model.TrackMemoryEntry
import java.time.Instant

// ============================================================================
// 1. RETROFIT DTOs — SPOTIFY OAUTH 2.0 & WEB API (/v1/me/player/recently-played)
// ============================================================================

data class SpotifyTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String = "Bearer",
    @SerializedName("scope") val scope: String? = null,
    @SerializedName("expires_in") val expiresInSeconds: Long = 3600L,
    @SerializedName("refresh_token") val refreshToken: String? = null
)

data class SpotifyRecentlyPlayedResponse(
    @SerializedName("items") val items: List<SpotifyPlayHistoryItemDto> = emptyList(),
    @SerializedName("next") val next: String? = null,
    @SerializedName("cursors") val cursors: SpotifyCursorsDto? = null,
    @SerializedName("limit") val limit: Int = 50,
    @SerializedName("href") val href: String? = null
)

data class SpotifyCursorsDto(
    @SerializedName("after") val after: String? = null,
    @SerializedName("before") val before: String? = null
)

data class SpotifyPlayHistoryItemDto(
    @SerializedName("track") val track: SpotifyTrackDto,
    @SerializedName("played_at") val playedAtIso: String,
    @SerializedName("context") val context: SpotifyContextDto? = null
)

data class SpotifyCurrentlyPlayingResponse(
    @SerializedName("timestamp") val timestampMs: Long = 0L,
    @SerializedName("progress_ms") val progressMs: Long? = null,
    @SerializedName("is_playing") val isPlaying: Boolean = false,
    @SerializedName("item") val item: SpotifyTrackDto? = null,
    @SerializedName("context") val context: SpotifyContextDto? = null
)

data class SpotifyTopTracksResponse(
    @SerializedName("items") val items: List<SpotifyTrackDto> = emptyList(),
    @SerializedName("total") val total: Int = 0,
    @SerializedName("limit") val limit: Int = 30
)

data class SpotifyTrackDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("uri") val uri: String,
    @SerializedName("duration_ms") val durationMs: Long = 0L,
    @SerializedName("popularity") val popularity: Int = 0,
    @SerializedName("explicit") val explicit: Boolean = false,
    @SerializedName("album") val album: SpotifyAlbumDto? = null,
    @SerializedName("artists") val artists: List<SpotifyArtistDto> = emptyList(),
    @SerializedName("external_urls") val externalUrls: SpotifyExternalUrlsDto? = null
)

data class SpotifyAlbumDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String = "",
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("images") val images: List<SpotifyImageDto> = emptyList(),
    @SerializedName("release_date") val releaseDate: String? = null
)

data class SpotifyArtistDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String = "",
    @SerializedName("uri") val uri: String? = null
)

data class SpotifyImageDto(
    @SerializedName("url") val url: String,
    @SerializedName("height") val height: Int? = null,
    @SerializedName("width") val width: Int? = null
)

data class SpotifyContextDto(
    @SerializedName("type") val type: String? = null,
    @SerializedName("uri") val uri: String? = null,
    @SerializedName("href") val href: String? = null
)

data class SpotifyExternalUrlsDto(
    @SerializedName("spotify") val spotifyUrl: String? = null
)

// ============================================================================
// 2. LOCAL-FIRST ROOM ENTITY & SQLITE AGGREGATE PROJECTION
// ============================================================================

/**
 * Local-First SQLite table storing exact Spotify Web API listening history.
 *
 * Uses a deterministic composite primary key (`history_id = "{trackId}_{playedAtEpochMs}"`)
 * so repeated 15-minute or audio-focus sync passes are 100% idempotent and never duplicate rows.
 * By reading exclusively from Room via Flow, the TuneMind UI renders in < 2ms with zero
 * network-blocking spinners or HTTP 500 states.
 */
@Entity(
    tableName = "spotify_listening_history",
    indices = [
        Index(value = ["played_at_epoch_ms"], orders = [Index.Order.DESC]),
        Index(value = ["track_id"]),
        Index(value = ["track_title", "artist_name"])
    ]
)
data class SpotifyListeningHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "history_id")
    val historyId: String,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "spotify_uri")
    val spotifyUri: String,

    @ColumnInfo(name = "track_title")
    val trackTitle: String,

    @ColumnInfo(name = "artist_name")
    val artistName: String,

    @ColumnInfo(name = "artist_ids_csv")
    val artistIdsCsv: String = "",

    @ColumnInfo(name = "album_name")
    val albumName: String = "",

    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "popularity")
    val popularity: Int = 0,

    @ColumnInfo(name = "played_at_iso")
    val playedAtIso: String,

    @ColumnInfo(name = "played_at_epoch_ms")
    val playedAtEpochMs: Long,

    @ColumnInfo(name = "ingestion_source")
    val ingestionSource: String = "RECENTLY_PLAYED", // RECENTLY_PLAYED | CURRENTLY_PLAYING | TOP_TRACKS

    @ColumnInfo(name = "context_uri")
    val contextUri: String? = null,

    @ColumnInfo(name = "synced_at_epoch_ms")
    val syncedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Aggregated per-track statistics computed directly by SQLite (`GROUP BY track_id`)
 * for instant Dopamine Satiety and Rhythm DNA rendering.
 */
data class SpotifyTrackAggregate(
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "spotify_uri") val spotifyUri: String,
    @ColumnInfo(name = "track_title") val trackTitle: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_name") val albumName: String,
    @ColumnInfo(name = "album_art_url") val albumArtUrl: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "first_played_epoch_ms") val firstPlayedEpochMs: Long,
    @ColumnInfo(name = "last_played_epoch_ms") val lastPlayedEpochMs: Long
)

// ============================================================================
// 3. DOMAIN MAPPERS (DTO -> ROOM ENTITY -> UI TRACK MEMORY ENTRY)
// ============================================================================

fun parseSpotifyIsoTimestampMs(isoString: String?): Long {
    if (isoString.isNullOrBlank()) return System.currentTimeMillis()
    return runCatching {
        Instant.parse(isoString.trim()).toEpochMilli()
    }.getOrElse {
        System.currentTimeMillis()
    }
}

/**
 * Local-First Room Entity representing a single Spotify listening telemetry record (`track_history_telemetry`).
 * Uses a deterministic primary key (`telemetryId = "{trackId}_{playedAtEpochMs}"`) so inserting via
 * `OnConflictStrategy.IGNORE` automatically deduplicates repeated 15-minute sync runs.
 */
@Entity(
    tableName = "track_history_telemetry",
    indices = [
        Index(value = ["played_at_epoch_ms"], orders = [Index.Order.DESC]),
        Index(value = ["track_id"])
    ]
)
data class TrackHistoryTelemetryItem(
    @PrimaryKey
    @ColumnInfo(name = "telemetry_id")
    val telemetryId: String,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "spotify_uri")
    val spotifyUri: String,

    @ColumnInfo(name = "track_title")
    val trackTitle: String,

    @ColumnInfo(name = "artist_name")
    val artistName: String,

    @ColumnInfo(name = "album_name")
    val albumName: String = "",

    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "played_at_iso")
    val playedAtIso: String,

    @ColumnInfo(name = "played_at_epoch_ms")
    val playedAtEpochMs: Long,

    @ColumnInfo(name = "context_uri")
    val contextUri: String? = null,

    @ColumnInfo(name = "ingested_at_epoch_ms")
    val ingestedAtEpochMs: Long = System.currentTimeMillis()
)

fun SpotifyPlayHistoryItemDto.toTrackHistoryTelemetryItem(
    nowMs: Long = System.currentTimeMillis()
): TrackHistoryTelemetryItem {
    val playedMs = parseSpotifyIsoTimestampMs(playedAtIso)
    val primaryArtist = track.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
    val cleanId = track.id.ifBlank { "${track.name.lowercase()}_${primaryArtist.lowercase()}" }
    val cleanUri = track.uri.ifBlank { "spotify:track:$cleanId" }

    return TrackHistoryTelemetryItem(
        telemetryId = "${cleanId}_$playedMs",
        trackId = cleanId,
        spotifyUri = cleanUri,
        trackTitle = track.name.trim(),
        artistName = primaryArtist.trim(),
        albumName = track.album?.name?.trim() ?: "",
        albumArtUrl = track.album?.images?.firstOrNull()?.url,
        durationMs = track.durationMs,
        playedAtIso = playedAtIso,
        playedAtEpochMs = playedMs,
        contextUri = context?.uri,
        ingestedAtEpochMs = nowMs
    )
}

fun SpotifyPlayHistoryItemDto.toHistoryEntity(nowMs: Long = System.currentTimeMillis()): SpotifyListeningHistoryEntity {
    val playedMs = parseSpotifyIsoTimestampMs(playedAtIso)
    val primaryArtist = track.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
    val artistIds = track.artists.mapNotNull { it.id }.joinToString(",")
    val cleanId = track.id.ifBlank { "${track.name.lowercase()}_${primaryArtist.lowercase()}" }
    val cleanUri = track.uri.ifBlank { "spotify:track:$cleanId" }

    return SpotifyListeningHistoryEntity(
        historyId = "${cleanId}_$playedMs",
        trackId = cleanId,
        spotifyUri = cleanUri,
        trackTitle = track.name.trim(),
        artistName = primaryArtist.trim(),
        artistIdsCsv = artistIds,
        albumName = track.album?.name?.trim() ?: "",
        albumArtUrl = track.album?.images?.firstOrNull()?.url,
        durationMs = track.durationMs,
        popularity = track.popularity,
        playedAtIso = playedAtIso,
        playedAtEpochMs = playedMs,
        ingestionSource = "RECENTLY_PLAYED",
        contextUri = context?.uri,
        syncedAtEpochMs = nowMs
    )
}

fun SpotifyTrackDto.toHistoryEntity(
    playedAtMs: Long,
    ingestionSource: String,
    contextUri: String? = null
): SpotifyListeningHistoryEntity {
    val primaryArtist = artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
    val artistIds = artists.mapNotNull { it.id }.joinToString(",")
    val cleanId = id.ifBlank { "${name.lowercase()}_${primaryArtist.lowercase()}" }
    val cleanUri = uri.ifBlank { "spotify:track:$cleanId" }
    val iso = Instant.ofEpochMilli(playedAtMs).toString()

    return SpotifyListeningHistoryEntity(
        historyId = "${cleanId}_$playedAtMs",
        trackId = cleanId,
        spotifyUri = cleanUri,
        trackTitle = name.trim(),
        artistName = primaryArtist.trim(),
        artistIdsCsv = artistIds,
        albumName = album?.name?.trim() ?: "",
        albumArtUrl = album?.images?.firstOrNull()?.url,
        durationMs = durationMs,
        popularity = popularity,
        playedAtIso = iso,
        playedAtEpochMs = playedAtMs,
        ingestionSource = ingestionSource,
        contextUri = contextUri,
        syncedAtEpochMs = System.currentTimeMillis()
    )
}

fun SpotifyTrackAggregate.toTrackMemoryEntry(): TrackMemoryEntry {
    return TrackMemoryEntry(
        entryId = "spotify:$trackId",
        sourcePackage = "com.spotify.music",
        providerTrackId = spotifyUri.ifBlank { "spotify:track:$trackId" },
        title = trackTitle,
        artist = artistName,
        album = albumName.ifBlank { null },
        distinctAppearanceCount = playCount.coerceAtLeast(1),
        firstObservedAppearanceAtMs = firstPlayedEpochMs,
        lastObservedAppearanceAtMs = lastPlayedEpochMs,
        supportingObservationIds = listOf("spotify_api:$trackId"),
        identityBasis = TrackIdentityBasis.PROVIDER_TRACK_ID,
        evidenceQuality = MemoryEvidenceQuality.SUPPORTED_IDENTITY,
        historyMayBeIncomplete = false,
        appearances = emptyList()
    )
}
