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

@Entity(
    tableName = "spotify_track_visual_cache",
    indices = [
        Index(value = ["spotify_uri"]),
        Index(value = ["updated_at_ms"])
    ]
)
data class TrackVisualCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "track_key")
    val trackKey: String,
    @ColumnInfo(name = "spotify_track_id")
    val spotifyTrackId: String? = null,
    @ColumnInfo(name = "spotify_uri")
    val spotifyUri: String? = null,
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
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "spotify_artist_profile_cache",
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

@Dao
interface SpotifyVisualDao {
    @Query("SELECT * FROM spotify_track_visual_cache")
    fun observeAllTrackVisuals(): Flow<List<TrackVisualCacheEntity>>

    @Query("SELECT * FROM spotify_artist_profile_cache")
    fun observeAllArtistProfiles(): Flow<List<ArtistProfileCacheEntity>>

    @Query("SELECT * FROM spotify_track_visual_cache")
    suspend fun getTrackVisualSnapshot(): List<TrackVisualCacheEntity>

    @Query("SELECT * FROM spotify_artist_profile_cache")
    suspend fun getArtistProfileSnapshot(): List<ArtistProfileCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackVisual(item: TrackVisualCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackVisuals(items: List<TrackVisualCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistProfile(item: ArtistProfileCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistProfiles(items: List<ArtistProfileCacheEntity>)

    @Query("DELETE FROM spotify_track_visual_cache")
    suspend fun clearTrackVisuals()

    @Query("DELETE FROM spotify_artist_profile_cache")
    suspend fun clearArtistProfiles()
}

data class ResolvedTrackVisuals(
    val trackKey: String = "",
    val spotifyTrackId: String? = null,
    val spotifyUri: String? = null,
    val albumName: String? = null,
    val albumArtUrl: String? = null,
    val artistImageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val popularity: Int = 0
)

data class SpotifyUserProfileSnapshot(
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val country: String = "",
    val product: String = "",
    val avatarUrl: String = "",
    val followersCount: Long = 0L,
    val syncedAtMs: Long = 0L
)

data class SpotifyArtistInsight(
    val artistId: String = "",
    val name: String = "",
    val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val popularity: Int = 0,
    val followersTotal: Long = 0L
)

data class SpotifyGenreWeight(
    val genre: String,
    val count: Int,
    val sharePercent: Int
)

data class SpotifyTasteShiftSummary(
    val topGenres: List<SpotifyGenreWeight> = emptyList(),
    val risingArtists: List<SpotifyArtistInsight> = emptyList(),
    val consistentArtists: List<SpotifyArtistInsight> = emptyList(),
    val emergingGenres: List<String> = emptyList(),
    val shortTermTopArtists: List<SpotifyArtistInsight> = emptyList(),
    val mediumTermTopArtists: List<SpotifyArtistInsight> = emptyList(),
    val longTermTopArtists: List<SpotifyArtistInsight> = emptyList()
)

data class SpotifyNewReleaseItem(
    val albumId: String,
    val title: String,
    val artistName: String,
    val releaseDate: String,
    val albumType: String,
    val imageUrl: String?,
    val spotifyUri: String
)

data class SpotifyDeviceItem(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val volumePercent: Int?
)

data class SpotifyActionResult(
    val success: Boolean,
    val message: String,
    val needsScopeUpgrade: Boolean = false
)
