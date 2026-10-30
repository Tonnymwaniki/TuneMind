package com.tunemind.app.domain.audio

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for Audio Device sessions and Volume observations.
 */
@Dao
interface AudioDeviceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeviceSession(session: AudioDeviceSessionEntity)

    @Query("UPDATE audio_device_sessions SET disconnectedAt = :disconnectedAt WHERE deviceSessionId = :sessionId")
    suspend fun closeDeviceSession(sessionId: String, disconnectedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM audio_device_sessions ORDER BY connectedAt DESC LIMIT :limit")
    fun observeRecentSessions(limit: Int = 50): Flow<List<AudioDeviceSessionEntity>>

    @Query("SELECT * FROM audio_device_sessions WHERE disconnectedAt IS NULL ORDER BY connectedAt DESC")
    suspend fun getActiveSessions(): List<AudioDeviceSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVolumeObservation(obs: VolumeObservationEntity)

    @Query("SELECT * FROM volume_observations ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecentVolume(limit: Int = 100): Flow<List<VolumeObservationEntity>>

    @Query("SELECT AVG(volumeIndex * 1.0 / maximumVolumeIndex) FROM volume_observations WHERE occurredAt >= :sinceMs")
    suspend fun getAverageVolumeRatioSince(sinceMs: Long): Double?
}
