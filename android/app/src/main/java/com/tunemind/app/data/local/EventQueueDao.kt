package com.tunemind.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the [EventQueueEntity] table.
 *
 * Used by EventBufferRepository for local capture, ordering, retention, and clear operations.
 */
@Dao
interface EventQueueDao {

    /** Insert a new observation into the local buffer. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventQueueEntity): Long

    /** Room assigns sequence_number via SQLite AUTOINCREMENT on accepted inserts. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventQueueEntity>): List<Long>

    /** Legacy queue query, ordered by local insertion sequence. */
    @Query("SELECT * FROM event_queue WHERE synced = 0 ORDER BY sequence_number ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 100): List<EventQueueEntity>

    /** Mark a batch of events as successfully synced by their IDs. */
    @Query("UPDATE event_queue SET synced = 1 WHERE event_id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /** Delete all synced events. Called periodically to prune old records. */
    @Query("DELETE FROM event_queue WHERE synced = 1")
    suspend fun deleteSynced()

    /** Return the count of unsynchronised events (for debug/monitoring). */
    @Query("SELECT COUNT(*) FROM event_queue WHERE synced = 0")
    suspend fun unsyncedCount(): Int

    @Query("SELECT * FROM event_queue ORDER BY sequence_number DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<EventQueueEntity>>

    @Query("SELECT * FROM event_queue ORDER BY sequence_number DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 100): List<EventQueueEntity>

    @Query("SELECT COUNT(*) FROM event_queue")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM event_queue WHERE sequence_number IN (SELECT sequence_number FROM event_queue ORDER BY sequence_number DESC LIMIT -1 OFFSET :keepCount)")
    suspend fun pruneToLimit(keepCount: Int)

    @Query("DELETE FROM event_queue")
    suspend fun clearAll()
}
