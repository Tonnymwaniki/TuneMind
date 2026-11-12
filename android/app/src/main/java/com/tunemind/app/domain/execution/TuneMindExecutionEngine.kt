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
    val suggested_bpm_target: Int?,
    val track_title: String? = null,
    val track_artist: String? = null,
    val spotify_uri: String? = null
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
        Log.d(TAG, "Executing AI Command: ${response.os_execution_command.intent} with query: ${response.os_execution_command.search_query}")

        // 1. Immediately pause whatever background music was playing so the voice response is crystal clear
        pauseCurrentMedia()

        val textToSpeak = response.voice_agent_tts.ifBlank { "Playing ${response.os_execution_command.search_query} on Spotify." }

        val onSpeechFinished = {
            // 2. Once speaking completes, launch the target player and auto-play the synced song!
            if (response.os_execution_command.intent == "OS_MEDIA_NEXT_AND_DEEP_LINK") {
                launchTargetPlayer(
                    targetPackage = response.os_execution_command.target_player,
                    searchQuery = response.os_execution_command.search_query,
                    trackTitle = response.os_execution_command.track_title,
                    trackArtist = response.os_execution_command.track_artist,
                    spotifyUri = response.os_execution_command.spotify_uri
                )
            }
        }

        // Play ElevenLabs Audio or fallback to native TTS
        if (!response.voice_agent_audio_base64.isNullOrEmpty()) {
            Log.d(TAG, "Playing ElevenLabs hyper-realistic voice audio...")
            playBase64Audio(response.voice_agent_audio_base64, onCompletion = onSpeechFinished)
        } else if (textToSpeak.isNotEmpty()) {
            Log.d(TAG, "Speaking voice response via TTS: $textToSpeak")
            speakWithListener(textToSpeak, onCompletion = onSpeechFinished)
        } else {
            onSpeechFinished()
        }
    }

    private fun speakWithListener(text: String, onCompletion: () -> Unit) {
        val ttsInstance = tts
        if (ttsInstance == null) {
            onCompletion()
            return
        }

        val utteranceId = "TuneMindSpeech_" + System.currentTimeMillis()
        ttsInstance.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.d(TAG, "TTS Speech started: $id")
            }

            override fun onDone(id: String?) {
                Log.d(TAG, "TTS Speech finished: $id")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onCompletion()
                }
            }

            override fun onError(id: String?) {
                Log.e(TAG, "TTS Speech error on: $id")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onCompletion()
                }
            }
        })

        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val result = ttsInstance.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS speak failed immediately (result=$result). Proceeding with launch.")
            onCompletion()
        }
    }

    private fun playBase64Audio(base64Str: String, onCompletion: () -> Unit) {
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
                    onCompletion()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    mp.release()
                    tempFile.delete()
                    mediaPlayer = null
                    onCompletion()
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play Base64 audio", e)
            onCompletion()
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

    private fun launchTargetPlayer(
        targetPackage: String,
        searchQuery: String,
        trackTitle: String? = null,
        trackArtist: String? = null,
        spotifyUri: String? = null
    ) {
        try {
            Log.d(TAG, "Auto-playing synced track in $targetPackage with query: '$searchQuery' title: '$trackTitle' artist: '$trackArtist' uri: '$spotifyUri'")

            val cleanQuery = searchQuery.trim()
            val resolvedTitle = trackTitle?.trim()?.ifBlank { null } ?: run {
                if (cleanQuery.contains(" - ")) cleanQuery.substringBefore(" - ").trim()
                else if (cleanQuery.contains(" by ", ignoreCase = true)) cleanQuery.substringBefore(" by ", ignoreCase = true).trim()
                else cleanQuery
            }
            val resolvedArtist = trackArtist?.trim()?.ifBlank { null } ?: run {
                if (cleanQuery.contains(" - ")) cleanQuery.substringAfter(" - ").trim()
                else if (cleanQuery.contains(" by ", ignoreCase = true)) cleanQuery.substringAfter(" by ", ignoreCase = true).trim()
                else ""
            }

            val targetTrack = com.tunemind.app.domain.recommendation.RecommendedTrack(
                id = spotifyUri ?: cleanQuery,
                title = resolvedTitle,
                artist = resolvedArtist,
                reason = "Autonomous neuro-acoustic flow match",
                confidence = 0.98f
            )

            // Direct track deep link if available, otherwise search deep link
            val directDeepLink = if (!spotifyUri.isNullOrBlank()) {
                spotifyUri
            } else if (targetPackage.contains("spotify")) {
                "spotify:search:${Uri.encode(cleanQuery)}"
            } else null

            com.tunemind.app.provider.MusicPlayerAutoLauncher.launchWithAutoPlay(
                context = context,
                providerId = if (targetPackage.contains("spotify")) "spotify" else targetPackage,
                displayName = if (targetPackage.contains("spotify")) "Spotify" else "Target Player",
                packageName = targetPackage,
                track = targetTrack,
                query = cleanQuery,
                deepLinkUri = directDeepLink
            )
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
