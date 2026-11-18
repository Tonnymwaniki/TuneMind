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
import kotlinx.coroutines.launch

data class AgentToolCallPayload(
    val action: String = "ADVISE_ONLY",
    val track_title: String? = null,
    val track_artist: String? = null,
    val track_id: String? = null,
    val spotify_uri: String? = null,
    val target_player: String? = null,
    val remember_default_player: Boolean? = null,
    val volume_percent: Int? = null,
    val palette_id: String? = null,
    val custom_accent_hex: String? = null,
    val theme_behavior: String? = null,
    val autonomous_dj_enabled: Boolean? = null,
    val stay_in_tunemind_enabled: Boolean? = null,
    val strict_no_app_switch_enabled: Boolean? = null,
    val never_open_external_app: Boolean? = null,
    val target_screen: String? = null,
    val expect_follow_up: Boolean = false,
    val extra_actions: List<AgentToolCallPayload> = emptyList()
)

data class OsExecutionCommand(
    val intent: String, // "OS_MEDIA_NEXT_AND_DEEP_LINK", "OS_MEDIA_PAUSE", "OS_MEDIA_RESUME", "OS_MEDIA_NEXT", "OS_MEDIA_PREV", "NONE"
    val target_player: String, // e.g., "com.spotify.music"
    val search_query: String,
    val suggested_bpm_target: Int? = null,
    val track_title: String? = null,
    val track_artist: String? = null,
    val track_id: String? = null,
    val spotify_uri: String? = null,
    val never_open_external_app: Boolean = false
)

data class ReasoningResponse(
    val calculated_satiety_percentage: Int,
    val flow_state_analysis: String,
    val voice_agent_tts: String,
    val os_execution_command: OsExecutionCommand,
    val reasoning_mode: String = "SONIC_CURATOR",
    val agent_tool_call: AgentToolCallPayload? = null,
    val database_tags: List<String> = emptyList(),
    val voice_agent_audio_base64: String? = null
)

