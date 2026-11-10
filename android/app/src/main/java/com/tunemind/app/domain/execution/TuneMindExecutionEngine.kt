package com.tunemind.app.domain.execution

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class OsExecutionCommand(
    val intent: String, // "OS_MEDIA_NEXT_AND_DEEP_LINK", "OS_MEDIA_PAUSE", "NONE"
    val target_player: String, // e.g., "com.spotify.music"
    val search_query: String,
    val suggested_bpm_target: Int?
)

data class ReasoningResponse(
    val calculated_satiety_percentage: Int,
    val flow_state_analysis: String,
    val voice_agent_tts: String,
    val os_execution_command: OsExecutionCommand,
    val database_tags: List<String> = emptyList(),
    val voice_agent_audio_base64: String? = null
)

class TuneMindExecutionEngine(private val context: Context) : TextToSpeech.OnInitListener {
    private val TAG = "TuneMindExecution"
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsInitialized = true
        } else {
            Log.e(TAG, "TTS Initialization failed!")
        }
    }

    fun executeBackendCommand(response: ReasoningResponse) {
        Log.d(TAG, "Executing AI Command: ${response.os_execution_command.intent}")

        // 1. Play ElevenLabs Audio (or fallback to native TTS if Base64 audio is missing)
        if (!response.voice_agent_audio_base64.isNullOrEmpty()) {
            Log.d(TAG, "Playing ElevenLabs hyper-realistic voice audio...")
            playBase64Audio(response.voice_agent_audio_base64)
        } else if (response.voice_agent_tts.isNotEmpty()) {
            // Fallback to native TTS if ElevenLabs failed or key is missing
            Log.d(TAG, "No Base64 Audio. Fallback to native TTS...")
            if (isTtsInitialized) {
                tts?.speak(response.voice_agent_tts, TextToSpeech.QUEUE_FLUSH, null, "TuneMindSpeech")
            }
        }

        // 2. Handle OS Media Control
        when (response.os_execution_command.intent) {
            "OS_MEDIA_PAUSE" -> {
                pauseCurrentMedia()
            }
            "OS_MEDIA_NEXT_AND_DEEP_LINK" -> {
                pauseCurrentMedia()
                launchTargetPlayer(
                    targetPackage = response.os_execution_command.target_player,
                    searchQuery = response.os_execution_command.search_query
                )
            }
            "NONE" -> {
                Log.d(TAG, "No OS action required. Satiety normal.")
            }
        }
    }

    private fun playBase64Audio(base64Str: String) {
        try {
            val audioBytes = Base64.decode(base64Str, Base64.DEFAULT)
            // Write to a temporary file
            val tempFile = File.createTempFile("elevenlabs_response_", ".mp3", context.cacheDir)
            FileOutputStream(tempFile).use { fos ->
                fos.write(audioBytes)
                fos.flush()
            }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    tempFile.delete()
                    mediaPlayer = null
                    Log.d(TAG, "Voice response playback finished.")
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    mp.release()
                    tempFile.delete()
                    mediaPlayer = null
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play Base64 audio", e)
        }
    }

    private fun pauseCurrentMedia() {
        // Sends a global media button pause command to the OS
        val pauseIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            val keyEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
        }
        context.sendOrderedBroadcast(pauseIntent, null)
        Log.d(TAG, "Broadcasted Global Pause Command")
    }

    private fun launchTargetPlayer(targetPackage: String, searchQuery: String) {
        try {
            var intent: Intent? = null
            
            // Handle Spotify Deep Linking natively
            if (targetPackage.contains("spotify")) {
                val encodedQuery = Uri.encode(searchQuery)
                val uri = "spotify:search:$encodedQuery"
                intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    setPackage(targetPackage)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            } else {
                // Fallback: Just open the app if deep linking isn't supported (Boomplay/Audiomack)
                intent = context.packageManager.getLaunchIntentForPackage(targetPackage)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }

            if (intent != null) {
                context.startActivity(intent)
                Log.d(TAG, "Launched target player: $targetPackage with query: $searchQuery")
            } else {
                Log.e(TAG, "Target player not installed: $targetPackage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch player", e)
        }
    }

    fun shutdown() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaPlayer", e)
        }
        mediaPlayer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS", e)
        }
        tts = null
    }
}
