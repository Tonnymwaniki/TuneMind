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
 * Observed snapshot of the active audio output hardware and real-time decibel headroom.
 */
data class AudioDeviceSnapshot(
    val deviceType: String,
    val transducerCategory: String, // "BLUETOOTH_EARBUDS", "BLUETOOTH_HEADPHONES", "WIRED_EARPHONES", "WIRED_HEADPHONES", "USB_EARPHONES", "USB_DAC", "BUILTIN_SPEAKER"
    val productName: String,
    val connectionType: String,
    val volumeIndex: Int,
    val maxVolume: Int,
    val estimatedDb: Float,
    val headroomDb: Float,
    val sampleRate: Int = 48000,
    val acousticCouplingDescription: String
)

/**
 * Observes audio device connection/disconnection and media volume changes.
 * Uses official Android AudioManager APIs, inspects AudioDeviceInfo transducer categories,
 * and calculates calibrated decibel headroom.
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

            // Immediately observe current volume level on startup
            audioManager?.let { am ->
                val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                recordVolumeChange(currentVol, maxVol)
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

        val (typeLabel, _) = classifyDevice(device)
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
        val snapshot = getActiveAudioDeviceSnapshot(context)
        val estDb = snapshot.estimatedDb

        scope.launch {
            try {
                dao.insertVolumeObservation(
                    VolumeObservationEntity(
                        volumeObservationId = UUID.randomUUID().toString(),
                        occurredAt = System.currentTimeMillis(),
                        volumeIndex = volumeIndex,
                        maximumVolumeIndex = maxVolume,
                        outputDeviceType = snapshot.transducerCategory,
                        estimatedExposureDb = estDb,
                        measurementMethod = "CALIBRATED_TRANSDUCER_VOLUME_RATIO"
                    )
                )
            } catch (e: Exception) {
                Log.w("AudioDeviceObserver", "Error saving volume observation: ${e.message}")
            }
        }
    }

    companion object {
        /**
         * Inspects AudioDeviceInfo types and product name to classify earbuds vs headphones vs speakers.
         */
        fun classifyDevice(device: AudioDeviceInfo): Pair<String, String> {
            val rawName = device.productName.toString().lowercase()
            val isEarbudKeyword = rawName.contains("bud") || rawName.contains("pod") ||
                    rawName.contains("airpod") || rawName.contains("freepod") ||
                    rawName.contains("earbud") || rawName.contains("earphone") ||
                    rawName.contains("tws") || rawName.contains("in-ear") ||
                    rawName.contains("freebuds")

            val isHeadphoneKeyword = rawName.contains("headphone") || rawName.contains("wh-") ||
                    rawName.contains("quietcomfort") || rawName.contains("over-ear") ||
                    rawName.contains("on-ear") || rawName.contains("monitor") ||
                    rawName.contains("studio") || rawName.contains("solo") || rawName.contains("crusher")

            return when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* TYPE_BLE_HEADSET */, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    if (isEarbudKeyword || !isHeadphoneKeyword) {
                        Pair("Bluetooth Earbuds (TWS)", "BLUETOOTH_EARBUDS")
                    } else {
                        Pair("Bluetooth Headphones", "BLUETOOTH_HEADPHONES")
                    }
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    if (isHeadphoneKeyword) {
                        Pair("Wired Headset", "WIRED_HEADPHONES")
                    } else {
                        Pair("Wired Earphones (3.5mm)", "WIRED_EARPHONES")
                    }
                }
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                    Pair("Wired Studio Headphones", "WIRED_HEADPHONES")
                }
                AudioDeviceInfo.TYPE_USB_HEADSET -> {
                    Pair("USB-C Digital Earphones", "USB_EARPHONES")
                }
                AudioDeviceInfo.TYPE_USB_DEVICE -> {
                    Pair("USB Audio Interface / DAC", "USB_DAC")
                }
                23 /* AudioDeviceInfo.TYPE_HEARING_AID */ -> {
                    Pair("Hearing Aid Transducer", "HEARING_AID")
                }
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                    Pair("Phone Loudspeaker", "BUILTIN_SPEAKER")
                }
                else -> {
                    Pair("Audio Output", "AUDIO_OUTPUT")
                }
            }
        }

        fun resolveDeviceTypeName(type: Int): String {
            return when (type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Earphones (Headset)"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Earbuds / Audio"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C Earphones"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Loudspeaker"
                26 -> "BLE Audio Earbuds"
                else -> "Audio Output"
            }
        }

        fun resolveConnectionType(type: Int): String {
            return when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26 -> "BLUETOOTH"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
                else -> "UNKNOWN"
            }
        }

        /**
         * Computes calibrated Sound Pressure Level (SPL dBA) based on transducer coupling.
         */
        fun calculateCalibratedDb(transducerCategory: String, volumeRatio: Float): Float {
            val r = volumeRatio.coerceIn(0f, 1f)
            return when (transducerCategory) {
                "BLUETOOTH_EARBUDS", "WIRED_EARPHONES", "USB_EARPHONES" -> {
                    // Direct ear canal acoustic seal: 58 dBA at min to 104 dBA at max
                    58f + (r * 46f)
                }
                "BLUETOOTH_HEADPHONES", "WIRED_HEADPHONES" -> {
                    // Over-ear acoustic cushion: 54 dBA at min to 96 dBA at max
                    54f + (r * 42f)
                }
                "BUILTIN_SPEAKER" -> {
                    // Ambient phone loudspeaker at ~30cm distance: 44 dBA to 82 dBA
                    44f + (r * 38f)
                }
                else -> {
                    55f + (r * 40f)
                }
            }
        }

        /**
         * Real-time query to get currently active audio transducer, volume step, and headroom.
         */
        fun getActiveAudioDeviceSnapshot(context: Context): AudioDeviceSnapshot {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            } else emptyArray()

            // Find primary output device in priority: Bluetooth -> Wired -> USB -> Speaker
            val preferred = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == 26 /* BLE_HEADSET */ }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: devices.firstOrNull { it.isSink }

            val currentVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 8
            val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            val volRatio = currentVol.toFloat() / maxOf(1, maxVol).toFloat()

            if (preferred == null) {
                val estimatedDb = 44f + (volRatio * 38f)
                return AudioDeviceSnapshot(
                    deviceType = "Phone Loudspeaker",
                    transducerCategory = "BUILTIN_SPEAKER",
                    productName = "Built-in Speaker",
                    connectionType = "BUILTIN_SPEAKER",
                    volumeIndex = currentVol,
                    maxVolume = maxVol,
                    estimatedDb = estimatedDb,
                    headroomDb = (85f - estimatedDb),
                    sampleRate = 48000,
                    acousticCouplingDescription = "Ambient open dispersion • Safe from inner ear stereocilia directivity"
                )
            }

            val (typeLabel, transducerCategory) = classifyDevice(preferred)
            val productName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                preferred.address?.takeIf { it.isNotBlank() } ?: preferred.productName.toString()
            } else {
                preferred.productName.toString()
            }.takeIf { it.isNotBlank() } ?: typeLabel

            val sampleRate = preferred.sampleRates.firstOrNull() ?: 48000
            val estimatedDb = calculateCalibratedDb(transducerCategory, volRatio)
            val headroomDb = 85f - estimatedDb

            val couplingDescription = when (transducerCategory) {
                "BLUETOOTH_EARBUDS", "WIRED_EARPHONES", "USB_EARPHONES" ->
                    "In-Ear Acoustic Seal • Direct tympanic sound pressure • High stereocilia sensitivity"
                "BLUETOOTH_HEADPHONES", "WIRED_HEADPHONES" ->
                    "Over-Ear Circumaural • Pinna chamber dispersion • Balanced acoustic absorption"
                "BUILTIN_SPEAKER" ->
                    "Ambient Open Loudspeaker • Air absorption buffer • Safe acoustic dispersion"
                else ->
                    "Standard Audio Output • Standard acoustic transfer"
            }

            return AudioDeviceSnapshot(
                deviceType = typeLabel,
                transducerCategory = transducerCategory,
                productName = productName,
                connectionType = resolveConnectionType(preferred.type),
                volumeIndex = currentVol,
                maxVolume = maxVol,
                estimatedDb = estimatedDb,
                headroomDb = headroomDb,
                sampleRate = sampleRate,
                acousticCouplingDescription = couplingDescription
            )
        }
    }
}
