package com.tunemind.app.domain.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Observes audio device connection/disconnection and media volume changes.
 * Uses official Android AudioManager APIs.
 */
class AudioDeviceObserver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: AudioDeviceDao
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeSessionMap = mutableMapOf<Int, String>() // deviceId -> sessionId

    private val deviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                addedDevices?.forEach { handleDeviceAdded(it) }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                removedDevices?.forEach { handleDeviceRemoved(it) }
            }
        }
    } else null

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val currentVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                    val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                    recordVolumeChange(currentVol, maxVol)
                }
            }
        }
    }

    private var isRegistered = false

    fun start() {
        if (isRegistered) return
        isRegistered = true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && deviceCallback != null) {
                audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
                // Log existing devices
                audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach {
                    handleDeviceAdded(it)
                }
            }

            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            context.registerReceiver(volumeReceiver, filter)
        } catch (e: Exception) {
            Log.w("AudioDeviceObserver", "Failed to register audio device or volume observer: ${e.message}")
        }
    }

    fun stop() {
        if (!isRegistered) return
        isRegistered = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && deviceCallback != null) {
                audioManager?.unregisterAudioDeviceCallback(deviceCallback)
            }
            context.unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            Log.w("AudioDeviceObserver", "Error stopping audio device observer: ${e.message}")
        }
    }

    private fun handleDeviceAdded(device: AudioDeviceInfo) {
        if (!device.isSink) return // Only interested in output devices
        val sessionId = UUID.randomUUID().toString()
        activeSessionMap[device.id] = sessionId

        val typeLabel = resolveDeviceTypeName(device.type)
        val connType = resolveConnectionType(device.type)
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.address?.takeIf { it.isNotBlank() } ?: device.productName.toString()
        } else {
            device.productName.toString()
        }

        scope.launch {
            try {
                dao.insertDeviceSession(
                    AudioDeviceSessionEntity(
                        deviceSessionId = sessionId,
                        deviceType = typeLabel,
                        deviceName = name.takeIf { it.isNotBlank() },
                        connectionType = connType,
                        connectedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.w("AudioDeviceObserver", "Error recording device session: ${e.message}")
            }
        }
    }

    private fun handleDeviceRemoved(device: AudioDeviceInfo) {
        val sessionId = activeSessionMap.remove(device.id) ?: return
        scope.launch {
            try {
                dao.closeDeviceSession(sessionId, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w("AudioDeviceObserver", "Error closing device session: ${e.message}")
            }
        }
    }

    private fun recordVolumeChange(volumeIndex: Int, maxVolume: Int) {
        if (volumeIndex < 0) return
        val ratio = volumeIndex.toFloat() / maxOf(1, maxVolume).toFloat()
        // Defensive estimation: typical headphone safe threshold is ~70-80dB SPL; volume ratio mapping
        val estDb = 50f + (ratio * 40f) // maps 0..1 to approx 50dB..90dB

        scope.launch {
            try {
                dao.insertVolumeObservation(
                    VolumeObservationEntity(
                        volumeObservationId = UUID.randomUUID().toString(),
                        occurredAt = System.currentTimeMillis(),
                        volumeIndex = volumeIndex,
                        maximumVolumeIndex = maxVolume,
                        estimatedExposureDb = estDb,
                        measurementMethod = "STREAM_MUSIC_PROPORTION_ESTIMATE"
                    )
                )
            } catch (e: Exception) {
                Log.w("AudioDeviceObserver", "Error saving volume observation: ${e.message}")
            }
        }
    }

    companion object {
        fun resolveDeviceTypeName(type: Int): String {
            return when (type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
                else -> "Audio Output"
            }
        }

        fun resolveConnectionType(type: Int): String {
            return when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
                else -> "UNKNOWN"
            }
        }
    }
}
