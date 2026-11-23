package com.tunemind.app.domain.execution

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class PlaylistTrackInput(
    val title: String,
    val artist: String = "",
    val spotify_uri: String? = null,
    val track_id: String? = null,
    val source_player: String? = null
)

data class AgentToolCallPayload(
    val action: String = "ADVISE_ONLY",
    val track_title: String? = null,
    val track_artist: String? = null,
    val track_id: String? = null,
    val spotify_uri: String? = null,
    val context_uri: String? = null,
    val shuffle_enabled: Boolean? = null,
    val repeat_mode: String? = null,
    val seek_position_ms: Long? = null,
    val seek_delta_ms: Long? = null,
    val device_id: String? = null,
    val device_name: String? = null,
    val ytm_video_id: String? = null,
    val preview_url: String? = null,
    val audius_stream_url: String? = null,
    val deezer_url: String? = null,
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
    val playlist_name: String? = null,
    val new_playlist_name: String? = null,
    val playlist_description: String? = null,
    val mood_or_audience: String? = null,
    val playlist_tracks: List<PlaylistTrackInput> = emptyList(),
    val queue_tracks: List<PlaylistTrackInput> = emptyList(),
    val open_playlist_screen: Boolean? = null,
    val sleep_timer_minutes: Int? = null,
    val kids_mode_enabled: Boolean? = null,
    val extra_actions: List<AgentToolCallPayload> = emptyList()
)

