package com.tunemind.app.data.ingestion

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory live Spotify playback state (for both music tracks and podcast episodes).
 * Stored only in reactive state, never spammed repeatedly into play history.
 */
data class SpotifyLiveTrackState(
    val id: String,
    val title: String,
    val artistOrShow: String,
    val albumOrSubtitle: String,
    val spotifyUri: String,
    val albumArtUrl: String?,
    val durationMs: Long,
    val progressMs: Long,
    val isPlaying: Boolean,
    val isPodcast: Boolean,
    val contextUri: String?,
    val updatedAtMs: Long
)

data class SpotifyPlaylistTrackEntry(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val spotifyUri: String,
    val durationMs: Long,
    val albumArtUrl: String?
)

@Entity(
    tableName = "spotify_liked_tracks",
    indices = [Index(value = ["added_at_epoch_ms"], orders = [Index.Order.DESC])]
)
data class SpotifyLikedTrackEntity(
    @PrimaryKey
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

    @ColumnInfo(name = "added_at_iso")
    val addedAtIso: String = "",

    @ColumnInfo(name = "added_at_epoch_ms")
    val addedAtEpochMs: Long = 0L,

    @ColumnInfo(name = "synced_at_epoch_ms")
    val syncedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "spotify_playlists",
    indices = [Index(value = ["synced_at_epoch_ms"], orders = [Index.Order.DESC])]
)
data class SpotifyPlaylistEntity(
    @PrimaryKey
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,

    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "owner_name")
    val ownerName: String = "",

    @ColumnInfo(name = "image_url")
    val imageUrl: String? = null,

    @ColumnInfo(name = "track_count")
    val trackCount: Int = 0,

    @ColumnInfo(name = "tracks_json")
    val tracksJson: String = "[]",

    @ColumnInfo(name = "synced_at_epoch_ms")
    val syncedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun decodeTracks(): List<SpotifyPlaylistTrackEntry> {
        if (tracksJson.isBlank() || tracksJson == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(tracksJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val tTitle = obj.optString("title", "").trim()
                    if (tTitle.isBlank()) continue
                    add(
                        SpotifyPlaylistTrackEntry(
                            trackId = obj.optString("trackId", ""),
                            title = tTitle,
                            artist = obj.optString("artist", "Unknown Artist"),
                            album = obj.optString("album", ""),
                            spotifyUri = obj.optString("spotifyUri", ""),
                            durationMs = obj.optLong("durationMs", 0L),
                            albumArtUrl = obj.optString("albumArtUrl", "").ifBlank { null }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

@Entity(
    tableName = "spotify_podcasts",
    indices = [
        Index(value = ["added_at_epoch_ms"], orders = [Index.Order.DESC]),
        Index(value = ["show_id"])
    ]
)
data class SpotifyPodcastEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "show_id")
    val showId: String,

    @ColumnInfo(name = "show_name")
    val showName: String,

    @ColumnInfo(name = "publisher")
    val publisher: String = "",

    @ColumnInfo(name = "episode_title")
    val episodeTitle: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "image_url")
    val imageUrl: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "resume_position_ms")
    val resumePositionMs: Long = 0L,

    @ColumnInfo(name = "fully_played")
    val fullyPlayed: Boolean = false,

    @ColumnInfo(name = "release_date")
    val releaseDate: String = "",

    @ColumnInfo(name = "total_episodes")
    val totalEpisodes: Int = 0,

    @ColumnInfo(name = "is_show_summary")
    val isShowSummary: Boolean = false,

    @ColumnInfo(name = "added_at_epoch_ms")
    val addedAtEpochMs: Long = 0L
)

@Dao
interface SpotifyLibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLikedTracks(items: List<SpotifyLikedTrackEntity>)

    @Query("SELECT * FROM spotify_liked_tracks ORDER BY added_at_epoch_ms DESC LIMIT :limit")
    fun observeLikedTracks(limit: Int = 150): Flow<List<SpotifyLikedTrackEntity>>

    @Query("DELETE FROM spotify_liked_tracks")
    suspend fun clearLikedTracks()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylists(items: List<SpotifyPlaylistEntity>)

    @Query("SELECT * FROM spotify_playlists")
    suspend fun getAllPlaylistsSnapshot(): List<SpotifyPlaylistEntity>

    @Query("SELECT * FROM spotify_playlists ORDER BY synced_at_epoch_ms DESC LIMIT :limit")
    fun observePlaylists(limit: Int = 60): Flow<List<SpotifyPlaylistEntity>>

    @Query("UPDATE spotify_playlists SET tracks_json = :tracksJson, track_count = CASE WHEN :trackCount > track_count THEN :trackCount ELSE track_count END, synced_at_epoch_ms = :syncedAtMs WHERE playlist_id = :playlistId")
    suspend fun updatePlaylistTracks(
        playlistId: String,
        tracksJson: String,
        trackCount: Int,
        syncedAtMs: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM spotify_playlists")
    suspend fun clearPlaylists()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPodcasts(items: List<SpotifyPodcastEntity>)

    @Query("SELECT * FROM spotify_podcasts ORDER BY is_show_summary ASC, added_at_epoch_ms DESC LIMIT :limit")
    fun observePodcasts(limit: Int = 100): Flow<List<SpotifyPodcastEntity>>

    @Query("DELETE FROM spotify_podcasts")
    suspend fun clearPodcasts()
}

fun encodePlaylistTracksJson(tracks: List<SpotifyPlaylistTrackEntry>): String {
    val arr = JSONArray()
    for (t in tracks) {
        arr.put(
            JSONObject().apply {
                put("trackId", t.trackId)
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("spotifyUri", t.spotifyUri)
                put("durationMs", t.durationMs)
                put("albumArtUrl", t.albumArtUrl ?: "")
            }
        )
    }
    return arr.toString()
}
