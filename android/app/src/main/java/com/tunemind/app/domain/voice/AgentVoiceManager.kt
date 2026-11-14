package com.tunemind.app.domain.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener as AndroidSpeechListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

enum class AgentState {
    IDLE, LISTENING, THINKING, SPEAKING, ERROR
}

data class PendingProposedTrack(
    val title: String,
    val artist: String,
    val trackId: String? = null,
    val source: String = "com.spotify.music"
)

class AgentVoiceManager(
    private val context: Context,
    private val getNowPlaying: (() -> com.tunemind.app.ui.NowPlayingObservation?)? = null,
    private val getRecentTracks: (() -> List<com.tunemind.app.domain.model.TrackMemoryEntry>)? = null,
    private val getHearingInsights: (() -> com.tunemind.app.ui.HearingInsightsState)? = null,
    private val getVaultTracks: (() -> List<com.tunemind.app.ui.VaultTrack>)? = null,
    private val getDopamineCapsule: (() -> com.tunemind.app.ui.DopamineCapsuleState)? = null,
    private val onTrackDispatched: ((title: String, artist: String, sourcePlayer: String) -> Unit)? = null,
    private val onToolActionExecuted: ((toolCall: com.tunemind.app.domain.execution.AgentToolCallPayload) -> Unit)? = null
) : RecognitionListener {
    private val TAG = "AgentVoiceManager"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState: StateFlow<AgentState> = _agentState

    private val _flowAnalysisFlow = MutableStateFlow("Studio Neural Engine calibrated to your circadian flow.")
    val flowAnalysisFlow: StateFlow<String> = _flowAnalysisFlow

    private val _agentSatietyFlow = MutableStateFlow(22)
    val agentSatietyFlow: StateFlow<Int> = _agentSatietyFlow

    private val _pendingProposedTrackFlow = MutableStateFlow<PendingProposedTrack?>(null)
    val pendingProposedTrackFlow: StateFlow<PendingProposedTrack?> = _pendingProposedTrackFlow

    @Volatile
    private var clientPendingProposedTrack: PendingProposedTrack? = null

    @Volatile
    private var lastUserTranscript: String = ""

    private fun updatePendingProposedTrack(track: PendingProposedTrack?) {
        clientPendingProposedTrack = track
        _pendingProposedTrackFlow.value = track
    }

    // Eagerly initialize TTS so the very first voice response is ready with zero initialization delay
    private val executionEngine = com.tunemind.app.domain.execution.TuneMindExecutionEngine(context)

    private var audioFocusRequest: AudioFocusRequest? = null

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var automaticGainControl: android.media.audiofx.AutomaticGainControl? = null
    private val preConnectAudioBuffer = java.util.concurrent.ConcurrentLinkedQueue<ByteString>()
    @Volatile
    private var isWsOpenForAudio = false
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    private val reasoningHttpClient = OkHttpClient.Builder()
        .connectTimeout(12000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(25000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(12000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _transcriptionFlow = MutableSharedFlow<String>()
    val transcriptionFlow = _transcriptionFlow.asSharedFlow()

    private val _finalTranscriptFlow = MutableSharedFlow<String>()
    val finalTranscriptFlow = _finalTranscriptFlow.asSharedFlow()

    // Vosk Variables
    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var isVoskListening = false

    @Volatile
    private var isAppInForeground = false

    private val powerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    }
    private var autonomousDjWakeLock: android.os.PowerManager.WakeLock? = null

    @Volatile
    private var isExecutingAutonomousHandover = false
    @Volatile
    private var isAwaitingEndOfSongUserChoice = false
    private var endOfSongAutoPlayTimeoutJob: Job? = null
    @Volatile
    private var lastEndOfSongFinishedTitle: String = ""
    @Volatile
    private var lastEndOfSongFinishedArtist: String = ""
    @Volatile
    private var lastEndOfSongTargetPlayer: String = "com.spotify.music"

    @Volatile
    private var monitoredTrackTitle: String = ""
    @Volatile
    private var monitoredTrackArtist: String = ""
    @Volatile
    private var monitoredTrackPackage: String = "com.spotify.music"
    @Volatile
    private var monitoredTrackDurationMs: Long = 0L
    @Volatile
    private var monitoredTrackMaxPosMs: Long = 0L
    @Volatile
    private var lastDispatchedByTuneMindTitle: String = ""
    @Volatile
    private var lastDispatchedAtMs: Long = 0L
    @Volatile
    private var lastHandoverCompletedForTitle: String = ""
    @Volatile
    private var lastHandoverAtMs: Long = 0L

    // Native Speech Recognizer Fallback
    private var nativeRecognizer: AndroidSpeechRecognizer? = null
    private var errorResetJob: Job? = null

    init {
        initWakeWordEngine()
        startCloudKeepAlivePing()
        startAutonomousEndOfSongMonitor()
    }

    private fun isDeviceScreenInteractive(): Boolean {
        return try {
            powerManager?.isInteractive != false
        } catch (_: Exception) {
            true
        }
    }

    private fun acquireTemporaryHandoverWakeLock(timeoutMs: Long = 38_000L) {
        try {
            if (autonomousDjWakeLock == null) {
                autonomousDjWakeLock = powerManager?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "TuneMind:AutonomousDjWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            autonomousDjWakeLock?.acquire(timeoutMs)
        } catch (e: Exception) {
            Log.d(TAG, "WakeLock acquire notice: ${e.message}")
        }
    }

    /**
     * Controls whether the TuneMind UI is actively visible on screen.
     * When false (e.g., screen turns off or Spotify briefly wakes), we pause the continuous Vosk
     * wake-word microphone listener to save battery, while keeping autonomous track playback
     * and End-of-Song DJ handover 100% active even when the screen is off!
     */
    fun setForegroundActive(active: Boolean) {
        isAppInForeground = active
        if (!active) {
            if (isVoskListening || speechService != null) {
                try {
                    speechService?.stop()
                } catch (_: Exception) {}
                isVoskListening = false
            }
            // If an autonomous DJ handover or track launch is actively running (e.g. screen off or Spotify wake),
            // do NOT cancel TTS, mainHandler callbacks, or handover jobs!
            if (!isExecutingAutonomousHandover && _agentState.value != AgentState.SPEAKING && _agentState.value != AgentState.THINKING) {
                errorResetJob?.cancel()
                stopMicrophoneStream()
                stopNativeSpeechRecognizer()
                releaseAudioDucking()
            }
            Log.d(TAG, "App left foreground (autonomousHandover=$isExecutingAutonomousHandover) — Vosk wake-word paused; End-of-Song DJ remains active.")
        } else {
            resumeWakeWordListening()
        }
    }

    private fun isMostPlayedSongRequest(text: String): Boolean {
        val clean = text.lowercase()
        return Regex(
            "\\b(most\\s+(played|listened|heard)|listened\\s+(to\\s+)?(the\\s+)?most|listen\\s+(to\\s+)?(the\\s+)?most|played\\s+(the\\s+)?most|play\\s+(the\\s+)?most|heard\\s+(the\\s+)?most|favorite\\s+(song|track|music)|favourite\\s+(song|track|music)|top\\s+(song|track)|number\\s+one\\s+(song|track)|highest\\s+play|song\\s+(that\\s+)?i\\s+(have\\s+)?(listened|played|heard))\\b"
        ).containsMatchIn(clean)
    }

    private fun isNewOrRecommendSongRequest(text: String): Boolean {
        val clean = text.lowercase()
        return Regex(
            "\\b(new\\s+(song|track|music|tune|vibe)|fresh\\s+(song|track|music|tune)|something\\s+(new|fresh|different|good)|never\\s+heard|haven'?t\\s+heard|discover|recommend|recommendation|suggest|suggestion|pick\\s+(a|me)|choose\\s+(a|me)|find\\s+me\\s+a\\s+song|give\\s+me\\s+a\\s+(new\\s+)?(song|track)|play\\s+(for\\s+me\\s+|me\\s+)?(a\\s+|some\\s+|any\\s+|another\\s+)?(new\\s+|fresh\\s+|good\\s+|nice\\s+)?(song|track|music|tune|something))\\b"
        ).containsMatchIn(clean)
    }

    private fun isConversationalNonTitlePhrase(candidate: String?): Boolean {
        val clean = candidate?.lowercase()?.replace(Regex("[^a-z0-9\\s']"), " ")?.replace(Regex("\\s+"), " ")?.trim() ?: return true
        if (clean.length < 2) return true
        if (isMostPlayedSongRequest(clean) || isNewOrRecommendSongRequest(clean)) return true
        return Regex(
            "\\b(most|listened|played|heard|recommend|recommended|suggest|suggested|new song|fresh song|a song|the song|my song|some music|for me|to me|something|anything|another song|different song|favorite|favourite|that i|what i|which i|have listened|i listen|my top|my history|how are you|how is it going|what is up|who are you)\\b"
        ).containsMatchIn(clean)
    }

    private fun isExplicitMusicPlayOrRecommendRequest(text: String): Boolean {
        val clean = text.lowercase().trim()
        if (clean.isEmpty()) return false
        if (isMostPlayedSongRequest(clean) || isNewOrRecommendSongRequest(clean)) return true
        return Regex(
            "\\b(play|spin|put on|queue|recommend|suggest|find me a song|give me a song|play something|play music|another song|different song|next song|bongo|afrobeat|amapiano|r&b|hip hop|chill music|workout music|focus music|study music|capsule|dopamine capsule)\\b"
        ).containsMatchIn(clean)
    }

    private fun startCloudKeepAlivePing() {
        scope.launch {
            while (isActive) {
                try {
                    if (isAppInForeground) {
                        val baseUrl = com.tunemind.app.domain.network.BackendEndpointManager.getActiveHttpBaseUrl(context)
                        val healthUrl = "${baseUrl.trimEnd('/')}/health"
                        val req = Request.Builder().url(healthUrl).get().build()
                        client.newCall(req).execute().close()
                        Log.d(TAG, "☁️ Cloud Brain keep-alive ping succeeded: $healthUrl")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "☁️ Cloud Brain keep-alive ping notice: ${e.message}")
                }
                delay(8 * 60 * 1000L)
            }
        }
    }

    private fun initWakeWordEngine() {
        try {
            StorageService.unpack(context, "model-en", "model",
                { model ->
                    this.voskModel = model
                    Log.d(TAG, "Vosk Model loaded successfully.")
                    if (isAppInForeground) {
                        startWakeWordListener()
                    }
                },
                { exception ->
                    Log.e(TAG, "Failed to unpack the Vosk model: ${exception?.message}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Vosk StorageService: ${e.message}")
        }
    }

    private fun startWakeWordListener() {
        if (!isAppInForeground) return
        if (voskModel == null) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start Vosk SpeechService: RECORD_AUDIO permission not granted.")
            return
        }
        try {
            if (speechService != null) {
                speechService?.stop()
                speechService?.shutdown()
                speechService = null
            }
            val rec = Recognizer(voskModel, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            isVoskListening = true
            Log.d(TAG, "Vosk is now listening for the wake word while app is in foreground...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Vosk SpeechService: ${e.message}")
        }
    }

    // --- VOSK RECOGNITION LISTENER CALLBACKS ---
    override fun onPartialResult(hypothesis: String?) {
        checkWakeWord(hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        checkWakeWord(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(e: Exception?) {
        Log.e(TAG, "Vosk Error: ${e?.message}")
    }

    override fun onTimeout() {
        Log.d(TAG, "Vosk Timeout")
    }

    private fun checkWakeWord(jsonStr: String?) {
        if (!isAppInForeground || jsonStr == null || _agentState.value != AgentState.IDLE) return

        val text = jsonStr.lowercase()
        if (text.contains("tune mind") || text.contains("hi tune") || text.contains("hey tune")) {
            Log.d(TAG, "Wake Word Detected! Handoff to AssemblyAI...")

            try {
                speechService?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Vosk SpeechService", e)
            }
            isVoskListening = false

            simulateWakeWordDetected()
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences("tunemind_agent_memory", Context.MODE_PRIVATE)
    }

    private fun getRecentlyPlayedByAgent(): List<String> {
        val raw = prefs.getString("recent_agent_tracks", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|||").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun recordAgentSuggestedTrack(title: String?) {
        val clean = title?.trim() ?: return
        if (clean.isEmpty()) return
        val existing = getRecentlyPlayedByAgent().filterNot { it.equals(clean, ignoreCase = true) }
        val updated = (listOf(clean) + existing).take(25)
        prefs.edit().putString("recent_agent_tracks", updated.joinToString("|||")).apply()
    }

    private fun getDialogueHistoryJsonArray(): org.json.JSONArray {
        val raw = prefs.getString("agent_dialogue_history_json", null)
        if (raw.isNullOrBlank()) return org.json.JSONArray()
        return try {
            org.json.JSONArray(raw)
        } catch (_: Exception) {
            org.json.JSONArray()
        }
    }

    private fun recordDialogueTurn(userText: String?, assistantText: String?) {
        val cleanUser = userText?.trim() ?: ""
        val cleanAssistant = assistantText?.trim() ?: ""
        if (cleanUser.isEmpty() && cleanAssistant.isEmpty()) return
        try {
            val currentArr = getDialogueHistoryJsonArray()
            val list = mutableListOf<JSONObject>()
            for (i in 0 until currentArr.length()) {
                currentArr.optJSONObject(i)?.let { list.add(it) }
            }
            if (cleanUser.isNotEmpty()) {
                list.add(JSONObject().apply {
                    put("role", "user")
                    put("text", cleanUser.take(360))
                })
            }
            if (cleanAssistant.isNotEmpty()) {
                list.add(JSONObject().apply {
                    put("role", "assistant")
                    put("text", cleanAssistant.take(360))
                })
            }
            val trimmed = list.takeLast(12)
            val newArr = org.json.JSONArray()
            for (item in trimmed) {
                newArr.put(item)
            }
            prefs.edit().putString("agent_dialogue_history_json", newArr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist dialogue turn: ${e.message}")
        }
    }

    private fun getCurrentSpotifyAccessTokenBlocking(): String {
        return try {
            runBlocking(Dispatchers.IO) {
                com.tunemind.app.data.ingestion.SpotifyAuthManager(context).getValidAccessToken()
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    // Wake Word / Manual Orb tap handler
    fun simulateWakeWordDetected() {
        isAppInForeground = true
        if (_agentState.value == AgentState.LISTENING) return

        if (_agentState.value == AgentState.SPEAKING || _agentState.value == AgentState.THINKING) {
            Log.d(TAG, "Interrupting active agent speech/thinking to start new voice turn...")
            executionEngine.stopSpeaking()
        }

        errorResetJob?.cancel()
        Log.d(TAG, "Wake Word / Orb Activated! Starting TuneMind Voice Agent...")

        if (isVoskListening) {
            try {
                speechService?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Vosk SpeechService", e)
            }
            isVoskListening = false
        }

        stopMicrophoneStream()
        stopNativeSpeechRecognizer()

        // Pause any playing background music immediately so speaker audio never bleeds into the microphone
        try {
            executionEngine.pauseCurrentMedia()
        } catch (e: Exception) {
            Log.w(TAG, "Could not pause media session before listening: ${e.message}")
        }

        _agentState.value = AgentState.LISTENING
        requestAudioDucking()
        startMicrophoneStream()
    }

    /**
     * Triggered when the user taps the circular ▶ button on any song card or when Autonomous End-of-Song DJ
     * launches Claude's next curated recommendation.
     * Speaks a brief confirmation and immediately launches playback on the target player.
     */
    fun playTrackImmediatelyWithVoice(
        title: String,
        artist: String,
        spotifyUri: String? = null,
        targetPlayer: String = "com.spotify.music",
        customSpokenLine: String? = null
    ) {
        acquireTemporaryHandoverWakeLock(35_000L)
        isExecutingAutonomousHandover = true
        isAwaitingEndOfSongUserChoice = false
        endOfSongAutoPlayTimeoutJob?.cancel()
        errorResetJob?.cancel()
        stopMicrophoneStream()
        stopNativeSpeechRecognizer()
        executionEngine.stopSpeaking()
        updatePendingProposedTrack(null)

        val cleanTitle = title.trim()
        val cleanArtist = artist.trim().takeIf { !it.equals("Unknown Artist", ignoreCase = true) } ?: ""
        val spokenLine = customSpokenLine?.takeIf { it.isNotBlank() } ?: if (cleanArtist.isNotEmpty()) {
            "Playing $cleanTitle by $cleanArtist now."
        } else {
            "Playing $cleanTitle now."
        }

        lastDispatchedByTuneMindTitle = cleanTitle
        lastDispatchedAtMs = System.currentTimeMillis()
        monitoredTrackTitle = cleanTitle
        monitoredTrackArtist = cleanArtist
        monitoredTrackMaxPosMs = 0L

        recordAgentSuggestedTrack(cleanTitle)
        _agentState.value = AgentState.SPEAKING
        _flowAnalysisFlow.value = "Direct studio launch: spinning $cleanTitle in peak dopamine state."
        _agentSatietyFlow.value = 18
        mainHandler.post {
            onTrackDispatched?.invoke(cleanTitle, cleanArtist, targetPlayer.ifBlank { "com.spotify.music" })
        }
        scope.launch {
            _transcriptionFlow.emit(spokenLine)
        }

        val searchQuery = if (cleanArtist.isNotEmpty()) "$cleanTitle $cleanArtist" else cleanTitle
        val responseObj = com.tunemind.app.domain.execution.ReasoningResponse(
            calculated_satiety_percentage = 18,
            flow_state_analysis = "Direct studio launch: spinning $cleanTitle in peak dopamine state.",
            voice_agent_tts = spokenLine,
            os_execution_command = com.tunemind.app.domain.execution.OsExecutionCommand(
                intent = "OS_MEDIA_NEXT_AND_DEEP_LINK",
                target_player = targetPlayer.ifBlank { "com.spotify.music" },
                search_query = searchQuery,
                suggested_bpm_target = 108,
                track_title = cleanTitle,
                track_artist = cleanArtist,
                spotify_uri = spotifyUri
            )
        )

        executionEngine.executeBackendCommand(
            response = responseObj,
            onBeforeLaunch = {
                mainHandler.post {
                    abandonAssistantAudioFocusOnly()
                }
            },
            onFinished = {
                mainHandler.post {
                    isExecutingAutonomousHandover = false
                    releaseAudioDucking()
                    resumeWakeWordListening()
                }
            }
        )
    }

    /**
     * Autonomous End-of-Song DJ Monitor (Works both in foreground and when the phone screen is OFF):
     * 1. Subscribes to real-time `TuneMindListenerService.onPlaybackSnapshotUpdated` events AND polls
     *    active MediaSession progress every 750ms.
     * 2. Detects when a playing song reaches its end (within the final 1.75s of `durationMs`) OR when
     *    Spotify attempts to auto-play an unapproved random next track after the current track played.
     * 3. Immediately pauses Spotify's random autoplay and:
     *    - If the screen is OFF (or user is hands-free / away): automatically queries Claude for the
     *      next curated recommendation and plays it immediately!
     *    - If the screen is ON and TuneMind is visible: asks what song the user wants next for 6.5s,
     *      and if they don't specify one, automatically plays Claude's next curated recommendation!
     */
    private fun startAutonomousEndOfSongMonitor() {
        com.tunemind.app.service.TuneMindListenerService.onPlaybackSnapshotUpdated = { snap ->
            evaluatePlaybackSnapshotForEndOfSong(snap)
        }

        scope.launch {
            while (isActive) {
                try {
                    val snap = com.tunemind.app.service.TuneMindListenerService.instance?.getActivePlaybackSnapshot()
                    if (snap != null) {
                        evaluatePlaybackSnapshotForEndOfSong(snap)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "End-of-song monitor poll notice: ${e.message}")
                }
                delay(750L)
            }
        }
    }

    private fun isPlaceholderTrackTitle(title: String?): Boolean {
        val clean = title?.trim() ?: return true
        if (clean.length < 2) return true
        return clean.equals("Standing By", ignoreCase = true) ||
            clean.equals("Active Session", ignoreCase = true) ||
            clean.equals("Deep Flow State", ignoreCase = true) ||
            clean.equals("Advertisement", ignoreCase = true) ||
            clean.equals("Spotify", ignoreCase = true)
    }

    private fun evaluatePlaybackSnapshotForEndOfSong(snap: com.tunemind.app.media.ActivePlaybackSnapshot) {
        val rawTitle = snap.title.trim()
        if (isPlaceholderTrackTitle(rawTitle)) return

        val now = System.currentTimeMillis()
        // Do not interrupt while TuneMind is actively launching a track or executing an end-of-song handover
        if (isExecutingAutonomousHandover || _agentState.value == AgentState.SPEAKING || _agentState.value == AgentState.THINKING) {
            return
        }
        if (now - lastDispatchedAtMs < 7500L || now - lastHandoverAtMs < 12_000L) {
            if (lastDispatchedByTuneMindTitle.isNotBlank() &&
                (rawTitle.contains(lastDispatchedByTuneMindTitle, ignoreCase = true) ||
                    lastDispatchedByTuneMindTitle.contains(rawTitle, ignoreCase = true))
            ) {
                monitoredTrackTitle = rawTitle
                monitoredTrackArtist = snap.artist
                monitoredTrackPackage = snap.packageName
                if (snap.durationMs > 0L) monitoredTrackDurationMs = snap.durationMs
                monitoredTrackMaxPosMs = snap.positionMs
            }
            return
        }

        val isSameTrack = monitoredTrackTitle.isBlank() ||
            rawTitle.equals(monitoredTrackTitle, ignoreCase = true) ||
            rawTitle.contains(monitoredTrackTitle, ignoreCase = true) ||
            monitoredTrackTitle.contains(rawTitle, ignoreCase = true)

        if (isSameTrack) {
            monitoredTrackTitle = rawTitle
            monitoredTrackArtist = snap.artist
            monitoredTrackPackage = snap.packageName
            if (snap.durationMs > 0L) {
                monitoredTrackDurationMs = snap.durationMs
            }
            if (snap.positionMs > monitoredTrackMaxPosMs) {
                monitoredTrackMaxPosMs = snap.positionMs
            }

            // TRIGGER A: Song is within the final 1.75 seconds of its duration
            if (snap.isPlaying &&
                monitoredTrackDurationMs >= 25_000L &&
                snap.positionMs >= (monitoredTrackDurationMs - 1750L) &&
                !lastHandoverCompletedForTitle.equals(rawTitle, ignoreCase = true)
            ) {
                Log.i(
                    TAG,
                    "🎵 Autonomous End-of-Song Trigger A (Near-End ${snap.positionMs}/${monitoredTrackDurationMs}ms) for '$rawTitle'"
                )
                triggerAutonomousEndOfSongHandover(
                    finishedTitle = rawTitle,
                    finishedArtist = snap.artist,
                    targetPlayer = snap.packageName.ifBlank { "com.spotify.music" }
                )
            }
            return
        }

        // Track title just changed (`rawTitle != monitoredTrackTitle`)
        val prevTitle = monitoredTrackTitle
        val prevArtist = monitoredTrackArtist
        val prevDur = monitoredTrackDurationMs
        val prevMaxPos = monitoredTrackMaxPosMs

        val wasDispatchedByTuneMind = lastDispatchedByTuneMindTitle.isNotBlank() && (
            rawTitle.equals(lastDispatchedByTuneMindTitle, ignoreCase = true) ||
                rawTitle.contains(lastDispatchedByTuneMindTitle, ignoreCase = true) ||
                lastDispatchedByTuneMindTitle.contains(rawTitle, ignoreCase = true)
            )
        val previousPlayedSubstantially = prevMaxPos >= 25_000L || (prevDur >= 25_000L && prevMaxPos >= (prevDur * 65L) / 100L)

        // TRIGGER B: Previous track played substantially and Spotify auto-advanced to an unapproved random next track!
        if (!wasDispatchedByTuneMind &&
            previousPlayedSubstantially &&
            !lastHandoverCompletedForTitle.equals(prevTitle, ignoreCase = true)
        ) {
            Log.i(
                TAG,
                "🎵 Autonomous End-of-Song Trigger B (Intercepted unapproved Spotify auto-advance '$prevTitle' -> '$rawTitle')"
            )
            triggerAutonomousEndOfSongHandover(
                finishedTitle = prevTitle,
                finishedArtist = prevArtist,
                targetPlayer = snap.packageName.ifBlank { "com.spotify.music" }
            )
            return
        }

        // Otherwise, adopt the newly observed track as the active monitored track
        monitoredTrackTitle = rawTitle
        monitoredTrackArtist = snap.artist
        monitoredTrackPackage = snap.packageName
        monitoredTrackDurationMs = snap.durationMs
        monitoredTrackMaxPosMs = snap.positionMs
    }

    private fun triggerAutonomousEndOfSongHandover(
        finishedTitle: String,
        finishedArtist: String,
        targetPlayer: String
    ) {
        if (isExecutingAutonomousHandover) return
        isExecutingAutonomousHandover = true
        lastHandoverCompletedForTitle = finishedTitle
        lastHandoverAtMs = System.currentTimeMillis()
        lastEndOfSongFinishedTitle = finishedTitle
        lastEndOfSongFinishedArtist = finishedArtist
        lastEndOfSongTargetPlayer = targetPlayer

        // Hold CPU WakeLock so handover completes even when the phone screen is OFF
        acquireTemporaryHandoverWakeLock(40_000L)

        // 1. Immediately pause Spotify so its random autoplay track does not play
        try {
            executionEngine.pauseCurrentMedia()
            com.tunemind.app.service.TuneMindListenerService.instance?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "End-of-song pause notice: ${e.message}")
        }

        val screenInteractiveAndVisible = isDeviceScreenInteractive() && isAppInForeground &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        // 2A. If screen is OFF (or TuneMind is in background), immediately curate & play Claude's next pick!
        if (!screenInteractiveAndVisible) {
            Log.i(TAG, "🌙 Screen is OFF or hands-free — automatically curating & playing Claude's next recommendation after '$finishedTitle'...")
            autoCurateAndPlayNextTrackWithClaude(
                finishedTitle = finishedTitle,
                finishedArtist = finishedArtist,
                targetPlayer = targetPlayer
            )
            return
        }

        // 2B. Screen is ON & TuneMind is visible: Ask user what song they want next, with automatic 6.5s fallback to Claude's pick!
        isAwaitingEndOfSongUserChoice = true
        val promptLine = "$finishedTitle just finished. Tell me what song you want next, or in a few seconds I'll automatically spin my next pick for you."
        _agentState.value = AgentState.SPEAKING
        _flowAnalysisFlow.value = "Track Complete • Say your next song or let Claude auto-spin in 6s..."
        scope.launch {
            _transcriptionFlow.emit(promptLine)
        }

        val pauseResponse = com.tunemind.app.domain.execution.ReasoningResponse(
            calculated_satiety_percentage = _agentSatietyFlow.value,
            flow_state_analysis = "Track Complete • Awaiting your next request or auto-curating with Claude...",
            voice_agent_tts = promptLine,
            os_execution_command = com.tunemind.app.domain.execution.OsExecutionCommand(
                intent = "OS_MEDIA_PAUSE",
                target_player = targetPlayer,
                search_query = ""
            )
        )

        executionEngine.executeBackendCommand(
            response = pauseResponse,
            onBeforeLaunch = {},
            onFinished = {
                mainHandler.post {
                    isExecutingAutonomousHandover = false
                    if (!isDeviceScreenInteractive() || !isAppInForeground) {
                        autoCurateAndPlayNextTrackWithClaude(finishedTitle, finishedArtist, targetPlayer)
                        return@post
                    }
                    simulateWakeWordDetected()
                    endOfSongAutoPlayTimeoutJob?.cancel()
                    endOfSongAutoPlayTimeoutJob = scope.launch {
                        delay(6800L)
                        if (isAwaitingEndOfSongUserChoice && _agentState.value == AgentState.LISTENING) {
                            Log.i(TAG, "⏱️ No user voice override within 6.8s after '$finishedTitle' — auto-curating next track with Claude!")
                            isAwaitingEndOfSongUserChoice = false
                            mainHandler.post {
                                stopMicrophoneStream()
                                stopNativeSpeechRecognizer()
                            }
                            autoCurateAndPlayNextTrackWithClaude(finishedTitle, finishedArtist, targetPlayer)
                        }
                    }
                }
            }
        )
    }

    /**
     * Queries Claude (`/api/v1/reasoning/evaluate-satiety` with `AUTO_CURATE_NEXT_TRACK`) to select the best
     * fresh next song after `finishedTitle` and immediately launches playback (even when the screen is off).
     * Falls back to `SpotifyTrackUriResolver.fetchNewDiscoveryRecommendationBlocking` if offline.
     */
    fun autoCurateAndPlayNextTrackWithClaude(
        finishedTitle: String,
        finishedArtist: String,
        targetPlayer: String = "com.spotify.music"
    ) {
        acquireTemporaryHandoverWakeLock(40_000L)
        isExecutingAutonomousHandover = true
        isAwaitingEndOfSongUserChoice = false
        endOfSongAutoPlayTimeoutJob?.cancel()

        setThinkingState()
        _flowAnalysisFlow.value = "Claude Autonomous DJ • Curating your next track after \"$finishedTitle\"..."

        scope.launch {
            var chosenTitle: String? = null
            var chosenArtist: String? = null
            var chosenUri: String? = null
            var chosenAnnouncement: String? = null

            try {
                val recents = getRecentTracks?.invoke() ?: emptyList()
                val unifiedTracks = com.tunemind.app.provider.SpotifyTrackUriResolver.getUnifiedUserSpotifyTracksBlocking(context, recents)
                val hearing = getHearingInsights?.invoke()
                val vaultList = getVaultTracks?.invoke() ?: emptyList()
                val capsule = getDopamineCapsule?.invoke()

                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val daypart = when (hour) {
                    in 6..11 -> "Morning"
                    in 12..16 -> "Midday"
                    in 17..21 -> "Evening"
                    else -> "Late Night"
                }

                val autoCommand = "AUTO_CURATE_NEXT_TRACK: The song \"$finishedTitle\"${if (finishedArtist.isNotBlank()) " by $finishedArtist" else ""} just ended. Select the single best next fresh song for my current flow and play it immediately."
                val bodyJson = JSONObject().apply {
                    put("timestamp_utc", java.time.Instant.now().toString())
                    put("circadian_daypart", daypart)
                    put("user_command", autoCommand)
                    put("current_satiety_percentage", _agentSatietyFlow.value)
                    put("current_track", JSONObject().apply {
                        put("title", finishedTitle)
                        put("artist", finishedArtist)
                        put("source_player", targetPlayer)
                        put("is_playing", false)
                    })

                    val historyArr = org.json.JSONArray()
                    for (u in unifiedTracks.take(60)) {
                        historyArr.put(JSONObject().apply {
                            put("title", u.trackTitle)
                            put("artist", u.artistName)
                            put("source_player", "com.spotify.music")
                            put("playCount", u.playCount)
                            put("trackId", u.spotifyUri)
                        })
                    }
                    put("rolling_history", historyArr)
                    put("recently_played_by_agent", org.json.JSONArray((getRecentlyPlayedByAgent() + listOf(finishedTitle)).distinct()))

                    if (hearing != null) {
                        put("hearing_telemetry", JSONObject().apply {
                            put("estimated_db", hearing.estimatedAverageDb.toDouble().takeIf { !it.isNaN() } ?: 68.0)
                            put("safe_headroom_db", hearing.currentHeadroomDb.toDouble().takeIf { !it.isNaN() } ?: 17.0)
                            put("daily_exposure_minutes", hearing.dailyExposureMinutes)
                            put("output_device", hearing.activeSnapshot?.deviceType ?: "Phone / Wireless Audio")
                            put("safety_guidance", hearing.safetyGuidance)
                        })
                    }

                    val vaultArr = org.json.JSONArray()
                    for (vt in vaultList) {
                        vaultArr.put(JSONObject().apply {
                            put("id", vt.id)
                            put("title", vt.title)
                            put("artist", vt.artist)
                            put("cooldown_days_remaining", vt.cooldownDaysRemaining.toDouble().takeIf { !it.isNaN() } ?: 3.0)
                            put("recovery_percent", (vt.recoveryProgress * 100).toInt())
                            put("satiety_score", vt.satietyScore)
                        })
                    }
                    put("vault_tracks", vaultArr)

                    if (capsule != null && capsule.tracks.isNotEmpty()) {
                        val capArr = org.json.JSONArray()
                        for (ct in capsule.tracks) {
                            capArr.put(JSONObject().apply {
                                put("title", ct.title)
                                put("artist", ct.artist)
                                put("trackId", ct.spotifyUri ?: "")
                                put("freshness_percent", ct.freshnessPercent)
                                put("reason", ct.reason)
                            })
                        }
                        put("dopamine_capsule", capArr)
                    }

                    val identity = com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.resolveUserIdentity(context)
                    put("user_id", identity.userId)
                    put("user_email", identity.email)
                    put("supabase_url", identity.supabaseUrl)
                    put("supabase_anon_key", identity.supabaseAnonKey)
                    put("dialogue_history", getDialogueHistoryJsonArray())
                    put("spotify_access_token", getCurrentSpotifyAccessTokenBlocking())
                }

                val baseUrl = com.tunemind.app.domain.network.BackendEndpointManager.getActiveHttpBaseUrl(context).trimEnd('/')
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/reasoning/evaluate-satiety")
                    .post(bodyJson.toString().toRequestBody(mediaType))
                    .build()

                reasoningHttpClient.newCall(req).execute().use { resp ->
                    val respStr = resp.body?.string()
                    if (resp.isSuccessful && !respStr.isNullOrBlank()) {
                        val respJson = JSONObject(respStr)
                        val cmdObj = respJson.optJSONObject("os_execution_command")
                        val toolObj = respJson.optJSONObject("agent_tool_call")
                        val candidateTitle = cmdObj?.optString("track_title", "")?.takeIf { it.isNotBlank() }
                            ?: toolObj?.optString("track_title", "")?.takeIf { it.isNotBlank() }
                        if (!candidateTitle.isNullOrBlank() && !isConversationalNonTitlePhrase(candidateTitle)) {
                            chosenTitle = candidateTitle
                            chosenArtist = cmdObj?.optString("track_artist", "")?.takeIf { it.isNotBlank() }
                                ?: toolObj?.optString("track_artist", "") ?: ""
                            chosenUri = cmdObj?.optString("spotify_uri", "")?.takeIf { it.isNotBlank() }
                                ?: toolObj?.optString("spotify_uri", "")?.takeIf { it.isNotBlank() }
                            val rawTts = respJson.optString("voice_agent_tts", "").trim()
                            if (rawTts.isNotBlank() && !rawTts.contains("?")) {
                                chosenAnnouncement = rawTts
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Claude auto-curate next track fallback notice: ${e.message}")
            }

            // Fallback to live Spotify Discovery or Dopamine Capsule if backend was unreachable or didn't return a track
            if (chosenTitle.isNullOrBlank()) {
                val recents = getRecentTracks?.invoke() ?: emptyList()
                val excluded = (getRecentlyPlayedByAgent() + listOf(finishedTitle)).toSet()
                val discovery = com.tunemind.app.provider.SpotifyTrackUriResolver.fetchNewDiscoveryRecommendationBlocking(
                    context = context,
                    fallbackMemoryTracks = recents,
                    excludedTitles = excluded
                )
                if (discovery != null) {
                    chosenTitle = discovery.trackTitle
                    chosenArtist = discovery.artistName
                    chosenUri = discovery.spotifyUri
                } else {
                    val capsulePick = getDopamineCapsule?.invoke()?.tracks?.firstOrNull {
                        !it.title.equals(finishedTitle, ignoreCase = true)
                    }
                    chosenTitle = capsulePick?.title ?: "Midnight City"
                    chosenArtist = capsulePick?.artist ?: "M83"
                    chosenUri = capsulePick?.spotifyUri
                }
            }

            val finalTitle = chosenTitle ?: "Midnight City"
            val finalArtist = chosenArtist ?: ""
            val finalUri = chosenUri?.takeIf { it.startsWith("spotify:track:") }
                ?: com.tunemind.app.provider.SpotifyTrackUriResolver.resolveTrackUriBlocking(
                    context = context,
                    cleanTitle = finalTitle,
                    cleanArtist = finalArtist,
                    fullQuery = "$finalTitle $finalArtist".trim()
                )
            val artistPart = if (finalArtist.isNotBlank() && !finalArtist.equals("Unknown Artist", ignoreCase = true)) {
                " by $finalArtist"
            } else {
                ""
            }
            val spokenAnnouncement = chosenAnnouncement ?: "Up next for your flow, playing $finalTitle$artistPart."

            mainHandler.post {
                playTrackImmediatelyWithVoice(
                    title = finalTitle,
                    artist = finalArtist,
                    spotifyUri = finalUri,
                    targetPlayer = targetPlayer,
                    customSpokenLine = spokenAnnouncement
                )
            }
        }
    }

    /**
     * Triggered when the user approves a proposed song (either by tapping Approve on PulseAgentScreen
     * or via voice confirmation). Executes playback immediately on Spotify and logs approval to Supabase.
     */
    fun approvePendingProposedTrack() {
        val pending = clientPendingProposedTrack ?: return
        updatePendingProposedTrack(null)
        com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.recordVoiceOrTrackDecisionAsync(
            context = context,
            triggerTranscript = "User approved proposed song: ${pending.title}",
            agentTtsResponse = "Playing ${pending.title} by ${pending.artist} now.",
            action = "APPROVED_TRACK",
            osIntent = "OS_MEDIA_NEXT_AND_DEEP_LINK",
            trackTitle = pending.title,
            trackArtist = pending.artist,
            spotifyUri = pending.trackId,
            satietyPercent = 18
        )
        playTrackImmediatelyWithVoice(
            title = pending.title,
            artist = pending.artist,
            spotifyUri = pending.trackId,
            targetPlayer = pending.source.ifBlank { "com.spotify.music" }
        )
    }

    /**
     * Triggered when the user passes/rejects a proposed song on PulseAgentScreen and wants another suggestion.
     */
    fun rejectPendingProposedTrack() {
        val rejected = clientPendingProposedTrack
        updatePendingProposedTrack(null)
        if (rejected != null) {
            recordAgentSuggestedTrack(rejected.title)
            com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.recordVoiceOrTrackDecisionAsync(
                context = context,
                triggerTranscript = "User passed/rejected proposed song: ${rejected.title}",
                agentTtsResponse = "Finding a different song that fits your vibe better.",
                action = "REJECTED_TRACK",
                osIntent = "NONE",
                trackTitle = rejected.title,
                trackArtist = rejected.artist,
                spotifyUri = rejected.trackId,
                satietyPercent = _agentSatietyFlow.value
            )
        }
        errorResetJob?.cancel()
        stopMicrophoneStream()
        stopNativeSpeechRecognizer()
        executionEngine.stopSpeaking()
        lastUserTranscript = "Suggest another song instead"
        setThinkingState()
        processFinalTranscriptWithFallback("Suggest a different fresh song from my history and ask if I want to play it.")
    }

    /**
     * Triggered when the user selects Neural Voice Breakdown & Dialogue on any song card.
     * Routes through live Claude Sonnet reasoning so the agent generates a fresh, unscripted
     * psychoacoustic breakdown of the track and opens the microphone for follow-up or approval.
     */
    fun askAgentAboutTrack(
        title: String,
        artist: String,
        freshnessPercent: Int = 92,
        spotifyUri: String? = null,
        targetPlayer: String = "com.spotify.music"
    ) {
        errorResetJob?.cancel()
        stopMicrophoneStream()
        stopNativeSpeechRecognizer()
        executionEngine.stopSpeaking()

        val cleanTitle = title.trim()
        val cleanArtist = artist.trim().takeIf { !it.equals("Unknown Artist", ignoreCase = true) } ?: ""
        val mood = com.tunemind.app.ui.theme.AdaptiveThemeEngine.resolveTrackMood(cleanTitle, cleanArtist)

        updatePendingProposedTrack(
            PendingProposedTrack(
                title = cleanTitle,
                artist = cleanArtist,
                trackId = spotifyUri,
                source = targetPlayer
            )
        )

        val artistPart = if (cleanArtist.isNotEmpty()) " by $cleanArtist" else ""
        val dynamicPrompt = "Give me a fresh psychoacoustic breakdown of \"$cleanTitle\"$artistPart (currently at $freshnessPercent% dopamine freshness, ${mood.label} mood) in my listening journey and ask if I want you to spin it right now."

        lastUserTranscript = dynamicPrompt
        _flowAnalysisFlow.value = "Mood DNA: ${mood.label} • $freshnessPercent% Dopamine Readiness"
        _agentSatietyFlow.value = (100 - freshnessPercent).coerceIn(10, 90)
        setThinkingState()
        processFinalTranscriptWithFallback(dynamicPrompt)
    }

    /**
     * Triggered when the user taps the glowing Dopamine Capsule card on the Studio Mascot screen.
     * Routes through live Claude Sonnet reasoning to brief the user on their compacted capsule
     * and wait for voice approval to launch the lead capsule track.
     */
    fun launchDopamineCapsuleBriefing(
        capsule: com.tunemind.app.ui.DopamineCapsuleState,
        targetPlayer: String = "com.spotify.music"
    ) {
        errorResetJob?.cancel()
        stopMicrophoneStream()
        stopNativeSpeechRecognizer()
        executionEngine.stopSpeaking()

        val tracks = capsule.tracks
        val lead = tracks.firstOrNull()
        if (lead == null) {
            simulateWakeWordDetected()
            return
        }

        updatePendingProposedTrack(
            PendingProposedTrack(
                title = lead.title,
                artist = lead.artist,
                trackId = lead.spotifyUri,
                source = targetPlayer
            )
        )

        val trackSummary = tracks.joinToString(", ") { "${it.title} by ${it.artist} (${it.freshnessPercent}% fresh)" }
        val dynamicPrompt = "Brief me on my ${capsule.slotLabel} Dopamine Capsule that you compacted ($trackSummary) and ask if I want to kick off the capsule with \"${lead.title}\"."

        lastUserTranscript = dynamicPrompt
        _flowAnalysisFlow.value = "Dopamine Capsule Ready: ${tracks.joinToString(" • ") { it.title }}"
        _agentSatietyFlow.value = (100 - lead.freshnessPercent).coerceIn(10, 45)
        setThinkingState()
        processFinalTranscriptWithFallback(dynamicPrompt)
    }

    private fun requestAudioDucking() {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.d(TAG, "Lost audio focus")
                    }
                }
            }
            .build()

        audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        Log.d(TAG, "Exclusive assistant audio focus requested for clean voice capture.")
    }

    private fun startMicrophoneStream() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            handleVoiceError("RECORD_AUDIO permission not granted!")
            return
        }

        startLocalAudioCapture()

        val candidates = com.tunemind.app.domain.network.BackendEndpointManager.getCandidateWebSocketUrls(context)
        connectToCandidateWebSocket(candidates, 0)
    }

    private fun connectToCandidateWebSocket(candidates: List<String>, index: Int) {
        val targetUrl = candidates.getOrElse(index) { "ws://localhost:3000/ws/stt" }
        val request = Request.Builder()
            .url(targetUrl)
            .addHeader("Bypass-Tunnel-Reminder", "true")
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        Log.d(TAG, "Attempting WebSocket connection (${index + 1}/${candidates.size}) to $targetUrl ...")

        var hasOpened = false
        var hasCompletedReasoning = false

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (ws != this@AgentVoiceManager.webSocket) return
                hasOpened = true
                Log.d(TAG, "🟢 WebSocket OPENED successfully on $targetUrl. HTTP Status: ${response.code}")

                // Send complete client playback, hearing, vault, and dopamine capsule telemetry to backend
                try {
                    val np = getNowPlaying?.invoke()
                    val recents: List<com.tunemind.app.domain.model.TrackMemoryEntry> = getRecentTracks?.invoke() ?: emptyList()
                    val unifiedTracks = com.tunemind.app.provider.SpotifyTrackUriResolver.getUnifiedUserSpotifyTracksBlocking(context, recents)
                    val hearing = getHearingInsights?.invoke()
                    val vaultList = getVaultTracks?.invoke() ?: emptyList()
                    val capsule = getDopamineCapsule?.invoke()
                    val pendingProp = clientPendingProposedTrack

                    val metaJson = JSONObject().apply {
                        put("type", "client_context")
                        val currentTrackId = np?.let { cur ->
                            unifiedTracks.firstOrNull { t -> t.trackTitle.equals(cur.title, ignoreCase = true) }?.spotifyUri
                                ?: recents.firstOrNull { t -> t.title.equals(cur.title, ignoreCase = true) }?.providerTrackId
                        } ?: ""
                        val currentObj = JSONObject().apply {
                            put("title", np?.title ?: "Standing By")
                            put("artist", np?.artist ?: "")
                            put("source_player", np?.sourcePackage ?: "com.spotify.music")
                            put("trackId", currentTrackId)
                            put("is_playing", np?.isPlaying == true)
                        }
                        put("current_track", currentObj)

                        val historyArr = org.json.JSONArray()
                        for (u in unifiedTracks.take(60)) {
                            historyArr.put(JSONObject().apply {
                                put("title", u.trackTitle)
                                put("artist", u.artistName)
                                put("source", "com.spotify.music")
                                put("playCount", u.playCount)
                                put("trackId", u.spotifyUri)
                            })
                        }
                        put("recent_tracks", historyArr)
                        put("recently_played_by_agent", org.json.JSONArray(getRecentlyPlayedByAgent()))

                        if (hearing != null) {
                            put("hearing_telemetry", JSONObject().apply {
                                put("estimated_db", hearing.estimatedAverageDb.toDouble())
                                put("safe_headroom_db", hearing.currentHeadroomDb.toDouble())
                                put("daily_exposure_minutes", hearing.dailyExposureMinutes)
                                put("output_device", hearing.activeSnapshot?.deviceType ?: "Phone / Wireless Audio")
                                put("safety_guidance", hearing.safetyGuidance)
                            })
                        }

                        val vaultArr = org.json.JSONArray()
                        for (vt in vaultList) {
                            vaultArr.put(JSONObject().apply {
                                put("id", vt.id)
                                put("title", vt.title)
                                put("artist", vt.artist)
                                put("cooldown_days_remaining", vt.cooldownDaysRemaining.toDouble())
                                put("recovery_percent", (vt.recoveryProgress * 100).toInt())
                                put("satiety_score", vt.satietyScore)
                            })
                        }
                        put("vault_tracks", vaultArr)

                        if (capsule != null && capsule.tracks.isNotEmpty()) {
                            val capArr = org.json.JSONArray()
                            for (ct in capsule.tracks) {
                                capArr.put(JSONObject().apply {
                                    put("title", ct.title)
                                    put("artist", ct.artist)
                                    put("trackId", ct.spotifyUri ?: "")
                                    put("freshness_percent", ct.freshnessPercent)
                                    put("reason", ct.reason)
                                })
                            }
                            put("dopamine_capsule", capArr)
                        }

                        if (pendingProp != null) {
                            put("pending_proposed_track", JSONObject().apply {
                                put("title", pendingProp.title)
                                put("artist", pendingProp.artist)
                                put("trackId", pendingProp.trackId ?: "")
                                put("source", pendingProp.source)
                            })
                        }
                        val identity = com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.resolveUserIdentity(context)
                        put("user_id", identity.userId)
                        put("user_email", identity.email)
                        put("supabase_url", identity.supabaseUrl)
                        put("supabase_anon_key", identity.supabaseAnonKey)
                        put("dialogue_history", getDialogueHistoryJsonArray())
                        put("spotify_access_token", getCurrentSpotifyAccessTokenBlocking())
                    }
                    ws.send(metaJson.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send initial client context over WS: ${e.message}")
                }

                // Flush all pre-connection audio frames captured while the WebSocket was opening
                while (preConnectAudioBuffer.isNotEmpty()) {
                    val frame = preConnectAudioBuffer.poll() ?: break
                    ws.send(frame)
                }
                isWsOpenForAudio = true
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (ws != this@AgentVoiceManager.webSocket && !hasCompletedReasoning) return
                Log.d(TAG, "🔵 WebSocket MESSAGE received: $text")
                try {
                    val json = JSONObject(text)
                    if (json.has("error")) {
                        if (hasCompletedReasoning || _agentState.value == AgentState.SPEAKING) return
                        val errMsg = json.optString("error") + ": " + json.optString("message")
                        Log.w(TAG, "Server reported STT notice ($errMsg) — switching seamlessly to on-device SpeechRecognizer...")
                        fallbackToNativeSpeechRecognizer(errMsg)
                        return
                    }

                    // Handle backend reasoning completion
                    if (json.optString("type") == "command" && json.optString("action") == "THINKING_COMPLETE") {
                        hasCompletedReasoning = true
                        val payload = json.optString("payload", "")
                        Log.d(TAG, "🟢 Backend reasoning response received: $payload")
                        stopMicrophoneStream()
                        val dataObj = json.optJSONObject("data")
                        dispatchReasoningResult(dataObj, payload)
                        return
                    }

                    val transcript = json.optString("transcript", "").ifBlank { json.optString("text", "") }
                    val isFinal = json.optBoolean("isFinal", false) || json.optString("message_type") == "FinalTranscript"

                    if (transcript.isNotBlank()) {
                        lastUserTranscript = transcript
                        scope.launch {
                            _transcriptionFlow.emit(transcript)
                        }
                    }

                    if (isFinal && transcript.isNotBlank()) {
                        Log.d(TAG, "User finished speaking: $transcript")
                        lastUserTranscript = transcript
                        stopAudioRecordingOnly()
                        setThinkingState()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parse error: ${e.message}")
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                Log.d(TAG, "🔵 WebSocket BYTES received (Length: ${bytes.size})")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "🟡 WebSocket CLOSING. Code: $code, Reason: $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "🔴 WebSocket CLOSED. Code: $code, Reason: $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                // Ignore failures from already-closed or superseded WebSockets
                if (hasCompletedReasoning || ws != this@AgentVoiceManager.webSocket) {
                    Log.d(TAG, "Ignoring non-active WebSocket close/failure notice: ${t.message}")
                    return
                }

                Log.e(TAG, "❌ WebSocket FAILURE on $targetUrl: ${t.message}")
                if (!hasOpened && index + 1 < candidates.size) {
                    Log.d(TAG, "🔄 Failing over to next candidate WebSocket endpoint (${index + 2}/${candidates.size})...")
                    connectToCandidateWebSocket(candidates, index + 1)
                    return
                }

                if (response != null) {
                    val body = runCatching { response.body?.string() }.getOrNull()
                    Log.e(TAG, "❌ WebSocket FAILURE HTTP Code: ${response.code}, Body: $body")
                }

                Log.w(TAG, "WebSocket stream unavailable — switching seamlessly to on-device SpeechRecognizer...")
                fallbackToNativeSpeechRecognizer(t.message ?: "WebSocket failure")
            }
        }

        webSocket = client.newWebSocket(request, listener)
    }

    private fun fallbackToNativeSpeechRecognizer(reason: String?) {
        Log.d(TAG, "Activating on-device SpeechRecognizer fallback (reason: $reason)")
        stopMicrophoneStream()
        _agentState.value = AgentState.LISTENING
        startNativeSpeechRecognizer()
    }

    fun dispatchReasoningResult(dataObj: JSONObject?, explicitTtsPayload: String = "") {
        val wasAwaitingEndOfSongChoice = isAwaitingEndOfSongUserChoice
        isAwaitingEndOfSongUserChoice = false
        endOfSongAutoPlayTimeoutJob?.cancel()
        setSpeakingState()

        val cmdObj = dataObj?.optJSONObject("os_execution_command")
        val toolObj = dataObj?.optJSONObject("agent_tool_call")
        val rawAction = toolObj?.optString("action", "ADVISE_ONLY") ?: "ADVISE_ONLY"
        val rawIntent = cmdObj?.optString("intent", "NONE") ?: "NONE"

        val pendingBeforeTurn = clientPendingProposedTrack
        val userTranscriptLower = lastUserTranscript.lowercase().trim()
        val isSyntheticPrompt = userTranscriptLower.startsWith("give me a fresh psychoacoustic breakdown") ||
            userTranscriptLower.startsWith("brief me on my") ||
            userTranscriptLower.startsWith("suggest a different fresh song")

        val isRejectingPending = Regex("\\b(no|nope|nah|not that|don'?t|another|different|change|switch|skip|else|next)\\b")
            .containsMatchIn(userTranscriptLower)
        val isApprovingPending = pendingBeforeTurn != null && !isSyntheticPrompt && !isRejectingPending && (
            Regex("\\b(yes|yeah|yep|yup|ya|sure|ok|okay|alright|fine|play|play it|play that|go ahead|do it|put it on|let'?s go|sounds good|good|perfect|ndio|sawa|haya|please|approve|confirm|start|spin it|guess|yet|yea|yah)\\b")
                .containsMatchIn(userTranscriptLower) ||
            (pendingBeforeTurn.title.length >= 4 && userTranscriptLower.contains(pendingBeforeTurn.title.lowercase().take(5).trim()))
        )

        var rawTrackTitle = cmdObj?.optString("track_title", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("track_title", "")?.takeIf { it.isNotBlank() }
        var rawTrackArtist = cmdObj?.optString("track_artist", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("track_artist", "")?.takeIf { it.isNotBlank() }
        var rawSearchQuery = cmdObj?.optString("search_query", "") ?: ""
        var resolvedSpotifyUri = cmdObj?.optString("spotify_uri", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("spotify_uri", "")?.takeIf { it.isNotBlank() }

        if (isApprovingPending && pendingBeforeTurn != null) {
            rawTrackTitle = pendingBeforeTurn.title
            rawTrackArtist = pendingBeforeTurn.artist
            resolvedSpotifyUri = pendingBeforeTurn.trackId ?: resolvedSpotifyUri
            rawSearchQuery = "${pendingBeforeTurn.title} ${pendingBeforeTurn.artist}".trim()
        }

        // Guard against any conversational non-title phrase leaking into rawTrackTitle when offline/fallback
        val wantsMostPlayed = !isApprovingPending && !isSyntheticPrompt && isMostPlayedSongRequest(userTranscriptLower)
        val wantsNewOrRecommend = !isApprovingPending && !isSyntheticPrompt && !wantsMostPlayed && (
            isNewOrRecommendSongRequest(userTranscriptLower) ||
            (isExplicitMusicPlayOrRecommendRequest(userTranscriptLower) && !rawTrackTitle.isNullOrBlank() && isConversationalNonTitlePhrase(rawTrackTitle))
        )

        // When the AI chooses ADVISE_ONLY on a conversational turn, never attach a track or trigger a song proposal
        if (rawAction == "ADVISE_ONLY" && !isApprovingPending && !isSyntheticPrompt && !wantsMostPlayed && !wantsNewOrRecommend) {
            rawTrackTitle = null
            rawTrackArtist = null
            resolvedSpotifyUri = null
            rawSearchQuery = ""
        }

        val backendGaveValidTrack = !rawTrackTitle.isNullOrBlank() && !isConversationalNonTitlePhrase(rawTrackTitle)
        var customProposalSpeechOverride: String? = null
        if (wantsMostPlayed && !backendGaveValidTrack) {
            val recents = getRecentTracks?.invoke() ?: emptyList()
            val topTrack = com.tunemind.app.provider.SpotifyTrackUriResolver.findMostPlayedTrackBlocking(context, recents)
            if (topTrack != null) {
                rawTrackTitle = topTrack.trackTitle
                rawTrackArtist = topTrack.artistName
                resolvedSpotifyUri = topTrack.spotifyUri
                rawSearchQuery = "${topTrack.trackTitle} ${topTrack.artistName}".trim()
                val artistPart = if (topTrack.artistName.isNotBlank() && topTrack.artistName != "Unknown Artist") " by ${topTrack.artistName}" else ""
                val countNote = if (topTrack.playCount > 1) " with ${topTrack.playCount} plays in your Spotify history" else " from the top of your Spotify rotation"
                customProposalSpeechOverride = "Your most-listened song is ${topTrack.trackTitle}$artistPart$countNote. Would you like me to play it right now?"
            }
        } else if (wantsNewOrRecommend && !backendGaveValidTrack) {
            val recents = getRecentTracks?.invoke() ?: emptyList()
            val excluded = (getRecentlyPlayedByAgent() + listOfNotNull(getNowPlaying?.invoke()?.title)).toSet()
            val discovery = com.tunemind.app.provider.SpotifyTrackUriResolver.fetchNewDiscoveryRecommendationBlocking(
                context = context,
                fallbackMemoryTracks = recents,
                excludedTitles = excluded
            )
            if (discovery != null) {
                rawTrackTitle = discovery.trackTitle
                rawTrackArtist = discovery.artistName
                resolvedSpotifyUri = discovery.spotifyUri
                rawSearchQuery = "${discovery.trackTitle} ${discovery.artistName}".trim()
                val artistPart = if (discovery.artistName.isNotBlank() && discovery.artistName != "Unknown Artist") " by ${discovery.artistName}" else ""
                customProposalSpeechOverride = "Based on your Spotify listening taste, I recommend ${discovery.trackTitle}$artistPart. Would you like me to play it right now?"
            }
        }

        if (!rawTrackTitle.isNullOrBlank()) {
            recordAgentSuggestedTrack(rawTrackTitle)
        }

        if (resolvedSpotifyUri.isNullOrBlank() && !rawTrackTitle.isNullOrBlank()) {
            val recents = getRecentTracks?.invoke() ?: emptyList()
            val matchedLocal = recents.firstOrNull { r ->
                val rTitle = r.title?.trim() ?: ""
                rTitle.isNotEmpty() && (
                    rTitle.equals(rawTrackTitle!!.trim(), ignoreCase = true) ||
                    rawSearchQuery.lowercase().contains(rTitle.lowercase())
                )
            }
            if (matchedLocal?.providerTrackId?.startsWith("spotify:track:") == true) {
                resolvedSpotifyUri = matchedLocal.providerTrackId
                Log.d(TAG, "Bound exact Spotify URI from local memory: $resolvedSpotifyUri")
            } else {
                resolvedSpotifyUri = com.tunemind.app.provider.SpotifyTrackUriResolver.resolveTrackUriBlocking(
                    context = context,
                    cleanTitle = rawTrackTitle!!,
                    cleanArtist = rawTrackArtist ?: "",
                    fullQuery = rawSearchQuery.ifBlank { "${rawTrackTitle} ${rawTrackArtist ?: ""}".trim() }
                )
            }
        }

        val targetPlayerPkg = toolObj?.optString("target_player", "com.spotify.music")
            ?.takeIf { it.isNotBlank() }
            ?: cmdObj?.optString("target_player", "com.spotify.music")
            ?: "com.spotify.music"

        // Determine whether this turn is proposing a song (must wait for user approval) vs executing an approved song
        val isTrackProposalOrPlayAttempt = !rawTrackTitle.isNullOrBlank() && (
            wantsMostPlayed ||
            wantsNewOrRecommend ||
            rawAction == "PROPOSE_TRACK" ||
            rawAction == "LAUNCH_DOPAMINE_CAPSULE" ||
            rawAction == "PLAY_TRACK_NOW" ||
            rawIntent == "OS_MEDIA_NEXT_AND_DEEP_LINK"
        )

        val effectiveAction: String
        val effectiveIntent: String
        val effectiveExpectFollowUp: Boolean
        var spokenTts = customProposalSpeechOverride
            ?: explicitTtsPayload.ifBlank { dataObj?.optString("voice_agent_tts", "") ?: "" }.trim()

        if (isApprovingPending && pendingBeforeTurn != null) {
            effectiveAction = "PLAY_TRACK_NOW"
            effectiveIntent = "OS_MEDIA_NEXT_AND_DEEP_LINK"
            effectiveExpectFollowUp = false
            updatePendingProposedTrack(null)
            val artistPart = if (!rawTrackArtist.isNullOrBlank() && rawTrackArtist != "Unknown Artist") " by $rawTrackArtist" else ""
            spokenTts = spokenTts.takeIf { it.isNotBlank() && !it.contains("?") }
                ?: "Awesome, playing ${rawTrackTitle}$artistPart on Spotify right now."
        } else if (wasAwaitingEndOfSongChoice && isTrackProposalOrPlayAttempt && !rawTrackTitle.isNullOrBlank()) {
            // When the user responds to the End-of-Song prompt ("Tell me what song you want next"),
            // immediately play their requested song without requiring a second confirmation!
            effectiveAction = "PLAY_TRACK_NOW"
            effectiveIntent = "OS_MEDIA_NEXT_AND_DEEP_LINK"
            effectiveExpectFollowUp = false
            updatePendingProposedTrack(null)
            val artistPart = if (!rawTrackArtist.isNullOrBlank() && rawTrackArtist != "Unknown Artist") " by $rawTrackArtist" else ""
            spokenTts = "Spinning ${rawTrackTitle}$artistPart for you right now."
        } else if (isTrackProposalOrPlayAttempt) {
            // Always propose the song first and wait for the user's approval before executing!
            effectiveAction = if (rawAction == "LAUNCH_DOPAMINE_CAPSULE") "LAUNCH_DOPAMINE_CAPSULE" else "PROPOSE_TRACK"
            effectiveIntent = "NONE"
            effectiveExpectFollowUp = true
            updatePendingProposedTrack(
                PendingProposedTrack(
                    title = rawTrackTitle!!,
                    artist = rawTrackArtist ?: "",
                    trackId = resolvedSpotifyUri,
                    source = targetPlayerPkg
                )
            )
            // Ensure spoken TTS asks the user for approval instead of claiming it is already spinning
            spokenTts = spokenTts
                .replace(Regex("^(spinning|playing|launching)\\s+", RegexOption.IGNORE_CASE), "I picked ")
                .replace(Regex("\\s+right now[.!]*$", RegexOption.IGNORE_CASE), ".")
                .trim()
            if (!spokenTts.contains("?")) {
                val cleanSentence = spokenTts.trimEnd('.', '!').ifBlank {
                    val artistPart = if (!rawTrackArtist.isNullOrBlank() && rawTrackArtist != "Unknown Artist") " by $rawTrackArtist" else ""
                    "I picked $rawTrackTitle$artistPart for your vibe"
                }
                spokenTts = "$cleanSentence. Would you like me to play it right now?"
            }
        } else {
            effectiveAction = rawAction
            effectiveIntent = rawIntent
            effectiveExpectFollowUp = toolObj?.optBoolean("expect_follow_up", true) ?: true
            if (effectiveAction == "PAUSE_PLAYBACK") {
                updatePendingProposedTrack(null)
            }
        }

        val toolPayload = com.tunemind.app.domain.execution.AgentToolCallPayload(
            action = effectiveAction,
            track_title = rawTrackTitle,
            track_artist = rawTrackArtist,
            spotify_uri = resolvedSpotifyUri,
            target_player = targetPlayerPkg,
            volume_percent = if (toolObj != null && toolObj.has("volume_percent")) toolObj.optInt("volume_percent", 60) else null,
            palette_id = toolObj?.optString("palette_id", "")?.takeIf { it.isNotBlank() },
            target_screen = toolObj?.optString("target_screen", "")?.takeIf { it.isNotBlank() },
            expect_follow_up = effectiveExpectFollowUp
        )

        val effectiveFlowAnalysis = if (effectiveAction == "PROPOSE_TRACK" && !rawTrackTitle.isNullOrBlank()) {
            val artistPart = if (!rawTrackArtist.isNullOrBlank() && rawTrackArtist != "Unknown Artist") " • $rawTrackArtist" else ""
            "Proposed \"$rawTrackTitle\"$artistPart — say \"Yes\" or tap Approve to play."
        } else {
            dataObj?.optString("flow_state_analysis", "") ?: ""
        }

        val responseObj = com.tunemind.app.domain.execution.ReasoningResponse(
            calculated_satiety_percentage = dataObj?.optInt("calculated_satiety_percentage", 30) ?: 30,
            flow_state_analysis = effectiveFlowAnalysis,
            voice_agent_tts = spokenTts,
            reasoning_mode = dataObj?.optString("reasoning_mode", "SONIC_CURATOR") ?: "SONIC_CURATOR",
            agent_tool_call = toolPayload,
            os_execution_command = com.tunemind.app.domain.execution.OsExecutionCommand(
                intent = effectiveIntent,
                target_player = targetPlayerPkg,
                search_query = rawSearchQuery,
                suggested_bpm_target = cmdObj?.optInt("suggested_bpm_target"),
                track_title = rawTrackTitle,
                track_artist = rawTrackArtist,
                spotify_uri = resolvedSpotifyUri
            ),
            voice_agent_audio_base64 = if (effectiveAction == "PROPOSE_TRACK" && rawAction == "PLAY_TRACK_NOW") {
                // Force Android TTS to speak our rewritten approval question if cloud audio said "Spinning..."
                null
            } else {
                dataObj?.optString("voice_agent_audio_base64", "")?.takeIf { it.isNotBlank() && it != "null" }
            }
        )

        // Persist user + assistant turn in rolling multi-turn conversation memory & live Supabase
        recordDialogueTurn(lastUserTranscript, responseObj.voice_agent_tts)
        com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.recordVoiceOrTrackDecisionAsync(
            context = context,
            triggerTranscript = lastUserTranscript.ifBlank { "voice_turn" },
            agentTtsResponse = responseObj.voice_agent_tts,
            action = effectiveAction,
            osIntent = effectiveIntent,
            trackTitle = rawTrackTitle,
            trackArtist = rawTrackArtist,
            spotifyUri = resolvedSpotifyUri,
            satietyPercent = responseObj.calculated_satiety_percentage
        )

        mainHandler.post {
            onToolActionExecuted?.invoke(toolPayload)
        }

        if (responseObj.voice_agent_tts.isNotBlank()) {
            scope.launch {
                _transcriptionFlow.emit(responseObj.voice_agent_tts)
            }
        }
        if (responseObj.flow_state_analysis.isNotBlank()) {
            _flowAnalysisFlow.value = responseObj.flow_state_analysis
        }
        _agentSatietyFlow.value = responseObj.calculated_satiety_percentage

        errorResetJob?.cancel()
        executionEngine.executeBackendCommand(
            response = responseObj,
            onBeforeLaunch = {
                mainHandler.post {
                    abandonAssistantAudioFocusOnly()
                    if (responseObj.os_execution_command.intent == "OS_MEDIA_NEXT_AND_DEEP_LINK") {
                        val dispatchedTitle = rawTrackTitle ?: rawSearchQuery.ifBlank { "Fresh Selection" }
                        val dispatchedArtist = rawTrackArtist ?: "Autonomous Studio Flow"
                        lastDispatchedByTuneMindTitle = dispatchedTitle
                        lastDispatchedAtMs = System.currentTimeMillis()
                        monitoredTrackTitle = dispatchedTitle
                        monitoredTrackArtist = dispatchedArtist
                        monitoredTrackMaxPosMs = 0L
                        onTrackDispatched?.invoke(
                            dispatchedTitle,
                            dispatchedArtist,
                            responseObj.os_execution_command.target_player
                        )
                    }
                }
            },
            onFinished = {
                mainHandler.post {
                    isExecutingAutonomousHandover = false
                    if (isAppInForeground && toolPayload.expect_follow_up && responseObj.os_execution_command.intent == "NONE") {
                        Log.d(TAG, "Waiting for user follow-up/approval in dialogue; re-opening mic in 650ms...")
                        _agentState.value = AgentState.IDLE
                        mainHandler.postDelayed({
                            if (isAppInForeground && _agentState.value == AgentState.IDLE) {
                                simulateWakeWordDetected()
                            }
                        }, 650L)
                    } else {
                        releaseAudioDucking()
                        resumeWakeWordListening()
                    }
                }
            }
        )
    }

    /**
     * Evaluates a finalized transcript via the Cloud HTTP `/api/v1/reasoning/evaluate-satiety` endpoint
     * with full client telemetry, or falls back to autonomous LLM / local on-device reasoning if offline.
     */
    fun processFinalTranscriptWithFallback(transcript: String) {
        endOfSongAutoPlayTimeoutJob?.cancel()
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) {
            if (isAwaitingEndOfSongUserChoice) {
                isAwaitingEndOfSongUserChoice = false
                autoCurateAndPlayNextTrackWithClaude(
                    lastEndOfSongFinishedTitle,
                    lastEndOfSongFinishedArtist,
                    lastEndOfSongTargetPlayer
                )
                return
            }
            releaseAudioDucking()
            resumeWakeWordListening()
            return
        }
        // If user says "you pick", "surprise me", "next", "anything", or "go ahead" during end-of-song handover,
        // immediately trigger Claude's auto-curated next track!
        if (isAwaitingEndOfSongUserChoice &&
            Regex("\\b(you pick|your pick|surprise me|anything|whatever|next|go ahead|auto|spin something|choose for me)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(cleaned)
        ) {
            isAwaitingEndOfSongUserChoice = false
            autoCurateAndPlayNextTrackWithClaude(
                lastEndOfSongFinishedTitle,
                lastEndOfSongFinishedArtist,
                lastEndOfSongTargetPlayer
            )
            return
        }
        lastUserTranscript = cleaned

        setThinkingState()
        scope.launch {
            try {
                val np = getNowPlaying?.invoke()
                val recents = getRecentTracks?.invoke() ?: emptyList()
                val unifiedTracks = com.tunemind.app.provider.SpotifyTrackUriResolver.getUnifiedUserSpotifyTracksBlocking(context, recents)
                val hearing = getHearingInsights?.invoke()
                val vaultList = getVaultTracks?.invoke() ?: emptyList()
                val capsule = getDopamineCapsule?.invoke()
                val pendingProp = clientPendingProposedTrack

                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val daypart = when (hour) {
                    in 6..11 -> "Morning"
                    in 12..16 -> "Midday"
                    in 17..21 -> "Evening"
                    else -> "Late Night"
                }

                val bodyJson = JSONObject().apply {
                    put("timestamp_utc", java.time.Instant.now().toString())
                    put("circadian_daypart", daypart)
                    put("user_command", cleaned)
                    put("current_satiety_percentage", _agentSatietyFlow.value)
                    put("current_track", JSONObject().apply {
                        put("title", np?.title ?: "Standing By")
                        put("artist", np?.artist ?: "")
                        put("source_player", np?.sourcePackage ?: "com.spotify.music")
                        put("is_playing", np?.isPlaying == true)
                    })

                    val historyArr = org.json.JSONArray()
                    for (u in unifiedTracks.take(60)) {
                        historyArr.put(JSONObject().apply {
                            put("title", u.trackTitle)
                            put("artist", u.artistName)
                            put("source_player", "com.spotify.music")
                            put("playCount", u.playCount)
                            put("trackId", u.spotifyUri)
                        })
                    }
                    put("rolling_history", historyArr)
                    put("recently_played_by_agent", org.json.JSONArray(getRecentlyPlayedByAgent()))

                    if (hearing != null) {
                        put("hearing_telemetry", JSONObject().apply {
                            put("estimated_db", hearing.estimatedAverageDb.toDouble().takeIf { !it.isNaN() } ?: 68.0)
                            put("safe_headroom_db", hearing.currentHeadroomDb.toDouble().takeIf { !it.isNaN() } ?: 17.0)
                            put("daily_exposure_minutes", hearing.dailyExposureMinutes)
                            put("output_device", hearing.activeSnapshot?.deviceType ?: "Phone / Wireless Audio")
                            put("safety_guidance", hearing.safetyGuidance)
                        })
                    }

                    val vaultArr = org.json.JSONArray()
                    for (vt in vaultList) {
                        vaultArr.put(JSONObject().apply {
                            put("id", vt.id)
                            put("title", vt.title)
                            put("artist", vt.artist)
                            put("cooldown_days_remaining", vt.cooldownDaysRemaining.toDouble().takeIf { !it.isNaN() } ?: 3.0)
                            put("recovery_percent", (vt.recoveryProgress * 100).toInt())
                            put("satiety_score", vt.satietyScore)
                        })
                    }
                    put("vault_tracks", vaultArr)

                    if (capsule != null && capsule.tracks.isNotEmpty()) {
                        val capArr = org.json.JSONArray()
                        for (ct in capsule.tracks) {
                            capArr.put(JSONObject().apply {
                                put("title", ct.title)
                                put("artist", ct.artist)
                                put("trackId", ct.spotifyUri ?: "")
                                put("freshness_percent", ct.freshnessPercent)
                                put("reason", ct.reason)
                            })
                        }
                        put("dopamine_capsule", capArr)
                    }

                    if (pendingProp != null) {
                        put("pending_proposed_track", JSONObject().apply {
                            put("title", pendingProp.title)
                            put("artist", pendingProp.artist)
                            put("trackId", pendingProp.trackId ?: "")
                            put("source", pendingProp.source)
                        })
                    }
                    val identity = com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.resolveUserIdentity(context)
                    put("user_id", identity.userId)
                    put("user_email", identity.email)
                    put("supabase_url", identity.supabaseUrl)
                    put("supabase_anon_key", identity.supabaseAnonKey)
                    put("dialogue_history", getDialogueHistoryJsonArray())
                    put("spotify_access_token", getCurrentSpotifyAccessTokenBlocking())
                }

                val baseUrl = com.tunemind.app.domain.network.BackendEndpointManager.getActiveHttpBaseUrl(context).trimEnd('/')
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/reasoning/evaluate-satiety")
                    .post(bodyJson.toString().toRequestBody(mediaType))
                    .build()

                val resp = reasoningHttpClient.newCall(req).execute()
                val respStr = resp.body?.string()
                resp.close()

                if (resp.isSuccessful && !respStr.isNullOrBlank()) {
                    val respJson = JSONObject(respStr)
                    dispatchReasoningResult(respJson, respJson.optString("voice_agent_tts", ""))
                    return@launch
                }
            } catch (e: Exception) {
                Log.w(TAG, "HTTP Claude reasoning fallback notice, using on-device smart agent: ${e.message}")
            }

            // Local On-Device Smart Agent Fallback (works 100% offline)
            val localJson = buildLocalOnDeviceFallbackJson(cleaned)
            dispatchReasoningResult(localJson, localJson.optString("voice_agent_tts", ""))
        }
    }

    private fun buildLocalOnDeviceFallbackJson(transcript: String): JSONObject {
        val lower = transcript.lowercase().trim()
        val np = getNowPlaying?.invoke()
        val recents = getRecentTracks?.invoke() ?: emptyList()
        val hearing = getHearingInsights?.invoke()
        val capsule = getDopamineCapsule?.invoke()
        val pending = clientPendingProposedTrack
        val targetPlayer = np?.sourcePackage?.takeIf { it.isNotBlank() } ?: "com.spotify.music"

        // 1. Pause / Stop
        if (Regex("\\b(pause|stop|quiet|mute|silence|hold on|simama|nyamaza)\\b").containsMatchIn(lower)) {
            return JSONObject().apply {
                put("calculated_satiety_percentage", 25)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Playback paused via on-device studio control.")
                put("voice_agent_tts", "Paused your music. Let me know whenever you want to spin something else.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "PAUSE_PLAYBACK")
                    put("target_player", targetPlayer)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "OS_MEDIA_PAUSE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }

        // 2. Resume
        if (Regex("\\b(resume|unpause|continue playing|keep playing)\\b").containsMatchIn(lower)) {
            return JSONObject().apply {
                put("calculated_satiety_percentage", 25)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Playback resumed via on-device studio control.")
                put("voice_agent_tts", "Resuming your music right now.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "RESUME_PLAYBACK")
                    put("target_player", targetPlayer)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "OS_MEDIA_RESUME")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }

        // 3. Approval of pending proposed track
        if (pending != null && Regex("\\b(yes|yeah|yep|sure|okay|ok|play|play it|go ahead|do it|sounds good|ndio|sawa|spin it)\\b").containsMatchIn(lower) &&
            !Regex("\\b(no|nope|nah|not that|don'?t|another|different|change|switch|skip|else|next)\\b").containsMatchIn(lower)
        ) {
            val artistPart = if (pending.artist.isNotBlank() && pending.artist != "Unknown Artist") " by ${pending.artist}" else ""
            return JSONObject().apply {
                put("calculated_satiety_percentage", 20)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Approved ${pending.title} — launching playback.")
                put("voice_agent_tts", "Spinning ${pending.title}$artistPart for you right now.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "PLAY_TRACK_NOW")
                    put("track_title", pending.title)
                    put("track_artist", pending.artist)
                    put("spotify_uri", pending.trackId ?: "")
                    put("target_player", pending.source)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "OS_MEDIA_NEXT_AND_DEEP_LINK")
                    put("target_player", pending.source)
                    put("search_query", "${pending.title} ${pending.artist}".trim())
                    put("track_title", pending.title)
                    put("track_artist", pending.artist)
                    put("spotify_uri", pending.trackId ?: "")
                })
            }
        }

        // 4. Skip Next / Previous
        if (Regex("\\b(skip|next song|next track)\\b").containsMatchIn(lower)) {
            return JSONObject().apply {
                put("calculated_satiety_percentage", 25)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Skipping to next track in queue.")
                put("voice_agent_tts", "Skipping to your next track right now.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "SKIP_NEXT")
                    put("target_player", targetPlayer)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "OS_MEDIA_NEXT")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }
        if (Regex("\\b(previous|go back|last song)\\b").containsMatchIn(lower)) {
            return JSONObject().apply {
                put("calculated_satiety_percentage", 25)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Returning to previous track.")
                put("voice_agent_tts", "Going back to the previous track.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "SKIP_PREVIOUS")
                    put("target_player", targetPlayer)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "OS_MEDIA_PREV")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }

        // 5. Volume Adjustment
        if (Regex("\\b(volume|louder|quieter|turn it down|turn it up|lower the volume|raise the volume)\\b").containsMatchIn(lower)) {
            val numMatch = Regex("(\\d{1,3})").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val targetVol = when {
                numMatch != null -> numMatch.coerceIn(15, 100)
                lower.contains("down") || lower.contains("quiet") || lower.contains("lower") -> 45
                else -> 75
            }
            return JSONObject().apply {
                put("calculated_satiety_percentage", 24)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Adjusted system media volume to $targetVol% for optimal acoustic headroom.")
                put("voice_agent_tts", "Set your media volume to $targetVol percent to keep your hearing in the sweet spot.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "SET_VOLUME_PERCENT")
                    put("volume_percent", targetVol)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }

        // 6. Theme / Atmosphere Shift
        if (Regex("\\b(theme|atmosphere|color|palette|mood light)\\b").containsMatchIn(lower)) {
            return JSONObject().apply {
                put("calculated_satiety_percentage", 22)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Shifting Studio Adaptive Atmosphere palette.")
                put("voice_agent_tts", "Shifted your studio atmosphere palette to match your current vibe.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "SHIFT_ATMOSPHERE")
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }

        // 7. Cooldown Vault Lock / Restore or Screen Navigation
        if (Regex("\\b(quarantine|lock this|put in vault|cooldown vault)\\b").containsMatchIn(lower)) {
            val curTitle = np?.title ?: recents.firstOrNull()?.title ?: "Current Track"
            val curArtist = np?.artist ?: recents.firstOrNull()?.artist ?: ""
            return JSONObject().apply {
                put("calculated_satiety_percentage", 78)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Locked $curTitle in the Cooldown Vault to restore dopamine sensitivity.")
                put("voice_agent_tts", "I've locked $curTitle into your Cooldown Vault so its dopamine hit can fully recover.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "QUARANTINE_TO_VAULT")
                    put("track_title", curTitle)
                    put("track_artist", curArtist)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }
        if (Regex("\\b(open vault|show vault|go to vault|open rhythm|show dna|rhythm dna)\\b").containsMatchIn(lower)) {
            val screen = if (lower.contains("rhythm") || lower.contains("dna")) "RHYTHM" else "DOPAMINE"
            return JSONObject().apply {
                put("calculated_satiety_percentage", 24)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Navigating studio view to $screen screen.")
                put("voice_agent_tts", "Opening your ${if (screen == "RHYTHM") "Rhythm DNA and Hearing" else "Dopamine Vault"} screen right now.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "NAVIGATE_SCREEN")
                    put("target_screen", screen)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }

        // 8. Hearing / Ears check
        if (Regex("\\b(hearing|ears|decibel|db|loud|safe|exposure)\\b").containsMatchIn(lower)) {
            val db = hearing?.estimatedAverageDb?.toInt() ?: 68
            val headroom = hearing?.currentHeadroomDb?.toInt() ?: 17
            val mins = hearing?.dailyExposureMinutes ?: 40
            return JSONObject().apply {
                put("calculated_satiety_percentage", 24)
                put("reasoning_mode", "JOURNEY_ADVISOR")
                put("flow_state_analysis", "Hearing Telemetry: ${db}dB • ${headroom}dB safe headroom • ${mins}m today")
                put("voice_agent_tts", "Your hearing exposure is sitting around $db decibels with $headroom decibels of safe headroom after $mins minutes today. Want me to adjust the volume or pick a calmer track?")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "ADVISE_ONLY")
                    put("expect_follow_up", true)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }

        // 9. Most-Played Song Request ("play the song that I have listened the most", "favorite song", "top song")
        if (isMostPlayedSongRequest(lower)) {
            val topTrack = com.tunemind.app.provider.SpotifyTrackUriResolver.findMostPlayedTrackBlocking(context, recents)
            if (topTrack != null) {
                val artistPart = if (topTrack.artistName.isNotBlank() && topTrack.artistName != "Unknown Artist") " by ${topTrack.artistName}" else ""
                val countText = if (topTrack.playCount > 1) " with ${topTrack.playCount} plays in your Spotify history" else " at the top of your Spotify rotation"
                return JSONObject().apply {
                    put("calculated_satiety_percentage", 22)
                    put("reasoning_mode", "PLAYBACK_SUGGESTION")
                    put("flow_state_analysis", "Top Track: \"${topTrack.trackTitle}\"$artistPart (${topTrack.playCount}x plays) — awaiting approval.")
                    put("voice_agent_tts", "Your most-listened song is ${topTrack.trackTitle}$artistPart$countText. Would you like me to play it right now?")
                    put("agent_tool_call", JSONObject().apply {
                        put("action", "PROPOSE_TRACK")
                        put("track_title", topTrack.trackTitle)
                        put("track_artist", topTrack.artistName)
                        put("spotify_uri", topTrack.spotifyUri)
                        put("target_player", targetPlayer)
                        put("expect_follow_up", true)
                    })
                    put("os_execution_command", JSONObject().apply {
                        put("intent", "NONE")
                        put("target_player", targetPlayer)
                        put("search_query", "${topTrack.trackTitle} ${topTrack.artistName}".trim())
                        put("track_title", topTrack.trackTitle)
                        put("track_artist", topTrack.artistName)
                        put("spotify_uri", topTrack.spotifyUri)
                    })
                }
            }
        }

        // 10. New Song / Discovery / Recommendation Request ("play for me a new song", "recommend a song")
        if (isNewOrRecommendSongRequest(lower)) {
            val excluded = (getRecentlyPlayedByAgent() + listOfNotNull(np?.title)).toSet()
            val discovery = com.tunemind.app.provider.SpotifyTrackUriResolver.fetchNewDiscoveryRecommendationBlocking(
                context = context,
                fallbackMemoryTracks = recents,
                excludedTitles = excluded
            )
            if (discovery != null) {
                val artistPart = if (discovery.artistName.isNotBlank() && discovery.artistName != "Unknown Artist") " by ${discovery.artistName}" else ""
                val mood = com.tunemind.app.ui.theme.AdaptiveThemeEngine.resolveTrackMood(discovery.trackTitle, discovery.artistName)
                return JSONObject().apply {
                    put("calculated_satiety_percentage", 18)
                    put("reasoning_mode", "PLAYBACK_SUGGESTION")
                    put("flow_state_analysis", "Spotify Discovery: \"${discovery.trackTitle}\"$artistPart • ${mood.label} Mood DNA.")
                    put("voice_agent_tts", "Based on your Spotify listening profile, I picked a fresh song for you: ${discovery.trackTitle}$artistPart. Would you like me to play it right now?")
                    put("agent_tool_call", JSONObject().apply {
                        put("action", "PROPOSE_TRACK")
                        put("track_title", discovery.trackTitle)
                        put("track_artist", discovery.artistName)
                        put("spotify_uri", discovery.spotifyUri)
                        put("target_player", targetPlayer)
                        put("expect_follow_up", true)
                    })
                    put("os_execution_command", JSONObject().apply {
                        put("intent", "NONE")
                        put("target_player", targetPlayer)
                        put("search_query", "${discovery.trackTitle} ${discovery.artistName}".trim())
                        put("track_title", discovery.trackTitle)
                        put("track_artist", discovery.artistName)
                        put("spotify_uri", discovery.spotifyUri)
                    })
                }
            }
        }

        // 11. Explicit Song Title / Artist Request (Only if NOT a conversational phrase!)
        val cleanNoPunct = lower.replace(Regex("[.!?,;:'\"]+"), " ").replace(Regex("\\s+"), " ").trim()
        val explicitMatch = Regex("^\\s*(please\\s+)?(play|spin|put on)\\s+(.+)$").find(cleanNoPunct)
        val rawNamedTarget = explicitMatch?.groupValues?.getOrNull(3)
            ?.replace(Regex("^(me\\s+|for me\\s+)"), "")
            ?.replace(Regex("\\b(right now|now|for me|please|on spotify)\\b"), "")
            ?.trim()

        if (!rawNamedTarget.isNullOrBlank() && rawNamedTarget.length >= 2 && !isConversationalNonTitlePhrase(rawNamedTarget)) {
            val unified = com.tunemind.app.provider.SpotifyTrackUriResolver.getUnifiedUserSpotifyTracksBlocking(context, recents)
            val matchedLocal = unified.firstOrNull { u ->
                val ut = u.trackTitle.lowercase().trim()
                val ua = u.artistName.lowercase().trim()
                (ut.isNotEmpty() && (ut.contains(rawNamedTarget) || rawNamedTarget.contains(ut))) ||
                (ua.isNotEmpty() && ua != "unknown artist" && rawNamedTarget.contains(ua))
            }
            val finalTitle = matchedLocal?.trackTitle ?: rawNamedTarget.replaceFirstChar { it.uppercase() }
            val finalArtist = matchedLocal?.artistName ?: ""
            val finalUri = matchedLocal?.spotifyUri?.takeIf { it.startsWith("spotify:track:") }
                ?: com.tunemind.app.provider.SpotifyTrackUriResolver.resolveTrackUriBlocking(
                    context = context,
                    cleanTitle = finalTitle,
                    cleanArtist = finalArtist,
                    fullQuery = "$finalTitle $finalArtist".trim()
                ) ?: ""
            val artistPart = if (finalArtist.isNotBlank() && finalArtist != "Unknown Artist") " by $finalArtist" else ""
            return JSONObject().apply {
                put("calculated_satiety_percentage", 20)
                put("reasoning_mode", "PLAYBACK_SUGGESTION")
                put("flow_state_analysis", "Proposed \"$finalTitle\"$artistPart — awaiting your approval to play.")
                put("voice_agent_tts", "I found $finalTitle$artistPart ready for your session. Would you like me to play it right now?")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "PROPOSE_TRACK")
                    put("track_title", finalTitle)
                    put("track_artist", finalArtist)
                    put("spotify_uri", finalUri)
                    put("target_player", targetPlayer)
                    put("expect_follow_up", true)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "$finalTitle $finalArtist".trim())
                    put("track_title", finalTitle)
                    put("track_artist", finalArtist)
                    put("spotify_uri", finalUri)
                })
            }
        }

        // 11.5 Conversational / Open Dialogue Turn ("how are you", greetings, questions, feelings, chat)
        // NEVER propose a song unless the user explicitly asked to play or recommend music!
        if (!isExplicitMusicPlayOrRecommendRequest(lower)) {
            val activeTitle = np?.title?.takeIf { it.isNotBlank() && it != "Standing By" }
            val activeArtist = np?.artist?.takeIf { it.isNotBlank() && it != "Unknown Artist" }
            val playingNote = if (activeTitle != null) {
                " while ${activeTitle}${if (activeArtist != null) " by $activeArtist" else ""} is in your session"
            } else {
                ""
            }

            val conversationalReply = when {
                Regex("\\b(how are you|how're you|how are u|how is it going|how's it going|how you doing|habari|mambo|niaje|sasa)\\b").containsMatchIn(lower) ->
                    "I'm doing great, thanks for asking! My neural audio engine is dialed in$playingNote. How are you feeling today, and what kind of vibe are you in the mood for?"

                Regex("\\b(hi|hello|hey|yo|good morning|good afternoon|good evening|greetings)\\b").containsMatchIn(lower) ->
                    "Hey there! Good to hear your voice. How's your day going so far?"

                Regex("\\b(who are you|what are you|what can you do|help|capabilities)\\b").containsMatchIn(lower) ->
                    "I'm TuneMind, your autonomous music companion. We can just chat about your day, check your hearing headroom and taste DNA, or whenever you're ready I can find and play music on Spotify. What's on your mind?"

                Regex("\\b(what is playing|what's playing|what song is this|current song|currently playing)\\b").containsMatchIn(lower) -> {
                    if (activeTitle != null) {
                        "Right now you have $activeTitle${if (activeArtist != null) " by $activeArtist" else ""} loaded up. How is it hitting for you?"
                    } else {
                        "Nothing is actively spinning right now. Let me know if you want to chat or if you'd like me to put something on."
                    }
                }

                Regex("\\b(tired|exhausted|stressed|sad|down|anxious|overwhelmed|bored|sleepy)\\b").containsMatchIn(lower) ->
                    "I hear you—some days really take it out of you. Do you want to just unwind and talk for a bit, or would you like me to put on something calm and restorative when you're ready?"

                Regex("\\b(good|great|awesome|fine|okay|doing well|happy|excited|chill|relaxed|fantastic|amazing)\\b").containsMatchIn(lower) ->
                    "Love to hear that! Are you just chilling right now, working on something, or in the mood to explore some music together?"

                Regex("\\b(thank|thanks|appreciate|asante|shukran)\\b").containsMatchIn(lower) ->
                    "Anytime! I'm right here whenever you want to talk or switch up the vibe."

                else ->
                    "I'm right here with you. Tell me more about what's on your mind, or let me know if you'd like me to do anything with your music."
            }

            return JSONObject().apply {
                put("calculated_satiety_percentage", _agentSatietyFlow.value)
                put("reasoning_mode", "JOURNEY_ADVISOR")
                put("flow_state_analysis", "Conversational Neural Dialogue • Listening & reasoning with you.")
                put("voice_agent_tts", conversationalReply)
                put("agent_tool_call", JSONObject().apply {
                    put("action", "ADVISE_ONLY")
                    put("target_player", targetPlayer)
                    put("expect_follow_up", true)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }

        // 12. Explicit music request fallback: Pick freshest unplayed track from capsule or unified Spotify history, propose it, and wait for user approval
        val excluded = (getRecentlyPlayedByAgent() + listOfNotNull(np?.title)).toSet()
        val discoveryFallback = com.tunemind.app.provider.SpotifyTrackUriResolver.fetchNewDiscoveryRecommendationBlocking(
            context = context,
            fallbackMemoryTracks = recents,
            excludedTitles = excluded
        )
        val recentSuggested = getRecentlyPlayedByAgent().map { it.lowercase() }.toSet()
        val freshPick = capsule?.tracks?.firstOrNull { ct ->
            !recentSuggested.contains(ct.title.lowercase().trim())
        } ?: capsule?.tracks?.firstOrNull()

        val chosenTitle = com.tunemind.app.provider.MusicPlayerAutoLauncher.sanitizeTrackTitleForPlay(
            discoveryFallback?.trackTitle ?: freshPick?.title ?: "Midnight City"
        )
        val chosenArtist = com.tunemind.app.provider.MusicPlayerAutoLauncher.sanitizeArtistForPlay(
            discoveryFallback?.artistName ?: freshPick?.artist ?: "M83"
        )
        val chosenUri = discoveryFallback?.spotifyUri?.takeIf { it.startsWith("spotify:track:") }
            ?: freshPick?.spotifyUri
            ?: ""
        val mood = com.tunemind.app.ui.theme.AdaptiveThemeEngine.resolveTrackMood(chosenTitle, chosenArtist)
        val artistPart = if (chosenArtist.isNotBlank()) " by $chosenArtist" else ""

        return JSONObject().apply {
            put("calculated_satiety_percentage", 22)
            put("reasoning_mode", "PLAYBACK_SUGGESTION")
            put("flow_state_analysis", "Recommending $chosenTitle$artistPart • ${mood.label} Mood DNA.")
            put("voice_agent_tts", "$chosenTitle$artistPart carries a ${mood.label.lowercase()} cadence with zero receptor fatigue in your recent rotation. Want me to spin it right now?")
            put("agent_tool_call", JSONObject().apply {
                put("action", "PROPOSE_TRACK")
                put("track_title", chosenTitle)
                put("track_artist", chosenArtist)
                put("spotify_uri", chosenUri)
                put("target_player", targetPlayer)
                put("expect_follow_up", true)
            })
            put("os_execution_command", JSONObject().apply {
                put("intent", "NONE")
                put("target_player", targetPlayer)
                put("search_query", "$chosenTitle $chosenArtist".trim())
                put("track_title", chosenTitle)
                put("track_artist", chosenArtist)
                put("spotify_uri", chosenUri)
            })
        }
    }

    private fun startNativeSpeechRecognizer() {
        mainHandler.post {
            try {
                if (!AndroidSpeechRecognizer.isRecognitionAvailable(context)) {
                    releaseAudioDucking()
                    resumeWakeWordListening()
                    return@post
                }

                stopNativeSpeechRecognizer()

                nativeRecognizer = AndroidSpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : AndroidSpeechListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "Native SpeechRecognizer ready")
                            _agentState.value = AgentState.LISTENING
                        }

                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "Native SpeechRecognizer speech started")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            Log.d(TAG, "Native SpeechRecognizer speech ended")
                            setThinkingState()
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "Native SpeechRecognizer finished with code: $error")
                            stopNativeSpeechRecognizer()
                            if (isAwaitingEndOfSongUserChoice) {
                                Log.i(TAG, "No user speech detected after end-of-song prompt (code=$error) — auto-curating next track with Claude!")
                                isAwaitingEndOfSongUserChoice = false
                                endOfSongAutoPlayTimeoutJob?.cancel()
                                autoCurateAndPlayNextTrackWithClaude(
                                    lastEndOfSongFinishedTitle,
                                    lastEndOfSongFinishedArtist,
                                    lastEndOfSongTargetPlayer
                                )
                                return
                            }
                            releaseAudioDucking()
                            resumeWakeWordListening()
                            scope.launch {
                                _transcriptionFlow.emit("I'm right here whenever you're ready—tap the mascot and speak freely.")
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
                            val transcript = matches?.firstOrNull()?.trim()
                            if (!transcript.isNullOrBlank()) {
                                Log.d(TAG, "Native SpeechRecognizer transcript: $transcript")
                                endOfSongAutoPlayTimeoutJob?.cancel()
                                setThinkingState()
                                scope.launch {
                                    _transcriptionFlow.emit(transcript)
                                    _finalTranscriptFlow.emit(transcript)
                                }
                            } else if (isAwaitingEndOfSongUserChoice) {
                                isAwaitingEndOfSongUserChoice = false
                                endOfSongAutoPlayTimeoutJob?.cancel()
                                autoCurateAndPlayNextTrackWithClaude(
                                    lastEndOfSongFinishedTitle,
                                    lastEndOfSongFinishedArtist,
                                    lastEndOfSongTargetPlayer
                                )
                            } else {
                                releaseAudioDucking()
                                resumeWakeWordListening()
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
                            val transcript = matches?.firstOrNull()?.trim()
                            if (!transcript.isNullOrBlank()) {
                                scope.launch {
                                    _transcriptionFlow.emit(transcript)
                                }
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                nativeRecognizer?.startListening(intent)
                _agentState.value = AgentState.LISTENING
                Log.d(TAG, "Native SpeechRecognizer listening started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start native speech recognizer", e)
                releaseAudioDucking()
                resumeWakeWordListening()
            }
        }
    }

    private fun stopNativeSpeechRecognizer() {
        mainHandler.post {
            try {
                nativeRecognizer?.stopListening()
                nativeRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping native recognizer", e)
            }
            nativeRecognizer = null
        }
    }

    private fun handleVoiceError(reason: String?) {
        Log.e(TAG, "Voice Error notice: $reason")
        stopMicrophoneStream()
        stopNativeSpeechRecognizer()
        releaseAudioDucking()
        resumeWakeWordListening()
    }

    private fun resolveWebSocketUrl(): String {
        val baseUrl = com.tunemind.app.BuildConfig.TUNEMIND_BASE_URL.trim().trimEnd('/')
        return if (baseUrl.startsWith("https://")) {
            baseUrl.replaceFirst("https://", "wss://") + "/ws/stt"
        } else if (baseUrl.startsWith("http://")) {
            baseUrl.replaceFirst("http://", "ws://") + "/ws/stt"
        } else {
            "ws://10.0.2.2:3000/ws/stt"
        }
    }

    private fun startLocalAudioCapture() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            handleVoiceError("Microphone permission missing")
            return
        }
        stopAudioRecordingOnly()
        preConnectAudioBuffer.clear()
        isWsOpenForAudio = false

        val sampleRate = 16000
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBufferSize * 4, 16000)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                Thread.sleep(65)
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord creation notice: ${e.message}")
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized; falling back to native SpeechRecognizer.")
            fallbackToNativeSpeechRecognizer("AudioRecord uninitialized")
            return
        }

        val sessionId = audioRecord?.audioSessionId ?: 0
        if (sessionId != 0) {
            try {
                if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                    automaticGainControl = android.media.audiofx.AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                }
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                }
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = android.media.audiofx.AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio effects initialization notice: ${e.message}")
            }
        }

        try {
            audioRecord?.startRecording()
            isRecording = true
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.startRecording failed: ${e.message}")
            fallbackToNativeSpeechRecognizer("startRecording failed")
            return
        }

        scope.launch {
            // Read exact 100ms frames (3200 bytes at 16kHz 16-bit mono) so AssemblyAI v3 never receives <20ms sub-frames
            val targetFrameBytes = 3200
            val frameBuffer = ByteArray(targetFrameBytes)
            while (isRecording && _agentState.value == AgentState.LISTENING) {
                var accumulated = 0
                while (accumulated < targetFrameBytes && isRecording && _agentState.value == AgentState.LISTENING) {
                    val read = audioRecord?.read(frameBuffer, accumulated, targetFrameBytes - accumulated) ?: -1
                    if (read > 0) {
                        accumulated += read
                    } else {
                        break
                    }
                }
                // Only send frames of at least 1600 bytes (50ms, even byte count) to guarantee AssemblyAI v3 compliance
                val validLen = accumulated - (accumulated % 2)
                if (validLen >= 1600) {
                    val boostedBytes = boostPcm16VoiceClarity(frameBuffer, validLen)
                    val byteStr = boostedBytes.toByteString()
                    val activeWs = webSocket
                    if (isWsOpenForAudio && activeWs != null) {
                        while (preConnectAudioBuffer.isNotEmpty()) {
                            val buffered = preConnectAudioBuffer.poll() ?: break
                            activeWs.send(buffered)
                        }
                        activeWs.send(byteStr)
                    } else {
                        // Keep up to 4 seconds (40 frames of 100ms) of pre-connection speech in memory while socket connects
                        if (preConnectAudioBuffer.size < 40) {
                            preConnectAudioBuffer.offer(byteStr)
                        }
                    }
                }
            }
        }
    }

    /**
     * Applies a clean 1.75x software microphone gain with soft saturation clamping on 16-bit little-endian PCM
     * so quiet or arm's-length speech is crystal clear for AssemblyAI word recognition.
     */
    private fun boostPcm16VoiceClarity(buffer: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var i = 0
        while (i + 1 < length) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()
            val sample = (high shl 8) or low // signed 16-bit
            val boosted = (sample * 7) / 4   // 1.75x gain
            val clamped = boosted.coerceIn(-32760, 32760)
            out[i] = (clamped and 0xFF).toByte()
            out[i + 1] = ((clamped shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun stopAudioRecordingOnly() {
        isRecording = false
        isWsOpenForAudio = false
        preConnectAudioBuffer.clear()
        try {
            automaticGainControl?.release()
        } catch (_: Exception) {}
        automaticGainControl = null
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null
        try {
            echoCanceler?.release()
        } catch (_: Exception) {}
        echoCanceler = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioRecord", e)
        }
        audioRecord = null
        Log.d(TAG, "Microphone recording stopped, socket kept open for reasoning response.")
    }

    private fun stopMicrophoneStream() {
        stopAudioRecordingOnly()
        try {
            webSocket?.close(1000, "Finished speaking")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing webSocket", e)
        }
        webSocket = null
        Log.d(TAG, "Microphone stream stopped.")
    }

    fun resumeWakeWordListening() {
        if (isAppInForeground && !isVoskListening && _agentState.value == AgentState.IDLE) {
            startWakeWordListener()
        }
    }

    fun abandonAssistantAudioFocusOnly() {
        audioFocusRequest?.let {
            try {
                audioManager.abandonAudioFocusRequest(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error abandoning audio focus: ${e.message}")
            }
        }
        audioFocusRequest = null
        Log.d(TAG, "Assistant audio focus abandoned so target music player can claim audio focus.")
    }

    fun releaseAudioDucking() {
        abandonAssistantAudioFocusOnly()
        _agentState.value = AgentState.IDLE
        Log.d(TAG, "Audio ducking released. Agent state returned to IDLE.")
    }

    fun setThinkingState() {
        _agentState.value = AgentState.THINKING
    }

    fun setSpeakingState() {
        _agentState.value = AgentState.SPEAKING
    }

    fun destroy() {
        errorResetJob?.cancel()
        stopMicrophoneStream()
        stopNativeSpeechRecognizer()
        releaseAudioDucking()
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down Vosk SpeechService", e)
        }
        speechService = null
        isVoskListening = false
        try {
            executionEngine.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down executionEngine", e)
        }
        scope.cancel()
    }
}
