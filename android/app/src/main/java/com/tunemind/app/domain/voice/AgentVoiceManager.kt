package com.tunemind.app.domain.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
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

class AgentVoiceManager(private val context: Context) : RecognitionListener {
    private val TAG = "AgentVoiceManager"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState: StateFlow<AgentState> = _agentState

    private var audioFocusRequest: AudioFocusRequest? = null

    private var audioRecord: AudioRecord? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _transcriptionFlow = MutableSharedFlow<String>()
    val transcriptionFlow = _transcriptionFlow.asSharedFlow()

    // Vosk Variables
    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var isVoskListening = false

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
        
        // Vosk returns JSON like {"text": "hi tune mind"} or {"partial": "tune"}
        val text = jsonStr.lowercase()
        if (text.contains("tune mind") || text.contains("hi tune") || text.contains("hey tune")) {
            Log.d(TAG, "Wake Word Detected! Handoff to AssemblyAI...")
            
            // 1. STOP Vosk to release the microphone hardware
            try {
                speechService?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Vosk SpeechService", e)
            }
            isVoskListening = false
            
            // 2. Trigger the agent flow
            simulateWakeWordDetected() // This ducks audio and starts AssemblyAI
        }
    }

    // This is our "Phantom Trigger" / Wake Word handler. The UI or Vosk calls this.
    fun simulateWakeWordDetected() {
        if (_agentState.value != AgentState.IDLE) return
        
        Log.d(TAG, "Wake Word / Phantom Trigger Activated! Waking up TuneMind Agent...")
        if (isVoskListening) {
            try {
                speechService?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Vosk SpeechService", e)
            }
            isVoskListening = false
        }
        _agentState.value = AgentState.LISTENING
        
        // 1. Duck the currently playing music so the user can speak and hear the UI sound
        requestAudioDucking()

        // 2. Open Android AudioRecord and stream bytes via WebSocket to Backend (AssemblyAI proxy)
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
            Log.e(TAG, "Audio permission not granted!")
            _agentState.value = AgentState.ERROR
            return
        }

        // Resolves WebSocket endpoint (remote cloud backend, local LAN, or emulator 10.0.2.2)
        val wsUrl = resolveWebSocketUrl()
        val request = Request.Builder().url(wsUrl).build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Connected to $wsUrl. Starting AudioRecord...")
                startRecordingAndStreaming(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val transcript = json.getString("transcript")
                    val isFinal = json.optBoolean("isFinal", false)
                    
                    scope.launch {
                        _transcriptionFlow.emit(transcript)
                    }

                    if (isFinal) {
                        Log.d(TAG, "User finished speaking: $transcript")
                        stopMicrophoneStream()
                        setThinkingState()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure", t)
                stopMicrophoneStream()
                _agentState.value = AgentState.ERROR
            }
        })
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

    private fun stopMicrophoneStream() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioRecord", e)
        }
        audioRecord = null
        try {
            webSocket?.close(1000, "Finished speaking")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing webSocket", e)
        }
        webSocket = null
        Log.d(TAG, "AssemblyAI stream stopped. Microphone released.")
    }

    // Add a public method so the Orchestrator can wake Vosk back up after TTS finishes
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
        stopMicrophoneStream()
        releaseAudioDucking()
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down Vosk SpeechService", e)
        }
        speechService = null
        isVoskListening = false
        scope.cancel()
    }
}
