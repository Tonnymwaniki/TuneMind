package com.tunemind.app.domain.audio

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Observed audio device connection session (wired headphones, bluetooth earbuds, speakers, etc.).
 */
@Entity(
    tableName = "audio_device_sessions",
    indices = [
        Index("deviceType"),
        Index("connectedAt")
    ]
)
data class AudioDeviceSessionEntity(
    @PrimaryKey val deviceSessionId: String,
    val episodeId: String? = null,
    val deviceType: String,
    val deviceName: String? = null,
    val connectionType: String, // "BLUETOOTH", "WIRED", "USB", "BUILTIN_SPEAKER", "UNKNOWN"
    val connectedAt: Long = System.currentTimeMillis(),
    val disconnectedAt: Long? = null,
    val identificationConfidence: String = "OBSERVED_HARDWARE"
)
