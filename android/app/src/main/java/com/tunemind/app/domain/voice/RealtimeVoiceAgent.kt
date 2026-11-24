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
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
import com.tunemind.app.domain.network.BackendEndpointManager
import com.tunemind.app.provider.MusicPlayerAutoLauncher
import com.tunemind.app.provider.SpotifyPremiumConnectManager
import com.tunemind.app.provider.TuneMindInAppPlayer
import com.tunemind.app.provider.TuneMindYouTubeEmbedController
import com.tunemind.app.service.TuneMindListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
import okio.ByteString
import org.json.JSONArray
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
    private var playbackChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val playbackGen = AtomicLong(0L)
    private val totalFramesWritten = AtomicLong(0L)
    private val isConnectingOrActive = AtomicBoolean(false)
    @Volatile
    private var isCapturingAudio = false
    @Volatile
    private var suppressMicStreaming = false
    @Volatile
    private var activeProvider: String = "openai"
    @Volatile
    private var pendingInitialPrompt: String? = null
    @Volatile
    private var activeResponseId: String? = null
    @Volatile
    private var pendingAutoSettleAfterPlayback = false
    private var settleOrDrainJob: Job? = null
    @Volatile
    private var pendingExternalAppLaunch: (() -> Unit)? = null
    private var deferredLaunchWatchdogJob: Job? = null

    private fun firePendingExternalLaunchIfAny() {
        deferredLaunchWatchdogJob?.cancel()
        deferredLaunchWatchdogJob = null
        val action = pendingExternalAppLaunch ?: return
        pendingExternalAppLaunch = null
        Handler(Looper.getMainLooper()).post {
            runCatching { action.invoke() }
                .onFailure { Log.w(TAG, "Deferred external launch failed: ${it.message}") }
        }
    }

    @Suppress("DEPRECATION")
    private fun pulseThinkingHaptic() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(18L)
            }
        }
    }

    private fun isPlaybackOrMutatingTool(name: String): Boolean {
        return name == "play_track" ||
            name == "play_spotify_context" ||
            name == "play_track_sequence" ||
            name == "control_playback" ||
            name == "queue_spotify_track" ||
            name == "set_spotify_track_like" ||
            name == "manage_resting_vault" ||
            name == "manage_tunemind_playlists"
    }

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
        suppressMicStreaming = false
        settleOrDrainJob?.cancel()
        settleOrDrainJob = null
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
            if (activeProvider == "gemini") {
                val turnObj = JSONObject().apply {
                    put("clientContent", JSONObject().apply {
                        put("turns", JSONArray().put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", clean)))
                        }))
                        put("turnComplete", true)
                    })
                }
                webSocket?.send(turnObj.toString())
            } else {
                val itemEv = JSONObject().apply {
                    put("type", "conversation.item.create")
                    put("item", JSONObject().apply {
                        put("type", "message")
                        put("role", "user")
                        put("content", JSONArray().put(JSONObject().apply {
                            put("type", "input_text")
                            put("text", clean)
                        }))
                    })
                }
                webSocket?.send(itemEv.toString())
                webSocket?.send(JSONObject().put("type", "response.create").toString())
            }
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
        val provider: String,
        val clientSecret: String,
        val wsUrl: String,
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

        val jwtDiag = runCatching {
            val parts = freshJwt.split(".")
            if (parts.size < 2) {
                "missingOrMalformed"
            } else {
                val payloadJson = JSONObject(
                    String(
                        Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                        Charsets.UTF_8
                    )
                )
                val exp = payloadJson.optLong("exp", 0L)
                val nowSec = System.currentTimeMillis() / 1000L
                val iss = payloadJson.optString("iss", "")
                "expDeltaSec=${exp - nowSec},iss=$iss"
            }
        }.getOrDefault("decodeErr")
        Log.i(TAG, "Minting Realtime session for user=${identity.userId} supabaseUrl=$supabaseUrl jwt=$jwtDiag")

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
                    lastError = "Edge Function HTTP ${resp.code}: ${bodyStr.take(220)}"
                    Log.w(TAG, "Supabase Edge Function mint note: $lastError")
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "Supabase Edge Function unavailable (${e.message}), trying TuneMind backend mirror.")
            }
        } else {
            lastError = "Missing Supabase session token on device (jwtPresent=${freshJwt.isNotBlank()})"
            Log.w(TAG, lastError!!)
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
            Log.w(TAG, "Backend /api/v1/realtime/session failed HTTP ${resp.code}: ${bodyStr.take(220)}")
            val errObj = runCatching { JSONObject(bodyStr) }.getOrNull()
            val msg = listOfNotNull(
                errObj?.optString("message")?.takeIf { it.isNotBlank() },
                errObj?.optString("error")?.takeIf { it.isNotBlank() }
            ).firstOrNull().orEmpty()

            if (resp.code == 401 || lastError?.contains("401") == true) {
                throw IllegalStateException("Session expired or unauthorized — open Profile → Log Out and sign back in.")
            }
            throw IllegalStateException(
                msg.ifBlank { lastError ?: "Realtime session mint failed (HTTP ${resp.code})" }
            )
        }
    }

    private fun parseMintedResponse(json: JSONObject): MintedSession {
        val provider = json.optString("provider", "openai").trim().lowercase().ifBlank { "openai" }
        val secret = json.optString("client_secret").trim()
        val wsUrl = json.optString("ws_url").trim()
        if (secret.isBlank() && wsUrl.isBlank()) {
            throw IllegalStateException("Server did not return an ephemeral session credential.")
        }
        return MintedSession(
            provider = provider,
            clientSecret = secret,
            wsUrl = wsUrl,
            model = json.optString("model", if (provider == "gemini") "models/gemini-2.5-flash-native-audio-preview-12-2025" else "gpt-realtime"),
            voice = json.optString("voice", if (provider == "gemini") "Aoede" else "coral"),
            userContext = json.optJSONObject("user_context")
        )
    }

    // ─── Step 2: Realtime WebSocket + Dynamic Grounded Session Setup ──────────

    private fun openRealtimeWebSocket(minted: MintedSession, initialPrompt: String?) {
        activeProvider = minted.provider
        pendingInitialPrompt = initialPrompt?.takeIf { it.isNotBlank() }

        val url = if (minted.provider == "gemini") {
            minted.wsUrl.ifBlank {
                "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained?access_token=${java.net.URLEncoder.encode(minted.clientSecret, "UTF-8")}"
            }
        } else {
            "wss://api.openai.com/v1/realtime?model=${minted.model}"
        }

        val reqBuilder = Request.Builder().url(url)
        if (minted.provider != "gemini") {
            reqBuilder.header("Authorization", "Bearer ${minted.clientSecret}")
            if (minted.model.contains("preview", ignoreCase = true)) {
                reqBuilder.header("OpenAI-Beta", "realtime=v1")
            }
        }

        webSocket = wsClient.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(sock: WebSocket, response: Response) {
                if (sock !== webSocket) return
                Log.i(TAG, "🟢 Connected to ${minted.provider} Realtime WebSocket (model=${minted.model})")
                if (minted.provider == "gemini") {
                    sendGeminiSetup(sock, minted)
                    _uiState.value = _uiState.value.copy(
                        phase = RealtimePhase.CONNECTING,
                        isSessionActive = true,
                        liveTranscript = "Starting TuneMind Live...",
                        activeToolStatus = "Initializing Gemini Live voice"
                    )
                } else {
                    sendSessionUpdate(sock, minted)
                    if (initialPrompt.isNullOrBlank()) {
                        startAudioInputCapture(SAMPLE_RATE_HZ)
                        _uiState.value = _uiState.value.copy(
                            phase = RealtimePhase.LISTENING,
                            isSessionActive = true,
                            liveTranscript = "Listening...",
                            activeToolStatus = ""
                        )
                    } else {
                        startAudioInputCapture(SAMPLE_RATE_HZ)
                        sendTextTurn(initialPrompt)
                    }
                }
            }

            override fun onMessage(sock: WebSocket, text: String) {
                if (sock !== webSocket) return
                if (activeProvider == "gemini") {
                    handleGeminiServerEvent(sock, text)
                } else {
                    handleServerEvent(sock, text)
                }
            }

            override fun onMessage(sock: WebSocket, bytes: ByteString) {
                if (sock !== webSocket) return
                val text = bytes.utf8()
                if (activeProvider == "gemini") {
                    handleGeminiServerEvent(sock, text)
                } else {
                    handleServerEvent(sock, text)
                }
            }

            override fun onClosing(sock: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "${minted.provider} Realtime closing ($code): $reason")
                runCatching { sock.close(1000, null) }
                if (sock === webSocket && code != 1000 && isConnectingOrActive.get()) {
                    stopSessionInternal(
                        RealtimePhase.ERROR,
                        "Voice connection interrupted ($code) — tap the orb to retry.",
                        reason.ifBlank { "Closed ($code)" }
                    )
                }
            }

            override fun onClosed(sock: WebSocket, code: Int, reason: String) {
                if (sock !== webSocket) return
                Log.i(TAG, "${minted.provider} Realtime closed ($code): $reason")
                if (isConnectingOrActive.get()) {
                    stopSessionInternal(RealtimePhase.IDLE, "Tap the orb to talk with TuneMind", null)
                }
            }

            override fun onFailure(sock: WebSocket, t: Throwable, response: Response?) {
                if (sock !== webSocket) return
                val errBody = runCatching { response?.body?.string() }.getOrNull()
                Log.e(TAG, "${minted.provider} Realtime WS failure: ${t.message} | $errBody")
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

    private fun sendGeminiSetup(ws: WebSocket, minted: MintedSession) {
        val instructions = buildDynamicGroundedInstructions(minted.userContext)
        val modelPath = if (minted.model.startsWith("models/")) minted.model else "models/${minted.model}"
        val decls = tools.toGeminiFunctionDeclarationsJsonArray()

        val setupFrame = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", modelPath)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", minted.voice.ifBlank { "Aoede" })
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", instructions)))
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                put("realtimeInputConfig", JSONObject().apply {
                    put("automaticActivityDetection", JSONObject().apply {
                        put("disabled", false)
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                        put("prefixPaddingMs", 200)
                        put("silenceDurationMs", 450)
                    })
                })
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
            })
        }
        ws.send(setupFrame.toString())
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
                put("input_audio_transcription", JSONObject().put("model", "gpt-4o-transcribe").put("language", "en"))
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.68)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 700)
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
                        put("transcription", JSONObject().put("model", "gpt-4o-transcribe").put("language", "en"))
                        put("turn_detection", JSONObject().apply {
                            put("type", "server_vad")
                            put("threshold", 0.68)
                            put("prefix_padding_ms", 300)
                            put("silence_duration_ms", 700)
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
        val billingState = com.tunemind.app.billing.TuneMindBillingManager.getInstance(appContext).state.value
        val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(appContext)
        val installedPlayers = registry.getInstalledProviders()
            .filter { it.providerId != "tunemind_inapp" && it.providerId != "local_files" }
            .joinToString(", ") { it.displayName }
            .ifBlank { "None detected" }
        val defaultPlayerName = registry.getProvider(registry.getPreferredProviderId()).displayName
        val spotifyTierLabel = when {
            spotifyStatus.isConnected && spotifyStatus.isPremium -> "Premium (silent background playback inside TuneMind)"
            spotifyStatus.isConnected -> "Free (opens Spotify directly to play)"
            else -> "Not connected"
        }
        val hasFreeTrialOffer = billingState.plans.any { it.hasFreeTrialOffer }
        val trialLabel = when {
            billingState.isTrial -> "7-day TuneMind Pro Free Trial active (${billingState.trialDaysRemaining} days remaining)"
            billingState.hasProAccess -> "TuneMind Pro Active (${billingState.tier.name})"
            hasFreeTrialOffer -> "TuneMind Free Tier (7-day Free Trial available)"
            else -> "TuneMind Free Tier"
        }
        val localTime = SimpleDateFormat("HH:mm (EEEE)", Locale.getDefault()).format(Date())
        val tz = TimeZone.getDefault().id
        val kidsMode = AgentSettingsStore.isKidsModeEnabled(appContext)
        val serverSummary = serverContext?.toString() ?: "{}"

        return """
You are TuneMind — the warm, natural, human-sounding AI music companion built into the TuneMind Android app.

LIVE SESSION TELEMETRY:
- Listener: $displayName (user_id: ${session.userId.ifBlank { "local" }})
- Local time: $localTime ($tz)
- TuneMind Plan / Trial: $trialLabel
- Spotify account: $spotifyTierLabel
- Default music player: $defaultPlayerName | Detected music apps on phone: $installedPlayers
- Kids mode: $kidsMode
- Initial server-scoped snapshot: $serverSummary

PLAYER ROUTING & 7-DAY TRIAL EXPLANATION (KNOW & EXPLAIN ACCURATELY):
- TuneMind 7-Day Free Trial: All new TuneMind listeners get a 7-day free trial of TuneMind Pro (full access to the voice companion, Song Cards, Resting Vault, and discovery). Note that the TuneMind 7-day trial is separate from a user's Spotify subscription.
- Spotify Premium: Plays silently in the background WITHOUT leaving TuneMind.
- Spotify Free: Spotify's platform blocks background Connect playback on Free accounts, so TuneMind opens the Spotify app directly to play the song. Keep your confirmation crisp (1 short warm sentence, e.g. "Opening Spotify for [Song] now — tap Play there and I'll keep tracking your stats!").
- YouTube Music (ONLY when the listener selected YouTube Music as default or asks for it): Plays inside TuneMind's built-in YouTube player without leaving the app.
- Boomplay, Audiomack, and any other detected music app: Opens that music app directly and plays the requested song there. Never switch to Audius.

CORE REASONING & ZERO-HALLUCINATION RULES:
1. NEVER guess or invent song titles, artist names, play counts, playlists, devices, or playback state. ALWAYS call the appropriate tools FIRST and inspect their JSON output before speaking facts.
2. CONTEXTUAL REFERENCE RESOLUTION: When the user refers to "that song", "the paused song", "that same artist", "this track", or asks about what was just playing / their most recent songs, ALWAYS call `get_live_playback_state` first (it reads live MediaSession, live Spotify /me/player including paused tracks, and `recent_chronological_tracks`), then chain `get_spotify_artist_top_tracks`, `query_listening_history_and_analytics` (`sort_by="recent"`), `search_spotify_catalog`, or `get_spotify_account_library` as needed before acting or answering.
3. PLAYBACK APPROVAL GATE & MULTI-STEP VERIFICATION:
   - ALWAYS ASK BEFORE PLAYING: Before calling `play_track`, `play_track_sequence`, or `play_spotify_context` to play ANY song, sequence, or playlist, you MUST first tell the listener the exact song title(s) and artist(s) and ask for their approval — e.g. "Should I play [Song] by [Artist]?" or "Should I play these songs: [Song 1], [Song 2]?" — and WAIT for them to approve in their next turn. NEVER jump straight into executing playback before the listener approves!
   - NATURAL APPROVAL UNDERSTANDING (NO RIGID PHRASES): Treat ANY natural human affirmation as approval — e.g. "yeah", "yes", "yep", "uh-huh", "sure", "cool", "bet", "sounds good", "go ahead", "go for it", "play it", "play that", "do it", "let's go", "okay" — and immediately execute the playback tool without asking a second time.
   - If the listener says "no", "skip", or "something else", suggest a different verified song and ask again.
   - Only call `play_track` / `play_track_sequence` / `play_spotify_context` AFTER the listener confirms your proposed song(s).
   - Never claim a song is playing, queued, paused, skipped, liked, vaulted, or saved to a playlist unless the corresponding tool returned `"ok": true`.
   - For simple playback controls on the ALREADY active song (pause, resume, next, previous, volume, like), execute `control_playback` immediately without asking.
4. FRESHNESS, ALL-SCREEN AWARENESS & ZERO-REPEAT VARIETY:
   - Whenever the user asks for fresh songs, recommendations, "surprise me", "what's in my Fresh screen / For You / playlists", or something they haven't heard in a while, ALWAYS call `get_fresh_and_discovery_picks` first. It reads across the user's Fresh screen (all 90+ fresh tracks), For You AI Discovery Crate, Dopamine Capsule, and TuneMind/Spotify playlists, and automatically excludes songs already played or served recently (persisted across app restarts).
   - NEVER repeat a song that was recently played, suggested, or listed in `recently_played_or_served_avoid` unless the user explicitly asks to replay that specific song. Actively vary artists and sources (Fresh screen, For You crate, playlists, and Spotify catalog) across every request.
5. SONG CARD & RELATIONSHIP INTELLIGENCE:
   - Whenever the user asks how many times they've played a song, their skip count, freshness/fatigue score, vault status, which playlists contain a song, or their relationship/history with the current song or any named song, ALWAYS call `get_song_card_details` first. NEVER say you don't know the play count without calling `get_song_card_details`.
6. AUTHENTIC USER ISOLATION: All database, Spotify, Vault, and preference tools are strictly scoped to this authenticated user's session.
7. CONVERSATIONAL VOICE STYLE: Speak warmly and naturally in 1–3 concise sentences unless more detail is asked for. No markdown symbols, bullet formatting, or robotic phrasing. If Kids Mode is true or clean/kids music is requested, avoid explicit tracks (`explicit: true`).
8. STRICT ENGLISH-ONLY & WAKE NAME RECOGNITION: Always understand and respond strictly in clear, natural English only. The listener speaks English (including East African / Kenyan English pronunciation) with occasional Swahili, Kikuyu, or Dancehall song titles and artist names. Never speak, translate into, or reply in Swahili, Sheng, Chinese, or any other non-English language. Your name is TuneMind (pronounced "Tune-Mind") — because speech recognition sometimes mishears "TuneMind" as "twin", "tune mine", "to mind", or "two mind", always recognize greetings like "Hi twin" or "Hey tune mine" as the listener greeting you as "Hi TuneMind" and respond naturally as TuneMind.
9. NEW LISTENERS, EMPTY LIBRARIES & DIVERSE QUESTIONS:
   - Brand-new user with empty history/playlists: If tools return zero tracks, zero plays, or no playlists, NEVER invent history. Warmly let them know their TuneMind library is just getting started, ask what artist, genre, or mood they love, or call `search_spotify_catalog` to propose a real song (and ask before playing).
   - No Spotify or Spotify Free: If `spotify.is_connected` is false or `spotify.is_premium` is false, never claim Spotify Connect is active. You can still recommend music, answer questions, use installed players reported by `get_live_playback_state`, or gently mention they can link Spotify in Profile anytime.
   - General, App-Help, or Off-Topic Questions: If the listener asks how TuneMind works (Freshness %, Resting Vault cooldowns, Dopamine Capsule, Song Cards, Hearing Health, Sleep Timer, Themes) or asks casual/off-topic questions (mood, greetings, jokes, general chat), answer warmly and concisely in 1–2 sentences without forcing unnecessary tool calls. If a tool returns an error or network timeout, explain it gently and naturally — never read raw JSON or technical error codes aloud.
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
                settleOrDrainJob?.cancel()
                settleOrDrainJob = null
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
                    deferredLaunchWatchdogJob?.cancel()
                    deferredLaunchWatchdogJob = null
                    settleOrDrainJob?.cancel()
                    settleOrDrainJob = scope.launch {
                        waitUntilAudioDrained()
                        if (isConnectingOrActive.get() && activeResponseId == null) {
                            val deferred = pendingExternalAppLaunch
                            pendingExternalAppLaunch = null
                            stopSession()
                            if (deferred != null) {
                                Handler(Looper.getMainLooper()).post {
                                    runCatching { deferred.invoke() }
                                }
                            }
                        }
                    }
                } else if (isConnectingOrActive.get() && _uiState.value.phase != RealtimePhase.ERROR) {
                    settleOrDrainJob?.cancel()
                    settleOrDrainJob = scope.launch {
                        waitUntilAudioDrained()
                        if (isConnectingOrActive.get() && activeResponseId == null && _uiState.value.phase == RealtimePhase.SPEAKING) {
                            _uiState.value = _uiState.value.copy(
                                phase = RealtimePhase.LISTENING,
                                activeToolName = "",
                                activeToolStatus = ""
                            )
                        }
                    }
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
            pulseThinkingHaptic()
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

                val isPlaybackLaunchTool = call.name == "play_track" ||
                    call.name == "play_spotify_context" ||
                    call.name == "play_track_sequence" ||
                    (call.name == "control_playback" && argsJson.optString("command").equals("resume", ignoreCase = true))

                Log.i(TAG, "🛠️ Executing Realtime tool: ${call.name} args=$argsJson")
                val result = if (isPlaybackLaunchTool) {
                    MusicPlayerAutoLauncher.setDeferredExternalLaunchSink { launchAction ->
                        pendingExternalAppLaunch = launchAction
                    }
                    try {
                        tools.execute(call.name, argsJson)
                    } finally {
                        MusicPlayerAutoLauncher.setDeferredExternalLaunchSink(null)
                    }
                } else {
                    tools.execute(call.name, argsJson)
                }
                Log.i(TAG, "✅ Tool result (${call.name}): $result")

                if (isPlaybackLaunchTool && result.optBoolean("ok", false)) {
                    launchedPlayback = true
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
                // Mute mic upload while TuneMind finishes reading its final confirmation so incoming music doesn't cut it off
                suppressMicStreaming = true
                pendingAutoSettleAfterPlayback = true
                if (pendingExternalAppLaunch != null) {
                    deferredLaunchWatchdogJob?.cancel()
                    deferredLaunchWatchdogJob = scope.launch {
                        // Never cut off the agent mid-sentence: wait until speaking finishes or 16s max
                        val deadline = System.currentTimeMillis() + 16_000L
                        var silentPolls = 0
                        delay(4_500L)
                        while (pendingExternalAppLaunch != null && System.currentTimeMillis() < deadline) {
                            val speakingNow = _uiState.value.phase == RealtimePhase.SPEAKING ||
                                activeResponseId != null ||
                                !playbackChannel.isEmpty
                            if (speakingNow) {
                                silentPolls = 0
                            } else {
                                silentPolls++
                                if (silentPolls >= 5) break
                            }
                            delay(500L)
                        }
                        if (pendingExternalAppLaunch != null) {
                            waitUntilAudioDrained(maxWaitMs = 4_000L)
                            firePendingExternalLaunchIfAny()
                        }
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                lastToolSummary = summaryLabels.joinToString(" · "),
                activeToolStatus = "Generating voice response..."
            )
            ws.send(JSONObject().put("type", "response.create").toString())
        }
    }

    // ─── Gemini Live Event Router + Tool Loop ────────────────────────────────

    private fun handleGeminiServerEvent(ws: WebSocket, rawText: String) {
        val json = runCatching { JSONObject(rawText) }.getOrNull() ?: return

        if (json.has("setupComplete")) {
            Log.d(TAG, "Gemini Live setupComplete confirmed.")
            startAudioInputCapture(16_000)
            val initPrompt = pendingInitialPrompt
            pendingInitialPrompt = null
            if (!initPrompt.isNullOrBlank()) {
                sendTextTurn(initPrompt)
            } else {
                _uiState.value = _uiState.value.copy(
                    phase = RealtimePhase.LISTENING,
                    isSessionActive = true,
                    liveTranscript = "Listening...",
                    activeToolStatus = ""
                )
            }
            return
        }

        if (json.has("error")) {
            val errObj = json.optJSONObject("error")
            val msg = errObj?.optString("message")?.ifBlank { null } ?: "Gemini Live error"
            Log.e(TAG, "Gemini Live error: $msg")
            _uiState.value = _uiState.value.copy(
                phase = RealtimePhase.ERROR,
                errorMessage = msg,
                liveTranscript = msg
            )
            return
        }

        val toolCallObj = json.optJSONObject("toolCall")
        val fnCallsArr = toolCallObj?.optJSONArray("functionCalls")
        if (fnCallsArr != null && fnCallsArr.length() > 0) {
            val calls = mutableListOf<PendingCall>()
            for (i in 0 until fnCallsArr.length()) {
                val fc = fnCallsArr.optJSONObject(i) ?: continue
                val id = fc.optString("id").trim()
                val name = fc.optString("name").trim()
                val argsObj = fc.optJSONObject("args")?.toString() ?: "{}"
                if (name.isNotBlank()) {
                    calls.add(PendingCall(callId = id.ifBlank { "call_$i" }, name = name, argsBuffer = StringBuilder(argsObj)))
                }
            }
            if (calls.isNotEmpty()) {
                executeGeminiToolBatchAndContinue(ws, calls)
            }
            return
        }

        val sc = json.optJSONObject("serverContent") ?: return

        if (sc.optBoolean("interrupted", false)) {
            Log.d(TAG, "🎙️ Gemini Live barge-in interrupted — flushing AI audio output.")
            activeResponseId = null
            flushAudioOutputBuffer()
            pendingAutoSettleAfterPlayback = false
            _uiState.value = _uiState.value.copy(
                phase = RealtimePhase.LISTENING,
                liveTranscript = "Listening...",
                activeToolName = "",
                activeToolStatus = ""
            )
            return
        }

        sc.optJSONObject("inputTranscription")?.optString("text")?.let { rawChunk ->
            val cleaned = sanitizeEnglishTranscript(rawChunk)
            if (cleaned.isNotEmpty()) {
                activeUserText.append(cleaned)
                val display = activeUserText.toString().replace(Regex("\\s+"), " ").trim()
                if (display.isNotBlank()) {
                    _uiState.value = _uiState.value.copy(liveTranscript = display)
                }
            }
        }

        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { chunk ->
            if (activeResponseId == null) {
                activeResponseId = "gemini-turn"
                activeAssistantText.clear()
                activeUserText.clear()
            }
            activeAssistantText.append(chunk)
            _uiState.value = _uiState.value.copy(
                liveTranscript = activeAssistantText.toString().trim()
            )
        }

        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) {
            settleOrDrainJob?.cancel()
            settleOrDrainJob = null
            if (activeResponseId == null) {
                activeResponseId = "gemini-turn"
                activeUserText.clear()
            }
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val inlineData = part.optJSONObject("inlineData")
                val b64Audio = inlineData?.optString("data").orEmpty()
                if (b64Audio.isNotBlank()) {
                    val pcm = runCatching { Base64.decode(b64Audio, Base64.DEFAULT) }.getOrNull()
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
                val partText = part.optString("text")
                if (partText.isNotBlank() && !part.optBoolean("thought", false) && sc.optJSONObject("outputTranscription") == null) {
                    activeAssistantText.append(partText)
                    _uiState.value = _uiState.value.copy(
                        liveTranscript = activeAssistantText.toString().trim()
                    )
                }
            }
        }

        if (sc.optBoolean("turnComplete", false)) {
            activeResponseId = null
            activeUserText.clear()
            if (pendingAutoSettleAfterPlayback) {
                pendingAutoSettleAfterPlayback = false
                deferredLaunchWatchdogJob?.cancel()
                deferredLaunchWatchdogJob = null
                settleOrDrainJob?.cancel()
                settleOrDrainJob = scope.launch {
                    waitUntilAudioDrained()
                    if (isConnectingOrActive.get() && activeResponseId == null) {
                        val deferred = pendingExternalAppLaunch
                        pendingExternalAppLaunch = null
                        stopSession()
                        if (deferred != null) {
                            Handler(Looper.getMainLooper()).post {
                                runCatching { deferred.invoke() }
                            }
                        }
                    }
                }
            } else if (isConnectingOrActive.get() && _uiState.value.phase != RealtimePhase.ERROR) {
                settleOrDrainJob?.cancel()
                settleOrDrainJob = scope.launch {
                    waitUntilAudioDrained()
                    if (isConnectingOrActive.get() && activeResponseId == null && _uiState.value.phase == RealtimePhase.SPEAKING) {
                        _uiState.value = _uiState.value.copy(
                            phase = RealtimePhase.LISTENING,
                            activeToolName = "",
                            activeToolStatus = ""
                        )
                    }
                }
            }
        }
    }

    private fun executeGeminiToolBatchAndContinue(ws: WebSocket, calls: List<PendingCall>) {
        scope.launch {
            pulseThinkingHaptic()
            var launchedPlayback = false
            val summaryLabels = mutableListOf<String>()
            val responsesArr = JSONArray()

            val canRunParallel = calls.size > 1 && calls.none { isPlaybackOrMutatingTool(it.name) }
            if (canRunParallel) {
                val firstCall = calls.first()
                _uiState.value = _uiState.value.copy(
                    phase = RealtimePhase.THINKING_CALLING_TOOLS,
                    activeToolName = firstCall.name,
                    activeToolStatus = tools.getStatusLabel(firstCall.name)
                )
                val results = coroutineScope {
                    calls.map { call ->
                        async(Dispatchers.IO) {
                            val argsJson = runCatching {
                                val raw = call.argsBuffer.toString().trim()
                                if (raw.isBlank()) JSONObject() else JSONObject(raw)
                            }.getOrElse { JSONObject() }
                            Log.i(TAG, "🛠️ Executing Gemini Live tool (parallel): ${call.name} args=$argsJson")
                            val res = tools.execute(call.name, argsJson)
                            Log.i(TAG, "✅ Tool result (${call.name}): $res")
                            call to res
                        }
                    }.awaitAll()
                }
                if (!isActive || ws !== webSocket) return@launch
                for ((call, result) in results) {
                    summaryLabels.add("${call.name}: ${if (result.optBoolean("ok")) "ok" else "err"}")
                    responsesArr.put(JSONObject().apply {
                        put("id", call.callId)
                        put("name", call.name)
                        put("response", JSONObject().put("output", result))
                    })
                }
            } else {
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

                    val isPlaybackLaunchTool = call.name == "play_track" ||
                        call.name == "play_spotify_context" ||
                        call.name == "play_track_sequence" ||
                        (call.name == "control_playback" && argsJson.optString("command").equals("resume", ignoreCase = true))

                    Log.i(TAG, "🛠️ Executing Gemini Live tool: ${call.name} args=$argsJson")
                    val result = if (isPlaybackLaunchTool) {
                        MusicPlayerAutoLauncher.setDeferredExternalLaunchSink { launchAction ->
                            pendingExternalAppLaunch = launchAction
                        }
                        try {
                            tools.execute(call.name, argsJson)
                        } finally {
                            MusicPlayerAutoLauncher.setDeferredExternalLaunchSink(null)
                        }
                    } else {
                        tools.execute(call.name, argsJson)
                    }
                    Log.i(TAG, "✅ Tool result (${call.name}): $result")

                    if (isPlaybackLaunchTool && result.optBoolean("ok", false)) {
                        launchedPlayback = true
                    }
                    summaryLabels.add("${call.name}: ${if (result.optBoolean("ok")) "ok" else "err"}")

                    responsesArr.put(JSONObject().apply {
                        put("id", call.callId)
                        put("name", call.name)
                        put("response", JSONObject().put("output", result))
                    })
                }
            }

            if (launchedPlayback) {
                suppressMicStreaming = true
                pendingAutoSettleAfterPlayback = true
                if (pendingExternalAppLaunch != null) {
                    deferredLaunchWatchdogJob?.cancel()
                    deferredLaunchWatchdogJob = scope.launch {
                        // Never cut off the agent mid-sentence: wait until speaking finishes or 16s max
                        val deadline = System.currentTimeMillis() + 16_000L
                        var silentPolls = 0
                        delay(4_500L)
                        while (pendingExternalAppLaunch != null && System.currentTimeMillis() < deadline) {
                            val speakingNow = _uiState.value.phase == RealtimePhase.SPEAKING ||
                                activeResponseId != null ||
                                !playbackChannel.isEmpty
                            if (speakingNow) {
                                silentPolls = 0
                            } else {
                                silentPolls++
                                if (silentPolls >= 5) break
                            }
                            delay(500L)
                        }
                        if (pendingExternalAppLaunch != null) {
                            waitUntilAudioDrained(maxWaitMs = 4_000L)
                            firePendingExternalLaunchIfAny()
                        }
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                lastToolSummary = summaryLabels.joinToString(" · "),
                activeToolStatus = "Generating voice response..."
            )
            ws.send(
                JSONObject().apply {
                    put("toolResponse", JSONObject().put("functionResponses", responsesArr))
                }.toString()
            )
        }
    }

    // ─── Step 4: Bidirectional Hardware-AEC AudioRecord & AudioTrack ─────────

    @SuppressLint("MissingPermission")
    private fun startAudioInputCapture(
        sampleRateHz: Int = SAMPLE_RATE_HZ,
        preferredSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        allowFallback: Boolean = true
    ) {
        stopAudioInputCapture()
        val frameBytes = ((sampleRateHz * 2) / 25).coerceAtLeast(640) // 40ms @ 16kHz (1280B) or 24kHz (1920B)
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameBytes * 4)

        val candidateSources = if (preferredSource == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
                MediaRecorder.AudioSource.MIC to "MIC",
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION"
            )
        } else {
            listOf(
                preferredSource to "FALLBACK_$preferredSource",
                MediaRecorder.AudioSource.MIC to "MIC"
            )
        }

        var rec: AudioRecord? = null
        var chosenLabel = "UNKNOWN"
        for ((src, label) in candidateSources) {
            val attempt = runCatching {
                AudioRecord(
                    src,
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            }.getOrNull()
            if (attempt != null && attempt.state == AudioRecord.STATE_INITIALIZED) {
                rec = attempt
                chosenLabel = label
                break
            } else {
                runCatching { attempt?.release() }
            }
        }

        if (rec == null) {
            Log.e(TAG, "AudioRecord failed to initialize on all audio sources at ${sampleRateHz}Hz")
            stopSessionInternal(
                finalPhase = RealtimePhase.ERROR,
                transcript = "Microphone is busy or unavailable — tap the orb to retry.",
                error = "Microphone unavailable"
            )
            return
        }

        val sessionId = rec.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
        }
        // Avoid stacking hardware AGC on top of VOICE_RECOGNITION, which already applies wideband speech gain
        // and caused peakRms=1.000 clipping + consonant distortion on Redmi devices.
        automaticGainControl = null

        val startedOk = runCatching { rec.startRecording() }.isSuccess
        if (!startedOk) {
            Log.e(TAG, "AudioRecord.startRecording() failed on $chosenLabel")
            runCatching { rec.release() }
            stopSessionInternal(
                finalPhase = RealtimePhase.ERROR,
                transcript = "Microphone couldn't start — tap the orb to retry.",
                error = "AudioRecord start failed"
            )
            return
        }
        audioRecord = rec
        isCapturingAudio = true
        Log.i(TAG, "🎤 Mic capture started (source=$chosenLabel, rate=${sampleRateHz}Hz, aec=${echoCanceler != null}, ns=${noiseSuppressor != null}, agc=false)")

        recordJob = scope.launch {
            val frame = ByteArray(frameBytes)
            var zeroFrameStreak = 0
            var everHeardNonZero = false
            var framesLogged = 0
            var peakRmsWindow = 0f
            while (isActive && isCapturingAudio && webSocket != null) {
                val readBytes = runCatching { rec.read(frame, 0, frame.size) }.getOrElse { -1 }
                if (readBytes < 0) {
                    Log.w(TAG, "AudioRecord.read returned $readBytes — stopping mic loop cleanly")
                    break
                }
                if (readBytes > 0) {
                    val rms = computeNormalizedEnergy(frame, readBytes)
                    if (rms > peakRmsWindow) peakRmsWindow = rms
                    _uiState.value = _uiState.value.copy(micEnergy = rms)

                    if (rms > 0f) {
                        everHeardNonZero = true
                        zeroFrameStreak = 0
                    } else if (!everHeardNonZero) {
                        zeroFrameStreak++
                        if (allowFallback && zeroFrameStreak == 30) {
                            Log.w(TAG, "⚠️ Mic returned 30 all-zero hardware frames at startup on $chosenLabel — reopening on AudioSource.MIC")
                            delay(150L)
                            if (isActive && isCapturingAudio && webSocket != null) {
                                startAudioInputCapture(
                                    sampleRateHz = sampleRateHz,
                                    preferredSource = MediaRecorder.AudioSource.MIC,
                                    allowFallback = false
                                )
                            }
                            return@launch
                        }
                    }

                    framesLogged++
                    if (framesLogged % 50 == 0) {
                        Log.d(TAG, "🎤 Mic telemetry ($chosenLabel @ ${sampleRateHz}Hz): peakRms=${"%.3f".format(peakRmsWindow)} muted=$suppressMicStreaming")
                        peakRmsWindow = 0f
                    }

                    if (!suppressMicStreaming) {
                        val b64 = Base64.encodeToString(frame, 0, readBytes, Base64.NO_WRAP)
                        val ev = if (activeProvider == "gemini") {
                            JSONObject().apply {
                                put("realtimeInput", JSONObject().apply {
                                    put("audio", JSONObject().apply {
                                        put("mimeType", "audio/pcm;rate=$sampleRateHz")
                                        put("data", b64)
                                    })
                                })
                            }
                        } else {
                            JSONObject().apply {
                                put("type", "input_audio_buffer.append")
                                put("audio", b64)
                            }
                        }
                        webSocket?.send(ev.toString())
                    }
                }
            }
        }
    }

    private fun sanitizeEnglishTranscript(raw: String): String {
        // Strip stray CJK / non-Latin hallucinated characters caused by ambient noise bursts
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val code = ch.code
            if (code in 0x20..0x7E || code in 0x00C0..0x024F || ch == '\n') {
                sb.append(ch)
            }
        }
        // Normalize common ASR wake-name mishearings ("Hi twin", "Hey tune mine") -> "TuneMind"
        return sb.toString().replace(
            Regex("\\b(hi|hey|hello)\\s+(twin|tune\\s*mine|to\\s*mind|two\\s*mind|tunemine)\\b", RegexOption.IGNORE_CASE),
            "$1 TuneMind"
        )
    }

    private fun stopAudioInputCapture() {
        isCapturingAudio = false
        recordJob?.cancel()
        recordJob = null
        val oldAec = echoCanceler
        val oldNs = noiseSuppressor
        val oldAgc = automaticGainControl
        val oldRec = audioRecord
        echoCanceler = null
        noiseSuppressor = null
        automaticGainControl = null
        audioRecord = null
        val releaseBlock = {
            runCatching { oldAec?.release() }
            runCatching { oldNs?.release() }
            runCatching { oldAgc?.release() }
            runCatching { oldRec?.stop() }
            runCatching { oldRec?.release() }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scope.launch(Dispatchers.IO) { releaseBlock() }
        } else {
            releaseBlock()
        }
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
        totalFramesWritten.set(0L)
        playbackChannel = Channel(capacity = Channel.UNLIMITED)
        val gen = playbackGen.incrementAndGet()

        playbackJob = scope.launch {
            for (chunk in playbackChannel) {
                if (!isActive || gen != playbackGen.get()) break
                val written = runCatching { track.write(chunk, 0, chunk.size) }.getOrDefault(0)
                if (written > 0) {
                    totalFramesWritten.addAndGet((written / 2).toLong())
                }
            }
        }
    }

    private suspend fun waitUntilAudioDrained(maxWaitMs: Long = 18_000L) {
        val startMs = System.currentTimeMillis()
        // Wait for queued PCM chunks + AudioTrack hardware playback head to finish speaking every word
        delay(120L)
        while (isConnectingOrActive.get() && activeResponseId == null && (System.currentTimeMillis() - startMs) < maxWaitMs) {
            val tr = audioTrack ?: break
            val headFrames = runCatching { tr.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
            val writtenFrames = totalFramesWritten.get()
            val remainingFrames = writtenFrames - headFrames
            if (playbackChannel.isEmpty && remainingFrames <= 480L) {
                break
            }
            delay(60L)
        }
        delay(350L)
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
                val head = tr.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                totalFramesWritten.set(head)
            }
        }
    }

    private fun stopAudioOutputTrack() {
        playbackGen.incrementAndGet()
        totalFramesWritten.set(0L)
        runCatching { playbackChannel.close() }
        playbackJob?.cancel()
        playbackJob = null
        val oldTrack = audioTrack
        audioTrack = null
        if (oldTrack != null) {
            val releaseBlock = {
                runCatching { oldTrack.pause() }
                runCatching { oldTrack.flush() }
                runCatching { oldTrack.stop() }
                runCatching { oldTrack.release() }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                scope.launch(Dispatchers.IO) { releaseBlock() }
            } else {
                releaseBlock()
            }
        }
    }

    private fun stopSessionInternal(
        finalPhase: RealtimePhase,
        transcript: String,
        error: String?
    ) {
        isConnectingOrActive.set(false)
        pendingAutoSettleAfterPlayback = false
        suppressMicStreaming = false
        deferredLaunchWatchdogJob?.cancel()
        deferredLaunchWatchdogJob = null
        pendingExternalAppLaunch = null
        MusicPlayerAutoLauncher.setDeferredExternalLaunchSink(null)
        settleOrDrainJob?.cancel()
        settleOrDrainJob = null
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
