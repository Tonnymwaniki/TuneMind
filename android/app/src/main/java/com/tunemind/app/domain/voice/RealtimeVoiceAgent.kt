package com.tunemind.app.domain.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
import com.tunemind.app.domain.network.BackendEndpointManager
import com.tunemind.app.provider.SpotifyPremiumConnectManager
import com.tunemind.app.provider.TuneMindInAppPlayer
import com.tunemind.app.provider.TuneMindYouTubeEmbedController
import com.tunemind.app.service.TuneMindListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class RealtimePhase {
    IDLE,
    CONNECTING,
    LISTENING,
    THINKING_CALLING_TOOLS,
    SPEAKING,
    ERROR
}

data class RealtimeVoiceUiState(
    val phase: RealtimePhase = RealtimePhase.IDLE,
    val isSessionActive: Boolean = false,
    val liveTranscript: String = "Tap the orb to talk with TuneMind",
    val activeToolName: String = "",
    val activeToolStatus: String = "",
    val lastToolSummary: String = "",
    val micEnergy: Float = 0f,
    val errorMessage: String? = null
) {
    fun toLegacyAgentState(): AgentState = when (phase) {
        RealtimePhase.IDLE -> AgentState.IDLE
        RealtimePhase.CONNECTING, RealtimePhase.THINKING_CALLING_TOOLS -> AgentState.THINKING
        RealtimePhase.LISTENING -> AgentState.LISTENING
        RealtimePhase.SPEAKING -> AgentState.SPEAKING
        RealtimePhase.ERROR -> AgentState.ERROR
    }
}

/**
 * Native OpenAI Realtime API Voice Agent for TuneMind.
 *
 * - Mints a short-lived ephemeral client secret via Supabase Edge Function (or TuneMind backend mirror)
 *   using the signed-in user's JWT so OPENAI_API_KEY never touches Android.
 * - Streams bidirectional 24kHz PCM16 audio (`AudioRecord` with hardware AEC/NS/AGC + `AudioTrack` output)
 *   with automatic server-VAD barge-in interruption.
 * - Executes recursive multi-step `function_call_output` -> `response.create` tool loops via
 *   [TuneMindVoiceTools] with zero hardcoded intent rules or mock data.
 */
