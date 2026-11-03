package com.tunemind.app.domain.recommendation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiRecommendationDao {

    @Query("SELECT * FROM ai_recommendation_crate ORDER BY recommendedAtMs DESC")
    fun observeAll(): Flow<List<AiRecommendationEntity>>

    @Query("SELECT * FROM ai_recommendation_crate ORDER BY recommendedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<AiRecommendationEntity>>

    @Query("SELECT * FROM ai_recommendation_crate WHERE isSaved = 1 ORDER BY recommendedAtMs DESC")
    fun observeSaved(): Flow<List<AiRecommendationEntity>>

    @Query("SELECT * FROM ai_recommendation_crate WHERE timeSlotKey = :slotKey ORDER BY recommendedAtMs DESC LIMIT :limit")
    fun observeByTimeSlot(slotKey: String, limit: Int = 10): Flow<List<AiRecommendationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AiRecommendationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: AiRecommendationEntity)

    @Query("UPDATE ai_recommendation_crate SET isSaved = :saved WHERE id = :id")
    suspend fun updateSaved(id: String, saved: Boolean)

    @Query("UPDATE ai_recommendation_crate SET wasPlayed = 1 WHERE id = :id")
    suspend fun markPlayed(id: String)

    @Query("DELETE FROM ai_recommendation_crate WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM ai_recommendation_crate WHERE isSaved = 0 AND recommendedAtMs < :cutoffMs")
    suspend fun pruneOldUnsaved(cutoffMs: Long)
}