data class OsExecutionCommand(
    val intent: String, // "OS_MEDIA_NEXT_AND_DEEP_LINK", "OS_SPOTIFY_CONTEXT", "OS_MEDIA_SEEK", "OS_MEDIA_PAUSE", "OS_MEDIA_RESUME", "OS_MEDIA_NEXT", "OS_MEDIA_PREV", "NONE"
    val target_player: String, // e.g., "com.spotify.music"
    val search_query: String,
    val suggested_bpm_target: Int? = null,
    val track_title: String? = null,
    val track_artist: String? = null,
    val track_id: String? = null,
    val spotify_uri: String? = null,
    val context_uri: String? = null,
    val shuffle_enabled: Boolean? = null,
    val seek_position_ms: Long? = null,
    val seek_delta_ms: Long? = null,
    val ytm_video_id: String? = null,
    val preview_url: String? = null,
    val audius_stream_url: String? = null,
    val deezer_url: String? = null,
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

class TuneMindExecutionEngine(private val context: Context) {
    private val TAG = "TuneMindExecution"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var isShutDown = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val openAiTtsCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var activeSpeechGeneration: Long = 0L

    fun stopSpeaking() {
        activeSpeechGeneration++
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun stopPlaybackEnginesOnly() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun fetchOpenAiSpeechBase64Blocking(text: String): String? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        openAiTtsCache[clean]?.let { return it }
        val baseUrl = com.tunemind.app.domain.network.BackendEndpointManager.getActiveHttpBaseUrl(context).trimEnd('/')
        val authClient = com.tunemind.app.domain.network.TuneMindAuthOkHttp.getAuthenticatedClient(context)
            .newBuilder()
            .callTimeout(9000, TimeUnit.MILLISECONDS)
            .readTimeout(8500, TimeUnit.MILLISECONDS)
            .build()
        val body = JSONObject().put("text", clean).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        for (attempt in 1..2) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/reasoning/tts")
                    .post(body)
                    .build()
                authClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "OpenAI /tts HTTP ${resp.code} (attempt $attempt)")
                        return@use
                    }
                    val b64 = JSONObject(resp.body?.string().orEmpty())
                        .optString("voice_agent_audio_base64", "")
                        .trim()
                    if (b64.isNotBlank() && b64 != "null") {
                        if (openAiTtsCache.size > 40) openAiTtsCache.clear()
                        openAiTtsCache[clean] = b64
                        return b64
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OpenAI /tts attempt $attempt error (${e.message})")
            }
        }
        return null
    }

    private fun speakTextWithOpenAIOrOfflineFallback(
        text: String,
        preloadedBase64: String?,
        onCompletion: () -> Unit
    ) {
        stopSpeaking()
        val gen = activeSpeechGeneration
        if (!preloadedBase64.isNullOrBlank()) {
            Log.d(TAG, "Playing cloud OpenAI voice audio (${preloadedBase64.length} b64 chars)...")
            playBase64Audio(
                base64Str = preloadedBase64,
                expectedGen = gen,
                onCompletion = onCompletion
            )
            return
        }
        val clean = text.trim()
        if (clean.isBlank()) {
            onCompletion()
            return
        }
        openAiTtsCache[clean]?.let { cached ->
            playBase64Audio(
                base64Str = cached,
                expectedGen = gen,
                onCompletion = onCompletion
            )
            return
        }
        engineScope.launch {
            val fetchedB64 = fetchOpenAiSpeechBase64Blocking(clean)
            mainHandler.post {
                if (isShutDown || gen != activeSpeechGeneration) {
                    return@post
                }
                if (!fetchedB64.isNullOrBlank()) {
                    playBase64Audio(
                        base64Str = fetchedB64,
                        expectedGen = gen,
                        onCompletion = onCompletion
                    )
                } else {
                    Log.w(TAG, "OpenAI /tts unavailable for '$clean' — proceeding silently (legacy Android TTS removed).")
                    onCompletion()
                }
            }
        }
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
                            spotifyUri = response.os_execution_command.spotify_uri ?: response.agent_tool_call?.spotify_uri,
                            ytmVideoId = response.os_execution_command.ytm_video_id ?: response.agent_tool_call?.ytm_video_id,
                            deezerUrl = response.os_execution_command.deezer_url ?: response.agent_tool_call?.deezer_url,
                            neverOpenExternalApp = response.os_execution_command.never_open_external_app,
                            onLaunchResult = onLaunchResult
                        )
                        onFinished?.invoke()
                    }
                    "OS_SPOTIFY_CONTEXT" -> {
                        val ctxUri = response.os_execution_command.context_uri
                            ?: response.agent_tool_call?.context_uri
                            ?: response.os_execution_command.spotify_uri
                            ?: ""
                        val shuffleFlag = response.os_execution_command.shuffle_enabled
                            ?: response.agent_tool_call?.shuffle_enabled
                        val label = response.agent_tool_call?.playlist_name
                            ?: response.os_execution_command.track_title
                            ?: "Spotify Mix"
                        launchSpotifyContextSilently(
                            contextUri = ctxUri,
                            shuffleEnabled = shuffleFlag,
                            label = label,
                            onLaunchResult = onLaunchResult
                        )
                        onFinished?.invoke()
                    }
                    "OS_MEDIA_SEEK" -> {
                        val posMs = response.os_execution_command.seek_position_ms
                            ?: response.agent_tool_call?.seek_position_ms
                        val deltaMs = response.os_execution_command.seek_delta_ms
                            ?: response.agent_tool_call?.seek_delta_ms
                        seekCurrentMedia(posMs, deltaMs)
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

        // Play OpenAI/cloud Base64 MP3 audio (fetching /api/v1/reasoning/tts if missing) — local TTS only when completely offline
        if (textToSpeak.isNotEmpty() || !response.voice_agent_audio_base64.isNullOrEmpty()) {
            speakTextWithOpenAIOrOfflineFallback(
                text = textToSpeak,
                preloadedBase64 = response.voice_agent_audio_base64,
                onCompletion = onSpeechFinished
            )
        } else {
            onSpeechFinished()
        }
    }

    /**
     * Speaks a short prompt (for example a circadian wake greeting when the user calls
     * "Hi TuneMind" and waits) using OpenAI voice when online, and invokes [onFinished] on completion.
     */
    fun speakPromptOnly(text: String, onFinished: () -> Unit) {
        if (text.isBlank()) {
            onFinished()
            return
        }
        pauseCurrentMedia()
        speakTextWithOpenAIOrOfflineFallback(
            text = text,
            preloadedBase64 = null,
            onCompletion = onFinished
        )
    }

    private fun playBase64Audio(
        base64Str: String,
        expectedGen: Long = activeSpeechGeneration,
        onCompletion: () -> Unit
    ) {
        stopPlaybackEnginesOnly()
        try {
            val audioBytes = Base64.decode(base64Str, Base64.DEFAULT)
            if (audioBytes == null || audioBytes.isEmpty()) {
                onCompletion()
                return
            }
            val tempFile = File.createTempFile("tunemind_voice_", ".mp3", context.cacheDir)
            FileOutputStream(tempFile).use { fos ->
                fos.write(audioBytes)
                fos.flush()
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setVolume(1.0f, 1.0f)
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    tempFile.delete()
                    if (mediaPlayer === it) mediaPlayer = null
                    Log.d(TAG, "Voice response playback finished.")
                    if (expectedGen == activeSpeechGeneration) {
                        onCompletion()
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    mp.release()
                    tempFile.delete()
                    if (mediaPlayer === mp) mediaPlayer = null
                    if (expectedGen == activeSpeechGeneration) {
                        onCompletion()
                    }
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play OpenAI Base64 audio", e)
            if (expectedGen == activeSpeechGeneration) {
                onCompletion()
            }
        }
    }

    fun pauseCurrentMedia() {
        try {
            com.tunemind.app.provider.TuneMindInAppPlayer.pause()
            com.tunemind.app.provider.TuneMindYouTubeEmbedController.pause()
        } catch (_: Throwable) {}
        var handledBySession = false
        try {
            handledBySession = com.tunemind.app.service.TuneMindListenerService.instance?.pause() == true
        } catch (e: Exception) {
            Log.w(TAG, "MediaController pause notice: ${e.message}")
        }
        try {
            val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
        } catch (e: Exception) {
            Log.w(TAG, "AudioManager pause dispatch notice: ${e.message}")
        }
        if (!handledBySession && com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.tunemind.app.provider.SpotifyPremiumConnectManager.pausePlayback(context)
            }
        }
        Log.d(TAG, "Executed Multi-Layer OS Media Pause")
    }

    fun resumeCurrentMedia() {
        val ytResumed = runCatching {
            if (com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideo()) {
                com.tunemind.app.provider.TuneMindYouTubeEmbedController.resume()
            } else false
        }.getOrDefault(false)
        if (ytResumed) {
            Log.d(TAG, "Resumed TuneMind In-App YouTube Player")
            return
        }
        val inAppResumed = runCatching {
            if (com.tunemind.app.provider.TuneMindInAppPlayer.hasActiveTrack()) {
                com.tunemind.app.provider.TuneMindInAppPlayer.resume()
            } else false
        }.getOrDefault(false)
        if (inAppResumed) {
            Log.d(TAG, "Resumed native TuneMind In-App Player")
            return
        }
        var handledBySession = false
        try {
            handledBySession = com.tunemind.app.service.TuneMindListenerService.instance?.play() == true
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
        if (!handledBySession && com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.tunemind.app.provider.SpotifyPremiumConnectManager.resumePlayback(context)
            }
        }
        Log.d(TAG, "Executed Multi-Layer OS Media Resume")
    }

    fun skipToNextMedia() {
        var handledBySession = false
        try {
            handledBySession = com.tunemind.app.service.TuneMindListenerService.instance?.skipNext() == true
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
        if (!handledBySession && com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.tunemind.app.provider.SpotifyPremiumConnectManager.skipNext(context)
            }
        }
    }

    fun skipToPreviousMedia() {
        var handledBySession = false
        try {
            handledBySession = com.tunemind.app.service.TuneMindListenerService.instance?.skipPrev() == true
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
        if (!handledBySession && com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.tunemind.app.provider.SpotifyPremiumConnectManager.skipPrevious(context)
            }
        }
    }

    fun seekCurrentMedia(positionMs: Long?, deltaMs: Long?) {
        val targetDelta = deltaMs
        val absPos = positionMs
        val ytActive = runCatching { com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideo() }.getOrDefault(false)
        if (ytActive) {
            val cur = com.tunemind.app.provider.TuneMindYouTubeEmbedController.positionMsFlow.value
            val dest = (absPos ?: (cur + (targetDelta ?: 0L))).coerceAtLeast(0L)
            com.tunemind.app.provider.TuneMindYouTubeEmbedController.seekTo(dest)
            return
        }
        val inAppActive = runCatching { com.tunemind.app.provider.TuneMindInAppPlayer.hasActiveTrack() }.getOrDefault(false)
        if (inAppActive) {
            val cur = com.tunemind.app.provider.TuneMindInAppPlayer.positionMsFlow.value
            val dest = (absPos ?: (cur + (targetDelta ?: 0L))).coerceAtLeast(0L)
            com.tunemind.app.provider.TuneMindInAppPlayer.seekTo(dest)
            return
        }
        if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val ok = com.tunemind.app.provider.SpotifyPremiumConnectManager.seekPlayback(
                    context = context,
                    positionMs = absPos,
                    deltaMs = targetDelta
                )
                if (!ok) {
                    val snapPos = com.tunemind.app.service.TuneMindListenerService.instance
                        ?.getActivePlaybackSnapshot()?.positionMs ?: 0L
                    val dest = (absPos ?: (snapPos + (targetDelta ?: 0L))).coerceAtLeast(0L)
                    runCatching { com.tunemind.app.service.TuneMindListenerService.instance?.seekTo(positionMs = dest) }
                }
            }
            return
        }
        val snapPos = com.tunemind.app.service.TuneMindListenerService.instance
            ?.getActivePlaybackSnapshot()?.positionMs ?: 0L
        val dest = (absPos ?: (snapPos + (targetDelta ?: 0L))).coerceAtLeast(0L)
        runCatching { com.tunemind.app.service.TuneMindListenerService.instance?.seekTo(positionMs = dest) }
    }

    fun launchSpotifyContextSilently(
        contextUri: String,
        shuffleEnabled: Boolean? = null,
        label: String = "Spotify Mix",
        onLaunchResult: ((com.tunemind.app.provider.PlaybackLaunchResult) -> Unit)? = null
    ) {
        if (contextUri.isBlank()) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val res = com.tunemind.app.provider.SpotifyPremiumConnectManager.playSpotifyContextSilently(
                context = context,
                contextUri = contextUri,
                shuffleEnabled = shuffleEnabled
            )
            val mapped = when (res) {
                is com.tunemind.app.provider.SpotifyPlayResult.Success ->
                    com.tunemind.app.provider.PlaybackLaunchResult.DirectPlaybackLaunched(
                        providerId = "spotify",
                        providerDisplayName = "Spotify",
                        trackTitle = label,
                        destinationUri = res.trackUri,
                        message = "Playing $label silently inside TuneMind via ${res.deviceName}"
                    )
                else ->
                    com.tunemind.app.provider.PlaybackLaunchResult.LaunchFailed(
                        providerId = "spotify",
                        reason = res.toString()
                    )
            }
            mainHandler.post { onLaunchResult?.invoke(mapped) }
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
        ytmVideoId: String? = null,
        deezerUrl: String? = null,
        neverOpenExternalApp: Boolean = false,
        onLaunchResult: ((com.tunemind.app.provider.PlaybackLaunchResult) -> Unit)? = null
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Auto-playing synced track in $targetPackage with query: '$searchQuery' title: '$trackTitle' artist: '$trackArtist' id: '$trackId' uri: '$spotifyUri' ytm: '$ytmVideoId' neverOpenExternalApp=$neverOpenExternalApp")

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
                    "boomplay" -> "com.afmobi.boomplaymusic"
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

                val isSpotifyTarget = finalPackageName.contains("spotify", ignoreCase = true)
                val isYouTubeTarget = finalPackageName.contains("youtube", ignoreCase = true)
                val isAudiomackTarget = finalPackageName.contains("audiomack", ignoreCase = true)
                val directBackendSpotifyUri = com.tunemind.app.provider.MusicPlayerAutoLauncher.extractSpotifyTrackUri(spotifyUri)

                // Resolve clean cross-app playable metadata (skip pre-fetch when Spotify Premium Direct Connect is active)
                val isSpotifyOrInAppTarget = isSpotifyTarget ||
                    finalProviderId == "tunemind_inapp" ||
                    finalPackageName.equals("com.tunemind.app", ignoreCase = true)
                val spotifyPremiumDirectReady = isSpotifyOrInAppTarget &&
                    com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)
                val needsInAppFullStreamId = neverOpenExternalApp && ytmVideoId.isNullOrBlank() && !spotifyPremiumDirectReady
                val openResolved = if (
                    spotifyPremiumDirectReady ||
                    (
                        !needsInAppFullStreamId &&
                        (
                            (isSpotifyTarget && !directBackendSpotifyUri.isNullOrBlank()) ||
                            (isYouTubeTarget && !ytmVideoId.isNullOrBlank())
                        )
                    )
                ) {
                    null
                } else {
                    com.tunemind.app.provider.UniversalTrackResolver.resolveTrackBlocking(
                        context = context,
                        cleanTitle = resolvedTitle,
                        cleanArtist = resolvedArtist,
                        fullQuery = cleanQuery.ifBlank { "$resolvedTitle $resolvedArtist".trim() }
                    )
                }

                val effectiveYtmId = ytmVideoId?.takeIf { it.isNotBlank() } ?: openResolved?.ytmVideoId
                val canonicalIdentity = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = trackId ?: openResolved?.trackId,
                    spotifyUri = null,
                    title = resolvedTitle,
                    artist = resolvedArtist
                )
                val explicitDeepLink = when {
                    isYouTubeTarget && !effectiveYtmId.isNullOrBlank() ->
                        "https://music.youtube.com/watch?v=$effectiveYtmId"
                    isSpotifyTarget && !directBackendSpotifyUri.isNullOrBlank() -> directBackendSpotifyUri
                    isSpotifyTarget && !openResolved?.spotifyUri.isNullOrBlank() -> openResolved!!.spotifyUri
                    isSpotifyTarget && !spotifyUri.isNullOrBlank() && spotifyUri.contains("spotify", ignoreCase = true) ->
                        spotifyUri.trim()
                    isSpotifyTarget && !openResolved?.spotifyUrl.isNullOrBlank() -> openResolved!!.spotifyUrl
                    isAudiomackTarget && !openResolved?.audiomackUrl.isNullOrBlank() -> openResolved!!.audiomackUrl
                    !isSpotifyTarget && !(deezerUrl ?: openResolved?.deezerUrl).isNullOrBlank() ->
                        (deezerUrl ?: openResolved?.deezerUrl)!!.trim()
                    !spotifyUri.isNullOrBlank() && spotifyUri.startsWith("http", ignoreCase = true) -> spotifyUri.trim()
                    else -> null
                }

                val effectiveSpotifyUri = directBackendSpotifyUri ?: openResolved?.spotifyUri ?: spotifyUri
                val targetTrack = com.tunemind.app.domain.recommendation.RecommendedTrack(
                    id = canonicalIdentity.trackId,
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    reason = "Autonomous neuro-acoustic flow match",
                    confidence = 0.98f,
                    trackId = canonicalIdentity.trackId,
                    providerTrackId = effectiveYtmId,
                    spotifyUri = effectiveSpotifyUri
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
                        deepLinkUri = explicitDeepLink,
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
        isShutDown = true
        activeSpeechGeneration++
        runCatching { engineScope.cancel() }
        mainHandler.removeCallbacksAndMessages(null)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaPlayer", e)
        }
        mediaPlayer = null
    }
}