class RealtimeVoiceAgent(
    private val context: Context,
    private val tools: TuneMindVoiceTools
) {
    companion object {
        private const val TAG = "RealtimeVoiceAgent"
        private const val SAMPLE_RATE_HZ = 24_000
        private const val FRAME_BYTES = 1920 // 40ms @ 24kHz 16-bit mono
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val _uiState = MutableStateFlow(RealtimeVoiceUiState())
    val uiState: StateFlow<RealtimeVoiceUiState> = _uiState.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var recordJob: Job? = null
    private var playbackJob: Job? = null
    private var playbackChannel = Channel<ByteArray>(capacity = 256)
    private val playbackGen = AtomicLong(0L)
    private val isConnectingOrActive = AtomicBoolean(false)
    @Volatile
    private var isCapturingAudio = false
    @Volatile
    private var activeResponseId: String? = null
    @Volatile
    private var pendingAutoSettleAfterPlayback = false

    private data class PendingCall(
        val callId: String,
        val name: String,
        val argsBuffer: StringBuilder = StringBuilder()
    )
    private val pendingCallsById = ConcurrentHashMap<String, PendingCall>()
    private val activeAssistantText = StringBuilder()
    private val activeUserText = StringBuilder()

    fun toggleSession() {
        val current = _uiState.value
        if (current.isSessionActive || current.phase == RealtimePhase.CONNECTING) {
            stopSession()
        } else {
            startSession()
        }
    }

    fun startSession(initialPrompt: String? = null) {
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _uiState.value = _uiState.value.copy(
                phase = RealtimePhase.ERROR,
                isSessionActive = false,
                liveTranscript = "Microphone permission is required for voice control.",
                errorMessage = "RECORD_AUDIO permission missing"
            )
            return
        }
        if (!isConnectingOrActive.compareAndSet(false, true)) {
            Log.d(TAG, "Realtime session already starting or active.")
            return
        }

        // Pause active music cleanly so the voice microphone hears clearly without music bleed
        pauseCurrentMediaQuietly()
        pendingAutoSettleAfterPlayback = false
        pendingCallsById.clear()
        activeAssistantText.clear()
        activeUserText.clear()

        _uiState.value = RealtimeVoiceUiState(
            phase = RealtimePhase.CONNECTING,
            isSessionActive = true,
            liveTranscript = "Connecting to TuneMind Realtime...",
            activeToolStatus = "Authenticating secure session"
        )

        scope.launch {
            try {
                val minted = mintEphemeralSession()
                if (!isConnectingOrActive.get()) return@launch
                requestVoiceAudioFocus()
                startAudioOutputTrack()
                openRealtimeWebSocket(minted, initialPrompt)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Realtime session: ${t.message}", t)
                stopSessionInternal(
                    finalPhase = RealtimePhase.ERROR,
                    transcript = t.message ?: "Unable to connect to OpenAI Realtime.",
                    error = t.message
                )
            }
        }
    }

    fun sendTextTurn(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank()) return
        if (_uiState.value.isSessionActive && webSocket != null) {
            val itemEv = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "message")
                    put("role", "user")
                    put("content", org.json.JSONArray().put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", clean)
                    }))
                })
            }
            webSocket?.send(itemEv.toString())
            webSocket?.send(JSONObject().put("type", "response.create").toString())
        } else {
            startSession(initialPrompt = clean)
        }
    }

    fun stopSession() {
        stopSessionInternal(
            finalPhase = RealtimePhase.IDLE,
            transcript = "Tap the orb to talk with TuneMind",
            error = null
        )
    }

    fun shutdown() {
        stopSession()
        runCatching { scope.cancel() }
    }

    // ─── Step 1: Secure Ephemeral Token Minting ───────────────────────────────

    private data class MintedSession(
        val clientSecret: String,
        val model: String,
        val voice: String,
        val userContext: JSONObject?
    )

    private suspend fun mintEphemeralSession(): MintedSession {
        val authMgr = TuneMindAuthSessionManager.getInstance(appContext)
        val freshJwt = authMgr.ensureFreshAccessToken().trim()
        val identity = SupabaseIntelligenceSyncManager.resolveUserIdentity(appContext)
        val supabaseUrl = identity.supabaseUrl.trim().trimEnd('/')
        val anonKey = identity.supabaseAnonKey.trim()

        val payload = JSONObject().apply {
            put("model", "gpt-realtime")
            put("voice", "coral")
            put("user_id", identity.userId)
            put("user_email", identity.email)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        var lastError: String? = null

        // Primary: Supabase Edge Function (`mint-realtime-session`) with caller JWT
        if (supabaseUrl.startsWith("https://") && anonKey.isNotBlank() && freshJwt.isNotBlank()) {
            try {
                val req = Request.Builder()
                    .url("$supabaseUrl/functions/v1/mint-realtime-session")
                    .header("Authorization", "Bearer $freshJwt")
                    .header("apikey", anonKey)
                    .post(payload)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && bodyStr.isNotBlank()) {
                        return parseMintedResponse(JSONObject(bodyStr))
                    }
                    lastError = "Edge Function HTTP ${resp.code}: ${bodyStr.take(180)}"
                    Log.w(TAG, "Supabase Edge Function mint note: $lastError")
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "Supabase Edge Function unavailable (${e.message}), trying TuneMind backend mirror.")
            }
        }

        // Mirror fallback: TuneMind Express `/api/v1/realtime/session` (JWT validated)
        val baseUrl = BackendEndpointManager.getActiveHttpBaseUrl(appContext).trimEnd('/')
        val reqBuilder = Request.Builder()
            .url("$baseUrl/api/v1/realtime/session")
            .post(payload)
        if (freshJwt.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $freshJwt")
        }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (resp.isSuccessful && bodyStr.isNotBlank()) {
                return parseMintedResponse(JSONObject(bodyStr))
            }
            val msg = runCatching { JSONObject(bodyStr).optString("error") }.getOrNull().orEmpty()
            throw IllegalStateException(
                msg.ifBlank { lastError ?: "Realtime session mint failed (HTTP ${resp.code})" }
            )
        }
    }

    private fun parseMintedResponse(json: JSONObject): MintedSession {
        val secret = json.optString("client_secret").trim()
        if (secret.isBlank()) {
            throw IllegalStateException("Server did not return an ephemeral OpenAI client_secret.")
        }
        return MintedSession(
            clientSecret = secret,
            model = json.optString("model", "gpt-realtime").ifBlank { "gpt-realtime" },
            voice = json.optString("voice", "coral").ifBlank { "coral" },
            userContext = json.optJSONObject("user_context")
        )
    }

    // ─── Step 2: Realtime WebSocket + Dynamic Grounded Session Setup ──────────

    private fun openRealtimeWebSocket(minted: MintedSession, initialPrompt: String?) {
        val url = "wss://api.openai.com/v1/realtime?model=${minted.model}"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${minted.clientSecret}")
        if (minted.model.contains("preview", ignoreCase = true)) {
            reqBuilder.header("OpenAI-Beta", "realtime=v1")
        }

        webSocket = wsClient.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (ws !== webSocket) return
                Log.i(TAG, "🟢 Connected to OpenAI Realtime WebSocket (model=${minted.model})")
                sendSessionUpdate(ws, minted)
                if (initialPrompt.isNullOrBlank()) {
                    startAudioInputCapture()
                    _uiState.value = _uiState.value.copy(
                        phase = RealtimePhase.LISTENING,
                        isSessionActive = true,
                        liveTranscript = "Listening...",
                        activeToolStatus = ""
                    )
                } else {
                    startAudioInputCapture()
                    sendTextTurn(initialPrompt)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (ws !== webSocket) return
                handleServerEvent(ws, text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "OpenAI Realtime closing ($code): $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (ws !== webSocket) return
                Log.i(TAG, "OpenAI Realtime closed ($code): $reason")
                if (isConnectingOrActive.get()) {
                    stopSessionInternal(RealtimePhase.IDLE, "Tap the orb to talk with TuneMind", null)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (ws !== webSocket) return
                val errBody = runCatching { response?.body?.string() }.getOrNull()
                Log.e(TAG, "OpenAI Realtime WS failure: ${t.message} | $errBody")
                if (isConnectingOrActive.get()) {
                    stopSessionInternal(
                        RealtimePhase.ERROR,
                        "Connection error: ${t.message ?: "Realtime disconnected"}",
                        t.message
                    )
                }
            }
        })
    }

    private fun sendSessionUpdate(ws: WebSocket, minted: MintedSession) {
        val isPreview = minted.model.contains("preview", ignoreCase = true)
        val instructions = buildDynamicGroundedInstructions(minted.userContext)
        val toolsJson = tools.toOpenAiToolsJsonArray()

        val sessionObj = if (isPreview) {
            JSONObject().apply {
                put("modalities", org.json.JSONArray(listOf("audio", "text")))
                put("instructions", instructions)
                put("voice", minted.voice)
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().put("model", "gpt-4o-transcribe"))
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.55)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 650)
                })
                put("tools", toolsJson)
                put("tool_choice", "auto")
            }
        } else {
            JSONObject().apply {
                put("type", "realtime")
                put("output_modalities", org.json.JSONArray(listOf("audio")))
                put("instructions", instructions)
                put("audio", JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE_HZ))
                        put("transcription", JSONObject().put("model", "gpt-4o-transcribe"))
                        put("turn_detection", JSONObject().apply {
                            put("type", "server_vad")
                            put("threshold", 0.55)
                            put("prefix_padding_ms", 300)
                            put("silence_duration_ms", 650)
                            put("create_response", true)
                            put("interrupt_response", true)
                        })
                    })
                    put("output", JSONObject().apply {
                        put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE_HZ))
                        put("voice", minted.voice)
                    })
                })
                put("tools", toolsJson)
                put("tool_choice", "auto")
            }
        }

        ws.send(
            JSONObject()
                .put("type", "session.update")
                .put("session", sessionObj)
                .toString()
        )
    }

    private fun buildDynamicGroundedInstructions(serverContext: JSONObject?): String {
        val session = TuneMindAuthSessionManager.getInstance(appContext).sessionFlow.value
        val displayName = serverContext?.optString("display_name")?.takeIf { it.isNotBlank() }
            ?: session.displayName.ifBlank { "TuneMind Listener" }
        val spotifyStatus = SpotifyPremiumConnectManager.ensureLoaded(appContext)
        val localTime = SimpleDateFormat("HH:mm (EEEE)", Locale.getDefault()).format(Date())
        val tz = TimeZone.getDefault().id
        val kidsMode = AgentSettingsStore.isKidsModeEnabled(appContext)
        val stayInApp = AgentSettingsStore.isStayInTuneMindEnabled(appContext)
        val serverSummary = serverContext?.toString() ?: "{}"

        return """
You are TuneMind — the warm, natural, human-sounding AI music companion built into the TuneMind Android app.

LIVE SESSION TELEMETRY:
- Listener: $displayName (user_id: ${session.userId.ifBlank { "local" }})
- Local time: $localTime ($tz)
- Spotify status: connected=${spotifyStatus.isConnected}, premium=${spotifyStatus.isPremium}
- Kids mode: $kidsMode | Stay in TuneMind: $stayInApp
- Initial server-scoped snapshot: $serverSummary

CORE REASONING & ZERO-HALLUCINATION RULES:
1. NEVER guess or invent song titles, artist names, play counts, playlists, devices, or playback state. ALWAYS call the appropriate tools FIRST and inspect their JSON output before speaking facts.
2. CONTEXTUAL REFERENCE RESOLUTION: When the user refers to "that song", "that same artist", "this track", or asks about what was just playing, ALWAYS call `get_live_playback_state` first to inspect the active/just-paused track and artist, then chain `get_spotify_artist_top_tracks`, `query_listening_history_and_analytics`, `search_spotify_catalog`, or `get_spotify_account_library` as needed before acting or answering.
3. MULTI-STEP VERIFICATION BEFORE CLAIMING ACTION:
   - Never claim a song is playing, queued, paused, skipped, liked, vaulted, or saved to a playlist unless the corresponding tool returned `"ok": true`.
   - When asked to play or recommend: if the user names a song, artist, or asks for a specific track (or "most listened song of that same artist"), verify the track + URI via tools (`get_spotify_artist_top_tracks` / `search_spotify_catalog` / `query_listening_history_and_analytics`), call `play_track` if they explicitly asked you to play it (or propose concisely and wait for confirmation if it's an open mood/suggestion request).
4. AUTHENTIC USER ISOLATION: All database, Spotify, Vault, and preference tools are strictly scoped to this authenticated user's session.
5. CONVERSATIONAL VOICE STYLE: Speak warmly and naturally in 1–3 concise sentences unless more detail is asked for. No markdown symbols, bullet formatting, or robotic phrasing. If Kids Mode is true or clean/kids music is requested, avoid explicit tracks (`explicit: true`).
        """.trimIndent()
    }

    // ─── Step 3: Realtime Event Router + Recursive Tool Loop ──────────────────

    private fun handleServerEvent(ws: WebSocket, rawText: String) {
        val json = runCatching { JSONObject(rawText) }.getOrNull() ?: return
        val type = json.optString("type")

        when (type) {
            "session.created", "session.updated" -> {
                Log.d(TAG, "Realtime $type confirmed.")
            }

            "input_audio_buffer.speech_started" -> {
                // Instant barge-in interruption!
                Log.d(TAG, "🎙️ Barge-in speech detected — flushing AI audio output.")
                activeUserText.clear()
                flushAudioOutputBuffer()
                if (activeResponseId != null) {
                    runCatching { ws.send(JSONObject().put("type", "response.cancel").toString()) }
                    activeResponseId = null
                }
                pendingAutoSettleAfterPlayback = false
                _uiState.value = _uiState.value.copy(
                    phase = RealtimePhase.LISTENING,
                    liveTranscript = "Listening...",
                    activeToolName = "",
                    activeToolStatus = ""
                )
            }

            "conversation.item.input_audio_transcription.delta" -> {
                val delta = json.optString("delta")
                if (delta.isNotEmpty()) {
                    activeUserText.append(delta)
                    _uiState.value = _uiState.value.copy(
                        liveTranscript = activeUserText.toString().trim()
                    )
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val tr = json.optString("transcript").trim()
                if (tr.isNotBlank()) {
                    activeUserText.clear()
                    _uiState.value = _uiState.value.copy(
                        phase = RealtimePhase.THINKING_CALLING_TOOLS,
                        liveTranscript = tr
                    )
                }
            }

            "response.created" -> {
                activeResponseId = json.optJSONObject("response")?.optString("id")?.ifBlank { null }
                activeAssistantText.clear()
            }

            "response.output_item.added" -> {
                val item = json.optJSONObject("item") ?: return
                if (item.optString("type") == "function_call") {
                    val callId = item.optString("call_id").trim()
                    val name = item.optString("name").trim()
                    if (callId.isNotBlank() && name.isNotBlank()) {
                        pendingCallsById[callId] = PendingCall(callId = callId, name = name)
                        _uiState.value = _uiState.value.copy(
                            phase = RealtimePhase.THINKING_CALLING_TOOLS,
                            activeToolName = name,
                            activeToolStatus = tools.getStatusLabel(name)
                        )
                    }
                }
            }

            "response.function_call_arguments.delta" -> {
                val callId = json.optString("call_id").trim()
                val delta = json.optString("delta")
                if (callId.isNotBlank() && delta.isNotEmpty()) {
                    pendingCallsById[callId]?.argsBuffer?.append(delta)
                }
            }

            "response.function_call_arguments.done" -> {
                val callId = json.optString("call_id").trim()
                val name = json.optString("name").trim()
                val rawArgs = json.optString("arguments")
                if (callId.isNotBlank()) {
                    val existing = pendingCallsById[callId]
                    if (existing != null) {
                        if (rawArgs.isNotBlank()) {
                            existing.argsBuffer.clear()
                            existing.argsBuffer.append(rawArgs)
                        }
                    } else if (name.isNotBlank()) {
                        pendingCallsById[callId] = PendingCall(callId, name, StringBuilder(rawArgs))
                    }
                }
            }

            "response.output_audio.delta", "response.audio.delta" -> {
                val b64 = json.optString("delta")
                if (b64.isNotBlank()) {
                    val pcm = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    if (pcm != null && pcm.isNotEmpty()) {
                        playbackChannel.trySend(pcm)
                        if (_uiState.value.phase != RealtimePhase.SPEAKING) {
                            _uiState.value = _uiState.value.copy(
                                phase = RealtimePhase.SPEAKING,
                                activeToolName = "",
                                activeToolStatus = ""
                            )
                        }
                    }
                }
            }

            "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> {
                val delta = json.optString("delta")
                if (delta.isNotEmpty()) {
                    activeAssistantText.append(delta)
                    _uiState.value = _uiState.value.copy(
                        liveTranscript = activeAssistantText.toString().trim()
                    )
                }
            }

            "response.done" -> {
                activeResponseId = null
                val respObj = json.optJSONObject("response")
                val outputArr = respObj?.optJSONArray("output")
                val callsToRun = mutableListOf<PendingCall>()

                if (outputArr != null) {
                    for (i in 0 until outputArr.length()) {
                        val item = outputArr.optJSONObject(i) ?: continue
                        if (item.optString("type") == "function_call") {
                            val cid = item.optString("call_id").trim()
                            val name = item.optString("name").trim()
                            val argsStr = item.optString("arguments")
                            if (cid.isNotBlank() && name.isNotBlank()) {
                                val cached = pendingCallsById.remove(cid)
                                val finalArgs = if (argsStr.isNotBlank()) argsStr else cached?.argsBuffer?.toString().orEmpty()
                                callsToRun.add(PendingCall(cid, name, StringBuilder(finalArgs)))
                            }
                        }
                    }
                }
                if (callsToRun.isEmpty() && pendingCallsById.isNotEmpty()) {
                    callsToRun.addAll(pendingCallsById.values)
                    pendingCallsById.clear()
                }

                if (callsToRun.isNotEmpty()) {
                    executeToolBatchAndContinue(ws, callsToRun)
                } else if (pendingAutoSettleAfterPlayback) {
                    pendingAutoSettleAfterPlayback = false
                    // Let streamed audio drain cleanly before closing session so music plays undisturbed
                    scope.launch {
                        delay(2_200L)
                        if (isConnectingOrActive.get() && activeResponseId == null) {
                            stopSession()
                        }
                    }
                } else if (isConnectingOrActive.get() && _uiState.value.phase != RealtimePhase.ERROR) {
                    _uiState.value = _uiState.value.copy(
                        phase = RealtimePhase.LISTENING,
                        activeToolName = "",
                        activeToolStatus = ""
                    )
                }
            }

            "error" -> {
                val errObj = json.optJSONObject("error")
                val msg = errObj?.optString("message")?.ifBlank { null } ?: "Realtime session error"
                val code = errObj?.optString("code").orEmpty()
                if (code == "response_cancel_not_active") return
                Log.e(TAG, "OpenAI Realtime error [$code]: $msg")
                _uiState.value = _uiState.value.copy(
                    phase = RealtimePhase.ERROR,
                    errorMessage = msg,
                    liveTranscript = msg
                )
            }
        }
    }

    private fun executeToolBatchAndContinue(ws: WebSocket, calls: List<PendingCall>) {
        // Pause microphone capture briefly only if launching external/Spotify playback? Keep listening for barge-in during reads!
        scope.launch {
            var launchedPlayback = false
            val summaryLabels = mutableListOf<String>()

            for (call in calls) {
                if (!isActive || ws !== webSocket) return@launch
                val label = tools.getStatusLabel(call.name)
                _uiState.value = _uiState.value.copy(
                    phase = RealtimePhase.THINKING_CALLING_TOOLS,
                    activeToolName = call.name,
                    activeToolStatus = label
                )
                val argsJson = runCatching {
                    val raw = call.argsBuffer.toString().trim()
                    if (raw.isBlank()) JSONObject() else JSONObject(raw)
                }.getOrElse { JSONObject() }

                Log.i(TAG, "🛠️ Executing Realtime tool: ${call.name} args=$argsJson")
                val result = tools.execute(call.name, argsJson)
                Log.i(TAG, "✅ Tool result (${call.name}): $result")

                if ((call.name == "play_track" || call.name == "play_spotify_context") && result.optBoolean("ok", false)) {
                    launchedPlayback = true
                    abandonVoiceAudioFocus()
                }
                summaryLabels.add("${call.name}: ${if (result.optBoolean("ok")) "ok" else "err"}")

                val outputEvent = JSONObject().apply {
                    put("type", "conversation.item.create")
                    put("item", JSONObject().apply {
                        put("type", "function_call_output")
                        put("call_id", call.callId)
                        put("output", result.toString())
                    })
                }
                ws.send(outputEvent.toString())
            }

            if (launchedPlayback) {
                pendingAutoSettleAfterPlayback = true
            }

            _uiState.value = _uiState.value.copy(
                lastToolSummary = summaryLabels.joinToString(" · "),
                activeToolStatus = "Generating voice response..."
            )
            ws.send(JSONObject().put("type", "response.create").toString())
        }
    }

    // ─── Step 4: Bidirectional Hardware-AEC AudioRecord & AudioTrack ─────────

    @SuppressLint("MissingPermission")
    private fun startAudioInputCapture() {
        stopAudioInputCapture()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_BYTES * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize at ${SAMPLE_RATE_HZ}Hz")
            runCatching { rec.release() }
            return
        }

        val sessionId = rec.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            automaticGainControl = runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }

        audioRecord = rec
        isCapturingAudio = true
        rec.startRecording()

        recordJob = scope.launch {
            val frame = ByteArray(FRAME_BYTES)
            while (isActive && isCapturingAudio && webSocket != null) {
                val readBytes = rec.read(frame, 0, frame.size)
                if (readBytes > 0) {
                    val rms = computeNormalizedEnergy(frame, readBytes)
                    _uiState.value = _uiState.value.copy(micEnergy = rms)
                    val b64 = Base64.encodeToString(frame, 0, readBytes, Base64.NO_WRAP)
                    val ev = JSONObject().apply {
                        put("type", "input_audio_buffer.append")
                        put("audio", b64)
                    }
                    webSocket?.send(ev.toString())
                }
            }
        }
    }

    private fun stopAudioInputCapture() {
        isCapturingAudio = false
        recordJob?.cancel()
        recordJob = null
        runCatching { echoCanceler?.release() }; echoCanceler = null
        runCatching { noiseSuppressor?.release() }; noiseSuppressor = null
        runCatching { automaticGainControl?.release() }; automaticGainControl = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    private fun startAudioOutputTrack() {
        stopAudioOutputTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_BYTES * 6)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()
        audioTrack = track
        playbackChannel = Channel(capacity = 512)
        val gen = playbackGen.incrementAndGet()

        playbackJob = scope.launch {
            for (chunk in playbackChannel) {
                if (!isActive || gen != playbackGen.get()) break
                runCatching { track.write(chunk, 0, chunk.size) }
            }
        }
    }

    private fun flushAudioOutputBuffer() {
        while (playbackChannel.tryReceive().isSuccess) {
            // drain queued PCM deltas
        }
        runCatching {
            audioTrack?.let { tr ->
                tr.pause()
                tr.flush()
                tr.play()
            }
        }
    }

    private fun stopAudioOutputTrack() {
        playbackGen.incrementAndGet()
        runCatching { playbackChannel.close() }
        playbackJob?.cancel()
        playbackJob = null
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }

    private fun stopSessionInternal(
        finalPhase: RealtimePhase,
        transcript: String,
        error: String?
    ) {
        isConnectingOrActive.set(false)
        pendingAutoSettleAfterPlayback = false
        stopAudioInputCapture()
        stopAudioOutputTrack()
        val ws = webSocket
        webSocket = null
        runCatching { ws?.close(1000, "Session ended") }
        abandonVoiceAudioFocus()
        pendingCallsById.clear()

        _uiState.value = RealtimeVoiceUiState(
            phase = finalPhase,
            isSessionActive = false,
            liveTranscript = transcript,
            activeToolName = "",
            activeToolStatus = "",
            lastToolSummary = _uiState.value.lastToolSummary,
            micEnergy = 0f,
            errorMessage = error
        )
    }

    private fun pauseCurrentMediaQuietly() {
        runCatching { TuneMindInAppPlayer.pause() }
        runCatching { TuneMindYouTubeEmbedController.pause() }
        runCatching { TuneMindListenerService.instance?.pause() }
        if (SpotifyPremiumConnectManager.isConnectedAndPremium(appContext)) {
            scope.launch { runCatching { SpotifyPremiumConnectManager.pausePlayback(appContext) } }
        }
    }

    private fun requestVoiceAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioFocusRequest = req
            runCatching { audioManager.requestAudioFocus(req) }
        }
    }

    private fun abandonVoiceAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            audioFocusRequest = null
        }
    }

    private fun computeNormalizedEnergy(buf: ByteArray, len: Int): Float {
        if (len < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
            sum += sample * sample
            count++
            i += 2
        }
        val rms = if (count > 0) kotlin.math.sqrt(sum / count) else 0.0
        return (rms / 5500.0).toFloat().coerceIn(0f, 1f)
    }
}
