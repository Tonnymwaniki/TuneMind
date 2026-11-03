package com.tunemind.app.domain.recommendation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiRecommendationDao {

    @Query("SELECT * FROM ai_recommendation_crate WHERE isDismissed = 0 ORDER BY recommendedAtMs DESC")
    fun observeAll(): Flow<List<AiRecommendationEntity>>

    @Query("SELECT * FROM ai_recommendation_crate WHERE isDismissed = 0 ORDER BY recommendedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<AiRecommendationEntity>>

    @Query("SELECT * FROM ai_recommendation_crate WHERE isSaved = 1 AND isDismissed = 0 ORDER BY recommendedAtMs DESC")
    fun observeSaved(): Flow<List<AiRecommendationEntity>>

    @Query("SELECT * FROM ai_recommendation_crate WHERE timeSlotKey = :slotKey AND isDismissed = 0 ORDER BY recommendedAtMs DESC LIMIT :limit")
    fun observeByTimeSlot(slotKey: String, limit: Int = 20): Flow<List<AiRecommendationEntity>>

    @Query("SELECT DISTINCT title FROM ai_recommendation_crate")
    suspend fun getAllKnownTitles(): List<String>

    @Query("SELECT DISTINCT (title || ' by ' || artist) FROM ai_recommendation_crate WHERE wasPlayed = 1 OR isSaved = 1 ORDER BY recommendedAtMs DESC LIMIT 15")
    suspend fun getPositiveFeedbackSignals(): List<String>

    @Query("SELECT DISTINCT (title || ' by ' || artist) FROM ai_recommendation_crate WHERE isDismissed = 1 ORDER BY recommendedAtMs DESC LIMIT 15")
    suspend fun getDismissedSignals(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AiRecommendationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: AiRecommendationEntity)

    @Query("UPDATE ai_recommendation_crate SET isSaved = :saved, lastInteractedAtMs = :now WHERE id = :id")
    suspend fun updateSaved(id: String, saved: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE ai_recommendation_crate SET wasPlayed = 1, playCount = playCount + 1, lastInteractedAtMs = :now WHERE id = :id")
    suspend fun markPlayed(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE ai_recommendation_crate SET isDismissed = 1, lastInteractedAtMs = :now WHERE id = :id")
    suspend fun dismiss(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE ai_recommendation_crate SET isDismissed = 0, lastInteractedAtMs = :now WHERE id = :id")
    suspend fun restoreDismissed(id: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM ai_recommendation_crate WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM ai_recommendation_crate WHERE isSaved = 0 AND recommendedAtMs < :cutoffMs")
    suspend fun pruneOldUnsaved(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM ai_recommendation_crate WHERE wasPlayed = 1")
    fun observePlayedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM ai_recommendation_crate WHERE isSaved = 1 AND isDismissed = 0")
    fun observeSavedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM ai_recommendation_crate WHERE isDismissed = 1")
    fun observeDismissedCount(): Flow<Int>
}
