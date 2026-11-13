package com.tunemind.app.data.ingestion

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Local-First Room DAO for [SpotifyListeningHistoryEntity].
 *
 * Ensures the TuneMind UI and AI Agent read listening history directly from local SQLite
 * with zero network wait times, eliminating infinite loading spinners or HTTP 500 cascades.
 */
@Dao
interface SpotifyListeningHistoryDao {

    /**
     * Upserts a batch of recently played or currently playing tracks fetched from the Spotify Web API.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayHistory(items: List<SpotifyListeningHistoryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSingle(item: SpotifyListeningHistoryEntity): Long

    /**
     * Reactive stream of raw chronologically ordered play history for the UI.
     */
    @Query("SELECT * FROM spotify_listening_history ORDER BY played_at_epoch_ms DESC LIMIT :limit")
    fun observeRecentHistory(limit: Int = 100): Flow<List<SpotifyListeningHistoryEntity>>

    /**
     * Reactive stream of aggregated per-track statistics (play count, first/last played timestamps)
     * computed in SQLite for instant Dopamine Satiety & Rhythm DNA rendering.
     */
    @Query(
        """
        SELECT 
            track_id,
            spotify_uri,
            track_title,
            artist_name,
            album_name,
            album_art_url,
            duration_ms,
            COUNT(*) AS play_count,
            MIN(played_at_epoch_ms) AS first_played_epoch_ms,
            MAX(played_at_epoch_ms) AS last_played_epoch_ms
        FROM spotify_listening_history
        GROUP BY track_id
        ORDER BY last_played_epoch_ms DESC
        LIMIT :limit
        """
    )
    fun observeAggregatedTracks(limit: Int = 60): Flow<List<SpotifyTrackAggregate>>

    /**
     * Synchronous snapshot of aggregated tracks for the Voice Agent & Claude telemetry payload.
     */
    @Query(
        """
        SELECT 
            track_id,
            spotify_uri,
            track_title,
            artist_name,
            album_name,
            album_art_url,
            duration_ms,
            COUNT(*) AS play_count,
            MIN(played_at_epoch_ms) AS first_played_epoch_ms,
            MAX(played_at_epoch_ms) AS last_played_epoch_ms
        FROM spotify_listening_history
        GROUP BY track_id
        ORDER BY last_played_epoch_ms DESC
        LIMIT :limit
        """
    )
    suspend fun getAggregatedTracksSnapshot(limit: Int = 60): List<SpotifyTrackAggregate>

    /**
     * Returns the most recent `played_at_epoch_ms` cursor stored locally so the WorkManager
     * sync engine can pass `after=<timestamp>` to `/v1/me/player/recently-played`.
     */
    @Query(
        """
        SELECT MAX(played_at_epoch_ms) 
        FROM spotify_listening_history 
        WHERE ingestion_source = 'RECENTLY_PLAYED'
        """
    )
    suspend fun getLatestPlayedEpochMs(): Long?

    @Query("SELECT * FROM spotify_listening_history ORDER BY played_at_epoch_ms DESC LIMIT :limit")
    suspend fun getRecentHistorySnapshot(limit: Int = 100): List<SpotifyListeningHistoryEntity>

    @Query("SELECT COUNT(*) FROM spotify_listening_history")
    fun observeTotalPlayRecordsCount(): Flow<Int>

    @Query("DELETE FROM spotify_listening_history WHERE played_at_epoch_ms < :cutoffEpochMs")
    suspend fun pruneHistoryOlderThan(cutoffEpochMs: Long)

    @Query("DELETE FROM spotify_listening_history")
    suspend fun clearAll()
}
