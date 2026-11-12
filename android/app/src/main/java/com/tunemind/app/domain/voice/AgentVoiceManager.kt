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

class AgentVoiceManager(
    private val context: Context,
    private val getNowPlaying: (() -> com.tunemind.app.ui.NowPlayingObservation?)? = null,
    private val getRecentTracks: (() -> List<com.tunemind.app.domain.model.TrackMemoryEntry>)? = null
) : RecognitionListener {
    private val TAG = "AgentVoiceManager"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState: StateFlow<AgentState> = _agentState

    private val executionEngine by lazy {
        com.tunemind.app.domain.execution.TuneMindExecutionEngine(context)
    }

    private var audioFocusRequest: AudioFocusRequest? = null

    private var audioRecord: AudioRecord? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
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

    // Wake Word / Manual Orb tap handler
    fun simulateWakeWordDetected() {
        // Allow tap even if currently showing ERROR to allow instant retry
        if (_agentState.value != AgentState.IDLE && _agentState.value != AgentState.ERROR) return

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

        _agentState.value = AgentState.LISTENING

        // 1. Duck the currently playing music
        requestAudioDucking()

        // 2. Open microphone stream (WebSocket with native Android fallback)
        startMicrophoneStream()
    }

    private fun requestAudioDucking() {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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
        Log.d(TAG, "Audio ducking requested. Music volume lowered.")
    }

    private fun startMicrophoneStream() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            handleVoiceError("RECORD_AUDIO permission not granted!")
            return
        }

        val request = Request.Builder().url("ws://localhost:3000/ws/stt").build()
        Log.d(TAG, "Attempting WebSocket connection to ws://localhost:3000/ws/stt (ADB reverse tunnel)...")

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "🟢 WebSocket OPENED successfully. HTTP Status: ${response.code}")

                // Send initial client playback context to backend
                try {
                    val np = getNowPlaying?.invoke()
                    val recents: List<com.tunemind.app.domain.model.TrackMemoryEntry> = getRecentTracks?.invoke() ?: emptyList()

                    val metaJson = JSONObject().apply {
                        put("type", "client_context")
                        val currentObj = JSONObject().apply {
                            put("title", np?.title ?: "Standing By")
                            put("artist", np?.artist ?: "")
                            put("source_player", np?.sourcePackage ?: "com.spotify.music")
                        }
                        put("current_track", currentObj)

                        val historyArr = org.json.JSONArray()
                        for (r in recents.take(15)) {
                            historyArr.put(JSONObject().apply {
                                put("title", r.title ?: "")
                                put("artist", r.artist ?: "")
                                put("source", r.sourcePackage ?: "com.spotify.music")
                                put("playCount", r.distinctAppearanceCount)
                            })
                        }
                        put("recent_tracks", historyArr)
                    }
                    webSocket.send(metaJson.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send initial client context over WS: ${e.message}")
                }

                startRecordingAndStreaming(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "🔵 WebSocket MESSAGE received: $text")
                try {
                    val json = JSONObject(text)
                    if (json.has("error")) {
                        Log.w(TAG, "Server reported error: ${json.optString("error")}")
                        stopMicrophoneStream()
                        _agentState.value = AgentState.ERROR
                        return
                    }

                    // Handle backend reasoning completion
                    if (json.optString("type") == "command" && json.optString("action") == "THINKING_COMPLETE") {
                        val payload = json.optString("payload", "")
                        Log.d(TAG, "🟢 Backend reasoning response received: $payload")
                        stopMicrophoneStream() // Fully close socket now that server responded
                        setSpeakingState()

                        // Execute TTS & OS Actions directly from the server response
                        val dataObj = json.optJSONObject("data")
                        val cmdObj = dataObj?.optJSONObject("os_execution_command")
                        val responseObj = com.tunemind.app.domain.execution.ReasoningResponse(
                            calculated_satiety_percentage = dataObj?.optInt("calculated_satiety_percentage", 50) ?: 50,
                            flow_state_analysis = dataObj?.optString("flow_state_analysis", "") ?: "",
                            voice_agent_tts = payload.ifBlank { dataObj?.optString("voice_agent_tts", "") ?: "" },
                            os_execution_command = com.tunemind.app.domain.execution.OsExecutionCommand(
                                intent = cmdObj?.optString("intent", "NONE") ?: "NONE",
                                target_player = cmdObj?.optString("target_player", "com.spotify.music") ?: "com.spotify.music",
                                search_query = cmdObj?.optString("search_query", "") ?: "",
                                suggested_bpm_target = cmdObj?.optInt("suggested_bpm_target"),
                                track_title = cmdObj?.optString("track_title", "")?.takeIf { it.isNotBlank() },
                                track_artist = cmdObj?.optString("track_artist", "")?.takeIf { it.isNotBlank() },
                                spotify_uri = cmdObj?.optString("spotify_uri", "")?.takeIf { it.isNotBlank() }
                            ),
                            voice_agent_audio_base64 = dataObj?.optString("voice_agent_audio_base64", null)
                        )

                        executionEngine.executeBackendCommand(responseObj)

                        // Auto-reset to IDLE after speaking
                        errorResetJob?.cancel()
                        errorResetJob = scope.launch {
                            delay(7000)
                            withContext(Dispatchers.Main) {
                                releaseAudioDucking()
                                resumeWakeWordListening()
                            }
                        }
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
                        scope.launch {
                            _finalTranscriptFlow.emit(transcript)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parse error: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "🔵 WebSocket BYTES received (Length: ${bytes.size})")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "🟡 WebSocket CLOSING. Code: $code, Reason: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "🔴 WebSocket CLOSED. Code: $code, Reason: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket FAILURE: ${t.message}", t)
                if (response != null) {
                    val body = runCatching { response.body?.string() }.getOrNull()
                    Log.e(TAG, "❌ WebSocket FAILURE HTTP Code: ${response.code}, Body: $body")
                }

                stopMicrophoneStream()
                // Force the UI out of the listening state so it doesn't hang forever
                _agentState.value = AgentState.ERROR
                errorResetJob?.cancel()
                errorResetJob = scope.launch {
                    delay(3000)
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

    private fun startRecordingAndStreaming(ws: WebSocket) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            handleVoiceError("Microphone permission missing")
            return
        }
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)

        audioRecord?.startRecording()
        isRecording = true

        scope.launch {
            val buffer = ByteArray(bufferSize)
            while (isRecording && _agentState.value == AgentState.LISTENING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    ws.send(buffer.copyOfRange(0, read).toByteString())
                }
            }
        }
    }

    private fun stopAudioRecordingOnly() {
        isRecording = false
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

    fun releaseAudioDucking() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        _agentState.value = AgentState.IDLE
        Log.d(TAG, "Audio ducking released. Music volume restored.")
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