class TuneMindExecutionEngine(private val context: Context) : TextToSpeech.OnInitListener {
    private val TAG = "TuneMindExecution"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var pendingSpeech: Pair<String, () -> Unit>? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(1.12f)
            tts?.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            isTtsInitialized = true
            Log.d(TAG, "TTS initialized successfully.")
            val pending = pendingSpeech
            if (pending != null) {
                pendingSpeech = null
                Log.d(TAG, "Speaking queued first-attempt utterance now that TTS is ready.")
                speakWithListener(pending.first, pending.second)
            }
        } else {
            Log.e(TAG, "TTS Initialization failed (status=$status)!")
            val pending = pendingSpeech
            if (pending != null) {
                pendingSpeech = null
                mainHandler.post { pending.second() }
            }
        }
    }

    fun stopSpeaking() {
        pendingSpeech = null
        try {
            tts?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun executeBackendCommand(
        response: ReasoningResponse,
        onBeforeLaunch: (() -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
        onLaunchResult: ((com.tunemind.app.provider.PlaybackLaunchResult) -> Unit)? = null
    ) {
        Log.d(TAG, "Executing AI Command: ${response.os_execution_command.intent} | Tool: ${response.agent_tool_call?.action} | Query: ${response.os_execution_command.search_query}")

        // 1. Immediately pause whatever background music was playing so the voice response is crystal clear
        pauseCurrentMedia()

        // If the tool call is SET_VOLUME_PERCENT, adjust system volume right away
        response.agent_tool_call?.volume_percent?.let { targetVol ->
            setSystemMediaVolume(targetVol)
        }

        val textToSpeak = response.voice_agent_tts.ifBlank {
            if (response.os_execution_command.intent == "OS_MEDIA_NEXT_AND_DEEP_LINK") {
                "Playing ${response.os_execution_command.search_query}."
            } else {
                "I'm here with you. Tell me what you feel like hearing."
            }
        }

        var hasExecutedPostSpeech = false
        val onSpeechFinished = {
            if (!hasExecutedPostSpeech) {
                hasExecutedPostSpeech = true
                // 2. Release assistant AudioFocus BEFORE launching player or resuming so target app can claim AudioFocus
                onBeforeLaunch?.invoke()

                when (response.os_execution_command.intent) {
                    "OS_MEDIA_NEXT_AND_DEEP_LINK" -> {
                        launchTargetPlayer(
                            targetPackage = response.os_execution_command.target_player,
                            searchQuery = response.os_execution_command.search_query,
                            trackTitle = response.os_execution_command.track_title,
                            trackArtist = response.os_execution_command.track_artist,
                            trackId = response.os_execution_command.track_id ?: response.agent_tool_call?.track_id,
                            spotifyUri = response.os_execution_command.spotify_uri,
                            neverOpenExternalApp = response.os_execution_command.never_open_external_app,
                            onLaunchResult = onLaunchResult
                        )
                        onFinished?.invoke()
                    }
                    "OS_MEDIA_PAUSE" -> {
                        pauseCurrentMedia()
                        onFinished?.invoke()
                    }
                    "OS_MEDIA_RESUME" -> {
                        resumeCurrentMedia()
                        onFinished?.invoke()
                    }
                    "OS_MEDIA_NEXT" -> {
                        skipToNextMedia()
                        onFinished?.invoke()
                    }
                    "OS_MEDIA_PREV" -> {
                        skipToPreviousMedia()
                        onFinished?.invoke()
                    }
                    else -> {
                        onFinished?.invoke()
                    }
                }
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

    /**
     * Speaks a short prompt (for example a circadian wake greeting when the user calls
     * "Hi TuneMind" and waits) and invokes [onFinished] immediately on completion.
     */
    fun speakPromptOnly(text: String, onFinished: () -> Unit) {
        if (text.isBlank()) {
            onFinished()
            return
        }
        pauseCurrentMedia()
        speakWithListener(text, onCompletion = onFinished)
    }

    private fun speakWithListener(text: String, onCompletion: () -> Unit) {
        val ttsInstance = tts
        if (ttsInstance == null) {
            onCompletion()
            return
        }

        if (!isTtsInitialized) {
            Log.d(TAG, "TTS still initializing on first attempt; queuing utterance: $text")
            pendingSpeech = Pair(text, onCompletion)
            mainHandler.postDelayed({
                val stillPending = pendingSpeech
                if (stillPending != null) {
                    Log.w(TAG, "TTS init timed out after 4.5s; proceeding with completion.")
                    pendingSpeech = null
                    stillPending.second()
                }
            }, 4500L)
            return
        }

        val utteranceId = "TuneMindSpeech_" + System.currentTimeMillis()
        ttsInstance.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.d(TAG, "TTS Speech started: $id")
            }

            override fun onDone(id: String?) {
                Log.d(TAG, "TTS Speech finished: $id")
                mainHandler.post {
                    onCompletion()
                }
            }

            override fun onError(id: String?) {
                Log.e(TAG, "TTS Speech error on: $id")
                mainHandler.post {
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

    fun pauseCurrentMedia() {
        try {
            com.tunemind.app.provider.TuneMindInAppPlayer.pause()
        } catch (_: Throwable) {}
        try {
            com.tunemind.app.provider.SpotifyAppRemoteManager.pause()
        } catch (_: Throwable) {}
        try {
            // 1. Direct MediaController transportControls.pause() via TuneMindListenerService
            com.tunemind.app.service.TuneMindListenerService.instance?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "MediaController pause notice: ${e.message}")
        }
        try {
            // 2. OS AudioManager dispatchMediaKeyEvent (DOWN + UP) for universal Android 11-14 support
            val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
        } catch (e: Exception) {
            Log.w(TAG, "AudioManager pause dispatch notice: ${e.message}")
        }
        Log.d(TAG, "Executed Multi-Layer OS Media Pause")
    }

    fun resumeCurrentMedia() {
        val inAppResumed = runCatching {
            if (com.tunemind.app.provider.TuneMindInAppPlayer.hasActiveTrack()) {
                com.tunemind.app.provider.TuneMindInAppPlayer.resume()
            } else false
        }.getOrDefault(false)
        if (inAppResumed) {
            Log.d(TAG, "Resumed native TuneMind In-App Player")
            return
        }
        val remoteResumed = runCatching {
            com.tunemind.app.provider.SpotifyAppRemoteManager.resume()
        }.getOrDefault(false)
        if (!remoteResumed) {
            try {
                com.tunemind.app.service.TuneMindListenerService.instance?.play()
            } catch (e: Exception) {
                Log.w(TAG, "MediaController play notice: ${e.message}")
            }
            try {
                val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
            } catch (e: Exception) {
                Log.w(TAG, "AudioManager play dispatch notice: ${e.message}")
            }
        }
        Log.d(TAG, "Executed Multi-Layer OS Media Resume (appRemote=$remoteResumed)")
    }

    fun skipToNextMedia() {
        val remoteSkipped = runCatching {
            com.tunemind.app.provider.SpotifyAppRemoteManager.skipNext()
        }.getOrDefault(false)
        if (!remoteSkipped) {
            try {
                com.tunemind.app.service.TuneMindListenerService.instance?.skipNext()
            } catch (e: Exception) {
                Log.w(TAG, "MediaController skipNext notice: ${e.message}")
            }
            try {
                val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
            } catch (e: Exception) {
                Log.w(TAG, "AudioManager next dispatch notice: ${e.message}")
            }
        }
    }

    fun skipToPreviousMedia() {
        val remotePrev = runCatching {
            com.tunemind.app.provider.SpotifyAppRemoteManager.skipPrev()
        }.getOrDefault(false)
        if (!remotePrev) {
            try {
                com.tunemind.app.service.TuneMindListenerService.instance?.skipPrev()
            } catch (e: Exception) {
                Log.w(TAG, "MediaController skipPrev notice: ${e.message}")
            }
            try {
                val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                audioManager.dispatchMediaKeyEvent(downEvent)
                audioManager.dispatchMediaKeyEvent(upEvent)
            } catch (e: Exception) {
                Log.w(TAG, "AudioManager prev dispatch notice: ${e.message}")
            }
        }
    }

    fun setSystemMediaVolume(percent: Int) {
        try {
            val clamped = percent.coerceIn(10, 100)
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val targetIndex = ((maxVol * clamped) / 100f).toInt().coerceIn(1, maxVol)
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                targetIndex,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            Log.d(TAG, "Set system STREAM_MUSIC volume to $clamped% ($targetIndex/$maxVol)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to adjust system volume: ${e.message}")
        }
    }

    private fun launchTargetPlayer(
        targetPackage: String,
        searchQuery: String,
        trackTitle: String? = null,
        trackArtist: String? = null,
        trackId: String? = null,
        spotifyUri: String? = null,
        neverOpenExternalApp: Boolean = false,
        onLaunchResult: ((com.tunemind.app.provider.PlaybackLaunchResult) -> Unit)? = null
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Auto-playing synced track in $targetPackage with query: '$searchQuery' title: '$trackTitle' artist: '$trackArtist' id: '$trackId' uri: '$spotifyUri' neverOpenExternalApp=$neverOpenExternalApp")

                val cleanQuery = searchQuery.trim()
                val byParts = Regex("\\s+by\\s+", RegexOption.IGNORE_CASE).split(cleanQuery, 2)
                val resolvedTitle = trackTitle?.trim()?.ifBlank { null } ?: run {
                    if (cleanQuery.contains(" - ")) cleanQuery.substringBefore(" - ").trim()
                    else if (byParts.size == 2) byParts[0].trim()
                    else cleanQuery
                }
                val resolvedArtist = trackArtist?.trim()?.ifBlank { null } ?: run {
                    if (cleanQuery.contains(" - ")) cleanQuery.substringAfter(" - ").trim()
                    else if (byParts.size == 2) byParts[1].trim()
                    else ""
                }

                // Resolve exact music provider matching package name, providerId, or display name
                val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
                val targetTrim = targetPackage.trim()
                val resolvedProvider = registry.getInstalledProviders().firstOrNull { p ->
                    p.packageName.equals(targetTrim, ignoreCase = true) ||
                        p.providerId.equals(targetTrim, ignoreCase = true) ||
                        p.displayName.equals(targetTrim, ignoreCase = true) ||
                        p.displayName.contains(targetTrim, ignoreCase = true)
                }
                val finalProviderId = resolvedProvider?.providerId ?: when {
                    targetTrim.contains("spotify", ignoreCase = true) -> "spotify"
                    targetTrim.contains("boomplay", ignoreCase = true) -> "boomplay"
                    targetTrim.contains("youtube", ignoreCase = true) -> "youtube_music"
                    targetTrim.contains("audiomack", ignoreCase = true) -> "audiomack"
                    targetTrim.contains("tunemind", ignoreCase = true) -> "tunemind_inapp"
                    else -> targetTrim
                }
                val finalPackageName = resolvedProvider?.packageName ?: when (finalProviderId) {
                    "tunemind_inapp" -> "com.tunemind.app"
                    "boomplay" -> "com.afmobi.boomplayer"
                    "youtube_music" -> "com.google.android.apps.youtube.music"
                    "audiomack" -> "com.audiomack"
                    else -> targetTrim
                }
                val finalDisplayName = resolvedProvider?.displayName ?: when (finalProviderId) {
                    "spotify" -> "Spotify"
                    "boomplay" -> "Boomplay"
                    "youtube_music" -> "YouTube Music"
                    "audiomack" -> "Audiomack"
                    "tunemind_inapp" -> "TuneMind In-App Player"
                    else -> "Target Player"
                }

                // Preserve collection URIs (spotify:playlist/album/artist/episode) as-is; normalize track URIs
                val trimmedUri = spotifyUri?.trim().orEmpty()
                val isCollectionUri = trimmedUri.startsWith("spotify:playlist:", ignoreCase = true) ||
                    trimmedUri.startsWith("spotify:album:", ignoreCase = true) ||
                    trimmedUri.startsWith("spotify:artist:", ignoreCase = true) ||
                    trimmedUri.startsWith("spotify:episode:", ignoreCase = true) ||
                    trimmedUri.startsWith("https://open.spotify.com/playlist/", ignoreCase = true) ||
                    trimmedUri.startsWith("https://open.spotify.com/album/", ignoreCase = true)

                var canonicalSpotifyUri: String? = if (isCollectionUri) {
                    trimmedUri
                } else {
                    com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(trimmedUri)
                }
                if (canonicalSpotifyUri.isNullOrBlank() && finalPackageName.contains("spotify", ignoreCase = true)) {
                    canonicalSpotifyUri = com.tunemind.app.provider.SpotifyTrackUriResolver.resolveTrackUriBlocking(
                        context = context,
                        cleanTitle = resolvedTitle,
                        cleanArtist = resolvedArtist,
                        fullQuery = cleanQuery.ifBlank { "$resolvedTitle $resolvedArtist".trim() }
                    )
                }

                val canonicalIdentity = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = trackId,
                    spotifyUri = if (isCollectionUri) null else canonicalSpotifyUri,
                    title = resolvedTitle,
                    artist = resolvedArtist
                )
                val finalDeepLinkUri = if (isCollectionUri) canonicalSpotifyUri else canonicalIdentity.spotifyUri

                val targetTrack = com.tunemind.app.domain.recommendation.RecommendedTrack(
                    id = canonicalIdentity.trackId,
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    reason = "Autonomous neuro-acoustic flow match",
                    confidence = 0.98f,
                    trackId = canonicalIdentity.trackId,
                    spotifyUri = finalDeepLinkUri
                )

                val useInAppNow = finalProviderId == "tunemind_inapp" ||
                    finalPackageName.equals("com.tunemind.app", ignoreCase = true)

                val launchRes = if (useInAppNow) {
                    registry.launchRecommendation(targetTrack, "tunemind_inapp")
                } else {
                    com.tunemind.app.provider.MusicPlayerAutoLauncher.launchWithAutoPlay(
                        context = context,
                        providerId = finalProviderId,
                        displayName = finalDisplayName,
                        packageName = finalPackageName,
                        track = targetTrack,
                        query = cleanQuery.ifBlank { "$resolvedTitle $resolvedArtist".trim() },
                        deepLinkUri = finalDeepLinkUri,
                        neverOpenExternalApp = neverOpenExternalApp
                    )
                }
                mainHandler.post {
                    onLaunchResult?.invoke(launchRes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch player", e)
            }
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
