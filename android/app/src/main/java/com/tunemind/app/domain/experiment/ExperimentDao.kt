package com.tunemind.app.domain.experiment

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ExperimentDao {
    @Query("SELECT * FROM experiment ORDER BY startDateMs DESC LIMIT 1")
    fun observeActive(): Flow<ExperimentEntity?>

    @Query("SELECT * FROM experiment ORDER BY startDateMs DESC LIMIT 1")
    suspend fun getActive(): ExperimentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(experiment: ExperimentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: ExperimentEventEntity)

    @Query("SELECT COUNT(*) FROM experiment_event WHERE experimentId = :experimentId")
    suspend fun eventCount(experimentId: String): Int

    @Query("SELECT DISTINCT sourcePackage FROM experiment_event WHERE experimentId = :experimentId AND sourcePackage != ''")
    suspend fun observedSources(experimentId: String): List<String>

    @Query("SELECT MAX(eventTimeMs) FROM experiment_event WHERE experimentId = :experimentId")
    suspend fun lastEventTime(experimentId: String): Long?

    @Query("SELECT eventTimeMs FROM experiment_event WHERE experimentId = :experimentId AND eventTimeMs > 0 ORDER BY eventTimeMs ASC")
    suspend fun getEventTimestamps(experimentId: String): List<Long>

    @Query("SELECT * FROM experiment_event WHERE experimentId = :experimentId ORDER BY eventTimeMs ASC")
    suspend fun getEvents(experimentId: String): List<ExperimentEventEntity>

    @Query("SELECT * FROM experiment_event WHERE experimentId = :experimentId AND sourcePackage = :source ORDER BY eventTimeMs ASC")
    suspend fun getEventsBySource(experimentId: String, source: String): List<ExperimentEventEntity>

    @Query("SELECT COUNT(*) FROM experiment_event WHERE experimentId = :experimentId AND sourcePackage = :source")
    suspend fun eventCountBySource(experimentId: String, source: String): Int

    @Query("SELECT MAX(eventTimeMs) FROM experiment_event WHERE experimentId = :experimentId AND sourcePackage = :source")
    suspend fun lastEventTimeBySource(experimentId: String, source: String): Long?

    @Query("DELETE FROM experiment_event WHERE experimentId = :experimentId")
    suspend fun clearEvents(experimentId: String)
}
