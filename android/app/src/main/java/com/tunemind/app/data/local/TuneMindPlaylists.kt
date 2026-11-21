package com.tunemind.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.PrimaryKey
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "tunemind_playlists",
    indices = [Index(value = ["updated_at_ms"], orders = [Index.Order.DESC])]
)
data class TuneMindPlaylistEntity(
    @PrimaryKey
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "mood_or_audience")
    val moodOrAudience: String = "",

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tunemind_playlist_tracks",
    indices = [
        Index(value = ["playlist_id"]),
        Index(value = ["added_at_ms"])
    ]
)
data class TuneMindPlaylistTrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "entry_id")
    val entryId: String,

    @ColumnInfo(name = "playlist_id")
    val playlistId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String = "",

    @ColumnInfo(name = "external_uri")
    val externalUri: String = "",

    @ColumnInfo(name = "track_id")
    val trackId: String = "",

    @ColumnInfo(name = "source_player")
    val sourcePlayer: String = "unknown_player",

    @ColumnInfo(name = "position_index")
    val positionIndex: Int = 0,

    @ColumnInfo(name = "added_at_ms")
    val addedAtMs: Long = System.currentTimeMillis()
)

data class TuneMindPlaylistWithTracks(
    val playlist: TuneMindPlaylistEntity,
    val tracks: List<TuneMindPlaylistTrackEntity>
)

@Dao
interface TuneMindPlaylistDao {
    @Query("SELECT * FROM tunemind_playlists ORDER BY updated_at_ms DESC")
    fun observeAllPlaylists(): Flow<List<TuneMindPlaylistEntity>>

    @Query("SELECT * FROM tunemind_playlist_tracks ORDER BY position_index ASC, added_at_ms ASC")
    fun observeAllPlaylistTracks(): Flow<List<TuneMindPlaylistTrackEntity>>

    @Query("SELECT * FROM tunemind_playlists ORDER BY updated_at_ms DESC")
    suspend fun getAllPlaylistsOnce(): List<TuneMindPlaylistEntity>

    @Query("SELECT * FROM tunemind_playlist_tracks ORDER BY added_at_ms DESC")
    suspend fun getAllPlaylistTracksOnce(): List<TuneMindPlaylistTrackEntity>

    @Query("SELECT * FROM tunemind_playlist_tracks WHERE playlist_id = :playlistId ORDER BY position_index ASC, added_at_ms ASC")
    suspend fun getTracksForPlaylistOnce(playlistId: String): List<TuneMindPlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: TuneMindPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistTracks(tracks: List<TuneMindPlaylistTrackEntity>)

    @Query("UPDATE tunemind_playlists SET name = :newName, updated_at_ms = :updatedAtMs WHERE playlist_id = :playlistId")
    suspend fun renamePlaylist(playlistId: String, newName: String, updatedAtMs: Long = System.currentTimeMillis())

    @Query("UPDATE tunemind_playlists SET updated_at_ms = :updatedAtMs WHERE playlist_id = :playlistId")
    suspend fun touchPlaylist(playlistId: String, updatedAtMs: Long = System.currentTimeMillis())

    @Query("DELETE FROM tunemind_playlist_tracks WHERE entry_id = :entryId")
    suspend fun deleteTrackByEntryId(entryId: String)

    @Query("DELETE FROM tunemind_playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun deleteTracksByPlaylistId(playlistId: String)

    @Query("DELETE FROM tunemind_playlists WHERE playlist_id = :playlistId")
    suspend fun deletePlaylistById(playlistId: String)
}
