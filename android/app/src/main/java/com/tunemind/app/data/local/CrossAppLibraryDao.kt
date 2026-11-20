package com.tunemind.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CrossAppLibraryDao {

    @Query("SELECT * FROM cross_app_track_library ORDER BY last_played_at_ms DESC")
    fun observeAllLibraryTracks(): Flow<List<CrossAppTrackEntity>>

    @Query("SELECT * FROM cross_app_track_library WHERE is_liked = 1 ORDER BY last_played_at_ms DESC")
    fun observeLikedLibraryTracks(): Flow<List<CrossAppTrackEntity>>

    @Query("SELECT * FROM cross_app_play_log ORDER BY played_at_ms DESC LIMIT :limit")
    fun observePlayLogs(limit: Int = 500): Flow<List<CrossAppPlayLogEntity>>

    @Query("SELECT * FROM cross_app_track_library ORDER BY last_played_at_ms DESC")
    suspend fun getAllLibraryTracksOnce(): List<CrossAppTrackEntity>

    @Query("SELECT * FROM cross_app_track_library WHERE canonical_track_key = :canonicalTrackKey LIMIT 1")
    suspend fun getTrackByKey(canonicalTrackKey: String): CrossAppTrackEntity?

    @Query("SELECT * FROM cross_app_play_log ORDER BY played_at_ms DESC LIMIT :limit")
    suspend fun getPlayLogsOnce(limit: Int = 500): List<CrossAppPlayLogEntity>

    @Query("SELECT COUNT(*) FROM cross_app_track_library")
    suspend fun getTotalLibraryTrackCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibraryTrack(item: CrossAppTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibraryTracks(items: List<CrossAppTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlayLog(entry: CrossAppPlayLogEntity)

    @Query("UPDATE cross_app_track_library SET is_liked = :liked, updated_at_ms = :updatedAtMs WHERE canonical_track_key = :canonicalTrackKey")
    suspend fun setTrackLiked(canonicalTrackKey: String, liked: Boolean, updatedAtMs: Long = System.currentTimeMillis())

    @Query("UPDATE cross_app_track_library SET is_in_vault = :inVault, updated_at_ms = :updatedAtMs WHERE canonical_track_key = :canonicalTrackKey")
    suspend fun setTrackVaultState(canonicalTrackKey: String, inVault: Boolean, updatedAtMs: Long = System.currentTimeMillis())

    @Query("UPDATE cross_app_track_library SET play_count = :playCount, updated_at_ms = :updatedAtMs WHERE canonical_track_key = :canonicalTrackKey")
    suspend fun updateVerifiedPlayCount(canonicalTrackKey: String, playCount: Int, updatedAtMs: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM cross_app_play_log WHERE canonical_track_key = :canonicalTrackKey AND was_skipped = 0")
    suspend fun getPlayLogCountForTrack(canonicalTrackKey: String): Int

    @Query(
        """
        UPDATE cross_app_track_library SET
            album_art_url = COALESCE(:albumArtUrl, album_art_url),
            preview_url = COALESCE(:previewUrl, preview_url),
            audius_stream_url = COALESCE(:audiusStreamUrl, audius_stream_url),
            ytm_video_id = COALESCE(:ytmVideoId, ytm_video_id),
            deezer_url = COALESCE(:deezerUrl, deezer_url),
            genre = COALESCE(:genre, genre),
            updated_at_ms = :updatedAtMs
        WHERE canonical_track_key = :canonicalTrackKey
        """
    )
    suspend fun updateTrackMediaLinks(
        canonicalTrackKey: String,
        albumArtUrl: String?,
        previewUrl: String?,
        audiusStreamUrl: String?,
        ytmVideoId: String?,
        deezerUrl: String?,
        genre: String?,
        updatedAtMs: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM cross_app_track_library")
    suspend fun clearLibraryTracks()

    @Query("DELETE FROM cross_app_play_log")
    suspend fun clearPlayLogs()

    // ── Universal Visual & Open Catalog Caches ──────────────────────────────────

    @Query("SELECT * FROM universal_track_visual_cache")
    fun observeAllTrackVisuals(): Flow<List<TrackVisualCacheEntity>>

    @Query("SELECT * FROM universal_artist_profile_cache")
    fun observeAllArtistProfiles(): Flow<List<ArtistProfileCacheEntity>>

    @Query("SELECT * FROM universal_track_visual_cache")
    suspend fun getTrackVisualSnapshot(): List<TrackVisualCacheEntity>

    @Query("SELECT * FROM universal_artist_profile_cache")
    suspend fun getArtistProfileSnapshot(): List<ArtistProfileCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackVisual(item: TrackVisualCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackVisuals(items: List<TrackVisualCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistProfile(item: ArtistProfileCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistProfiles(items: List<ArtistProfileCacheEntity>)

    @Query("DELETE FROM universal_track_visual_cache")
    suspend fun clearTrackVisuals()

    @Query("DELETE FROM universal_artist_profile_cache")
    suspend fun clearArtistProfiles()
}
