package com.tunemind.app.domain.provider

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for Music Provider summaries.
 */
@Dao
interface MusicProviderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(provider: MusicProviderEntity)

    @Query("SELECT * FROM music_provider_summaries ORDER BY lastObservedAt DESC")
    fun observeAll(): Flow<List<MusicProviderEntity>>

    @Query("SELECT * FROM music_provider_summaries WHERE packageName = :pkg LIMIT 1")
    suspend fun getByPackage(pkg: String): MusicProviderEntity?

    @Query("SELECT COUNT(*) FROM music_provider_summaries")
    suspend fun getCount(): Int
}
