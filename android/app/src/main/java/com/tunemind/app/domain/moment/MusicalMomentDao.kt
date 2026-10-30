package com.tunemind.app.domain.moment

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for Musical Moments.
 */
@Dao
interface MusicalMomentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(moment: MusicalMomentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(moments: List<MusicalMomentEntity>)

    @Query("SELECT * FROM musical_moments ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MusicalMomentEntity>>

    @Query("SELECT * FROM musical_moments WHERE userConfirmed = 1 ORDER BY createdAt DESC")
    fun observeConfirmed(): Flow<List<MusicalMomentEntity>>

    @Query("SELECT * FROM musical_moments WHERE userConfirmed = 0 ORDER BY confidence DESC, createdAt DESC")
    fun observeCandidates(): Flow<List<MusicalMomentEntity>>

    @Query("SELECT * FROM musical_moments WHERE trackTitle = :title AND trackArtist = :artist ORDER BY createdAt DESC")
    suspend fun getForTrack(title: String, artist: String): List<MusicalMomentEntity>

    @Query("SELECT * FROM musical_moments WHERE momentId = :id LIMIT 1")
    suspend fun getById(id: String): MusicalMomentEntity?

    @Query("UPDATE musical_moments SET userConfirmed = 1, updatedAt = :now WHERE momentId = :id")
    suspend fun confirmMoment(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE musical_moments SET lastPlayedAt = :now WHERE momentId = :id")
    suspend fun markPlayed(id: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM musical_moments WHERE momentId = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM musical_moments WHERE userConfirmed = 0")
    suspend fun clearUnconfirmedCandidates()
}
