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

    @Volatile
    private var clientPendingProposedTrack: PendingProposedTrack? = null

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

    // Native Speech Recognizer Fallback
    private var nativeRecognizer: AndroidSpeechRecognizer? = null
    private var errorResetJob: Job? = null

    init {
        initWakeWordEngine()
        startCloudKeepAlivePing()
    }

    private fun startCloudKeepAlivePing() {
        scope.launch {
            while (isActive) {
                try {
                    val baseUrl = com.tunemind.app.domain.network.BackendEndpointManager.getActiveHttpBaseUrl(context)
                    val healthUrl = "${baseUrl.trimEnd('/')}/health"
                    val req = Request.Builder().url(healthUrl).get().build()
                    client.newCall(req).execute().close()
                    Log.d(TAG, "☁️ Cloud Brain keep-alive ping succeeded: $healthUrl")
                } catch (e: Exception) {
                    Log.w(TAG, "☁️ Cloud Brain keep-alive ping notice: ${e.message}")
                }
                delay(8 * 60 * 1000L) // Ping every 8 minutes so Render free tier never spins down while app is open
            }
        }
    }

    private fun initWakeWordEngine() {
        try {
            StorageService.unpack(context, "model-en", "model",
                { model ->
                    this.voskModel = model
                    Log.d(TAG, "Vosk Model loaded successfully.")
                    startWakeWordListener()
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
            Log.d(TAG, "Vosk is now listening for the wake word in the background...")
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
        if (jsonStr == null || _agentState.value != AgentState.IDLE) return

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

    // Wake Word / Manual Orb tap handler
    fun simulateWakeWordDetected() {
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
     * Triggered when the user taps the circular ▶ button on any song card.
     * Speaks a brief confirmation and immediately launches playback on the target player.
     */
    fun playTrackImmediatelyWithVoice(
        title: String,
        artist: String,
        spotifyUri: String? = null,
        targetPlayer: String = "com.spotify.music"
    ) {
        errorResetJob?.cancel()
        stopMicrophoneStream()
        stopNativeSpeechRecognizer()
        executionEngine.stopSpeaking()
        clientPendingProposedTrack = null

        val cleanTitle = title.trim()
        val cleanArtist = artist.trim().takeIf { !it.equals("Unknown Artist", ignoreCase = true) } ?: ""
        val spokenLine = if (cleanArtist.isNotEmpty()) {
            "Playing $cleanTitle by $cleanArtist now."
        } else {
            "Playing $cleanTitle now."
        }

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
                    releaseAudioDucking()
                    resumeWakeWordListening()
                }
            }
        )
    }

    /**
     * Triggered when the user selects Neural Voice Breakdown & Dialogue on any song card.
     * Speaks a tailored dopamine & mood insight about the track, stores it as pending proposal,
     * and opens the microphone after 650ms to listen for approval or follow-up dialogue.
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

        clientPendingProposedTrack = PendingProposedTrack(
            title = cleanTitle,
            artist = cleanArtist,
            trackId = spotifyUri,
            source = targetPlayer
        )

        val spokenProposal = if (freshnessPercent >= 65) {
            if (cleanArtist.isNotEmpty()) {
                "$cleanTitle by $cleanArtist is at $freshnessPercent percent dopamine freshness with a ${mood.label.lowercase()} vibe. Want me to spin it for you right now?"
            } else {
                "$cleanTitle is sitting at $freshnessPercent percent dopamine freshness. Want me to spin it right now?"
            }
        } else {
            if (cleanArtist.isNotEmpty()) {
                "$cleanTitle by $cleanArtist has been in heavy rotation lately, sitting at $freshnessPercent percent freshness. Want me to spin it anyway, or find a fresh reset?"
            } else {
                "$cleanTitle is cooling down at $freshnessPercent percent freshness. Want me to play it anyway, or pick something fresh?"
            }
        }

        _agentState.value = AgentState.SPEAKING
        _flowAnalysisFlow.value = "Mood DNA: ${mood.label} • $freshnessPercent% Dopamine Readiness"
        _agentSatietyFlow.value = (100 - freshnessPercent).coerceIn(10, 90)
        scope.launch {
            _transcriptionFlow.emit(spokenProposal)
        }

        val responseObj = com.tunemind.app.domain.execution.ReasoningResponse(
            calculated_satiety_percentage = (100 - freshnessPercent).coerceIn(10, 90),
            flow_state_analysis = "Mood DNA: ${mood.label} • $freshnessPercent% Dopamine Readiness",
            voice_agent_tts = spokenProposal,
            os_execution_command = com.tunemind.app.domain.execution.OsExecutionCommand(
                intent = "NONE",
                target_player = targetPlayer,
                search_query = if (cleanArtist.isNotEmpty()) "$cleanTitle $cleanArtist" else cleanTitle,
                suggested_bpm_target = 108,
                track_title = cleanTitle,
                track_artist = cleanArtist,
                spotify_uri = spotifyUri
            )
        )

        executionEngine.executeBackendCommand(
            response = responseObj,
            onFinished = {
                mainHandler.post {
                    _agentState.value = AgentState.IDLE
                    mainHandler.postDelayed({
                        if (_agentState.value == AgentState.IDLE) {
                            simulateWakeWordDetected()
                        }
                    }, 650L)
                }
            }
        )
    }

    /**
     * Triggered when the user taps the glowing Dopamine Capsule card on the Studio Mascot screen.
     * Briefs the user on the 3 compacted tracks prepared for their current circadian slot and
     * waits for voice approval to launch the lead capsule track.
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

        clientPendingProposedTrack = PendingProposedTrack(
            title = lead.title,
            artist = lead.artist,
            trackId = lead.spotifyUri,
            source = targetPlayer
        )

        val secondPart = tracks.getOrNull(1)?.let { ", alongside ${it.title}" } ?: ""
        val thirdPart = tracks.getOrNull(2)?.let { " and ${it.title}" } ?: ""
        val artistPart = if (lead.artist.isNotBlank() && !lead.artist.equals("Unknown Artist", ignoreCase = true)) {
            " by ${lead.artist}"
        } else ""

        val spokenBriefing = "For your ${capsule.slotLabel.lowercase()}, I compacted ${tracks.size} peak-dopamine tracks led by ${lead.title}$artistPart at ${lead.freshnessPercent} percent freshness$secondPart$thirdPart. Want me to kick off your capsule with ${lead.title} right now?"

        _agentState.value = AgentState.SPEAKING
        _flowAnalysisFlow.value = "Dopamine Capsule Ready: ${tracks.joinToString(" • ") { it.title }}"
        _agentSatietyFlow.value = (100 - lead.freshnessPercent).coerceIn(10, 45)
        scope.launch {
            _transcriptionFlow.emit(spokenBriefing)
        }

        val responseObj = com.tunemind.app.domain.execution.ReasoningResponse(
            calculated_satiety_percentage = (100 - lead.freshnessPercent).coerceIn(10, 45),
            flow_state_analysis = "Dopamine Capsule Ready: ${tracks.joinToString(" • ") { it.title }}",
            voice_agent_tts = spokenBriefing,
            os_execution_command = com.tunemind.app.domain.execution.OsExecutionCommand(
                intent = "NONE",
                target_player = targetPlayer,
                search_query = "${lead.title} ${lead.artist}".trim(),
                suggested_bpm_target = 108,
                track_title = lead.title,
                track_artist = lead.artist,
                spotify_uri = lead.spotifyUri
            )
        )

        executionEngine.executeBackendCommand(
            response = responseObj,
            onFinished = {
                mainHandler.post {
                    _agentState.value = AgentState.IDLE
                    mainHandler.postDelayed({
                        if (_agentState.value == AgentState.IDLE) {
                            simulateWakeWordDetected()
                        }
                    }, 650L)
                }
            }
        )
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
                    val hearing = getHearingInsights?.invoke()
                    val vaultList = getVaultTracks?.invoke() ?: emptyList()
                    val capsule = getDopamineCapsule?.invoke()
                    val pendingProp = clientPendingProposedTrack

                    val metaJson = JSONObject().apply {
                        put("type", "client_context")
                        val currentTrackId = np?.let { cur ->
                            recents.firstOrNull { t -> t.title.equals(cur.title, ignoreCase = true) }?.providerTrackId
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
                        for (r in recents.take(50)) {
                            historyArr.put(JSONObject().apply {
                                put("title", r.title ?: "")
                                put("artist", r.artist ?: "")
                                put("source", r.sourcePackage ?: "com.spotify.music")
                                put("playCount", r.distinctAppearanceCount)
                                put("trackId", r.providerTrackId ?: "")
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
                            clientPendingProposedTrack = null
                        }
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
                        Log.w(TAG, "Server reported error: ${json.optString("error")}")
                        stopMicrophoneStream()
                        _agentState.value = AgentState.ERROR
                        errorResetJob?.cancel()
                        errorResetJob = scope.launch {
                            delay(2500)
                            withContext(Dispatchers.Main) {
                                releaseAudioDucking()
                                resumeWakeWordListening()
                            }
                        }
                        return
                    }

                    // Handle backend reasoning completion
                    if (json.optString("type") == "command" && json.optString("action") == "THINKING_COMPLETE") {
                        hasCompletedReasoning = true
                        val payload = json.optString("payload", "")
                        Log.d(TAG, "🟢 Backend reasoning response received: $payload")
                        stopMicrophoneStream()
                        setSpeakingState()

                        val dataObj = json.optJSONObject("data")
                        val cmdObj = dataObj?.optJSONObject("os_execution_command")
                        val toolObj = dataObj?.optJSONObject("agent_tool_call")

                        val rawTrackTitle = cmdObj?.optString("track_title", "")?.takeIf { it.isNotBlank() }
                            ?: toolObj?.optString("track_title", "")?.takeIf { it.isNotBlank() }
                        val rawTrackArtist = cmdObj?.optString("track_artist", "")?.takeIf { it.isNotBlank() }
                            ?: toolObj?.optString("track_artist", "")?.takeIf { it.isNotBlank() }
                        val rawSearchQuery = cmdObj?.optString("search_query", "") ?: ""
                        var resolvedSpotifyUri = cmdObj?.optString("spotify_uri", "")?.takeIf { it.isNotBlank() }
                            ?: toolObj?.optString("spotify_uri", "")?.takeIf { it.isNotBlank() }

                        if (!rawTrackTitle.isNullOrBlank()) {
                            recordAgentSuggestedTrack(rawTrackTitle)
                        }

                        if (resolvedSpotifyUri.isNullOrBlank()) {
                            val recents = getRecentTracks?.invoke() ?: emptyList()
                            val matchedLocal = recents.firstOrNull { r ->
                                val rTitle = r.title?.trim() ?: ""
                                rTitle.isNotEmpty() && (
                                    (rawTrackTitle != null && rTitle.equals(rawTrackTitle.trim(), ignoreCase = true)) ||
                                    rawSearchQuery.lowercase().contains(rTitle.lowercase())
                                )
                            }
                            if (matchedLocal?.providerTrackId?.startsWith("spotify:track:") == true) {
                                resolvedSpotifyUri = matchedLocal.providerTrackId
                                Log.d(TAG, "Bound exact Spotify URI from local memory: $resolvedSpotifyUri")
                            }
                        }

                        val toolPayload = com.tunemind.app.domain.execution.AgentToolCallPayload(
                            action = toolObj?.optString("action", "ADVISE_ONLY") ?: "ADVISE_ONLY",
                            track_title = rawTrackTitle,
                            track_artist = rawTrackArtist,
                            spotify_uri = resolvedSpotifyUri,
                            target_player = toolObj?.optString("target_player", "com.spotify.music"),
                            volume_percent = if (toolObj != null && toolObj.has("volume_percent")) toolObj.optInt("volume_percent", 60) else null,
                            palette_id = toolObj?.optString("palette_id", null)?.takeIf { it.isNotBlank() },
                            target_screen = toolObj?.optString("target_screen", null)?.takeIf { it.isNotBlank() },
                            expect_follow_up = toolObj?.optBoolean("expect_follow_up", false)
                                ?: (cmdObj?.optString("intent", "NONE") == "NONE" && !rawTrackTitle.isNullOrBlank())
                        )

                        val responseObj = com.tunemind.app.domain.execution.ReasoningResponse(
                            calculated_satiety_percentage = dataObj?.optInt("calculated_satiety_percentage", 30) ?: 30,
                            flow_state_analysis = dataObj?.optString("flow_state_analysis", "") ?: "",
                            voice_agent_tts = payload.ifBlank { dataObj?.optString("voice_agent_tts", "") ?: "" },
                            reasoning_mode = dataObj?.optString("reasoning_mode", "SONIC_CURATOR") ?: "SONIC_CURATOR",
                            agent_tool_call = toolPayload,
                            os_execution_command = com.tunemind.app.domain.execution.OsExecutionCommand(
                                intent = cmdObj?.optString("intent", "NONE") ?: "NONE",
                                target_player = cmdObj?.optString("target_player", "com.spotify.music") ?: "com.spotify.music",
                                search_query = rawSearchQuery,
                                suggested_bpm_target = cmdObj?.optInt("suggested_bpm_target"),
                                track_title = rawTrackTitle,
                                track_artist = rawTrackArtist,
                                spotify_uri = resolvedSpotifyUri
                            ),
                            voice_agent_audio_base64 = dataObj?.optString("voice_agent_audio_base64", null)
                        )

                        // Execute app-level tool mutations immediately (Vault, Theme, Screen Navigation, Pause/Resume state)
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
                                    if (toolPayload.expect_follow_up && responseObj.os_execution_command.intent == "NONE") {
                                        // Conversational proposal or follow-up question: wait 650ms for speaker tail to clear, then open mic!
                                        Log.d(TAG, "Waiting for user follow-up/approval in dialogue; re-opening mic in 650ms...")
                                        _agentState.value = AgentState.IDLE
                                        mainHandler.postDelayed({
                                            if (_agentState.value == AgentState.IDLE) {
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
                        return
                    }

                    val transcript = json.optString("transcript", "").ifBlank { json.optString("text", "") }
                    val isFinal = json.optBoolean("isFinal", false) || json.optString("message_type") == "FinalTranscript"

                    if (transcript.isNotBlank()) {
                        scope.launch {
                            _transcriptionFlow.emit(transcript)
                        }
                    }

                    if (isFinal && transcript.isNotBlank()) {
                        Log.d(TAG, "User finished speaking: $transcript")
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

                stopMicrophoneStream()
                _agentState.value = AgentState.ERROR
                errorResetJob?.cancel()
                errorResetJob = scope.launch {
                    delay(2500)
                    withContext(Dispatchers.Main) {
                        releaseAudioDucking()
                        resumeWakeWordListening()
                    }
                }
            }
        }

        webSocket = client.newWebSocket(request, listener)
    }

    private fun startNativeSpeechRecognizer() {
        mainHandler.post {
            try {
                if (!AndroidSpeechRecognizer.isRecognitionAvailable(context)) {
                    handleVoiceError("Native speech recognition not available on this device")
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
                            Log.w(TAG, "Native SpeechRecognizer error code: $error")
                            // 7 = NO_MATCH (didn't catch anything), 6 = SPEECH_TIMEOUT
                            if (error == AndroidSpeechRecognizer.ERROR_NO_MATCH || error == AndroidSpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                handleVoiceError("No speech detected")
                            } else {
                                handleVoiceError("Speech error: $error")
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
                            val transcript = matches?.firstOrNull()?.trim()
                            if (!transcript.isNullOrBlank()) {
                                Log.d(TAG, "Native SpeechRecognizer transcript: $transcript")
                                setThinkingState()
                                scope.launch {
                                    _transcriptionFlow.emit(transcript)
                                    _finalTranscriptFlow.emit(transcript)
                                }
                            } else {
                                handleVoiceError("No speech detected")
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
                handleVoiceError(e.message)
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
        Log.e(TAG, "Voice Error: $reason")
        stopMicrophoneStream()
        stopNativeSpeechRecognizer()
        _agentState.value = AgentState.ERROR

        // Auto-recover after 2.5 seconds back to IDLE so the user is never permanently stuck
        errorResetJob?.cancel()
        errorResetJob = scope.launch {
            delay(2500)
            withContext(Dispatchers.Main) {
                releaseAudioDucking()
                resumeWakeWordListening()
            }
        }
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

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

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

        audioRecord?.startRecording()
        isRecording = true

        scope.launch {
            // Read 100ms frames (3200 bytes at 16kHz 16-bit mono) for optimal AssemblyAI v3 streaming
            val frameBuffer = ByteArray(3200)
            while (isRecording && _agentState.value == AgentState.LISTENING) {
                val read = audioRecord?.read(frameBuffer, 0, frameBuffer.size) ?: 0
                if (read > 0) {
                    val boostedBytes = boostPcm16VoiceClarity(frameBuffer, read)
                    val byteStr = boostedBytes.toByteString()
                    val activeWs = webSocket
                    if (isWsOpenForAudio && activeWs != null) {
                        while (preConnectAudioBuffer.isNotEmpty()) {
                            val buffered = preConnectAudioBuffer.poll() ?: break
                            activeWs.send(buffered)
                        }
                        activeWs.send(byteStr)
                    } else {
                        // Keep up to 4 seconds (40 frames) of pre-connection speech in memory while socket connects
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
        if (!isVoskListening && _agentState.value == AgentState.IDLE) {
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
