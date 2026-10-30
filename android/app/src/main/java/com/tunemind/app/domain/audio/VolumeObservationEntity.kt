package com.tunemind.app.domain.audio

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Observed media volume index and calculated hearing exposure estimation.
 */
@Entity(
    tableName = "volume_observations",
    indices = [
        Index("occurredAt")
    ]
)
data class VolumeObservationEntity(
    @PrimaryKey val volumeObservationId: String,
    val deviceSessionId: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val streamType: String = "STREAM_MUSIC",
    val volumeIndex: Int,
    val maximumVolumeIndex: Int,
    val outputDeviceType: String? = null,
    val estimatedExposureDb: Float? = null,
    val measurementMethod: String = "UNRESTRICTED_MEDIA_VOLUME_INDEX"
)
