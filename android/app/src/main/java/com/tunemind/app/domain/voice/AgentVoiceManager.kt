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
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
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
    val source: String = "tunemind_inapp",
    val neverOpenExternalApp: Boolean = false,
    val spotifyUri: String? = null
)

class AgentVoiceManager(
    private val context: Context,
    private val getNowPlaying: (() -> com.tunemind.app.ui.NowPlayingObservation?)? = null,
    private val getRecentTracks: (() -> List<com.tunemind.app.domain.model.TrackMemoryEntry>)? = null,
    private val getHearingInsights: (() -> com.tunemind.app.ui.HearingInsightsState)? = null,
    private val getVaultTracks: (() -> List<com.tunemind.app.ui.VaultTrack>)? = null,
    private val getDopamineCapsule: (() -> com.tunemind.app.ui.DopamineCapsuleState?)? = null,
    private val getForYouPicks: (() -> List<com.tunemind.app.domain.recommendation.AiRecommendationEntity>)? = null,
    private val getCrossAppLibraryTracks: (() -> List<com.tunemind.app.data.local.CrossAppTrackEntity>)? = null,
    private val getDetectedPlayerTabs: (() -> List<com.tunemind.app.data.local.DetectedPlayerTab>)? = null,
    private val getListeningStats: (() -> com.tunemind.app.domain.memory.OverallListeningStats)? = null,
    private val getCleanPlayHistory: (() -> List<com.tunemind.app.domain.memory.UnifiedPlayEvent>)? = null,
    private val getTuneMindPlaylists: (() -> List<com.tunemind.app.data.local.TuneMindPlaylistWithTracks>)? = null,
    private val getSleepTimerRemainingMinutes: (() -> Int?)? = null,
    private val getAppState: (() -> JSONObject)? = null,
    private val onTrackDispatched: ((title: String, artist: String, sourcePlayer: String) -> Unit)? = null,
    private val onToolActionExecuted: ((toolCall: com.tunemind.app.domain.execution.AgentToolCallPayload) -> Unit)? = null,
    private val onPlaybackLaunchResult: ((com.tunemind.app.provider.PlaybackLaunchResult) -> Unit)? = null,
    private val onWakeOpenAppRequested: (() -> Unit)? = null,
    private val onProTrialRequired: ((reason: String) -> Unit)? = null
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
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }
    private val reasoningHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(30000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(12000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }
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
    private val _isWakeWordListeningFlow = MutableStateFlow(false)
    val isWakeWordListeningFlow: StateFlow<Boolean> = _isWakeWordListeningFlow

    @Volatile
    private var isAppInForeground = false

    @Volatile
    private var pausedForExternalRecording = false

    @Volatile
    private var lastWakeWordTriggerAtMs = 0L

    @Volatile
    private var wasMediaPlayingBeforeWake = false

    @Volatile
    private var wasTurnTriggeredByWakeWord = false

    @Volatile
    private var hasReceivedSpeechThisTurn = false

    @Volatile
    private var hasSpokenMorningPromptThisTurn = false

    @Volatile
    private var wakeOpenAppOverride: (() -> Unit)? = onWakeOpenAppRequested

    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null
    private var audioMonitorThread: HandlerThread? = null
    private var wakeRestartJob: Job? = null
    private var noSpeechAfterWakeJob: Job? = null
    private var chimeToneGenerator: ToneGenerator? = null

    @Volatile
    private var latestLocalVoskCommandText: String = ""

    @Volatile
    private var latestWsPartialText: String = ""

    @Volatile
    private var lastWsPartialProgressAtMs: Long = 0L

    @Volatile
    private var latestNativePartialText: String = ""

    @Volatile
    private var adaptiveMicGain: Float = 1.0f

    private val wakeTailLock = Any()
    private val wakeTailFrames = ArrayDeque<ByteArray>()
    private val pendingWakeTailFrames = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var postAudioCooldownUntilMs: Long = 0L

    @Volatile
    private var consecutiveTuneMindPartialHits: Int = 0

    @Volatile
    private var hasCompletedCloudTurn: Boolean = false

    @Volatile
    private var cachedCrossAppLibrary: List<com.tunemind.app.data.local.CrossAppTrackEntity> = emptyList()

    private var wakeRecordJob: Job? = null
    private var wakeAudioRecord: AudioRecord? = null
    private var cloudResponseTimeoutJob: Job? = null
    private var postGreetingListenTimeoutJob: Job? = null
    private var wakeWatchdogJob: Job? = null

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
    private var lastEndOfSongTargetPlayer: String = "tunemind_inapp"

    @Volatile
    private var monitoredTrackTitle: String = ""
    @Volatile
    private var monitoredTrackArtist: String = ""
    @Volatile
    private var monitoredTrackPackage: String = "unknown_player"
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

    @Volatile
    private var hasStartedWakeEngineInit = false

    init {
        registerAudioRecordingMonitor()
        refreshUnifiedTracksCacheAsync()
        startCloudKeepAlivePing()
        startAutonomousEndOfSongMonitor()
        startWakeWordSelfHealingWatchdog()
    }

    private fun startWakeWordSelfHealingWatchdog() {
        wakeWatchdogJob?.cancel()
        wakeWatchdogJob = scope.launch {
            while (isActive) {
                delay(12_000L)
                if (shouldKeepWakeWordListening() &&
                    _agentState.value == AgentState.IDLE &&
                    !isVoskListening &&
                    !pausedForExternalRecording &&
                    voskModel != null &&
                    !isExternalMicOrCallActive()
                ) {
                    Log.w(TAG, "Self-healing wake watchdog: restarting \"Hi TuneMind\" listener...")
                    startWakeWordListener()
                }
            }
        }
    }

    private fun markPostAudioCooldown(durationMs: Long = 3_200L) {
        val target = System.currentTimeMillis() + durationMs
        if (target > postAudioCooldownUntilMs) {
            postAudioCooldownUntilMs = target
        }
    }

    private fun getCrossAppLibrarySnapshot(): List<com.tunemind.app.data.local.CrossAppTrackEntity> {
        val live = getCrossAppLibraryTracks?.invoke().orEmpty()
        if (live.isNotEmpty()) {
            cachedCrossAppLibrary = live
            return live
        }
        return cachedCrossAppLibrary
    }

    private fun refreshUnifiedTracksCacheAsync() {
        scope.launch(Dispatchers.IO) {
            delay(3_500L)
            runCatching {
                val fromCallback = getCrossAppLibraryTracks?.invoke().orEmpty()
                cachedCrossAppLibrary = if (fromCallback.isNotEmpty()) {
                    fromCallback
                } else {
                    com.tunemind.app.data.local.TuneMindDatabase.getInstance(context)
                        .crossAppLibraryDao()
                        .getAllLibraryTracksOnce()
                }
            }
        }
    }

    private fun resolveOpenCatalogLinkBlocking(
        title: String,
        artist: String = "",
        fullQuery: String = ""
    ): String? {
        val resolved = runCatching {
            com.tunemind.app.provider.UniversalTrackResolver.resolveTrackBlocking(
                context = context,
                cleanTitle = title,
                cleanArtist = artist,
                fullQuery = fullQuery.ifBlank { "$title $artist".trim() }
            )
        }.getOrNull() ?: return null
        return resolved.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
            ?: resolved.audiusStreamUrl?.takeIf { it.isNotBlank() }
            ?: resolved.deezerUrl?.takeIf { it.isNotBlank() }
            ?: resolved.previewUrl?.takeIf { it.isNotBlank() }
    }

    private data class OpenDiscoveryPick(
        val trackTitle: String,
        val artistName: String,
        val deepLinkUrl: String?
    )

    private fun findMostPlayedCrossAppTrack(
        recents: List<com.tunemind.app.domain.model.TrackMemoryEntry>
    ): Pair<com.tunemind.app.data.local.CrossAppTrackEntity?, com.tunemind.app.domain.model.TrackMemoryEntry?> {
        val libTop = getCrossAppLibrarySnapshot()
            .filter { it.title.isNotBlank() }
            .maxByOrNull { it.playCount }
        val memTop = recents
            .filter { !it.title.isNullOrBlank() }
            .maxByOrNull { it.distinctAppearanceCount }
        return if ((libTop?.playCount ?: 0) >= (memTop?.distinctAppearanceCount ?: 0) && libTop != null) {
            libTop to memTop
        } else {
            libTop to memTop
        }
    }

    private fun fetchOpenCatalogDiscoveryFallback(
        excludedTitlesLower: Set<String>
    ): OpenDiscoveryPick? {
        val lib = getCrossAppLibrarySnapshot()
        val seedArtist = lib.firstOrNull { it.artist.isNotBlank() && !it.artist.equals("Unknown Artist", ignoreCase = true) }?.artist
        val seedGenre = lib.firstOrNull { it.genre.isNotBlank() && !it.genre.equals("Music", ignoreCase = true) }?.genre
        val query = listOfNotNull(seedArtist, seedGenre).joinToString(" ").ifBlank { "afrobeats soul hits" }
        val results = runCatching {
            com.tunemind.app.provider.UniversalTrackResolver.searchCatalogBlocking(
                context = context,
                query = query,
                limit = 10
            )
        }.getOrDefault(emptyList())
        val match = results.firstOrNull { cand ->
            cand.title.isNotBlank() && !excludedTitlesLower.contains(cand.title.lowercase().trim())
        } ?: results.firstOrNull() ?: return null
        val link = match.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
            ?: match.audiusStreamUrl?.takeIf { it.isNotBlank() }
            ?: match.deezerUrl?.takeIf { it.isNotBlank() }
            ?: match.previewUrl?.takeIf { it.isNotBlank() }
        return OpenDiscoveryPick(match.title, match.artist, link)
    }

    private fun prewarmCloudConnectionAsync() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val baseUrl = com.tunemind.app.domain.network.BackendEndpointManager.getActiveHttpBaseUrl(context).trimEnd('/')
                val req = Request.Builder().url("$baseUrl/health").get().build()
                client.newCall(req).execute().close()
            }
        }
    }

    fun setWakeOpenAppHandler(handler: (() -> Unit)?) {
        wakeOpenAppOverride = handler
    }

    fun shouldKeepWakeWordListening(): Boolean {
        return isAppInForeground
    }

    fun syncAlwaysOnWakeWordState() {
        if (shouldKeepWakeWordListening()) {
            resumeWakeWordListening()
        } else {
            stopVoskWakeListener()
        }
    }

    @Volatile
    private var lastOwnMicActivityAtMs = 0L

    private val voskLock = Any()

    private fun stopVoskWakeListener() {
        wakeRestartJob?.cancel()
        val oldJob = wakeRecordJob
        wakeRecordJob = null
        val oldAudio = wakeAudioRecord
        wakeAudioRecord = null
        val oldService = speechService
        speechService = null
        val hadActive = isVoskListening || oldJob != null || oldAudio != null || oldService != null
        isVoskListening = false
        _isWakeWordListeningFlow.value = false
        consecutiveTuneMindPartialHits = 0
        oldJob?.cancel()
        if (hadActive) {
            lastOwnMicActivityAtMs = System.currentTimeMillis()
            scope.launch(Dispatchers.IO) {
                runCatching { oldAudio?.stop() }
                runCatching { oldAudio?.release() }
                if (oldService != null) {
                    runCatching { oldService.stop() }
                    runCatching { oldService.shutdown() }
                }
            }
        }
    }

    private fun isExternalMicOrCallActive(configs: List<AudioRecordingConfiguration>? = null): Boolean {
        val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
        ) {
            return true
        }
        val activeConfigs = configs ?: runCatching { audioManager.activeRecordingConfigurations }.getOrDefault(emptyList())
        if (activeConfigs.isEmpty()) return false
        val ownMicRecentlyActive = _agentState.value != AgentState.IDLE ||
            isRecording ||
            isVoskListening ||
            (System.currentTimeMillis() - lastOwnMicActivityAtMs) < 1_800L
        return if (ownMicRecentlyActive) activeConfigs.size >= 2 else activeConfigs.isNotEmpty()
    }

    private fun registerAudioRecordingMonitor() {
        try {
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    val externalBusy = isExternalMicOrCallActive(configs)
                    if (externalBusy && !pausedForExternalRecording) {
                        pausedForExternalRecording = true
                        Log.i(TAG, "External call or mic session detected — pausing Vosk wake-word cleanly.")
                        stopVoskWakeListener()
                    } else if (!externalBusy && pausedForExternalRecording) {
                        pausedForExternalRecording = false
                        Log.i(TAG, "External mic/call cleared — resuming Vosk wake-word.")
                        wakeRestartJob?.cancel()
                        wakeRestartJob = scope.launch {
                            delay(650L)
                            if (shouldKeepWakeWordListening() && !pausedForExternalRecording) {
                                resumeWakeWordListening()
                            }
                        }
                    }
                }
            }
            val bgThread = HandlerThread("TuneMindVoiceMonitor", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            audioMonitorThread = bgThread
            audioRecordingCallback = cb
            audioManager.registerAudioRecordingCallback(cb, Handler(bgThread.looper))
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecordingCallback registration notice: ${e.message}")
        }
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
            stopVoskWakeListener()
            // If an autonomous DJ handover or track launch is actively running (e.g. screen off or Spotify wake),
            // do NOT cancel TTS, mainHandler callbacks, or handover jobs!
            if (!isExecutingAutonomousHandover && _agentState.value != AgentState.SPEAKING && _agentState.value != AgentState.THINKING) {
                noSpeechAfterWakeJob?.cancel()
                postGreetingListenTimeoutJob?.cancel()
                errorResetJob?.cancel()
                stopMicrophoneStream()
                stopNativeSpeechRecognizer()
                releaseAudioDucking()
            }
            Log.d(TAG, "App left foreground (autonomousHandover=$isExecutingAutonomousHandover) — Vosk wake-word paused; End-of-Song DJ remains active.")
        } else {
            val hasMicPermission = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMicPermission && !hasStartedWakeEngineInit) {
                hasStartedWakeEngineInit = true
                scope.launch {
                    delay(4_500L)
                    if (isAppInForeground &&
                        ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    ) {
                        initWakeWordEngine()
                    } else {
                        hasStartedWakeEngineInit = false
                    }
                }
            }
            if (hasMicPermission) {
                resumeWakeWordListening()
            }
        }
    }

    private fun extractRequestedGenreQuery(text: String): String? {
        val clean = text.lowercase()
        return when {
            Regex("\\b(kikuyu|gikuyu|mugithi)\\b").containsMatchIn(clean) -> "Kikuyu Mugithi"
            Regex("\\bbongo(\\s*flava)?\\b").containsMatchIn(clean) -> "Bongo Flava"
            Regex("\\bgengetone\\b").containsMatchIn(clean) -> "Gengetone Kenyan"
            Regex("\\bohangla\\b").containsMatchIn(clean) -> "Ohangla Kenyan"
            Regex("\\blingala\\b").containsMatchIn(clean) -> "Lingala Rumba"
            Regex("\\bbenga\\b").containsMatchIn(clean) -> "Kenyan Benga"
            Regex("\\bgospel\\b").containsMatchIn(clean) -> "Kenyan Gospel"
            Regex("\\bafrobeat(s)?\\b").containsMatchIn(clean) -> "Afrobeats"
            Regex("\\bamapiano\\b").containsMatchIn(clean) -> "Amapiano"
            Regex("\\breggae\\b").containsMatchIn(clean) -> "Reggae"
            Regex("\\bjazz\\b").containsMatchIn(clean) -> "Jazz"
            Regex("\\bhip\\s*hop\\b").containsMatchIn(clean) -> "Hip Hop"
            Regex("\\br&b\\b").containsMatchIn(clean) -> "R&B"
            else -> null
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
        if (extractRequestedGenreQuery(clean) != null &&
            Regex("\\b(play|spin|put on|give me|find me|recommend|suggest|song|track|music|tune)\\b").containsMatchIn(clean)
        ) {
            return true
        }
        return Regex(
            "\\b(new\\s+(song|track|music|tune|vibe)|fresh\\s+(song|track|music|tune)|any\\s+(good\\s+|nice\\s+|random\\s+)?(song|track|music|tune)|random\\s+(song|track|music)|some\\s+(song|track|music|good|nice)|something\\s+(new|fresh|different|good|nice)|never\\s+heard|haven'?t\\s+heard|discover|recommend|recommendation|suggest|suggestion|surprise\\s+me|anything|whatever|pick\\s+(a|me|any|one)|choose\\s+(a|me|any|one)|find\\s+me\\s+(a|any)\\s+(song|track|music)|give\\s+me\\s+(a|any)\\s+(new\\s+)?(song|track|music)|play\\s+(for\\s+me\\s+|me\\s+)?(a\\s+|some\\s+|any\\s+|another\\s+|random\\s+)?(new\\s+|fresh\\s+|good\\s+|nice\\s+|cool\\s+)?(song|track|music|tune|something))\\b"
        ).containsMatchIn(clean)
    }

    private fun isConversationalNonTitlePhrase(candidate: String?): Boolean {
        val clean = candidate?.lowercase()?.replace(Regex("[^a-z0-9\\s']"), " ")?.replace(Regex("\\s+"), " ")?.trim() ?: return true
        if (clean.length < 2) return true
        if (isMostPlayedSongRequest(clean) || isNewOrRecommendSongRequest(clean)) return true
        if (Regex("^(a|an|any|some|the|good|nice|cool|fresh|new|random|kikuyu|gikuyu|mugithi|bongo|gospel)?\\s*(song|songs|track|tracks|music|tune|tunes|one|something|anything|whatever)$").matches(clean)) {
            return true
        }
        return Regex(
            "\\b(tunemind|tune mind|most|listened|played|heard|recommend|recommended|suggest|suggested|new song|any song|any music|some song|good song|nice song|random song|fresh song|a song|the song|my song|some music|for me|to me|something|anything|whatever|surprise me|another song|different song|favorite|favourite|that i|what i|which i|have listened|i listen|my top|my history|how are you|how is it going|what is up|who are you|settings|theme|color|colour|autonomous dj|stay in tunemind)\\b"
        ).containsMatchIn(clean)
    }

    private fun isExplicitMusicPlayOrRecommendRequest(text: String): Boolean {
        val clean = text.lowercase().trim()
        if (clean.isEmpty()) return false
        if (isMostPlayedSongRequest(clean) || isNewOrRecommendSongRequest(clean)) return true
        if (extractRequestedGenreQuery(clean) != null) return true
        return Regex(
            "\\b(play|spin|put on|queue|recommend|suggest|find me a song|give me a song|play something|play music|another song|different song|next song|bongo|kikuyu|gikuyu|mugithi|gengetone|ohangla|lingala|benga|gospel|afrobeat|amapiano|reggae|r&b|hip hop|chill music|workout music|focus music|study music|capsule|dopamine capsule)\\b"
        ).containsMatchIn(clean)
    }

    private fun isNeverLeaveAppPlayRequest(text: String): Boolean {
        val clean = text.lowercase()
        return Regex(
            "\\b(without\\s+(opening|leaving|launching|switching(\\s+to)?)|don'?t\\s+(open|leave|switch\\s+to)|do\\s+not\\s+(open|leave|switch\\s+to)|not\\s+open\\s+(spotify|boomplay|audiomack|external)|stay\\s+(in|inside)\\s+tunemind|inside\\s+tunemind|play\\s+(it\\s+|that\\s+)?in\\s+tunemind|with\\s+youtube\\s+(in|inside)\\s+tunemind|use\\s+youtube\\s+(in|inside)\\s+tunemind|in\\s*-?app\\s+only|never\\s+open\\s+(spotify|boomplay|audiomack)|no\\s+app\\s+switch)\\b"
        ).containsMatchIn(clean)
    }

    private fun isExplicitOpenExternalRequest(text: String): Boolean {
        val clean = text.lowercase()
        if (Regex("\\b(don'?t|do\\s+not|never|not|without)\\s+(?:open|launch|leave|switch)\\b").containsMatchIn(clean)) {
            return false
        }
        return Regex(
            "\\b(open\\s+(spotify|boomplay|audiomack|youtube\\s*music|deezer|soundcloud|the\\s+app|it\\s+externally|external(?:ly)?)|launch\\s+(spotify|boomplay|audiomack|youtube\\s*music)|switch\\s+to\\s+(spotify|boomplay|audiomack))\\b"
        ).containsMatchIn(clean)
    }

    private fun startCloudKeepAlivePing() {
        scope.launch {
            delay(4_000L)
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
        scope.launch {
            try {
                val modelDir = unpackVoskModelDirectory()
                if (modelDir == null) {
                    Log.e(TAG, "Vosk model assets missing under model-en.")
                    return@launch
                }
                val loaded = Model(modelDir.absolutePath)
                voskModel = loaded
                Log.i(TAG, "Vosk Model loaded cleanly from ${modelDir.absolutePath}.")
                if (shouldKeepWakeWordListening() && _agentState.value == AgentState.IDLE) {
                    mainHandler.post { startWakeWordListener() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unpack/load Vosk wake model: ${e.message}", e)
            }
        }
    }

    private fun unpackVoskModelDirectory(): java.io.File? {
        val targetRoot = java.io.File(context.filesDir, "vosk-model-en-us-0.15")
        val readyMarker = java.io.File(targetRoot, ".tunemind_ready_v1")
        if (readyMarker.exists() && java.io.File(targetRoot, "conf/mfcc.conf").exists()) {
            return targetRoot
        }
        val am = context.assets
        val nestedRoot = "model-en/vosk-model-small-en-us-0.15"
        val directRoot = "model-en"
        val assetSource = when {
            (am.list(nestedRoot)?.isNotEmpty() == true) -> nestedRoot
            (am.list(directRoot)?.contains("conf") == true) -> directRoot
            else -> return null
        }
        if (targetRoot.exists()) {
            targetRoot.deleteRecursively()
        }
        copyAssetTree(am, assetSource, targetRoot)
        readyMarker.writeText("ok")
        return targetRoot
    }

    private fun copyAssetTree(am: android.content.res.AssetManager, assetPath: String, dest: java.io.File) {
        val children = am.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                java.io.FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            dest.mkdirs()
            for (child in children) {
                copyAssetTree(am, "$assetPath/$child", java.io.File(dest, child))
            }
        }
    }

    private fun startWakeWordListener() {
        if (!shouldKeepWakeWordListening()) return
        if (voskModel == null) return
        if (_agentState.value != AgentState.IDLE) return
        if (isVoskListening && wakeRecordJob?.isActive == true) return
        if (pausedForExternalRecording || isExternalMicOrCallActive()) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start Vosk wake listener: RECORD_AUDIO permission not granted.")
            return
        }
        val previousJob = wakeRecordJob
        wakeRecordJob = scope.launch(Dispatchers.IO) {
            // Ensure any previous wake capture job finishes releasing the hardware mic cleanly
            runCatching {
                if (previousJob != null && previousJob.isActive) {
                    isVoskListening = false
                    wakeAudioRecord?.let { runCatching { it.stop() } }
                    previousJob.cancelAndJoin()
                }
            }
            var ownsWakeSession = false
            var localRec: Recognizer? = null
            var localAudio: AudioRecord? = null
            var wakeAec: android.media.audiofx.AcousticEchoCanceler? = null
            var wakeNs: android.media.audiofx.NoiseSuppressor? = null
            try {
                synchronized(voskLock) {
                    if (!shouldKeepWakeWordListening() || _agentState.value != AgentState.IDLE || isVoskListening) {
                        return@launch
                    }
                    val model = voskModel ?: return@launch
                    val sampleRate = 16000
                    val minBuf = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    val bufferSize = maxOf(minBuf * 2, 6400)
                    var ar = runCatching {
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                        )
                    }.getOrNull()
                    if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
                        runCatching { ar?.release() }
                        ar = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                        )
                    }
                    if (ar.state != AudioRecord.STATE_INITIALIZED) {
                        runCatching { ar.release() }
                        Log.w(TAG, "Wake AudioRecord failed to initialize; retrying in 1.5s...")
                        scheduleVoskRestart(1_500L)
                        return@launch
                    }
                    val wakeGrammar = """["hi tune mind", "hey tune mind", "okay tune mind", "tune mind", "[unk]"]"""
                    localRec = try {
                        Recognizer(model, 16000.0f, wakeGrammar)
                    } catch (_: Exception) {
                        Recognizer(model, 16000.0f)
                    }
                    val wakeSessionId = ar.audioSessionId
                    if (wakeSessionId != 0) {
                        runCatching {
                            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                                wakeAec = android.media.audiofx.AcousticEchoCanceler.create(wakeSessionId)?.apply { enabled = true }
                            }
                            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                                wakeNs = android.media.audiofx.NoiseSuppressor.create(wakeSessionId)?.apply { enabled = true }
                            }
                        }
                    }
                    ar.startRecording()
                    localAudio = ar
                    wakeAudioRecord = ar
                    consecutiveTuneMindPartialHits = 0
                    isVoskListening = true
                    ownsWakeSession = true
                    _isWakeWordListeningFlow.value = true
                    Log.d(TAG, "Vosk wake listener active for \"Hi TuneMind\" (foreground=$isAppInForeground)...")
                }

                val chunk = ByteArray(3200) // 100ms @ 16kHz mono PCM16
                synchronized(wakeTailLock) {
                    wakeTailFrames.clear()
                }
                while (isActive && isVoskListening && _agentState.value == AgentState.IDLE && shouldKeepWakeWordListening()) {
                    val ar = localAudio ?: break
                    val read = ar.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        val copy = chunk.copyOf(read)
                        synchronized(wakeTailLock) {
                            while (wakeTailFrames.size >= 3) {
                                wakeTailFrames.removeFirst()
                            }
                            wakeTailFrames.addLast(copy)
                        }
                        val rec = localRec ?: break
                        val isFinal = rec.acceptWaveForm(chunk, read)
                        val jsonOut = if (isFinal) rec.result else rec.partialResult
                        evaluateWakeHypothesis(jsonOut, isFinal)
                    } else if (read < 0) {
                        Log.w(TAG, "Wake AudioRecord read returned $read — restarting loop...")
                        if (shouldKeepWakeWordListening() && _agentState.value == AgentState.IDLE) {
                            scheduleVoskRestart(800L)
                        }
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Vosk wake loop error: ${e.message}")
                if (shouldKeepWakeWordListening()) {
                    scheduleVoskRestart(1_200L)
                }
            } finally {
                synchronized(voskLock) {
                    if (ownsWakeSession) {
                        isVoskListening = false
                        _isWakeWordListeningFlow.value = false
                        lastOwnMicActivityAtMs = System.currentTimeMillis()
                    }
                    if (wakeAudioRecord === localAudio) wakeAudioRecord = null
                    runCatching { wakeAec?.release() }
                    runCatching { wakeNs?.release() }
                    runCatching { localAudio?.stop() }
                    runCatching { localAudio?.release() }
                    runCatching { localRec?.close() }
                }
            }
        }
    }

    private fun scheduleVoskRestart(delayMs: Long = 1_000L) {
        wakeRestartJob?.cancel()
        val cooldownWait = (postAudioCooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val effectiveDelay = maxOf(delayMs, cooldownWait)
        wakeRestartJob = scope.launch {
            delay(effectiveDelay)
            if (shouldKeepWakeWordListening() && _agentState.value == AgentState.IDLE && !isVoskListening) {
                mainHandler.post { startWakeWordListener() }
            }
        }
    }

    // --- VOSK RECOGNITION LISTENER CALLBACKS ---
    override fun onPartialResult(hypothesis: String?) {
        evaluateWakeHypothesis(hypothesis, isFinalResult = false)
    }

    override fun onResult(hypothesis: String?) {
        evaluateWakeHypothesis(hypothesis, isFinalResult = true)
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(e: Exception?) {
        Log.e(TAG, "Vosk Error: ${e?.message}")
        stopVoskWakeListener()
        if (shouldKeepWakeWordListening()) {
            scheduleVoskRestart(1_200L)
        }
    }

    override fun onTimeout() {
        Log.d(TAG, "Vosk Timeout")
        stopVoskWakeListener()
        if (shouldKeepWakeWordListening()) {
            scheduleVoskRestart(400L)
        }
    }

    private fun evaluateWakeHypothesis(jsonStr: String?, isFinalResult: Boolean) {
        if (!shouldKeepWakeWordListening() || jsonStr == null || _agentState.value != AgentState.IDLE) return

        val now = System.currentTimeMillis()
        if (now < postAudioCooldownUntilMs) return
        if ((now - lastWakeWordTriggerAtMs) < 2_500L) return

        val extracted = runCatching {
            val obj = JSONObject(jsonStr)
            (if (isFinalResult) obj.optString("text", "") else obj.optString("partial", "")).ifBlank {
                obj.optString("text", "")
            }
        }.getOrDefault(jsonStr).lowercase().replace(Regex("\\s+"), " ").trim()

        if (extracted.isBlank() || extracted == "[unk]") {
            if (!isFinalResult) consecutiveTuneMindPartialHits = 0
            return
        }

        val hasDirectWakePhrase = extracted.contains("hi tune mind") ||
            extracted.contains("hey tune mind") ||
            extracted.contains("okay tune mind") ||
            extracted.contains("hi tunemind") ||
            extracted.contains("hey tunemind") ||
            extracted.contains("tunemind") ||
            extracted.contains("tune mind")

        if (hasDirectWakePhrase) {
            consecutiveTuneMindPartialHits++
        } else if (!isFinalResult) {
            consecutiveTuneMindPartialHits = 0
        }

        val musicPlayingNow = runCatching {
            com.tunemind.app.service.TuneMindListenerService.instance?.isPlaying() == true ||
                com.tunemind.app.provider.TuneMindInAppPlayer.isPlaying() ||
                com.tunemind.app.provider.TuneMindYouTubeEmbedController.isPlaying()
        }.getOrDefault(false)

        // When music or recent TTS audio is present, require either a final hypothesis or 2+ partial hits (~200ms)
        // so stray lyrics or speaker tail cannot false-trigger a pause/greeting loop right after playback starts.
        val shouldWake = hasDirectWakePhrase &&
            (!musicPlayingNow || isFinalResult || consecutiveTuneMindPartialHits >= 2)

        if (shouldWake) {
            lastWakeWordTriggerAtMs = now
            consecutiveTuneMindPartialHits = 0
            Log.d(TAG, "Wake Word Confirmed (\"$extracted\", final=$isFinalResult, music=$musicPlayingNow, fg=$isAppInForeground) -> activating!")
            isVoskListening = false
            _isWakeWordListeningFlow.value = false
            wakeRecordJob?.cancel()
            mainHandler.post {
                stopVoskWakeListener()
                handleHotwordDetected()
            }
        }
    }

    private val prefs: android.content.SharedPreferences
        get() = com.tunemind.app.data.auth.TuneMindAccountStorage.getUserPrefs(context, "tunemind_agent_memory")

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

    private fun JSONObject.appendHearingTelemetryJson(hearing: com.tunemind.app.ui.HearingInsightsState?) {
        if (hearing == null) return
        val estDb = hearing.estimatedAverageDb.toDouble().takeIf { !it.isNaN() } ?: 68.0
        val headroomDb = hearing.currentHeadroomDb.toDouble().takeIf { !it.isNaN() } ?: 17.0
        val riskLevel = when {
            estDb >= 92.0 -> "High Risk"
            estDb >= 85.0 -> "Loud"
            estDb >= 80.0 -> "Moderate"
            else -> "Safe"
        }
        val cleanDevice = com.tunemind.app.domain.audio.AudioDeviceObserver.sanitizeOutputDeviceLabel(
            raw = hearing.activeSnapshot?.productName ?: hearing.activeAudioDevice?.deviceName,
            transducerCategory = hearing.activeSnapshot?.transducerCategory
        )
        put("hearing_telemetry", JSONObject().apply {
            put("estimated_db", estDb)
            put("safe_headroom_db", headroomDb)
            put("daily_exposure_minutes", hearing.dailyExposureMinutes)
            put("daily_exposure_seconds", (hearing.dailyExposureMs / 1000L).toInt())
            put("sound_dose_percent", (hearing.soundDoseFraction * 100f).toInt().coerceIn(0, 200))
            put("safe_hours_remaining", String.format(java.util.Locale.US, "%.1f", hearing.safeHoursRemaining).toDoubleOrNull() ?: 24.0)
            put("risk_level", riskLevel)
            put("output_device", cleanDevice)
            put("transducer_category", hearing.activeSnapshot?.transducerCategory?.takeIf { it.isNotBlank() } ?: "AUDIO")
            put("safety_guidance", hearing.safetyGuidance)
        })
    }

    private fun JSONObject.appendLocalTimeContextJson() {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val daypart = when (hour) {
            in 6..11 -> "Morning"
            in 12..16 -> "Midday"
            in 17..21 -> "Evening"
            else -> "Late Night"
        }
        put("circadian_daypart", daypart)
        put("local_hour", hour)
        put("local_minute", minute)
        put("local_time", "%02d:%02d".format(hour, minute))
        put("timezone", java.util.TimeZone.getDefault().id)
    }

    private fun JSONObject.appendStudioContextJson() {
        appendLocalTimeContextJson()
        val isProUser = com.tunemind.app.billing.TuneMindBillingManager.getInstance(context).state.value.hasProAccess
        val savedName = com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(context)
            .sessionFlow.value.displayName.trim()
        if (isProUser && savedName.isNotBlank() && savedName != "TuneMind Listener") {
            put("user_display_name", savedName)
        }
        val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
        val installedPlayers = com.tunemind.app.ui.components.MusicPlayerUiHelper.filterMusicPlayers(
            context,
            registry.getInstalledProviders()
        )
        val hasExplicitPlayer = registry.hasExplicitDefault()
        val preferredId = registry.getPreferredProviderId()
        val preferredProvider = registry.getProvider(preferredId)
        put("player_preferences", JSONObject().apply {
            val ytmInstalled = installedPlayers.any {
                it.packageName.equals("com.google.android.apps.youtube.music", ignoreCase = true)
            } || com.tunemind.app.provider.YouTubeMusicProvider().isInstalled(context)
            val supportsFullInApp = preferredId == "tunemind_inapp" ||
                preferredId == "local_files" ||
                preferredProvider.packageName.equals("com.google.android.apps.youtube.music", ignoreCase = true)
            put("has_explicit_default_player", hasExplicitPlayer)
            put("default_player_id", preferredId)
            put("default_player_package", preferredProvider.packageName.ifBlank { preferredId })
            put("default_player_name", com.tunemind.app.ui.components.MusicPlayerUiHelper.shortDisplayName(preferredProvider))
            put("youtube_music_installed", ytmInstalled)
            put("default_player_supports_inapp_full_stream", supportsFullInApp)
            put("available_players", org.json.JSONArray().apply {
                installedPlayers.take(8).forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.providerId)
                        put("name", com.tunemind.app.ui.components.MusicPlayerUiHelper.shortDisplayName(p))
                        put("package_name", p.packageName.ifBlank { p.providerId })
                    })
                }
            })
        })

        val forYouList = getForYouPicks?.invoke()?.filter { !it.isDismissed }.orEmpty()
        if (forYouList.isNotEmpty()) {
            put("for_you_picks", org.json.JSONArray().apply {
                forYouList.take(15).forEach { rec ->
                    put(JSONObject().apply {
                        put("title", rec.title)
                        put("artist", rec.artist)
                        put("match_percent", rec.matchScorePercent)
                        put("category", rec.category)
                        put("sub_tier", rec.subTier)
                        put("genre_tag", rec.genreTag)
                        put("is_wildcard", rec.isWildcard)
                        put("is_saved", rec.isSaved)
                        put("reason", rec.reason)
                        put("spotify_uri", rec.spotifyUri ?: "")
                        put("track_id", rec.resolvedCanonicalTrackId())
                    })
                }
            })
        }

        val libTracks = getCrossAppLibrarySnapshot()
        if (libTracks.isNotEmpty()) {
            put("cross_app_library", org.json.JSONArray().apply {
                libTracks.take(45).forEach { lib ->
                    put(JSONObject().apply {
                        put("title", lib.title)
                        put("artist", lib.artist)
                        put("play_count", lib.playCount)
                        put("source_player", lib.primarySourcePackage)
                        put("source_packages", lib.sourcePackagesCsv)
                        put("is_liked", lib.isLiked)
                        put("genre", lib.genre)
                        put("track_id", lib.trackId)
                        lib.ytmVideoId?.takeIf { it.isNotBlank() }?.let { put("ytm_video_id", it) }
                        lib.audiusStreamUrl?.takeIf { it.isNotBlank() }?.let { put("audius_stream_url", it) }
                        lib.previewUrl?.takeIf { it.isNotBlank() }?.let { put("preview_url", it) }
                    })
                }
            })
            val likedTracks = libTracks.filter { it.isLiked }
            if (likedTracks.isNotEmpty()) {
                put("cross_app_liked_tracks", org.json.JSONArray().apply {
                    likedTracks.take(25).forEach { lk ->
                        put(JSONObject().apply {
                            put("title", lk.title)
                            put("artist", lk.artist)
                            put("source_player", lk.primarySourcePackage)
                            put("track_id", lk.trackId)
                        })
                    }
                })
            }
        }
        val playerTabs = getDetectedPlayerTabs?.invoke().orEmpty()
        if (playerTabs.isNotEmpty()) {
            put("detected_player_tabs", org.json.JSONArray().apply {
                playerTabs.forEach { tab ->
                    put(JSONObject().apply {
                        put("package_name", tab.packageName)
                        put("display_name", tab.displayName)
                        put("track_count", tab.trackCount)
                        put("total_plays", tab.totalPlays)
                    })
                }
            })
        }

        val tmPlaylists = getTuneMindPlaylists?.invoke().orEmpty()
        put("tunemind_playlists", org.json.JSONArray().apply {
            tmPlaylists.take(30).forEach { item ->
                put(JSONObject().apply {
                    put("playlist_id", item.playlist.playlistId)
                    put("name", item.playlist.name)
                    put("description", item.playlist.description)
                    put("mood_or_audience", item.playlist.moodOrAudience)
                    put("track_count", item.tracks.size)
                    put("tracks", org.json.JSONArray().apply {
                        item.tracks.take(40).forEach { tr ->
                            put(JSONObject().apply {
                                put("title", tr.title)
                                put("artist", tr.artist)
                                put("external_uri", tr.externalUri)
                                put("track_id", tr.trackId)
                                put("source_player", tr.sourcePlayer)
                            })
                        }
                    })
                })
            }
        })
        put("kids_mode_enabled", AgentSettingsStore.isKidsModeEnabled(context))
        getSleepTimerRemainingMinutes?.invoke()?.let { mins ->
            put("active_sleep_timer_minutes", mins)
        }

        val statsSnapshot = getListeningStats?.invoke()
        val cleanHistory = getCleanPlayHistory?.invoke().orEmpty()
        if (statsSnapshot != null) {
            val todayWin = com.tunemind.app.domain.memory.ListeningPlayDeduplicator.buildWindowedStats(
                cleanPlayHistory = cleanHistory,
                overallStats = statsSnapshot,
                window = com.tunemind.app.domain.memory.StatsTimeWindow.TODAY
            )
            val sevenWin = com.tunemind.app.domain.memory.ListeningPlayDeduplicator.buildWindowedStats(
                cleanPlayHistory = cleanHistory,
                overallStats = statsSnapshot,
                window = com.tunemind.app.domain.memory.StatsTimeWindow.SEVEN_DAYS
            )
            val thirtyWin = com.tunemind.app.domain.memory.ListeningPlayDeduplicator.buildWindowedStats(
                cleanPlayHistory = cleanHistory,
                overallStats = statsSnapshot,
                window = com.tunemind.app.domain.memory.StatsTimeWindow.THIRTY_DAYS
            )
            val allWin = com.tunemind.app.domain.memory.ListeningPlayDeduplicator.buildWindowedStats(
                cleanPlayHistory = cleanHistory,
                overallStats = statsSnapshot,
                window = com.tunemind.app.domain.memory.StatsTimeWindow.ALL_TIME
            )

            put("listening_stats", JSONObject().apply {
                put("today_minutes", (statsSnapshot.todayListeningMs / 60_000L).toInt())
                put("seven_day_minutes", (statsSnapshot.sevenDayListeningMs / 60_000L).toInt())
                put("thirty_day_minutes", (statsSnapshot.thirtyDayListeningMs / 60_000L).toInt())
                put("all_time_minutes", (statsSnapshot.allTimeListeningMs / 60_000L).toInt())
                put("total_verified_plays", statsSnapshot.totalVerifiedPlays)
                put("unique_tracks", statsSnapshot.uniqueTrackCount)
                put("unique_artists", statsSnapshot.uniqueArtistCount)
                put("skipped_count", statsSnapshot.skippedCount)
                put("completion_rate_percent", statsSnapshot.completionRatePercent)
                put("discovery_rate_percent", statsSnapshot.discoveryRatePercent)
                put("peak_window_label", statsSnapshot.peakWindowLabel)
                put("windows", JSONObject().apply {
                    put("today", JSONObject().apply {
                        put("minutes", (todayWin.totalListeningMs / 60_000L).toInt())
                        put("plays", todayWin.totalVerifiedPlays)
                        put("unique_tracks", todayWin.uniqueTrackCount)
                        put("skipped", todayWin.skippedCount)
                        put("completion_rate_percent", todayWin.completionRatePercent)
                        put("discovery_rate_percent", todayWin.discoveryRatePercent)
                        put("peak_window", todayWin.peakWindowLabel)
                    })
                    put("seven_days", JSONObject().apply {
                        put("minutes", (sevenWin.totalListeningMs / 60_000L).toInt())
                        put("plays", sevenWin.totalVerifiedPlays)
                        put("unique_tracks", sevenWin.uniqueTrackCount)
                        put("unique_artists", sevenWin.uniqueArtistCount)
                        put("skipped", sevenWin.skippedCount)
                        put("completion_rate_percent", sevenWin.completionRatePercent)
                        put("discovery_rate_percent", sevenWin.discoveryRatePercent)
                        put("peak_window", sevenWin.peakWindowLabel)
                        put("range_label", sevenWin.windowRangeLabel)
                    })
                    put("thirty_days", JSONObject().apply {
                        put("minutes", (thirtyWin.totalListeningMs / 60_000L).toInt())
                        put("plays", thirtyWin.totalVerifiedPlays)
                        put("unique_tracks", thirtyWin.uniqueTrackCount)
                        put("unique_artists", thirtyWin.uniqueArtistCount)
                        put("skipped", thirtyWin.skippedCount)
                        put("completion_rate_percent", thirtyWin.completionRatePercent)
                        put("discovery_rate_percent", thirtyWin.discoveryRatePercent)
                    })
                    put("all_time", JSONObject().apply {
                        put("minutes", (allWin.totalListeningMs / 60_000L).toInt())
                        put("plays", allWin.totalVerifiedPlays)
                        put("unique_tracks", allWin.uniqueTrackCount)
                        put("unique_artists", allWin.uniqueArtistCount)
                        put("skipped", allWin.skippedCount)
                        put("completion_rate_percent", allWin.completionRatePercent)
                        put("discovery_rate_percent", allWin.discoveryRatePercent)
                    })
                })
                put("top_artists", org.json.JSONArray().apply {
                    statsSnapshot.topArtists.take(8).forEach { a ->
                        put(JSONObject().apply {
                            put("artist", a.artistName)
                            put("plays", a.playCount)
                            put("minutes", (a.listeningMs / 60_000L).toInt())
                        })
                    }
                })
                put("top_tracks", org.json.JSONArray().apply {
                    statsSnapshot.topTracks.take(10).forEach { tr ->
                        put(JSONObject().apply {
                            put("title", tr.title)
                            put("artist", tr.artist)
                            put("plays", tr.verifiedPlayCount)
                            put("freshness_percent", tr.freshnessPercent)
                            put("is_cooling_down", tr.isCoolingDown)
                            put("app", tr.primaryAppName)
                        })
                    }
                })
                put("app_breakdown", org.json.JSONArray().apply {
                    statsSnapshot.appBreakdown.take(6).forEach { ap ->
                        put(JSONObject().apply {
                            put("app", ap.displayName)
                            put("plays", ap.playCount)
                            put("minutes", (ap.listeningMs / 60_000L).toInt())
                            put("share_percent", (ap.shareFraction * 100f).toInt())
                        })
                    }
                })
                if (cleanHistory.isNotEmpty()) {
                    put("recent_play_history", org.json.JSONArray().apply {
                        cleanHistory.take(10).forEach { ev ->
                            put(JSONObject().apply {
                                put("title", ev.title)
                                put("artist", ev.artist)
                                put("app", ev.appDisplayName)
                                put("listened_minutes", (ev.listenedMs / 60_000L).toInt())
                            })
                        }
                    })
                }
            })
        }

        val identity = com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.resolveUserIdentity(context)
        put("user_id", identity.userId)
        put("user_email", identity.email)
        put("supabase_url", identity.supabaseUrl)
        put("supabase_anon_key", identity.supabaseAnonKey)
        if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnected(context)) {
            com.tunemind.app.provider.SpotifyPremiumConnectManager.getValidAccessTokenQuick(context)
                ?.takeIf { it.isNotBlank() }
                ?.let { put("spotify_access_token", it) }
            com.tunemind.app.provider.SpotifyPremiumConnectManager.ensureLoaded(context)
                .lastDeviceName
                .takeIf { it.isNotBlank() }
                ?.let { put("spotify_device_name", it) }
        }
        put("dialogue_history", getDialogueHistoryJsonArray())
        getAppState?.invoke()?.let { put("app_state", it) }
    }

    // Wake Word / Manual Orb tap handler
    fun simulateWakeWordDetected() {
        activateVoiceTurn(fromHotword = false)
    }

    fun handleHotwordDetected() {
        activateVoiceTurn(fromHotword = true)
    }

    private fun activateVoiceTurn(fromHotword: Boolean) {
        if (!fromHotword) {
            isAppInForeground = true
        }
        if (_agentState.value == AgentState.LISTENING) return

        if (_agentState.value == AgentState.SPEAKING || _agentState.value == AgentState.THINKING) {
            Log.d(TAG, "Interrupting active agent speech/thinking to start new voice turn...")
            executionEngine.stopSpeaking()
        }

        noSpeechAfterWakeJob?.cancel()
        postGreetingListenTimeoutJob?.cancel()
        cloudResponseTimeoutJob?.cancel()
        errorResetJob?.cancel()
        wasTurnTriggeredByWakeWord = fromHotword
        hasReceivedSpeechThisTurn = false
        hasSpokenMorningPromptThisTurn = false
        hasCompletedCloudTurn = false
        latestWsPartialText = ""
        lastWsPartialProgressAtMs = 0L
        latestLocalVoskCommandText = ""
        latestNativePartialText = ""
        adaptiveMicGain = 1.0f
        preConnectAudioBuffer.clear()
        isWsOpenForAudio = false
        pendingWakeTailFrames.clear()
        if (fromHotword) {
            synchronized(wakeTailLock) {
                val frames = wakeTailFrames.toList()
                wakeTailFrames.clear()
                if (frames.isNotEmpty()) {
                    val tailPeakRms = frames.maxOf { computePcm16Rms(it, it.size) }
                    if (tailPeakRms > 210.0) {
                        frames.forEach { pendingWakeTailFrames.offer(it) }
                    }
                }
            }
        }
        prewarmCloudConnectionAsync()
        refreshUnifiedTracksCacheAsync()

        val listenerPlaying = runCatching { com.tunemind.app.service.TuneMindListenerService.instance?.isPlaying() == true }.getOrDefault(false)
        val inAppPlaying = runCatching {
            com.tunemind.app.provider.TuneMindInAppPlayer.isPlaying() ||
                com.tunemind.app.provider.TuneMindYouTubeEmbedController.isPlaying()
        }.getOrDefault(false)
        val npPlaying = getNowPlaying?.invoke()?.isPlaying == true
        wasMediaPlayingBeforeWake = listenerPlaying || inAppPlaying || npPlaying

        Log.d(TAG, "Voice turn activated (fromHotword=$fromHotword, foreground=$isAppInForeground, wasMediaPlaying=$wasMediaPlayingBeforeWake)")

        if (fromHotword) {
            acquireTemporaryHandoverWakeLock(45_000L)
        }

        stopVoskWakeListener()
        stopAudioRecordingOnly()
        try {
            webSocket?.close(1000, "New voice turn")
        } catch (_: Exception) {}
        webSocket = null
        stopNativeSpeechRecognizer()

        // Pause any playing background music immediately so speaker audio never bleeds into the microphone
        try {
            executionEngine.pauseCurrentMedia()
        } catch (e: Exception) {
            Log.w(TAG, "Could not pause media session before listening: ${e.message}")
        }

        _agentState.value = AgentState.LISTENING
        requestAudioDucking()

        // Open WebSocket immediately in parallel with chime/AudioRecord init so AssemblyAI Begin arrives sooner
        val wsCandidates = com.tunemind.app.domain.network.BackendEndpointManager.getCandidateWebSocketUrls(context)
        connectToCandidateWebSocket(wsCandidates, 0)

        val chimePlayed = if (fromHotword) {
            playReadyChimeIfEnabled()
        } else {
            false
        }

        val startDelayMs = if (chimePlayed) 80L else 0L
        if (startDelayMs > 0L) {
            mainHandler.postDelayed({
                if (_agentState.value == AgentState.LISTENING) {
                    startLocalAudioCapture()
                    startNoSpeechAfterWakeWatchdog()
                }
            }, startDelayMs)
        } else {
            startLocalAudioCapture()
            if (fromHotword) startNoSpeechAfterWakeWatchdog()
        }
    }

    private fun playReadyChimeIfEnabled(): Boolean {
        return try {
            val tg = chimeToneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, 58).also {
                chimeToneGenerator = it
            }
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 75)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Ready chime playback notice: ${e.message}")
            false
        }
    }

    private fun buildCircadianWakeGreeting(): String {
        if (wasMediaPlayingBeforeWake) {
            return "Yes, I'm TuneMind — I paused the music. What would you like?"
        }
        val hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hr) {
            in 4..11 -> "Good morning! I'm TuneMind, I'm listening. What would you like to hear?"
            in 12..16 -> "Hey! I'm TuneMind, right here with you. What would you like to play?"
            else -> "Hey, I'm TuneMind! Tell me what you feel like hearing."
        }
    }

    private fun startNoSpeechAfterWakeWatchdog() {
        noSpeechAfterWakeJob?.cancel()
        if (!wasTurnTriggeredByWakeWord || hasSpokenMorningPromptThisTurn) return
        noSpeechAfterWakeJob = scope.launch {
            delay(3_200L)
            if (_agentState.value == AgentState.LISTENING &&
                isRecording &&
                wasTurnTriggeredByWakeWord &&
                !hasReceivedSpeechThisTurn &&
                !hasSpokenMorningPromptThisTurn
            ) {
                hasSpokenMorningPromptThisTurn = true
                Log.i(TAG, "User called \"Hi TuneMind\" and waited — speaking circadian greeting then reopening mic once.")
                stopMicrophoneStream()
                stopNativeSpeechRecognizer()
                setSpeakingState()
                val greeting = buildCircadianWakeGreeting()
                scope.launch { _transcriptionFlow.emit(greeting) }
                mainHandler.post {
                    markPostAudioCooldown(2_400L)
                    executionEngine.speakPromptOnly(greeting) {
                        mainHandler.post {
                            markPostAudioCooldown(650L)
                            hasReceivedSpeechThisTurn = false
                            _agentState.value = AgentState.LISTENING
                            requestAudioDucking()
                            startMicrophoneStream()
                            postGreetingListenTimeoutJob?.cancel()
                            postGreetingListenTimeoutJob = scope.launch {
                                delay(6_000L)
                                if (_agentState.value == AgentState.LISTENING && !hasReceivedSpeechThisTurn) {
                                    Log.d(TAG, "No follow-up command after greeting — returning cleanly to IDLE.")
                                    val shouldResume = wasMediaPlayingBeforeWake
                                    wasMediaPlayingBeforeWake = false
                                    wasTurnTriggeredByWakeWord = false
                                    stopMicrophoneStream()
                                    stopNativeSpeechRecognizer()
                                    releaseAudioDucking()
                                    if (shouldResume) {
                                        executionEngine.resumeCurrentMedia()
                                    }
                                    resumeWakeWordListening()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun markUserSpeechDetectedThisTurn() {
        hasReceivedSpeechThisTurn = true
        noSpeechAfterWakeJob?.cancel()
        postGreetingListenTimeoutJob?.cancel()
    }

    private fun stripLeadingWakePhrase(raw: String): String {
        return raw
            .replace(Regex("^\\s*(?:hi|hey|hello|ok|okay)?\\s*(?:tune\\s*mind|tune)\\b[,:.!?\\s]*", RegexOption.IGNORE_CASE), "")
            .trim()
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
        targetPlayer: String = "tunemind_inapp",
        customSpokenLine: String? = null,
        neverOpenExternalApp: Boolean = false
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
        markPostAudioCooldown(4_000L)
        _agentState.value = AgentState.SPEAKING
        _flowAnalysisFlow.value = "Direct studio launch: spinning $cleanTitle in peak dopamine state."
        _agentSatietyFlow.value = 18
        mainHandler.post {
            onTrackDispatched?.invoke(cleanTitle, cleanArtist, targetPlayer.ifBlank { "tunemind_inapp" })
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
                target_player = targetPlayer.ifBlank { "tunemind_inapp" },
                search_query = searchQuery,
                suggested_bpm_target = 108,
                track_title = cleanTitle,
                track_artist = cleanArtist,
                spotify_uri = spotifyUri,
                never_open_external_app = neverOpenExternalApp
            )
        )

        executionEngine.executeBackendCommand(
            response = responseObj,
            onBeforeLaunch = {
                mainHandler.post {
                    markPostAudioCooldown(4_000L)
                    abandonAssistantAudioFocusOnly()
                }
            },
            onFinished = {
                mainHandler.post {
                    isExecutingAutonomousHandover = false
                    markPostAudioCooldown(3_200L)
                    releaseAudioDucking()
                    resumeWakeWordListening()
                }
            },
            onLaunchResult = { launchRes ->
                mainHandler.post {
                    onPlaybackLaunchResult?.invoke(launchRes)
                    if (launchRes is com.tunemind.app.provider.PlaybackLaunchResult.LaunchFailed) {
                        _flowAnalysisFlow.value = launchRes.reason
                        scope.launch {
                            _transcriptionFlow.emit(launchRes.reason)
                        }
                    } else if (launchRes is com.tunemind.app.provider.PlaybackLaunchResult.DirectPlaybackLaunched &&
                        launchRes.providerId == "tunemind_inapp"
                    ) {
                        _flowAnalysisFlow.value = launchRes.message
                    }
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

        com.tunemind.app.provider.TuneMindInAppPlayer.setOnTrackCompletedListener { doneTitle, doneArtist, pkg ->
            if (AgentSettingsStore.isAutonomousDjEnabled(context)) {
                triggerAutonomousEndOfSongHandover(
                    finishedTitle = doneTitle,
                    finishedArtist = doneArtist,
                    targetPlayer = pkg.ifBlank { "tunemind_inapp" }
                )
            }
        }

        scope.launch {
            while (isActive) {
                val autonomousOn = AgentSettingsStore.isAutonomousDjEnabled(context)
                try {
                    if (autonomousOn) {
                        val snap = com.tunemind.app.service.TuneMindListenerService.instance?.getActivePlaybackSnapshot()
                        if (snap != null) {
                            evaluatePlaybackSnapshotForEndOfSong(snap)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "End-of-song monitor poll notice: ${e.message}")
                }
                delay(if (autonomousOn) 750L else 3_000L)
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
        // Strict consent gate: NEVER talk or queue next songs autonomously unless user enabled Autonomous DJ.
        // Also yield to TuneMind's Up Next Auto Override whenever the user activated Auto Queue Takeover.
        if (!AgentSettingsStore.isAutonomousDjEnabled(context) ||
            AgentSettingsStore.isUpNextAutoOverrideEnabled(context)
        ) {
            isAwaitingEndOfSongUserChoice = false
            monitoredTrackTitle = ""
            monitoredTrackMaxPosMs = 0L
            return
        }

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
                    targetPlayer = snap.packageName.ifBlank { "tunemind_inapp" }
                )
            }
            return
        }

        // Track title just changed (`rawTitle != monitoredTrackTitle`)
        val prevTitle = monitoredTrackTitle
        val prevArtist = monitoredTrackArtist
        val prevDur = monitoredTrackDurationMs
        val prevMaxPos = monitoredTrackMaxPosMs

        val wasDispatchedByTuneMind = com.tunemind.app.provider.MusicPlayerAutoLauncher.wasRecentlyDispatched(rawTitle) || (
            lastDispatchedByTuneMindTitle.isNotBlank() && (
                rawTitle.equals(lastDispatchedByTuneMindTitle, ignoreCase = true) ||
                    rawTitle.contains(lastDispatchedByTuneMindTitle, ignoreCase = true) ||
                    lastDispatchedByTuneMindTitle.contains(rawTitle, ignoreCase = true)
                )
            )
        val previousPlayedSubstantially = prevMaxPos >= 25_000L || (prevDur >= 25_000L && prevMaxPos >= (prevDur * 65L) / 100L)

        // TRIGGER B: Previous track played substantially and player auto-advanced to an unapproved random next track!
        if (!wasDispatchedByTuneMind &&
            previousPlayedSubstantially &&
            !lastHandoverCompletedForTitle.equals(prevTitle, ignoreCase = true)
        ) {
            Log.i(
                TAG,
                "🎵 Autonomous End-of-Song Trigger B (Intercepted unapproved auto-advance '$prevTitle' -> '$rawTitle')"
            )
            triggerAutonomousEndOfSongHandover(
                finishedTitle = prevTitle,
                finishedArtist = prevArtist,
                targetPlayer = snap.packageName.ifBlank { "tunemind_inapp" }
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
        if (!AgentSettingsStore.isAutonomousDjEnabled(context)) return
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
                search_query = "",
                suggested_bpm_target = null
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
     * Falls back to open-catalog discovery + Dopamine Capsule if offline.
     */
    fun autoCurateAndPlayNextTrackWithClaude(
        finishedTitle: String,
        finishedArtist: String,
        targetPlayer: String = "tunemind_inapp"
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
                val crossAppLib = getCrossAppLibrarySnapshot()
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
                    if (crossAppLib.isNotEmpty()) {
                        for (lib in crossAppLib.take(60)) {
                            historyArr.put(JSONObject().apply {
                                put("title", lib.title)
                                put("artist", lib.artist)
                                put("source_player", lib.primarySourcePackage.ifBlank { "unknown_player" })
                                put("playCount", lib.playCount.coerceAtLeast(0))
                                put("trackId", lib.trackId)
                                val link = lib.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                                    ?: lib.deezerUrl?.takeIf { it.isNotBlank() } ?: ""
                                put("externalUri", link)
                            })
                        }
                    } else {
                        for (r in recents.take(60)) {
                            val tTitle = r.title?.takeIf { it.isNotBlank() } ?: continue
                            val tArtist = r.artist ?: ""
                            historyArr.put(JSONObject().apply {
                                put("title", tTitle)
                                put("artist", tArtist)
                                put("source_player", r.sourcePackage?.takeIf { it.isNotBlank() } ?: "unknown_player")
                                put("playCount", r.distinctAppearanceCount.coerceAtLeast(0))
                                put("trackId", r.canonicalTrackId())
                            })
                        }
                    }
                    put("rolling_history", historyArr)
                    put("recently_played_by_agent", org.json.JSONArray((getRecentlyPlayedByAgent() + listOf(finishedTitle)).distinct()))

                    appendHearingTelemetryJson(hearing)

                    put("vault_tracks", buildVaultJsonArray(vaultList))

                    if (capsule != null && capsule.tracks.isNotEmpty()) {
                        val capArr = org.json.JSONArray()
                        for (ct in capsule.tracks) {
                            val capIdentity = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                                spotifyUri = ct.spotifyUri,
                                title = ct.title,
                                artist = ct.artist
                            )
                            capArr.put(JSONObject().apply {
                                put("title", ct.title)
                                put("artist", ct.artist)
                                put("trackId", capIdentity.trackId)
                                put("spotifyUri", capIdentity.spotifyUri ?: "")
                                put("freshness_percent", ct.freshnessPercent)
                                put("reason", ct.reason)
                            })
                        }
                        put("dopamine_capsule", capArr)
                    }

                    appendStudioContextJson()
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

            // Fallback to open-catalog discovery or Dopamine Capsule if backend was unreachable or didn't return a track
            if (chosenTitle.isNullOrBlank()) {
                val excluded = (getRecentlyPlayedByAgent() + listOf(finishedTitle))
                    .map { it.lowercase().trim() }
                    .toSet()
                val discovery = fetchOpenCatalogDiscoveryFallback(excluded)
                if (discovery != null) {
                    chosenTitle = discovery.trackTitle
                    chosenArtist = discovery.artistName
                    chosenUri = discovery.deepLinkUrl
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
            val finalUri = chosenUri?.takeIf { it.startsWith("http") }
                ?: resolveOpenCatalogLinkBlocking(
                    title = finalTitle,
                    artist = finalArtist,
                    fullQuery = "$finalTitle $finalArtist".trim()
                ) ?: chosenUri
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
        val canonical = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
            trackId = pending.trackId,
            spotifyUri = pending.spotifyUri,
            title = pending.title,
            artist = pending.artist
        )
        com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.recordVoiceOrTrackDecisionAsync(
            context = context,
            triggerTranscript = "User approved proposed song: ${pending.title}",
            agentTtsResponse = "Playing ${pending.title} by ${pending.artist} now.",
            action = "APPROVED_TRACK",
            osIntent = "OS_MEDIA_NEXT_AND_DEEP_LINK",
            trackTitle = pending.title,
            trackArtist = pending.artist,
            spotifyUri = canonical.spotifyUri,
            satietyPercent = 18,
            trackId = canonical.trackId
        )
        playTrackImmediatelyWithVoice(
            title = pending.title,
            artist = pending.artist,
            spotifyUri = canonical.spotifyUri,
            targetPlayer = pending.source.ifBlank { "tunemind_inapp" },
            neverOpenExternalApp = pending.neverOpenExternalApp
        )
    }

    /**
     * Triggered when the user passes/rejects a proposed song on PulseAgentScreen and wants another suggestion.
     */
    fun rejectPendingProposedTrack() {
        val rejected = clientPendingProposedTrack
        updatePendingProposedTrack(null)
        if (rejected != null) {
            val canonical = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                trackId = rejected.trackId,
                spotifyUri = rejected.spotifyUri,
                title = rejected.title,
                artist = rejected.artist
            )
            recordAgentSuggestedTrack(rejected.title)
            com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager.recordVoiceOrTrackDecisionAsync(
                context = context,
                triggerTranscript = "User passed/rejected proposed song: ${rejected.title}",
                agentTtsResponse = "Finding a different song that fits your vibe better.",
                action = "REJECTED_TRACK",
                osIntent = "NONE",
                trackTitle = rejected.title,
                trackArtist = rejected.artist,
                spotifyUri = canonical.spotifyUri,
                satietyPercent = _agentSatietyFlow.value,
                trackId = canonical.trackId
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
        targetPlayer: String = "tunemind_inapp",
        trackId: String? = null
    ) {
        errorResetJob?.cancel()
        stopMicrophoneStream()
        stopNativeSpeechRecognizer()
        executionEngine.stopSpeaking()

        val cleanTitle = title.trim()
        val cleanArtist = artist.trim().takeIf { !it.equals("Unknown Artist", ignoreCase = true) } ?: ""
        val mood = com.tunemind.app.ui.theme.AdaptiveThemeEngine.resolveTrackMood(cleanTitle, cleanArtist)
        val canonical = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
            trackId = trackId,
            spotifyUri = spotifyUri,
            title = cleanTitle,
            artist = cleanArtist
        )

        updatePendingProposedTrack(
            PendingProposedTrack(
                title = cleanTitle,
                artist = cleanArtist,
                trackId = canonical.trackId,
                source = targetPlayer,
                spotifyUri = canonical.spotifyUri
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
        targetPlayer: String = "tunemind_inapp"
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

        val leadIdentity = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
            spotifyUri = lead.spotifyUri,
            title = lead.title,
            artist = lead.artist
        )
        updatePendingProposedTrack(
            PendingProposedTrack(
                title = lead.title,
                artist = lead.artist,
                trackId = leadIdentity.trackId,
                source = targetPlayer,
                spotifyUri = leadIdentity.spotifyUri
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

        isWsOpenForAudio = false
        hasCompletedCloudTurn = false
        preConnectAudioBuffer.clear()
        lastWsPartialProgressAtMs = 0L
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
                    val crossAppLib = getCrossAppLibrarySnapshot()
                    if (crossAppLib.isEmpty()) refreshUnifiedTracksCacheAsync()
                    val hearing = getHearingInsights?.invoke()
                    val vaultList = getVaultTracks?.invoke() ?: emptyList()
                    val capsule = getDopamineCapsule?.invoke()
                    val pendingProp = clientPendingProposedTrack

                    val metaJson = JSONObject().apply {
                        put("type", "client_context")
                        val currentIdentity = np?.let { cur ->
                            val matchedLib = crossAppLib.firstOrNull { t -> t.title.equals(cur.title, ignoreCase = true) }
                            val matchedRecent = recents.firstOrNull { t -> t.title.equals(cur.title, ignoreCase = true) }
                            com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                                trackId = matchedLib?.trackId ?: matchedRecent?.trackId?.ifBlank { null },
                                spotifyUri = matchedRecent?.providerTrackId,
                                title = cur.title,
                                artist = cur.artist
                            )
                        }
                        val currentObj = JSONObject().apply {
                            put("title", np?.title ?: "Standing By")
                            put("artist", np?.artist ?: "")
                            put("source_player", np?.sourcePackage ?: "unknown_player")
                            put("trackId", currentIdentity?.trackId ?: "")
                            put("spotifyUri", currentIdentity?.spotifyUri ?: "")
                            put("is_playing", np?.isPlaying == true)
                        }
                        put("current_track", currentObj)

                        val historyArr = org.json.JSONArray()
                        if (crossAppLib.isNotEmpty()) {
                            for (lib in crossAppLib.take(60)) {
                                val link = lib.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                                    ?: lib.deezerUrl?.takeIf { it.isNotBlank() } ?: ""
                                historyArr.put(JSONObject().apply {
                                    put("title", lib.title)
                                    put("artist", lib.artist)
                                    put("source", lib.primarySourcePackage.ifBlank { "unknown_player" })
                                    put("playCount", lib.playCount.coerceAtLeast(0))
                                    put("trackId", lib.trackId)
                                    put("externalUri", link)
                                })
                            }
                        } else {
                            for (r in recents.take(60)) {
                                val tTitle = r.title?.takeIf { it.isNotBlank() } ?: continue
                                historyArr.put(JSONObject().apply {
                                    put("title", tTitle)
                                    put("artist", r.artist ?: "")
                                    put("source", r.sourcePackage?.takeIf { it.isNotBlank() } ?: "unknown_player")
                                    put("playCount", r.distinctAppearanceCount.coerceAtLeast(0))
                                    put("trackId", r.canonicalTrackId())
                                })
                            }
                        }
                        put("recent_tracks", historyArr)
                        put("recently_played_by_agent", org.json.JSONArray(getRecentlyPlayedByAgent()))

                        appendHearingTelemetryJson(hearing)

                        put("vault_tracks", buildVaultJsonArray(vaultList))

                        if (capsule != null && capsule.tracks.isNotEmpty()) {
                            val capArr = org.json.JSONArray()
                            for (ct in capsule.tracks) {
                                val capId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                                    spotifyUri = ct.spotifyUri,
                                    title = ct.title,
                                    artist = ct.artist
                                )
                                capArr.put(JSONObject().apply {
                                    put("title", ct.title)
                                    put("artist", ct.artist)
                                    put("trackId", capId.trackId)
                                    put("spotifyUri", capId.spotifyUri ?: "")
                                    put("freshness_percent", ct.freshnessPercent)
                                    put("reason", ct.reason)
                                })
                            }
                            put("dopamine_capsule", capArr)
                        }

                        if (pendingProp != null) {
                            val propId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                                trackId = pendingProp.trackId,
                                spotifyUri = pendingProp.spotifyUri,
                                title = pendingProp.title,
                                artist = pendingProp.artist
                            )
                            put("pending_proposed_track", JSONObject().apply {
                                put("title", pendingProp.title)
                                put("artist", pendingProp.artist)
                                put("trackId", propId.trackId)
                                put("spotifyUri", propId.spotifyUri ?: "")
                                put("source", pendingProp.source)
                            })
                        }
                        appendStudioContextJson()
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
                        if (hasCompletedReasoning || hasCompletedCloudTurn || _agentState.value == AgentState.SPEAKING) {
                            Log.w(TAG, "Ignoring late/duplicate THINKING_COMPLETE after turn already completed.")
                            return
                        }
                        hasCompletedReasoning = true
                        hasCompletedCloudTurn = true
                        cloudResponseTimeoutJob?.cancel()
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
                        markUserSpeechDetectedThisTurn()
                        if (!transcript.equals(latestWsPartialText, ignoreCase = false)) {
                            lastWsPartialProgressAtMs = System.currentTimeMillis()
                        }
                        latestWsPartialText = transcript
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
                        scheduleCloudResponseWatchdog(transcript)
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
                if (!hasCompletedReasoning && ws == this@AgentVoiceManager.webSocket && _agentState.value == AgentState.LISTENING) {
                    fallbackToNativeSpeechRecognizer("WebSocket closed before reasoning")
                }
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
        val capturedLocalVosk = latestLocalVoskCommandText.trim()
        stopMicrophoneStream()
        if (!isAppInForeground && capturedLocalVosk.isNotBlank()) {
            Log.i(TAG, "Background STT WebSocket fallback using on-device Vosk transcript: $capturedLocalVosk")
            processFinalTranscriptWithFallback(capturedLocalVosk)
            return
        }
        Log.d(TAG, "Activating on-device SpeechRecognizer fallback (reason: $reason)")
        _agentState.value = AgentState.LISTENING
        startNativeSpeechRecognizer()
    }

    private fun isSpotifyCollectionUri(uri: String?): Boolean {
        val t = uri?.trim().orEmpty()
        return t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) ||
            t.startsWith("ytmusic://", ignoreCase = true) ||
            t.startsWith("boomplay://", ignoreCase = true)
    }

    private fun normalizePlayableSpotifyUri(raw: String?): String? {
        val t = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) ||
            t.startsWith("ytmusic://", ignoreCase = true) ||
            t.startsWith("boomplay://", ignoreCase = true)
        ) return t
        return com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(t)
    }

    private fun getFilteredInstalledPlayers(): List<com.tunemind.app.provider.MusicProvider> {
        val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
        return com.tunemind.app.ui.components.MusicPlayerUiHelper.filterMusicPlayers(
            context,
            registry.getInstalledProviders()
        )
    }

    private fun detectSpokenPlayerSelection(transcriptLower: String): String? {
        if (transcriptLower.isBlank()) return null
        val players = getFilteredInstalledPlayers()
        for (p in players) {
            val shortName = com.tunemind.app.ui.components.MusicPlayerUiHelper.shortDisplayName(p).lowercase()
            if (shortName.isNotBlank() && Regex("\\b${Regex.escape(shortName)}\\b").containsMatchIn(transcriptLower)) {
                return p.packageName.ifBlank { p.providerId }
            }
        }
        return when {
            transcriptLower.contains("boomplay") -> "com.afmobi.boomplaymusic"
            transcriptLower.contains("youtube music") ||
                transcriptLower.contains("yt music") ||
                Regex("\\b(use|with|via|on|allow|try)\\s+youtube\\b").containsMatchIn(transcriptLower) -> "com.google.android.apps.youtube.music"
            transcriptLower.contains("audiomack") -> "com.audiomack"
            transcriptLower.contains("deezer") -> "deezer.android.app"
            transcriptLower.contains("soundcloud") -> "com.soundcloud.android"
            transcriptLower.contains("built-in") || transcriptLower.contains("in-app player") || transcriptLower.contains("in app player") -> "tunemind_inapp"
            transcriptLower.contains("local audio") || transcriptLower.contains("local files") || transcriptLower.contains("phone storage") -> "local_files"
            Regex("\\bon spotify\\b|\\bwith spotify\\b|\\buse spotify\\b|\\bto spotify\\b|\\bspotify\\b").containsMatchIn(transcriptLower) &&
                !Regex("\\bspotify\\s+(history|playlist|library|liked|saved)\\b").containsMatchIn(transcriptLower) -> "com.spotify.music"
            else -> null
        }
    }

    private fun resolveTargetPlayerPackage(rawPlayer: String?, fallbackPkg: String? = null): String {
        val players = getFilteredInstalledPlayers()
        val candidate = rawPlayer?.trim()?.takeIf { it.isNotBlank() }
        if (candidate != null) {
            val matched = players.firstOrNull { p ->
                p.packageName.equals(candidate, ignoreCase = true) ||
                    p.providerId.equals(candidate, ignoreCase = true) ||
                    p.displayName.equals(candidate, ignoreCase = true) ||
                    com.tunemind.app.ui.components.MusicPlayerUiHelper.shortDisplayName(p).equals(candidate, ignoreCase = true)
            }
            if (matched != null) {
                return matched.packageName.ifBlank { matched.providerId }
            }
            val lower = candidate.lowercase()
            when {
                lower.contains("boomplay") -> return "com.afmobi.boomplaymusic"
                lower.contains("youtube") -> return "com.google.android.apps.youtube.music"
                lower.contains("audiomack") -> return "com.audiomack"
                lower.contains("deezer") -> return "deezer.android.app"
                lower.contains("soundcloud") -> return "com.soundcloud.android"
                lower.contains("tunemind") || lower.contains("inapp") -> return "tunemind_inapp"
                lower.contains("local") -> return "local_files"
                lower.contains("spotify") -> return "com.spotify.music"
            }
            return candidate
        }
        if (!fallbackPkg.isNullOrBlank()) return fallbackPkg
        if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)) {
            return com.tunemind.app.provider.SpotifyMusicProvider.SPOTIFY_PACKAGE
        }
        val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
        if (registry.hasExplicitDefault()) {
            val pref = registry.getProvider(registry.getPreferredProviderId())
            return pref.packageName.ifBlank { pref.providerId }
        }
        return players.firstOrNull()?.let { it.packageName.ifBlank { it.providerId } } ?: "tunemind_inapp"
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

        val spokenPlayerPkg = detectSpokenPlayerSelection(userTranscriptLower)
        val sanitizedForRejectCheck = userTranscriptLower.replace(
            Regex("\\b(?:don'?t|do\\s+not|never)\\s+(?:open|leave|switch(?:\\s+to)?|launch)\\s+(?:spotify|boomplay|audiomack|youtube\\s*music|deezer|soundcloud|tunemind|the\\s+app|another\\s+app|other\\s+apps?|external(?:\\s+app)?)\\b"),
            " "
        )
        val isRejectingPending = Regex(
            "\\b(no|nope|nah|cancel|never\\s*mind|not\\s+(?:that|this|really|now)|don'?t|do\\s+not|won'?t|wouldn'?t|pass|skip|reject|decline|dislike|hate|stop|another|different|something\\s+else|someone\\s+else|change|else|next|forget\\s+it)\\b"
        ).containsMatchIn(sanitizedForRejectCheck)

        var rawTrackTitle = cmdObj?.optString("track_title", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("track_title", "")?.takeIf { it.isNotBlank() }
        var rawTrackArtist = cmdObj?.optString("track_artist", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("track_artist", "")?.takeIf { it.isNotBlank() }
        var rawTrackId = cmdObj?.optString("track_id", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("track_id", "")?.takeIf { it.isNotBlank() }
            ?: cmdObj?.optString("trackId", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("trackId", "")?.takeIf { it.isNotBlank() }
        var rawSearchQuery = cmdObj?.optString("search_query", "") ?: ""
        var resolvedSpotifyUri = normalizePlayableSpotifyUri(
            cmdObj?.optString("spotify_uri", "")?.takeIf { it.isNotBlank() }
                ?: toolObj?.optString("spotify_uri", "")?.takeIf { it.isNotBlank() }
        )
        var rawYtmVideoId = cmdObj?.optString("ytm_video_id", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("ytm_video_id", "")?.takeIf { it.isNotBlank() }
        var rawPreviewUrl = cmdObj?.optString("preview_url", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("preview_url", "")?.takeIf { it.isNotBlank() }
        var rawAudiusStreamUrl = cmdObj?.optString("audius_stream_url", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("audius_stream_url", "")?.takeIf { it.isNotBlank() }
        var rawDeezerUrl = cmdObj?.optString("deezer_url", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("deezer_url", "")?.takeIf { it.isNotBlank() }

        val pendingNorm = pendingBeforeTurn?.let {
            com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(it.title)
        }.orEmpty()
        val backendTrackNorm = rawTrackTitle?.let {
            com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(it)
        }.orEmpty()
        val backendPickedDifferentTrack = backendTrackNorm.isNotBlank() &&
            pendingNorm.isNotBlank() &&
            backendTrackNorm != pendingNorm &&
            !backendTrackNorm.contains(pendingNorm) &&
            !pendingNorm.contains(backendTrackNorm)

        val explicitNamedSongAfterPlay = Regex(
            "^\\s*(?:please\\s+|hey\\s+|hi\\s+|tunemind\\s+)?(?:play|spin|put\\s+on)\\s+(?:for\\s+me\\s+|me\\s+)?(.+)$"
        ).find(userTranscriptLower)?.groupValues?.getOrNull(1)?.let { tail ->
            val stripped = tail
                .replace(Regex("\\b(?:on|with|in|using|via)\\s+(?:spotify|boomplay|audiomack|youtube\\s*music|yt\\s*music|deezer|soundcloud|tunemind|my\\s+player)\\b"), "")
                .replace(Regex("\\b(?:right\\s+now|now|please|for\\s+me)\\b"), "")
                .trim()
            val isPronoun = Regex("^(?:it|that|this|the\\s+song|the\\s+track|that\\s+one|this\\s+one)?$").matches(stripped)
            if (isPronoun) null else com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(stripped)
        }
        val userAskedForDifferentNamedSong = !explicitNamedSongAfterPlay.isNullOrBlank() &&
            pendingNorm.isNotBlank() &&
            explicitNamedSongAfterPlay != pendingNorm &&
            !explicitNamedSongAfterPlay.contains(pendingNorm) &&
            !pendingNorm.contains(explicitNamedSongAfterPlay)

        val isPurePendingConfirmation = Regex(
            "\\b(yes|yeah|yep|yup|ya|sure|ok|okay|alright|fine|play it|play that|play this|go ahead|do it|put it on|let'?s go|sounds good|good|perfect|ndio|sawa|haya|approve|confirm|spin it|yea|yah)\\b"
        ).containsMatchIn(userTranscriptLower) ||
            Regex("^\\s*(?:please\\s+)?(?:play|spin|start)(?:\\s+(?:on|with|via|in)\\s+\\w+(?:\\s+music)?)?\\s*[.!?]*\\s*$")
                .matches(userTranscriptLower)

        val isApprovingPending = pendingBeforeTurn != null &&
            !isSyntheticPrompt &&
            !isRejectingPending &&
            !userAskedForDifferentNamedSong && (
                (isPurePendingConfirmation && explicitNamedSongAfterPlay.isNullOrBlank()) ||
                (!backendPickedDifferentTrack && (
                    rawAction == "PLAY_TRACK_NOW" ||
                    (spokenPlayerPkg != null && explicitNamedSongAfterPlay.isNullOrBlank()) ||
                    (pendingBeforeTurn.title.length >= 4 && userTranscriptLower.contains(pendingBeforeTurn.title.lowercase().take(5).trim()))
                ))
            )

        if (isApprovingPending && pendingBeforeTurn != null) {
            rawTrackTitle = pendingBeforeTurn.title
            rawTrackArtist = pendingBeforeTurn.artist
            rawTrackId = pendingBeforeTurn.trackId ?: rawTrackId
            resolvedSpotifyUri = normalizePlayableSpotifyUri(
                pendingBeforeTurn.spotifyUri ?: pendingBeforeTurn.trackId
            ) ?: resolvedSpotifyUri
            rawSearchQuery = "${pendingBeforeTurn.title} ${pendingBeforeTurn.artist}".trim()
        }

        val backendGaveValidTrack = !rawTrackTitle.isNullOrBlank() && !isConversationalNonTitlePhrase(rawTrackTitle)
        val backendHasSpokenAdvice = explicitTtsPayload.isNotBlank() ||
            !dataObj?.optString("voice_agent_tts", "")?.trim().isNullOrBlank()

        // Honor Claude Sonnet's autonomous judgment whenever backend returned spoken reasoning.
        // Only run local track matching fallback if backend gave no advice or offline fallback ran.
        val isPlaylistOrMetaAction = rawAction == "CREATE_TUNEMIND_PLAYLIST" ||
            rawAction == "ADD_TO_TUNEMIND_PLAYLIST" ||
            rawAction == "REMOVE_FROM_TUNEMIND_PLAYLIST" ||
            rawAction == "RENAME_TUNEMIND_PLAYLIST" ||
            rawAction == "DELETE_TUNEMIND_PLAYLIST" ||
            rawAction == "PLAY_TUNEMIND_PLAYLIST" ||
            rawAction == "PLAY_SPOTIFY_CONTEXT" ||
            rawAction == "SET_SHUFFLE" ||
            rawAction == "SET_REPEAT_MODE" ||
            rawAction == "SEEK_TO_POSITION" ||
            rawAction == "TRANSFER_SPOTIFY_DEVICE" ||
            rawAction == "QUEUE_TRACKS" ||
            rawAction == "PLAY_NEXT_IN_QUEUE" ||
            rawAction == "CLEAR_UP_NEXT_QUEUE" ||
            rawAction == "LIKE_TRACK" ||
            rawAction == "UNLIKE_TRACK" ||
            rawAction == "REFRESH_FOR_YOU_PICKS" ||
            rawAction == "OPEN_MUSIC_PLAYER_APP" ||
            rawAction == "SET_SLEEP_TIMER" ||
            rawAction == "TOGGLE_KIDS_MODE"

        val wantsMostPlayed = !backendHasSpokenAdvice && !isPlaylistOrMetaAction && !isApprovingPending && !isSyntheticPrompt && isMostPlayedSongRequest(userTranscriptLower)
        val wantsNewOrRecommend = !backendHasSpokenAdvice && !isPlaylistOrMetaAction && !isApprovingPending && !isSyntheticPrompt && !wantsMostPlayed && (
            isNewOrRecommendSongRequest(userTranscriptLower) ||
            (isExplicitMusicPlayOrRecommendRequest(userTranscriptLower) && !rawTrackTitle.isNullOrBlank() && isConversationalNonTitlePhrase(rawTrackTitle))
        )

        // When the AI chooses ADVISE_ONLY on a conversational turn, never attach a track or trigger a song proposal
        if (rawAction == "ADVISE_ONLY" && !isApprovingPending && !isSyntheticPrompt && !wantsMostPlayed && !wantsNewOrRecommend) {
            rawTrackTitle = null
            rawTrackArtist = null
            rawTrackId = null
            resolvedSpotifyUri = null
            rawSearchQuery = ""
        }

        var customProposalSpeechOverride: String? = null
        if (wantsMostPlayed && !backendGaveValidTrack) {
            val recents = getRecentTracks?.invoke() ?: emptyList()
            val (libTop, memTop) = findMostPlayedCrossAppTrack(recents)
            val topTitle = libTop?.title ?: memTop?.title
            val topArtist = libTop?.artist ?: memTop?.artist ?: ""
            val topPlays = libTop?.playCount ?: memTop?.distinctAppearanceCount ?: 1
            if (!topTitle.isNullOrBlank()) {
                rawTrackTitle = topTitle
                rawTrackArtist = topArtist
                rawYtmVideoId = libTop?.ytmVideoId?.takeIf { it.isNotBlank() } ?: rawYtmVideoId
                rawDeezerUrl = libTop?.deezerUrl?.takeIf { it.isNotBlank() } ?: rawDeezerUrl
                rawSearchQuery = "$topTitle $topArtist".trim()
                val artistPart = if (topArtist.isNotBlank() && topArtist != "Unknown Artist") " by $topArtist" else ""
                val countNote = if (topPlays > 1) " with $topPlays plays in your listening history" else " from the top of your rotation"
                customProposalSpeechOverride = "Your most-listened song is $topTitle$artistPart$countNote. Would you like me to play it right now?"
            }
        } else if (wantsNewOrRecommend && !backendGaveValidTrack) {
            val excluded = (getRecentlyPlayedByAgent() + listOfNotNull(getNowPlaying?.invoke()?.title))
                .map { it.lowercase().trim() }
                .toSet()
            val discovery = fetchOpenCatalogDiscoveryFallback(excluded)
            if (discovery != null) {
                rawTrackTitle = discovery.trackTitle
                rawTrackArtist = discovery.artistName
                resolvedSpotifyUri = discovery.deepLinkUrl
                rawSearchQuery = "${discovery.trackTitle} ${discovery.artistName}".trim()
                val artistPart = if (discovery.artistName.isNotBlank() && discovery.artistName != "Unknown Artist") " by ${discovery.artistName}" else ""
                customProposalSpeechOverride = "Based on your listening taste, I recommend ${discovery.trackTitle}$artistPart. Would you like me to play it right now?"
            }
        }

        if (!rawTrackTitle.isNullOrBlank()) {
            recordAgentSuggestedTrack(rawTrackTitle)
        }

        if (!rawTrackTitle.isNullOrBlank() && (rawYtmVideoId.isNullOrBlank() || rawDeezerUrl.isNullOrBlank())) {
            val normTargetTitle = com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(rawTrackTitle!!)
            val normExpectedArtist = com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(rawTrackArtist ?: "")
            val libHit = getCrossAppLibrarySnapshot().firstOrNull { lib ->
                val normLibTitle = com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(lib.title)
                val normLibArtist = com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(lib.artist)
                val titleMatchesExactly = normTargetTitle.isNotBlank() && normLibTitle == normTargetTitle
                val artistMatches = normExpectedArtist.isBlank() ||
                    normExpectedArtist == "unknown artist" ||
                    normLibArtist == normExpectedArtist ||
                    normLibArtist.contains(normExpectedArtist) ||
                    normExpectedArtist.contains(normLibArtist)
                titleMatchesExactly && artistMatches
            }
            if (libHit != null) {
                if (rawYtmVideoId.isNullOrBlank() && !libHit.ytmVideoId.isNullOrBlank()) {
                    rawYtmVideoId = libHit.ytmVideoId
                }
                if (rawDeezerUrl.isNullOrBlank() && !libHit.deezerUrl.isNullOrBlank()) {
                    rawDeezerUrl = libHit.deezerUrl
                }
                if (rawPreviewUrl.isNullOrBlank() && !libHit.previewUrl.isNullOrBlank()) {
                    rawPreviewUrl = libHit.previewUrl
                }
                if (rawAudiusStreamUrl.isNullOrBlank() && !libHit.audiusStreamUrl.isNullOrBlank()) {
                    rawAudiusStreamUrl = libHit.audiusStreamUrl
                }
            }
        }

        val targetPlayerPkg = resolveTargetPlayerPackage(
            rawPlayer = spokenPlayerPkg
                ?: toolObj?.optString("target_player", "")?.takeIf { it.isNotBlank() }
                ?: cmdObj?.optString("target_player", "")?.takeIf { it.isNotBlank() },
            fallbackPkg = if (isApprovingPending) pendingBeforeTurn?.source else null
        )
        val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
        val hasExplicitDefaultPlayer = registry.hasExplicitDefault()
        val currentDefaultPkg = if (hasExplicitDefaultPlayer) {
            val pref = registry.getProvider(registry.getPreferredProviderId())
            pref.packageName.ifBlank { pref.providerId }
        } else null
        val isTemporaryInAppYouTubeOverride = !currentDefaultPkg.isNullOrBlank() &&
            !currentDefaultPkg.equals("com.google.android.apps.youtube.music", ignoreCase = true) &&
            targetPlayerPkg.equals("com.google.android.apps.youtube.music", ignoreCase = true) &&
            !Regex("\\b(default|remember|always use|save as default)\\b").containsMatchIn(userTranscriptLower)
        val rememberDefaultPlayer: Boolean? = when {
            isTemporaryInAppYouTubeOverride -> false
            toolObj != null && toolObj.has("remember_default_player") -> toolObj.optBoolean("remember_default_player")
            Regex("\\b(default|remember|always use|save as default)\\b").containsMatchIn(userTranscriptLower) &&
                (spokenPlayerPkg != null || rawAction == "SELECT_MUSIC_PLAYER") -> true
            !hasExplicitDefaultPlayer && (spokenPlayerPkg != null || rawAction == "SELECT_MUSIC_PLAYER") -> true
            else -> null
        }

        // Determine whether this turn is proposing a song (must wait for user approval) vs executing an approved/direct song
        val isTrackProposalOrPlayAttempt = !rawTrackTitle.isNullOrBlank() && (
            wantsMostPlayed ||
            wantsNewOrRecommend ||
            rawAction == "PROPOSE_TRACK" ||
            rawAction == "LAUNCH_DOPAMINE_CAPSULE" ||
            rawAction == "PLAY_TRACK_NOW" ||
            rawIntent == "OS_MEDIA_NEXT_AND_DEEP_LINK"
        )
        val spotifyPremiumConnected = com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)
        val isDirectPlayCommand = (hasExplicitDefaultPlayer || spokenPlayerPkg != null || spotifyPremiumConnected) &&
            !isSyntheticPrompt &&
            !Regex("^\\s*(?:what|which|who|how|why|where|when|can\\s+you\\s+suggest|can\\s+you\\s+recommend|do\\s+you\\s+have|tell\\s+me|show\\s+me)\\b").containsMatchIn(userTranscriptLower) &&
            !Regex("\\b(?:recommend|recommendation|suggest|suggestion|what\\s+is\\s+my|what'?s\\s+my|what\\s+do\\s+you\\s+have|what\\s+should\\s+i)\\b").containsMatchIn(userTranscriptLower) &&
            !Regex("\\b(?:i\\s*(?:am|'m)\\s+feeling|i\\s+feel|feeling\\s+(?:stressed|sad|down|tired|drained|anxious|happy|hyped|lonely|chill|romantic|heartbroken|bored|overwhelmed|focused))\\b").containsMatchIn(userTranscriptLower) &&
            Regex("\\b(?:play|spin|put\\s+on|start\\s+playing)\\b").containsMatchIn(userTranscriptLower) &&
            !(
                Regex("\\b(?:play|spin|put\\s+on)\\s+(?:for\\s+me\\s+|me\\s+)?(?:(?:a|an|any|some|another|different|random|fresh|new|good|nice|cool|great|chill)\\s+)*(?:song|songs|track|tracks|music|tune|vibe|something|anything)\\b").containsMatchIn(userTranscriptLower) &&
                    !Regex("\\b(?:by|from)\\s+[a-z0-9]").containsMatchIn(userTranscriptLower) &&
                    !Regex("\\bmost\\s+(?:played|listened)\\b").containsMatchIn(userTranscriptLower)
            )

        val effectiveAction: String
        val effectiveIntent: String
        val effectiveExpectFollowUp: Boolean
        var spokenTts = customProposalSpeechOverride
            ?: explicitTtsPayload.ifBlank { dataObj?.optString("voice_agent_tts", "") ?: "" }.trim()

        val explicitExternalNow = isExplicitOpenExternalRequest(userTranscriptLower) ||
            (toolObj != null && toolObj.has("never_open_external_app") && !toolObj.optBoolean("never_open_external_app", false))
        val resolvedNeverOpenExternalApp = if (explicitExternalNow) {
            false
        } else {
            isNeverLeaveAppPlayRequest(lastUserTranscript) ||
                (pendingBeforeTurn?.neverOpenExternalApp == true) ||
                (toolObj?.optBoolean("never_open_external_app", false) == true) ||
                (cmdObj?.optBoolean("never_open_external_app", false) == true)
        }

        val resolvedIdentity = if (!rawTrackTitle.isNullOrBlank()) {
            com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                trackId = rawTrackId,
                spotifyUri = if (isSpotifyCollectionUri(resolvedSpotifyUri)) null else resolvedSpotifyUri,
                title = rawTrackTitle,
                artist = rawTrackArtist
            )
        } else null
        val finalCanonicalTrackId = resolvedIdentity?.trackId
        resolvedSpotifyUri = if (isSpotifyCollectionUri(resolvedSpotifyUri)) {
            resolvedSpotifyUri
        } else {
            resolvedIdentity?.spotifyUri ?: resolvedSpotifyUri
        }

        val userDeclinedPending = pendingBeforeTurn != null && !isSyntheticPrompt && isRejectingPending
        val isRejectedSameTrack = userDeclinedPending && (
            rawTrackTitle.isNullOrBlank() ||
            com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(rawTrackTitle!!) ==
                com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(pendingBeforeTurn!!.title)
        )

        if (userDeclinedPending && isRejectedSameTrack) {
            // User declined the proposed track: strictly clear pending state and NEVER launch playback!
            updatePendingProposedTrack(null)
            rawTrackTitle = null
            rawTrackArtist = null
            resolvedSpotifyUri = null
            effectiveAction = "ADVISE_ONLY"
            effectiveIntent = "NONE"
            effectiveExpectFollowUp = true
            if (spokenTts.isBlank() || Regex("^(awesome,?\\s*)?(playing|spinning|launching)\\b", RegexOption.IGNORE_CASE).containsMatchIn(spokenTts)) {
                spokenTts = "Got it, I cancelled \"${pendingBeforeTurn?.title ?: "that track"}\". What song, artist, or vibe would you like instead?"
            }
        } else if (isApprovingPending && pendingBeforeTurn != null) {
            effectiveAction = "PLAY_TRACK_NOW"
            effectiveIntent = "OS_MEDIA_NEXT_AND_DEEP_LINK"
            effectiveExpectFollowUp = false
            updatePendingProposedTrack(null)
            val artistPart = if (!rawTrackArtist.isNullOrBlank() && rawTrackArtist != "Unknown Artist") " by $rawTrackArtist" else ""
            spokenTts = spokenTts.takeIf { it.isNotBlank() && !it.contains("?") }
                ?: "Awesome, playing ${rawTrackTitle}$artistPart right now."
        } else if ((wasAwaitingEndOfSongChoice || (rawAction == "PLAY_TRACK_NOW" && isDirectPlayCommand)) && isTrackProposalOrPlayAttempt && !rawTrackTitle.isNullOrBlank()) {
            // Execute direct play commands (when target/default player is known) or End-of-Song choice immediately!
            effectiveAction = "PLAY_TRACK_NOW"
            effectiveIntent = "OS_MEDIA_NEXT_AND_DEEP_LINK"
            effectiveExpectFollowUp = false
            updatePendingProposedTrack(null)
            val artistPart = if (!rawTrackArtist.isNullOrBlank() && rawTrackArtist != "Unknown Artist") " by $rawTrackArtist" else ""
            spokenTts = spokenTts.takeIf { it.isNotBlank() && !it.contains("?") }
                ?: "Spinning ${rawTrackTitle}$artistPart for you right now."
        } else if (isTrackProposalOrPlayAttempt) {
            // Propose the song first and wait for the user's approval before executing!
            effectiveAction = if (rawAction == "LAUNCH_DOPAMINE_CAPSULE") "LAUNCH_DOPAMINE_CAPSULE" else "PROPOSE_TRACK"
            effectiveIntent = "NONE"
            effectiveExpectFollowUp = true
            updatePendingProposedTrack(
                PendingProposedTrack(
                    title = rawTrackTitle!!,
                    artist = rawTrackArtist ?: "",
                    trackId = finalCanonicalTrackId,
                    source = targetPlayerPkg,
                    neverOpenExternalApp = resolvedNeverOpenExternalApp,
                    spotifyUri = resolvedSpotifyUri
                )
            )
            // Keep Claude's natural spoken wording verbatim unless blank
            if (spokenTts.isBlank()) {
                val artistPart = if (!rawTrackArtist.isNullOrBlank() && rawTrackArtist != "Unknown Artist") " by $rawTrackArtist" else ""
                if (!hasExplicitDefaultPlayer && spokenPlayerPkg == null) {
                    val playerNames = getFilteredInstalledPlayers()
                        .take(3)
                        .map { com.tunemind.app.ui.components.MusicPlayerUiHelper.shortDisplayName(it) }
                        .distinct()
                    val choicesText = if (playerNames.isNotEmpty()) playerNames.joinToString(", ") else "Boomplay, YouTube Music, or TuneMind"
                    spokenTts = "I picked $rawTrackTitle$artistPart. Which player should I use — $choicesText?"
                } else {
                    spokenTts = "I picked $rawTrackTitle$artistPart. Would you like me to play it?"
                }
            } else {
                // Only guard against falsely claiming a proposed track is already actively playing
                spokenTts = spokenTts
                    .replace(Regex("^(spinning|playing|launching)\\s+", RegexOption.IGNORE_CASE), "I picked ")
                    .trim()
            }
        } else {
            effectiveAction = rawAction
            effectiveIntent = rawIntent
            effectiveExpectFollowUp = toolObj?.optBoolean("expect_follow_up", true) ?: true
            if (effectiveAction == "PAUSE_PLAYBACK" ||
                effectiveAction == "ADVISE_ONLY" ||
                effectiveAction == "CREATE_TUNEMIND_PLAYLIST" ||
                effectiveAction == "ADD_TO_TUNEMIND_PLAYLIST"
            ) {
                updatePendingProposedTrack(null)
            }
        }

        fun parseTrackInputArray(arr: org.json.JSONArray?): List<com.tunemind.app.domain.execution.PlaylistTrackInput> {
            if (arr == null) return emptyList()
            val out = mutableListOf<com.tunemind.app.domain.execution.PlaylistTrackInput>()
            for (idx in 0 until arr.length()) {
                val obj = arr.optJSONObject(idx) ?: continue
                val t = obj.optString("title", "").ifBlank { obj.optString("track_title", "") }.trim()
                if (t.isNotBlank()) {
                    out.add(
                        com.tunemind.app.domain.execution.PlaylistTrackInput(
                            title = t,
                            artist = obj.optString("artist", "").ifBlank { obj.optString("track_artist", "") }.trim(),
                            spotify_uri = normalizePlayableSpotifyUri(obj.optString("spotify_uri", "").takeIf { it.isNotBlank() }),
                            track_id = obj.optString("track_id", "").takeIf { it.isNotBlank() } ?: obj.optString("trackId", "").takeIf { it.isNotBlank() },
                            source_player = obj.optString("source_player", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            return out
        }

        val parsedPlaylistTracks = parseTrackInputArray(toolObj?.optJSONArray("playlist_tracks"))
        val parsedQueueTracks = parseTrackInputArray(toolObj?.optJSONArray("queue_tracks"))

        val extraActionsList = mutableListOf<com.tunemind.app.domain.execution.AgentToolCallPayload>()
        val extraArr = toolObj?.optJSONArray("extra_actions")
        if (extraArr != null) {
            for (i in 0 until extraArr.length()) {
                val item = extraArr.optJSONObject(i) ?: continue
                val act = item.optString("action", "").trim()
                if (act.isNotBlank()) {
                    extraActionsList.add(
                        com.tunemind.app.domain.execution.AgentToolCallPayload(
                            action = act,
                            track_title = item.optString("track_title", "").takeIf { it.isNotBlank() },
                            track_artist = item.optString("track_artist", "").takeIf { it.isNotBlank() },
                            track_id = item.optString("track_id", "").takeIf { it.isNotBlank() }
                                ?: item.optString("trackId", "").takeIf { it.isNotBlank() },
                            spotify_uri = normalizePlayableSpotifyUri(
                                item.optString("spotify_uri", "").takeIf { it.isNotBlank() }
                            ),
                            context_uri = item.optString("context_uri", "").takeIf { it.isNotBlank() },
                            shuffle_enabled = if (item.has("shuffle_enabled")) item.optBoolean("shuffle_enabled") else null,
                            repeat_mode = item.optString("repeat_mode", "").takeIf { it.isNotBlank() },
                            seek_position_ms = if (item.has("seek_position_ms")) item.optLong("seek_position_ms", 0L) else null,
                            seek_delta_ms = if (item.has("seek_delta_ms")) item.optLong("seek_delta_ms", 0L) else null,
                            device_id = item.optString("device_id", "").takeIf { it.isNotBlank() },
                            device_name = item.optString("device_name", "").takeIf { it.isNotBlank() },
                            target_player = item.optString("target_player", "").takeIf { it.isNotBlank() }
                                ?.let { resolveTargetPlayerPackage(it) },
                            remember_default_player = if (item.has("remember_default_player")) item.optBoolean("remember_default_player") else null,
                            volume_percent = if (item.has("volume_percent")) item.optInt("volume_percent", 60) else null,
                            palette_id = item.optString("palette_id", "").takeIf { it.isNotBlank() },
                            custom_accent_hex = item.optString("custom_accent_hex", "").takeIf { it.isNotBlank() },
                            theme_behavior = item.optString("theme_behavior", "").takeIf { it.isNotBlank() },
                            autonomous_dj_enabled = if (item.has("autonomous_dj_enabled")) item.optBoolean("autonomous_dj_enabled") else null,
                            stay_in_tunemind_enabled = if (item.has("stay_in_tunemind_enabled")) item.optBoolean("stay_in_tunemind_enabled") else null,
                            strict_no_app_switch_enabled = if (item.has("strict_no_app_switch_enabled")) item.optBoolean("strict_no_app_switch_enabled") else null,
                            never_open_external_app = if (item.has("never_open_external_app")) item.optBoolean("never_open_external_app") else null,
                            target_screen = item.optString("target_screen", "").takeIf { it.isNotBlank() },
                            playlist_name = item.optString("playlist_name", "").takeIf { it.isNotBlank() },
                            new_playlist_name = item.optString("new_playlist_name", "").takeIf { it.isNotBlank() },
                            playlist_description = item.optString("playlist_description", "").takeIf { it.isNotBlank() },
                            mood_or_audience = item.optString("mood_or_audience", "").takeIf { it.isNotBlank() },
                            playlist_tracks = parseTrackInputArray(item.optJSONArray("playlist_tracks")),
                            queue_tracks = parseTrackInputArray(item.optJSONArray("queue_tracks")),
                            open_playlist_screen = if (item.has("open_playlist_screen")) item.optBoolean("open_playlist_screen") else null,
                            sleep_timer_minutes = if (item.has("sleep_timer_minutes")) item.optInt("sleep_timer_minutes", 0) else null,
                            kids_mode_enabled = if (item.has("kids_mode_enabled")) item.optBoolean("kids_mode_enabled") else null
                        )
                    )
                }
            }
        }

        val resolvedContextUri = cmdObj?.optString("context_uri", "")?.takeIf { it.isNotBlank() }
            ?: toolObj?.optString("context_uri", "")?.takeIf { it.isNotBlank() }
        val resolvedShuffleEnabled = when {
            toolObj != null && toolObj.has("shuffle_enabled") -> toolObj.optBoolean("shuffle_enabled")
            cmdObj != null && cmdObj.has("shuffle_enabled") -> cmdObj.optBoolean("shuffle_enabled")
            else -> null
        }
        val resolvedRepeatMode = toolObj?.optString("repeat_mode", "")?.takeIf { it.isNotBlank() }
        val resolvedSeekPositionMs = when {
            toolObj != null && toolObj.has("seek_position_ms") -> toolObj.optLong("seek_position_ms", 0L)
            cmdObj != null && cmdObj.has("seek_position_ms") -> cmdObj.optLong("seek_position_ms", 0L)
            else -> null
        }
        val resolvedSeekDeltaMs = when {
            toolObj != null && toolObj.has("seek_delta_ms") -> toolObj.optLong("seek_delta_ms", 0L)
            cmdObj != null && cmdObj.has("seek_delta_ms") -> cmdObj.optLong("seek_delta_ms", 0L)
            else -> null
        }
        val resolvedDeviceId = toolObj?.optString("device_id", "")?.takeIf { it.isNotBlank() }
        val resolvedDeviceName = toolObj?.optString("device_name", "")?.takeIf { it.isNotBlank() }

        val toolPayload = com.tunemind.app.domain.execution.AgentToolCallPayload(
            action = effectiveAction,
            track_title = rawTrackTitle,
            track_artist = rawTrackArtist,
            track_id = finalCanonicalTrackId,
            spotify_uri = resolvedSpotifyUri,
            context_uri = resolvedContextUri,
            shuffle_enabled = resolvedShuffleEnabled,
            repeat_mode = resolvedRepeatMode,
            seek_position_ms = resolvedSeekPositionMs,
            seek_delta_ms = resolvedSeekDeltaMs,
            device_id = resolvedDeviceId,
            device_name = resolvedDeviceName,
            ytm_video_id = rawYtmVideoId,
            preview_url = rawPreviewUrl,
            audius_stream_url = rawAudiusStreamUrl,
            deezer_url = rawDeezerUrl,
            target_player = targetPlayerPkg,
            remember_default_player = rememberDefaultPlayer,
            volume_percent = if (toolObj != null && toolObj.has("volume_percent")) toolObj.optInt("volume_percent", 60) else null,
            palette_id = toolObj?.optString("palette_id", "")?.takeIf { it.isNotBlank() },
            custom_accent_hex = toolObj?.optString("custom_accent_hex", "")?.takeIf { it.isNotBlank() },
            theme_behavior = toolObj?.optString("theme_behavior", "")?.takeIf { it.isNotBlank() },
            autonomous_dj_enabled = if (toolObj != null && toolObj.has("autonomous_dj_enabled")) toolObj.optBoolean("autonomous_dj_enabled") else null,
            stay_in_tunemind_enabled = if (toolObj != null && toolObj.has("stay_in_tunemind_enabled")) toolObj.optBoolean("stay_in_tunemind_enabled") else null,
            strict_no_app_switch_enabled = if (toolObj != null && toolObj.has("strict_no_app_switch_enabled")) toolObj.optBoolean("strict_no_app_switch_enabled") else null,
            never_open_external_app = resolvedNeverOpenExternalApp,
            target_screen = toolObj?.optString("target_screen", "")?.takeIf { it.isNotBlank() },
            expect_follow_up = effectiveExpectFollowUp,
            playlist_name = toolObj?.optString("playlist_name", "")?.takeIf { it.isNotBlank() },
            new_playlist_name = toolObj?.optString("new_playlist_name", "")?.takeIf { it.isNotBlank() },
            playlist_description = toolObj?.optString("playlist_description", "")?.takeIf { it.isNotBlank() },
            mood_or_audience = toolObj?.optString("mood_or_audience", "")?.takeIf { it.isNotBlank() },
            playlist_tracks = parsedPlaylistTracks,
            queue_tracks = parsedQueueTracks,
            open_playlist_screen = if (toolObj != null && toolObj.has("open_playlist_screen")) toolObj.optBoolean("open_playlist_screen") else null,
            sleep_timer_minutes = if (toolObj != null && toolObj.has("sleep_timer_minutes")) toolObj.optInt("sleep_timer_minutes", 0) else null,
            kids_mode_enabled = if (toolObj != null && toolObj.has("kids_mode_enabled")) toolObj.optBoolean("kids_mode_enabled") else null,
            extra_actions = extraActionsList
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
                track_id = finalCanonicalTrackId,
                spotify_uri = resolvedSpotifyUri,
                context_uri = resolvedContextUri,
                shuffle_enabled = resolvedShuffleEnabled,
                seek_position_ms = resolvedSeekPositionMs,
                seek_delta_ms = resolvedSeekDeltaMs,
                ytm_video_id = rawYtmVideoId,
                preview_url = rawPreviewUrl,
                audius_stream_url = rawAudiusStreamUrl,
                deezer_url = rawDeezerUrl,
                never_open_external_app = resolvedNeverOpenExternalApp
            ),
            voice_agent_audio_base64 = run {
                val cloudTts = dataObj?.optString("voice_agent_tts", "")?.trim().orEmpty()
                val cloudAudio = dataObj?.optString("voice_agent_audio_base64", "")?.takeIf { it.isNotBlank() && it != "null" }
                if (spokenTts.isNotBlank() && cloudTts.isNotBlank() && spokenTts != cloudTts) {
                    // Text changed locally — let TuneMindExecutionEngine synthesize spokenTts via OpenAI /tts so voice stays 100% consistent!
                    null
                } else {
                    cloudAudio
                }
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
            satietyPercent = responseObj.calculated_satiety_percentage,
            trackId = finalCanonicalTrackId
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

        cloudResponseTimeoutJob?.cancel()
        hasCompletedCloudTurn = true
        errorResetJob?.cancel()
        markPostAudioCooldown(4_500L)
        executionEngine.executeBackendCommand(
            response = responseObj,
            onBeforeLaunch = {
                mainHandler.post {
                    markPostAudioCooldown(4_500L)
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
                    markPostAudioCooldown(4_500L)
                    val allowFollowUp = isAppInForeground
                    if (allowFollowUp && toolPayload.expect_follow_up && responseObj.os_execution_command.intent == "NONE") {
                        Log.d(TAG, "Waiting for user follow-up/approval in dialogue; re-opening mic in 220ms...")
                        _agentState.value = AgentState.IDLE
                        mainHandler.postDelayed({
                            if (_agentState.value == AgentState.IDLE && isAppInForeground) {
                                hasReceivedSpeechThisTurn = false
                                hasSpokenMorningPromptThisTurn = true
                                wasTurnTriggeredByWakeWord = false
                                stopVoskWakeListener()
                                _agentState.value = AgentState.LISTENING
                                requestAudioDucking()
                                startMicrophoneStream()
                                postGreetingListenTimeoutJob?.cancel()
                                postGreetingListenTimeoutJob = scope.launch {
                                    delay(8_000L)
                                    if (_agentState.value == AgentState.LISTENING && !hasReceivedSpeechThisTurn) {
                                        Log.d(TAG, "No follow-up speech after prompt — returning cleanly to wake listening.")
                                        val shouldResume = wasMediaPlayingBeforeWake
                                        wasMediaPlayingBeforeWake = false
                                        wasTurnTriggeredByWakeWord = false
                                        stopMicrophoneStream()
                                        stopNativeSpeechRecognizer()
                                        releaseAudioDucking()
                                        if (shouldResume) {
                                            executionEngine.resumeCurrentMedia()
                                        }
                                        resumeWakeWordListening()
                                    }
                                }
                            }
                        }, 220L)
                    } else {
                        val keepsPausedOrNewTrack = effectiveAction == "PAUSE_PLAYBACK" ||
                            effectiveAction == "PLAY_TRACK_NOW" ||
                            effectiveAction == "PLAY_TUNEMIND_PLAYLIST" ||
                            effectiveAction == "SKIP_NEXT" ||
                            effectiveAction == "SKIP_PREVIOUS"
                        val shouldResumePreviousAudio = wasMediaPlayingBeforeWake &&
                            responseObj.os_execution_command.intent == "NONE" &&
                            !keepsPausedOrNewTrack
                        wasMediaPlayingBeforeWake = false
                        wasTurnTriggeredByWakeWord = false
                        releaseAudioDucking()
                        if (shouldResumePreviousAudio) {
                            executionEngine.resumeCurrentMedia()
                        }
                        resumeWakeWordListening()
                    }
                }
            },
            onLaunchResult = { launchRes ->
                mainHandler.post {
                    onPlaybackLaunchResult?.invoke(launchRes)
                    if (launchRes is com.tunemind.app.provider.PlaybackLaunchResult.LaunchFailed) {
                        _flowAnalysisFlow.value = launchRes.reason
                        scope.launch {
                            _transcriptionFlow.emit(launchRes.reason)
                        }
                    } else if (launchRes is com.tunemind.app.provider.PlaybackLaunchResult.DirectPlaybackLaunched &&
                        launchRes.providerId == "tunemind_inapp"
                    ) {
                        _flowAnalysisFlow.value = launchRes.message
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
        noSpeechAfterWakeJob?.cancel()
        endOfSongAutoPlayTimeoutJob?.cancel()
        val rawCleaned = transcript.trim()
        val cleaned = if (wasTurnTriggeredByWakeWord) {
            stripLeadingWakePhrase(rawCleaned).ifBlank {
                if (rawCleaned.contains("tune", ignoreCase = true)) "" else rawCleaned
            }
        } else {
            rawCleaned
        }
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
            if (wasTurnTriggeredByWakeWord && !hasSpokenMorningPromptThisTurn && rawCleaned.isNotBlank()) {
                hasSpokenMorningPromptThisTurn = true
                setSpeakingState()
                val greeting = buildCircadianWakeGreeting()
                scope.launch { _transcriptionFlow.emit(greeting) }
                mainHandler.post {
                    executionEngine.speakPromptOnly(greeting) {
                        mainHandler.post {
                            hasReceivedSpeechThisTurn = false
                            _agentState.value = AgentState.LISTENING
                            requestAudioDucking()
                            startMicrophoneStream()
                        }
                    }
                }
                return
            }
            val shouldResumeAfterEmptyWake = wasMediaPlayingBeforeWake
            wasMediaPlayingBeforeWake = false
            wasTurnTriggeredByWakeWord = false
            releaseAudioDucking()
            if (shouldResumeAfterEmptyWake) {
                executionEngine.resumeCurrentMedia()
            }
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
        hasCompletedCloudTurn = false

        setThinkingState()
        scope.launch {
            try {
                val np = getNowPlaying?.invoke()
                val recents = getRecentTracks?.invoke() ?: emptyList()
                val crossAppLib = getCrossAppLibrarySnapshot()
                val hearing = getHearingInsights?.invoke()
                val vaultList = getVaultTracks?.invoke() ?: emptyList()
                val capsule = getDopamineCapsule?.invoke()
                val pendingProp = clientPendingProposedTrack
                val authMgr = com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(context)
                val authSession = authMgr.sessionFlow.value

                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val daypart = when (hour) {
                    in 6..11 -> "Morning"
                    in 12..16 -> "Midday"
                    in 17..21 -> "Evening"
                    else -> "Late Night"
                }

                val bodyJson = JSONObject().apply {
                    if (authSession.userId.isNotBlank()) put("userId", authSession.userId)
                    authSession.email?.takeIf { it.isNotBlank() }?.let { put("userEmail", it) }
                    put("timestamp_utc", java.time.Instant.now().toString())
                    put("circadian_daypart", daypart)
                    put("user_command", cleaned)
                    put("current_satiety_percentage", _agentSatietyFlow.value)
                    put("current_track", JSONObject().apply {
                        put("title", np?.title ?: "Standing By")
                        put("artist", np?.artist ?: "")
                        put("source_player", np?.sourcePackage ?: "unknown_player")
                        put("is_playing", np?.isPlaying == true)
                    })

                    val historyArr = org.json.JSONArray()
                    if (crossAppLib.isNotEmpty()) {
                        for (lib in crossAppLib.take(60)) {
                            val link = lib.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                                ?: lib.deezerUrl?.takeIf { it.isNotBlank() } ?: ""
                            historyArr.put(JSONObject().apply {
                                put("title", lib.title)
                                put("artist", lib.artist)
                                put("source_player", lib.primarySourcePackage.ifBlank { "unknown_player" })
                                put("playCount", lib.playCount.coerceAtLeast(0))
                                put("trackId", lib.trackId)
                                put("externalUri", link)
                            })
                        }
                    } else {
                        for (r in recents.take(60)) {
                            val tTitle = r.title?.takeIf { it.isNotBlank() } ?: continue
                            historyArr.put(JSONObject().apply {
                                put("title", tTitle)
                                put("artist", r.artist ?: "")
                                put("source_player", r.sourcePackage?.takeIf { it.isNotBlank() } ?: "unknown_player")
                                put("playCount", r.distinctAppearanceCount.coerceAtLeast(0))
                                put("trackId", r.canonicalTrackId())
                            })
                        }
                    }
                    put("rolling_history", historyArr)
                    put("recently_played_by_agent", org.json.JSONArray(getRecentlyPlayedByAgent()))

                    appendHearingTelemetryJson(hearing)

                    put("vault_tracks", buildVaultJsonArray(vaultList))

                    if (capsule != null && capsule.tracks.isNotEmpty()) {
                        val capArr = org.json.JSONArray()
                        for (ct in capsule.tracks) {
                            val capId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                                spotifyUri = ct.spotifyUri,
                                title = ct.title,
                                artist = ct.artist
                            )
                            capArr.put(JSONObject().apply {
                                put("title", ct.title)
                                put("artist", ct.artist)
                                put("trackId", capId.trackId)
                                put("spotifyUri", capId.spotifyUri ?: "")
                                put("freshness_percent", ct.freshnessPercent)
                                put("reason", ct.reason)
                            })
                        }
                        put("dopamine_capsule", capArr)
                    }

                    if (pendingProp != null) {
                        val propId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                            trackId = pendingProp.trackId,
                            spotifyUri = pendingProp.spotifyUri,
                            title = pendingProp.title,
                            artist = pendingProp.artist
                        )
                        put("pending_proposed_track", JSONObject().apply {
                            put("title", pendingProp.title)
                            put("artist", pendingProp.artist)
                            put("trackId", propId.trackId)
                            put("spotifyUri", propId.spotifyUri ?: "")
                            put("source", pendingProp.source)
                        })
                    }
                    appendStudioContextJson()
                }

                val baseUrl = com.tunemind.app.domain.network.BackendEndpointManager.getActiveHttpBaseUrl(context).trimEnd('/')
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val reqBuilder = Request.Builder()
                    .url("$baseUrl/api/v1/reasoning/evaluate-satiety")
                    .post(bodyJson.toString().toRequestBody(mediaType))
                val accessToken = runCatching { authMgr.getAccessToken() }.getOrDefault("")
                if (accessToken.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $accessToken")
                }

                val resp = reasoningHttpClient.newCall(reqBuilder.build()).execute()
                val respStr = resp.body?.string()
                val respCode = resp.code
                resp.close()

                if (resp.isSuccessful && !respStr.isNullOrBlank()) {
                    val respJson = JSONObject(respStr)
                    dispatchReasoningResult(respJson, respJson.optString("voice_agent_tts", ""))
                    return@launch
                }

                if ((respCode == 402 || respCode == 429) && !respStr.isNullOrBlank()) {
                    val errJson = runCatching { JSONObject(respStr) }.getOrNull()
                    errJson?.optJSONObject("entitlement")?.let { entJson ->
                        com.tunemind.app.billing.TuneMindBillingManager.getInstance(context).applyEntitlementJson(entJson)
                    }
                    val errCode = errJson?.optString("code", "") ?: ""
                    val errMsg = errJson?.optString(
                        "message",
                        "Start your 7-day free trial to chat with Pulse AI Agent."
                    ) ?: "Start your 7-day free trial to chat with Pulse AI Agent."
                    val targetPlayer = np?.sourcePackage?.takeIf { it.isNotBlank() } ?: "tunemind_inapp"
                    val restrictedJson = JSONObject().apply {
                        put("calculated_satiety_percentage", _agentSatietyFlow.value)
                        put("reasoning_mode", "JOURNEY_ADVISOR")
                        put("flow_state_analysis", errMsg)
                        put("voice_agent_tts", errMsg)
                        put("agent_tool_call", JSONObject().apply {
                            put("action", "ADVISE_ONLY")
                            put("expect_follow_up", false)
                        })
                        put("os_execution_command", JSONObject().apply {
                            put("intent", "NONE")
                            put("target_player", targetPlayer)
                            put("search_query", "")
                        })
                    }
                    dispatchReasoningResult(restrictedJson, errMsg)
                    if (respCode == 402 || errCode == "PRO_TRIAL_REQUIRED") {
                        mainHandler.post { onProTrialRequired?.invoke(errMsg) }
                    }
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

    private fun isFreeTierLocalCommand(transcript: String): Boolean {
        val lower = transcript.lowercase().trim()
        if (lower.isBlank()) return true
        val pending = clientPendingProposedTrack
        if (pending != null && Regex(
                "\\b(yes|yeah|yep|sure|okay|ok|play|play it|go ahead|do it|sounds good|ndio|sawa|spin it|no|nope|nah|cancel|never\\s*mind|skip|next|stop|another)\\b"
            ).containsMatchIn(lower)
        ) {
            return true
        }
        if (Regex("\\b(pause|stop|quiet|mute|silence|resume|unpause|continue playing|keep playing|skip|next song|next track|previous|go back|last song|volume|louder|quieter|turn it down|turn it up)\\b").containsMatchIn(lower)) {
            return true
        }
        if (Regex("\\b(what is playing|what's playing|current song|open vault|show vault|go to vault|open rhythm|show dna|rhythm dna|open playlists?|show playlists?)\\b").containsMatchIn(lower)) {
            return true
        }
        val cleanNoPunct = lower.replace(Regex("[.!?,;:'\"]+"), " ").replace(Regex("\\s+"), " ").trim()
        val explicitPlay = Regex("^\\s*(please\\s+)?(play|spin|put on)\\s+(.+)$").find(cleanNoPunct)?.groupValues?.getOrNull(3)?.trim()
        if (!explicitPlay.isNullOrBlank() && explicitPlay.length >= 2 &&
            !isNewOrRecommendSongRequest(lower) && !isConversationalNonTitlePhrase(explicitPlay)
        ) {
            return true
        }
        return false
    }

    private fun buildLocalOnDeviceFallbackJson(transcript: String): JSONObject {
        val lower = transcript.lowercase().trim()
        val np = getNowPlaying?.invoke()
        val recents = getRecentTracks?.invoke() ?: emptyList()
        val hearing = getHearingInsights?.invoke()
        val capsule = getDopamineCapsule?.invoke()
        val pending = clientPendingProposedTrack
        val targetPlayer = np?.sourcePackage?.takeIf { it.isNotBlank() } ?: "tunemind_inapp"

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

        // 3. Approval of pending proposed track (only on clear affirmative and NEVER when negative words appear)
        val sanitizedForReject = lower.replace(
            Regex("\\b(?:don'?t|do\\s+not|never)\\s+(?:open|leave|switch(?:\\s+to)?|launch)\\s+(?:spotify|the\\s+app|another\\s+app|external(?:\\s+app)?)\\b"),
            " "
        )
        val isRejectingPending = Regex(
            "\\b(no|nope|nah|cancel|never\\s*mind|not\\s+(?:that|this|really|now)|don'?t|do\\s+not|won'?t|wouldn'?t|pass|skip|reject|decline|dislike|hate|stop|another|different|something\\s+else|someone\\s+else|change|switch|else|next|forget\\s+it)\\b"
        ).containsMatchIn(sanitizedForReject)

        if (pending != null && !isRejectingPending &&
            Regex("\\b(yes|yeah|yep|sure|okay|ok|play|play it|go ahead|do it|sounds good|ndio|sawa|spin it)\\b").containsMatchIn(lower)
        ) {
            val artistPart = if (pending.artist.isNotBlank() && pending.artist != "Unknown Artist") " by ${pending.artist}" else ""
            val pendingId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                trackId = pending.trackId,
                spotifyUri = pending.spotifyUri,
                title = pending.title,
                artist = pending.artist
            )
            return JSONObject().apply {
                put("calculated_satiety_percentage", 20)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Approved ${pending.title} — launching playback.")
                put("voice_agent_tts", "Spinning ${pending.title}$artistPart for you right now.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "PLAY_TRACK_NOW")
                    put("track_title", pending.title)
                    put("track_artist", pending.artist)
                    put("track_id", pendingId.trackId)
                    put("spotify_uri", pendingId.spotifyUri ?: "")
                    put("target_player", pending.source)
                    put("never_open_external_app", pending.neverOpenExternalApp)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "OS_MEDIA_NEXT_AND_DEEP_LINK")
                    put("target_player", pending.source)
                    put("search_query", "${pending.title} ${pending.artist}".trim())
                    put("track_title", pending.title)
                    put("track_artist", pending.artist)
                    put("track_id", pendingId.trackId)
                    put("spotify_uri", pendingId.spotifyUri ?: "")
                    put("never_open_external_app", pending.neverOpenExternalApp)
                })
            }
        }

        // 3B. Rejection / Cancellation of pending proposed track
        if (pending != null && isRejectingPending) {
            updatePendingProposedTrack(null)
            return JSONObject().apply {
                put("calculated_satiety_percentage", 25)
                put("reasoning_mode", "JOURNEY_ADVISOR")
                put("flow_state_analysis", "Cancelled ${pending.title} per your preference.")
                put("voice_agent_tts", "Got it, I won't play ${pending.title}. What song, artist, or vibe would you like instead?")
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

        // 6. Custom Theme Color / Atmosphere Shift / Spotify Library Sync & Export / Spotify Connect Co-Pilot
        if (Regex("\\b(sync|import|bring|pull|fetch|load|refresh)\\s+(my\\s+|all\\s+)?(spotify\\s+)?(liked\\s+songs?|playlists?|spotify\\s+library|spotify\\s+music|spotify\\s+tracks?|spotify)\\b").containsMatchIn(lower) &&
            lower.contains("spotify")
        ) {
            val spStatus = com.tunemind.app.provider.SpotifyPremiumConnectManager.ensureLoaded(context)
            return if (spStatus.isConnected) {
                JSONObject().apply {
                    put("calculated_satiety_percentage", 22)
                    put("reasoning_mode", "DIRECT_EXECUTION")
                    put("flow_state_analysis", "Syncing Spotify Liked Songs, Playlists & Top Artists.")
                    put("voice_agent_tts", "Syncing your Spotify Liked Songs and Playlists into TuneMind right now. They'll appear in your Library and Playlists in just a few seconds.")
                    put("agent_tool_call", JSONObject().apply {
                        put("action", "SYNC_SPOTIFY_LIBRARY")
                        put("open_playlist_screen", lower.contains("playlist"))
                        put("expect_follow_up", false)
                    })
                    put("os_execution_command", JSONObject().apply {
                        put("intent", "NONE")
                        put("target_player", targetPlayer)
                        put("search_query", "")
                    })
                }
            } else {
                JSONObject().apply {
                    put("calculated_satiety_percentage", 22)
                    put("reasoning_mode", "DIRECT_EXECUTION")
                    put("flow_state_analysis", "Spotify not connected yet — opening Spotify Connect Guide.")
                    put("voice_agent_tts", "Connect your Spotify account first and I'll import all your Liked Songs and Playlists right away. Opening the quick Spotify Connect guide now.")
                    put("agent_tool_call", JSONObject().apply {
                        put("action", "OPEN_SPOTIFY_CONNECT_GUIDE")
                        put("expect_follow_up", false)
                    })
                    put("os_execution_command", JSONObject().apply {
                        put("intent", "NONE")
                        put("target_player", targetPlayer)
                        put("search_query", "")
                    })
                }
            }
        }
        if (Regex("\\b(export|push|send|save|copy)\\b.*\\bplaylist\\b.*\\b(to|into)\\s+spotify\\b").containsMatchIn(lower)) {
            val spStatus = com.tunemind.app.provider.SpotifyPremiumConnectManager.ensureLoaded(context)
            val extractedName = Regex("(?:export|push|send|save|copy)\\s+(?:my\\s+)?(?:playlist\\s+)?([a-z0-9 '&-]+?)\\s+(?:playlist\\s+)?(?:to|into)\\s+spotify", RegexOption.IGNORE_CASE)
                .find(transcript)?.groupValues?.getOrNull(1)?.trim()
                ?.replace(Regex("^(my|the|this|a)\\s+", RegexOption.IGNORE_CASE), "")
                ?.trim().orEmpty()
            return if (spStatus.isConnected) {
                JSONObject().apply {
                    put("calculated_satiety_percentage", 22)
                    put("reasoning_mode", "DIRECT_EXECUTION")
                    put("flow_state_analysis", "Exporting TuneMind playlist to Spotify.")
                    put("voice_agent_tts", if (extractedName.isNotBlank()) "Exporting your $extractedName playlist straight to your Spotify account now." else "Exporting your TuneMind playlist straight to your Spotify account now.")
                    put("agent_tool_call", JSONObject().apply {
                        put("action", "EXPORT_PLAYLIST_TO_SPOTIFY")
                        put("playlist_name", extractedName)
                        put("expect_follow_up", false)
                    })
                    put("os_execution_command", JSONObject().apply {
                        put("intent", "NONE")
                        put("target_player", targetPlayer)
                        put("search_query", "")
                    })
                }
            } else {
                JSONObject().apply {
                    put("calculated_satiety_percentage", 22)
                    put("reasoning_mode", "DIRECT_EXECUTION")
                    put("flow_state_analysis", "Spotify not connected yet — opening Spotify Connect Guide.")
                    put("voice_agent_tts", "Connect your Spotify account first so I can export your playlist directly into Spotify. Opening the quick setup guide now.")
                    put("agent_tool_call", JSONObject().apply {
                        put("action", "OPEN_SPOTIFY_CONNECT_GUIDE")
                        put("expect_follow_up", false)
                    })
                    put("os_execution_command", JSONObject().apply {
                        put("intent", "NONE")
                        put("target_player", targetPlayer)
                        put("search_query", "")
                    })
                }
            }
        }
        if (Regex("\\b(connect|link|setup|set up|fix|reconnect|troubleshoot)\\s+(my\\s+)?spotify\\b|\\bhelp\\s+(me\\s+)?(connect|link|set up)\\s+spotify\\b").containsMatchIn(lower)) {
            val spStatus = com.tunemind.app.provider.SpotifyPremiumConnectManager.ensureLoaded(context)
            val spoken = when {
                spStatus.isConnected && spStatus.isPremium ->
                    "You're already linked to Spotify Premium as ${spStatus.displayName.ifBlank { "your account" }}. I'm opening the Spotify Connect guide in case you want to check your active device."
                spStatus.isFreeTier ->
                    "You're connected in Spotify Free Smart Mode. Your likes sync to Spotify, and full songs open directly in Spotify when needed so you never get 30-second previews. Opening Spotify settings now."
                else ->
                    "I'm opening the 60-second Spotify Connect guide right now. Copy the redirect URI, paste your Client ID from Spotify Dashboard, and tap Connect—TuneMind backs it up encrypted so you won't have to reconnect again."
            }
            return JSONObject().apply {
                put("calculated_satiety_percentage", 22)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Opening Spotify Connect Guide.")
                put("voice_agent_tts", spoken)
                put("agent_tool_call", JSONObject().apply {
                    put("action", "OPEN_SPOTIFY_CONNECT_GUIDE")
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }
        if (Regex("\\b(open settings|go to settings|show settings|settings page|settings screen)\\b").containsMatchIn(lower)) {
            return JSONObject().apply {
                put("calculated_satiety_percentage", 22)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", "Opening TuneMind Settings.")
                put("voice_agent_tts", "Opening your TuneMind settings right now.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "OPEN_SETTINGS")
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }
        if (Regex("\\b(autonomous dj|auto dj|end of song dj|auto play next)\\b").containsMatchIn(lower)) {
            val enableDj = !Regex("\\b(off|disable|stop|turn off|mute)\\b").containsMatchIn(lower)
            return JSONObject().apply {
                put("calculated_satiety_percentage", 22)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", if (enableDj) "Enabled Autonomous End-of-Song DJ." else "Disabled Autonomous End-of-Song DJ.")
                put("voice_agent_tts", if (enableDj) "Autonomous DJ mode is now on. I'll curate and transition your next tracks smoothly." else "Autonomous DJ mode is now off. I'll only play music when you ask.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "TOGGLE_AUTONOMOUS_DJ")
                    put("autonomous_dj_enabled", enableDj)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }
        if (Regex("\\b(strict mode|strict never leave|never leave tunemind|never open spotify mode)\\b").containsMatchIn(lower)) {
            val enableStrict = !Regex("\\b(off|disable|stop|turn off)\\b").containsMatchIn(lower)
            return JSONObject().apply {
                put("calculated_satiety_percentage", 22)
                put("reasoning_mode", "DIRECT_EXECUTION")
                put("flow_state_analysis", if (enableStrict) "Enabled Strict Never-Leave-TuneMind Mode." else "Disabled Strict Never-Leave-TuneMind Mode.")
                put("voice_agent_tts", if (enableStrict) "Strict Never-Leave-TuneMind mode is now on. I will never switch away from TuneMind to open any external player." else "Strict mode is off. Hybrid Mode playback is active.")
                put("agent_tool_call", JSONObject().apply {
                    put("action", "TOGGLE_STRICT_NO_APP_SWITCH")
                    put("strict_no_app_switch_enabled", enableStrict)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }
        if (Regex("\\b(theme|atmosphere|color|colour|palette|mood light)\\b").containsMatchIn(lower)) {
            val explicitHexMatch = Regex("#[0-9a-fA-F]{6}").find(transcript)?.value
            val colorMap = listOf(
                "pink" to "#FF2A85",
                "magenta" to "#FF2A85",
                "rose" to "#F43F5E",
                "red" to "#EF4444",
                "crimson" to "#DC2626",
                "purple" to "#A855F7",
                "violet" to "#8B5CF6",
                "blue" to "#3B82F6",
                "cyan" to "#00E5FF",
                "teal" to "#14B8A6",
                "mint" to "#00FFA3",
                "green" to "#10B981",
                "emerald" to "#10B981",
                "amber" to "#FF9400",
                "gold" to "#F59E0B",
                "orange" to "#F97316"
            )
            val namedColor = colorMap.firstOrNull { (k, _) -> Regex("\\b$k\\b").containsMatchIn(lower) }
            val isReset = Regex("\\b(reset|default|auto|automatic)\\b").containsMatchIn(lower)
            return if (explicitHexMatch != null || namedColor != null) {
                val chosenHex = explicitHexMatch ?: namedColor!!.second
                val colorName = namedColor?.first ?: chosenHex
                JSONObject().apply {
                    put("calculated_satiety_percentage", 22)
                    put("reasoning_mode", "DIRECT_EXECUTION")
                    put("flow_state_analysis", "Applied custom $colorName accent ($chosenHex) across TuneMind.")
                    put("voice_agent_tts", "Updated your TuneMind theme accent to $colorName.")
                    put("agent_tool_call", JSONObject().apply {
                        put("action", "SET_THEME_COLOR")
                        put("custom_accent_hex", chosenHex)
                        put("expect_follow_up", false)
                    })
                    put("os_execution_command", JSONObject().apply {
                        put("intent", "NONE")
                        put("target_player", targetPlayer)
                        put("search_query", "")
                    })
                }
            } else {
                JSONObject().apply {
                    put("calculated_satiety_percentage", 22)
                    put("reasoning_mode", "DIRECT_EXECUTION")
                    put("flow_state_analysis", "Shifting Studio Adaptive Atmosphere palette.")
                    put("voice_agent_tts", if (isReset) "Reset your TuneMind theme back to adaptive mode." else "Shifted your studio atmosphere palette to match your vibe.")
                    put("agent_tool_call", JSONObject().apply {
                        put("action", "SHIFT_ATMOSPHERE")
                        if (isReset) put("palette_id", "auto")
                        put("expect_follow_up", false)
                    })
                    put("os_execution_command", JSONObject().apply {
                        put("intent", "NONE")
                        put("target_player", targetPlayer)
                        put("search_query", "")
                    })
                }
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
            val (libTop, memTop) = findMostPlayedCrossAppTrack(recents)
            val topTitle = libTop?.title ?: memTop?.title
            val topArtist = libTop?.artist ?: memTop?.artist ?: ""
            val topPlays = libTop?.playCount ?: memTop?.distinctAppearanceCount ?: 1
            if (!topTitle.isNullOrBlank()) {
                val topLink = libTop?.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                    ?: libTop?.deezerUrl?.takeIf { it.isNotBlank() } ?: ""
                val artistPart = if (topArtist.isNotBlank() && topArtist != "Unknown Artist") " by $topArtist" else ""
                val countText = if (topPlays > 1) " with $topPlays plays in your listening history" else " at the top of your rotation"
                return JSONObject().apply {
                    put("calculated_satiety_percentage", 22)
                    put("reasoning_mode", "PLAYBACK_SUGGESTION")
                    put("flow_state_analysis", "Top Track: \"$topTitle\"$artistPart (${topPlays}x plays) — awaiting approval.")
                    put("voice_agent_tts", "Your most-listened song is $topTitle$artistPart$countText. Would you like me to play it right now?")
                    put("agent_tool_call", JSONObject().apply {
                        put("action", "PROPOSE_TRACK")
                        put("track_title", topTitle)
                        put("track_artist", topArtist)
                        put("spotify_uri", topLink)
                        put("target_player", targetPlayer)
                        put("expect_follow_up", true)
                    })
                    put("os_execution_command", JSONObject().apply {
                        put("intent", "NONE")
                        put("target_player", targetPlayer)
                        put("search_query", "$topTitle $topArtist".trim())
                        put("track_title", topTitle)
                        put("track_artist", topArtist)
                        put("spotify_uri", topLink)
                    })
                }
            }
        }

        // 10. Genre / New Song / Discovery / Recommendation Request
        if (isNewOrRecommendSongRequest(lower)) {
            val excludedLower = (getRecentlyPlayedByAgent() + listOfNotNull(np?.title))
                .map { it.lowercase().trim() }
                .filter { it.isNotBlank() }
                .toSet()

            val libSnapshot = getCrossAppLibrarySnapshot()
            val genreQuery = extractRequestedGenreQuery(lower)
            val genreMatchedLocal = if (genreQuery != null) {
                val primaryKeyword = genreQuery.split(" ").first()
                libSnapshot.firstOrNull { u ->
                    !excludedLower.contains(u.title.lowercase().trim()) && (
                        u.title.contains(primaryKeyword, ignoreCase = true) ||
                        u.artist.contains(primaryKeyword, ignoreCase = true) ||
                        u.genre.contains(primaryKeyword, ignoreCase = true) ||
                        (primaryKeyword.equals("Kikuyu", ignoreCase = true) && (
                            u.artist.contains("Samidoh", ignoreCase = true) ||
                            u.artist.contains("Salim", ignoreCase = true) ||
                            u.artist.contains("De'Mathew", ignoreCase = true) ||
                            u.artist.contains("Gatutura", ignoreCase = true) ||
                            u.artist.contains("Mamaa", ignoreCase = true) ||
                            u.title.contains("Gikuyu", ignoreCase = true) ||
                            u.title.contains("Mugithi", ignoreCase = true)
                        ))
                    )
                }
            } else null

            val cachedForYou = if (genreQuery == null) getForYouPicks?.invoke().orEmpty().firstOrNull { pick ->
                pick.title.isNotBlank() && !excludedLower.contains(pick.title.lowercase().trim())
            } else null
            val cachedCapsule = if (genreQuery == null) capsule?.tracks.orEmpty().firstOrNull { ct ->
                ct.title.isNotBlank() && !excludedLower.contains(ct.title.lowercase().trim())
            } else null
            val cachedLibPick = if (genreQuery == null) libSnapshot.firstOrNull { u ->
                u.title.isNotBlank() && !excludedLower.contains(u.title.lowercase().trim())
            } else null

            val fastMemoryTitle = genreMatchedLocal?.title ?: cachedForYou?.title ?: cachedCapsule?.title ?: cachedLibPick?.title
            val fastMemoryArtist = genreMatchedLocal?.artist ?: cachedForYou?.artist ?: cachedCapsule?.artist ?: cachedLibPick?.artist ?: ""
            val fastMemoryUri = genreMatchedLocal?.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                ?: genreMatchedLocal?.deezerUrl?.takeIf { it.isNotBlank() }
                ?: cachedForYou?.spotifyUri
                ?: cachedCapsule?.spotifyUri
                ?: cachedLibPick?.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                ?: ""

            var genreFallbackTitle: String? = null
            var genreFallbackArtist: String? = null
            var genreFallbackUri: String? = null
            val discovery = if (fastMemoryTitle == null && genreQuery != null) {
                val searchHits = runCatching {
                    com.tunemind.app.provider.UniversalTrackResolver.searchCatalogBlocking(
                        context = context,
                        query = genreQuery,
                        limit = 5
                    )
                }.getOrDefault(emptyList())
                val firstHit = searchHits.firstOrNull()
                if (firstHit != null) {
                    genreFallbackTitle = firstHit.title
                    genreFallbackArtist = firstHit.artist
                    genreFallbackUri = firstHit.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                        ?: firstHit.audiusStreamUrl?.takeIf { it.isNotBlank() }
                        ?: firstHit.deezerUrl?.takeIf { it.isNotBlank() }
                        ?: firstHit.previewUrl
                }
                null
            } else if (fastMemoryTitle == null) {
                fetchOpenCatalogDiscoveryFallback(excludedLower)
            } else {
                null
            }

            val resolvedTitle = discovery?.trackTitle ?: genreFallbackTitle ?: fastMemoryTitle
            val resolvedArtist = discovery?.artistName ?: genreFallbackArtist ?: fastMemoryArtist
            val resolvedUri = discovery?.deepLinkUrl ?: genreFallbackUri ?: fastMemoryUri

            if (!resolvedTitle.isNullOrBlank()) {
                val artistPart = if (resolvedArtist.isNotBlank() && resolvedArtist != "Unknown Artist") " by $resolvedArtist" else ""
                val mood = com.tunemind.app.ui.theme.AdaptiveThemeEngine.resolveTrackMood(resolvedTitle, resolvedArtist)
                return JSONObject().apply {
                    put("calculated_satiety_percentage", 18)
                    put("reasoning_mode", "PLAYBACK_SUGGESTION")
                    put("flow_state_analysis", "Curated Pick: \"$resolvedTitle\"$artistPart • ${mood.label} Mood DNA.")
                    put("voice_agent_tts", "I picked $resolvedTitle$artistPart for your vibe right now. Would you like me to play it?")
                    put("agent_tool_call", JSONObject().apply {
                        put("action", "PROPOSE_TRACK")
                        put("track_title", resolvedTitle)
                        put("track_artist", resolvedArtist)
                        put("spotify_uri", resolvedUri)
                        put("target_player", targetPlayer)
                        put("expect_follow_up", true)
                    })
                    put("os_execution_command", JSONObject().apply {
                        put("intent", "NONE")
                        put("target_player", targetPlayer)
                        put("search_query", "$resolvedTitle $resolvedArtist".trim())
                        put("track_title", resolvedTitle)
                        put("track_artist", resolvedArtist)
                        put("spotify_uri", resolvedUri)
                    })
                }
            }
        }

        // 11. Explicit Song Title / Artist Request (Only if NOT a generic/conversational phrase!)
        val cleanNoPunct = lower.replace(Regex("[.!?,;:'\"]+"), " ").replace(Regex("\\s+"), " ").trim()
        val explicitMatch = Regex("^\\s*(please\\s+)?(play|spin|put on)\\s+(.+)$").find(cleanNoPunct)
        val rawNamedTarget = explicitMatch?.groupValues?.getOrNull(3)
            ?.replace(Regex("^(me\\s+|for me\\s+)"), "")
            ?.replace(Regex("\\b(right now|now|for me|please|on spotify|on boomplay|on youtube music)\\b"), "")
            ?.trim()

        if (!rawNamedTarget.isNullOrBlank() &&
            rawNamedTarget.length >= 2 &&
            !isNewOrRecommendSongRequest(lower) &&
            !isConversationalNonTitlePhrase(rawNamedTarget)
        ) {
            val normNamed = com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(rawNamedTarget)
            val libSnapshot = getCrossAppLibrarySnapshot()
            val matchedLocal = libSnapshot.firstOrNull { u ->
                val normU = com.tunemind.app.provider.UniversalTrackResolver.normalizeTitleForExactMatch(u.title)
                normU.isNotEmpty() && normU == normNamed
            }
            val finalTitle = matchedLocal?.title ?: rawNamedTarget.replaceFirstChar { it.uppercase() }
            val finalArtist = matchedLocal?.artist ?: ""
            val finalUri = matchedLocal?.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
                ?: matchedLocal?.deezerUrl?.takeIf { it.isNotBlank() }
                ?: resolveOpenCatalogLinkBlocking(
                    title = finalTitle,
                    artist = finalArtist,
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

        // 11.5 Offline fallback for conversational/AI turns (never fake AI reasoning offline)
        if (!isExplicitMusicPlayOrRecommendRequest(lower)) {
            val activeTitle = np?.title?.takeIf { it.isNotBlank() && it != "Standing By" }
            val activeArtist = np?.artist?.takeIf { it.isNotBlank() && it != "Unknown Artist" }
            val offlineReply = when {
                Regex("\\b(what is playing|what's playing|current song)\\b").containsMatchIn(lower) && activeTitle != null ->
                    "You're listening to $activeTitle${if (activeArtist != null) " by $activeArtist" else ""}."
                Regex("\\b(open|show)\\s+(my\\s+)?playlists?\\b").containsMatchIn(lower) -> {
                    return JSONObject().apply {
                        put("calculated_satiety_percentage", _agentSatietyFlow.value)
                        put("reasoning_mode", "DIRECT_EXECUTION")
                        put("flow_state_analysis", "Opening TuneMind Playlists.")
                        put("voice_agent_tts", "Opening your TuneMind playlists.")
                        put("agent_tool_call", JSONObject().apply {
                            put("action", "NAVIGATE_SCREEN")
                            put("target_screen", "TUNEMIND_PLAYLISTS")
                            put("expect_follow_up", false)
                        })
                        put("os_execution_command", JSONObject().apply {
                            put("intent", "NONE")
                            put("target_player", targetPlayer)
                            put("search_query", "")
                        })
                    }
                }
                else ->
                    "I couldn't reach the cloud brain just now — please check your internet connection and ask me again."
            }

            return JSONObject().apply {
                put("calculated_satiety_percentage", _agentSatietyFlow.value)
                put("reasoning_mode", "JOURNEY_ADVISOR")
                put("flow_state_analysis", "Offline Fallback • Waiting for Cloud Brain.")
                put("voice_agent_tts", offlineReply)
                put("agent_tool_call", JSONObject().apply {
                    put("action", "ADVISE_ONLY")
                    put("target_player", targetPlayer)
                    put("expect_follow_up", false)
                })
                put("os_execution_command", JSONObject().apply {
                    put("intent", "NONE")
                    put("target_player", targetPlayer)
                    put("search_query", "")
                })
            }
        }

        // 12. Explicit music request fallback: Check local curated capsule / For-You / cross-app history in 0ms before HTTP discovery
        val recentSuggested = getRecentlyPlayedByAgent().map { it.lowercase() }.toSet()
        val forYouPick = getForYouPicks?.invoke().orEmpty().firstOrNull { p ->
            p.title.isNotBlank() && !recentSuggested.contains(p.title.lowercase().trim())
        }
        val freshPick = capsule?.tracks?.firstOrNull { ct ->
            !recentSuggested.contains(ct.title.lowercase().trim())
        } ?: capsule?.tracks?.firstOrNull()
        val libFallback = getCrossAppLibrarySnapshot().firstOrNull { u ->
            u.title.isNotBlank() && !recentSuggested.contains(u.title.lowercase().trim())
        }
        val hasFastPick = forYouPick != null || freshPick != null || libFallback != null
        val discoveryFallback = if (!hasFastPick) {
            val excluded = (getRecentlyPlayedByAgent() + listOfNotNull(np?.title))
                .map { it.lowercase().trim() }
                .toSet()
            fetchOpenCatalogDiscoveryFallback(excluded)
        } else {
            null
        }

        val chosenTitle = com.tunemind.app.provider.MusicPlayerAutoLauncher.sanitizeTrackTitleForPlay(
            discoveryFallback?.trackTitle
                ?: forYouPick?.title
                ?: freshPick?.title
                ?: libFallback?.title
                ?: "Midnight City"
        )
        val chosenArtist = com.tunemind.app.provider.MusicPlayerAutoLauncher.sanitizeArtistForPlay(
            discoveryFallback?.artistName
                ?: forYouPick?.artist
                ?: freshPick?.artist
                ?: libFallback?.artist
                ?: "M83"
        )
        val chosenUri = discoveryFallback?.deepLinkUrl
            ?: forYouPick?.spotifyUri?.takeIf { it.startsWith("http") }
            ?: freshPick?.spotifyUri
            ?: libFallback?.ytmVideoId?.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
            ?: libFallback?.deezerUrl?.takeIf { it.isNotBlank() }
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
                latestNativePartialText = ""

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
                            if (hasCompletedCloudTurn || _agentState.value == AgentState.SPEAKING || _agentState.value == AgentState.THINKING) {
                                Log.d(TAG, "Ignoring late native SpeechRecognizer error($error); turn already active/complete.")
                                return
                            }
                            val capturedNativePartial = latestNativePartialText.trim()
                            if (capturedNativePartial.length >= 2) {
                                Log.i(TAG, "Recovering SpeechRecognizer error($error) with partial transcript: $capturedNativePartial")
                                lastUserTranscript = capturedNativePartial
                                endOfSongAutoPlayTimeoutJob?.cancel()
                                setThinkingState()
                                scope.launch {
                                    _transcriptionFlow.emit(capturedNativePartial)
                                    _finalTranscriptFlow.emit(capturedNativePartial)
                                }
                                return
                            }
                            val capturedLocalVosk = latestLocalVoskCommandText.trim()
                            if (capturedLocalVosk.isNotBlank()) {
                                Log.i(TAG, "Recovering SpeechRecognizer error($error) with on-device Vosk transcript: $capturedLocalVosk")
                                processFinalTranscriptWithFallback(capturedLocalVosk)
                                return
                            }
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
                            if (hasCompletedCloudTurn || _agentState.value == AgentState.SPEAKING) {
                                Log.d(TAG, "Ignoring duplicate native SpeechRecognizer result after turn completed.")
                                return
                            }
                            val matches = results?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
                            val transcript = matches?.firstOrNull()?.trim()
                            if (!transcript.isNullOrBlank()) {
                                markUserSpeechDetectedThisTurn()
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
                                markUserSpeechDetectedThisTurn()
                                latestNativePartialText = transcript
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
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2200L)
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
        // Only tear down any stale AudioRecord instance; preserve already-opening WS state & buffered pre-roll frames
        stopAudioRecordingOnly()

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
                Thread.sleep(50)
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

        // Enqueue any continuous post-wake speech tail ahead of live AudioRecord frames so opening syllables aren't clipped
        while (pendingWakeTailFrames.isNotEmpty()) {
            val tailBytes = pendingWakeTailFrames.poll() ?: break
            val cleanTail = processPcm16CleanSpeech(tailBytes, tailBytes.size)
            val tailStr = cleanTail.toByteString()
            val activeWs = webSocket
            if (isWsOpenForAudio && activeWs != null) {
                activeWs.send(tailStr)
            } else if (preConnectAudioBuffer.size < 45) {
                preConnectAudioBuffer.offer(tailStr)
            }
        }

        latestLocalVoskCommandText = ""
        hasCompletedCloudTurn = false
        scope.launch(Dispatchers.IO) {
            val localCmdRec = runCatching {
                voskModel?.let { Recognizer(it, 16000.0f) }
            }.getOrNull()
            // Read exact 100ms frames (3200 bytes at 16kHz 16-bit mono) so AssemblyAI v3 never receives <20ms sub-frames
            val targetFrameBytes = 3200
            val frameBuffer = ByteArray(targetFrameBytes)
            var speechStartedAtMs = 0L
            var lastSpeechFrameAtMs = 0L
            var noiseFloorRms = 135.0
            var hasSentFinalizeHint = false
            try {
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
                        val now = System.currentTimeMillis()
                        val frameRms = computePcm16Rms(frameBuffer, validLen)
                        val speechThreshold = maxOf(190.0, noiseFloorRms * 2.35)
                        if (frameRms <= speechThreshold) {
                            noiseFloorRms = (noiseFloorRms * 0.85 + frameRms * 0.15).coerceIn(80.0, 340.0)
                        } else {
                            markUserSpeechDetectedThisTurn()
                            if (speechStartedAtMs == 0L) speechStartedAtMs = now
                            lastSpeechFrameAtMs = now
                        }

                        // Feed passive offline Vosk fallback only as emergency backup (never to override cloud STT)
                        if (localCmdRec != null && !isWsOpenForAudio) {
                            runCatching {
                                val finalized = localCmdRec.acceptWaveForm(frameBuffer, validLen)
                                val rawJson = if (finalized) localCmdRec.result else localCmdRec.partialResult
                                val parsed = JSONObject(rawJson).let { obj ->
                                    if (finalized) obj.optString("text", "") else obj.optString("partial", "")
                                }.trim()
                                val stripped = if (wasTurnTriggeredByWakeWord) stripLeadingWakePhrase(parsed) else parsed
                                if (stripped.isNotBlank()) {
                                    latestLocalVoskCommandText = stripped
                                    markUserSpeechDetectedThisTurn()
                                    if (speechStartedAtMs == 0L) speechStartedAtMs = now
                                    lastSpeechFrameAtMs = now
                                }
                            }
                        }

                        val lastActivityAtMs = maxOf(lastSpeechFrameAtMs, lastWsPartialProgressAtMs)
                        if ((speechStartedAtMs > 0L || latestWsPartialText.isNotBlank()) && lastActivityAtMs > 0L) {
                            val silenceElapsedMs = now - lastActivityAtMs
                            // Offline instant playback command shortcut only when cloud WS isn't streaming
                            val offlineCmd = latestLocalVoskCommandText.trim()
                            if (!isWsOpenForAudio && silenceElapsedMs >= 1150L && offlineCmd.isNotBlank() && isInstantLocalCommand(offlineCmd)) {
                                Log.i(TAG, "⚡ Offline Fast-Path executing command: \"$offlineCmd\"")
                                lastUserTranscript = offlineCmd
                                hasCompletedCloudTurn = true
                                cloudResponseTimeoutJob?.cancel()
                                stopMicrophoneStream()
                                val instantJson = buildLocalOnDeviceFallbackJson(offlineCmd)
                                mainHandler.post {
                                    dispatchReasoningResult(instantJson, instantJson.optString("voice_agent_tts", ""))
                                }
                                break
                            }
                            // Wait patiently (1850ms) so multi-clause sentences and breaths aren't cut mid-thought,
                            // then nudge AssemblyAI ForceEndpoint without replacing audio with offline Vosk guesses
                            if (!hasSentFinalizeHint && silenceElapsedMs >= 1850L && isWsOpenForAudio && webSocket != null) {
                                hasSentFinalizeHint = true
                                val activeWs = webSocket
                                if (activeWs != null) {
                                    runCatching {
                                        activeWs.send(
                                            JSONObject().apply {
                                                put("type", "finalize_utterance")
                                                if (latestWsPartialText.isNotBlank()) {
                                                    put("transcript", latestWsPartialText.trim())
                                                }
                                            }.toString()
                                        )
                                    }
                                    val watchdogText = latestWsPartialText.ifBlank { latestLocalVoskCommandText }.trim()
                                    if (watchdogText.length >= 2) {
                                        scheduleCloudResponseWatchdog(watchdogText)
                                    }
                                }
                            }
                        }

                        val cleanBytes = processPcm16CleanSpeech(frameBuffer, validLen)
                        val byteStr = cleanBytes.toByteString()
                        val activeWs = webSocket
                        if (isWsOpenForAudio && activeWs != null) {
                            while (preConnectAudioBuffer.isNotEmpty()) {
                                val buffered = preConnectAudioBuffer.poll() ?: break
                                activeWs.send(buffered)
                            }
                            activeWs.send(byteStr)
                        } else {
                            // Keep up to 4.5 seconds (45 frames of 100ms) of pre-connection speech in memory while socket connects
                            if (preConnectAudioBuffer.size < 45) {
                                preConnectAudioBuffer.offer(byteStr)
                            }
                        }
                    }
                }
            } finally {
                if (localCmdRec != null) {
                    if (!isWsOpenForAudio) {
                        runCatching {
                            val tailText = JSONObject(localCmdRec.finalResult).optString("text", "").trim()
                            val strippedTail = if (wasTurnTriggeredByWakeWord) stripLeadingWakePhrase(tailText) else tailText
                            if (strippedTail.isNotBlank()) {
                                latestLocalVoskCommandText = strippedTail
                            }
                        }
                    }
                    runCatching { localCmdRec.close() }
                }
            }
        }
    }

    private fun isInstantLocalCommand(rawText: String): Boolean {
        val lower = rawText.lowercase().trim()
        if (lower.isEmpty() || lower.split(Regex("\\s+")).size > 3) return false
        return Regex(
            "^(?:pause|pause music|stop|stop music|resume|continue playing|keep playing|skip|next song|next track|previous song|go back|volume up|volume down)[.!?]*$"
        ).matches(lower)
    }

    private fun scheduleCloudResponseWatchdog(fallbackTranscript: String) {
        cloudResponseTimeoutJob?.cancel()
        val clean = fallbackTranscript.trim()
        if (clean.isBlank()) return
        cloudResponseTimeoutJob = scope.launch {
            delay(32_000L)
            if (!hasCompletedCloudTurn && (_agentState.value == AgentState.LISTENING || _agentState.value == AgentState.THINKING)) {
                Log.w(TAG, "⚡ Cloud response >32s — replying cleanly for: \"$clean\"")
                hasCompletedCloudTurn = true
                lastUserTranscript = clean
                stopMicrophoneStream()
                stopNativeSpeechRecognizer()
                val localJson = buildLocalOnDeviceFallbackJson(clean)
                dispatchReasoningResult(localJson, localJson.optString("voice_agent_tts", ""))
            }
        }
    }

    private fun computePcm16Rms(buffer: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        var sumSq = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()
            val sample = ((high shl 8) or low).toDouble()
            sumSq += sample * sample
            count++
            i += 2
        }
        return if (count > 0) kotlin.math.sqrt(sumSq / count) else 0.0
    }

    /**
     * Adaptive soft-knee PCM16 speech conditioner: passes close-range speech untouched at 1.0x
     * so consonants never square-wave clip, gently raises quiet/distant voice, and limits peaks softly.
     */
    private fun processPcm16CleanSpeech(buffer: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var peakAbs = 0
        var i = 0
        while (i + 1 < length) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()
            val sample = (high shl 8) or low
            val absSample = kotlin.math.abs(sample)
            if (absSample > peakAbs) peakAbs = absSample
            i += 2
        }
        val targetGain = when {
            peakAbs < 280 -> 1.0f // Never amplify room hiss
            peakAbs >= 6000 -> 1.0f // Normal/close speech already crystal clear — preserve unclipped waveform
            else -> (7500.0f / peakAbs.toFloat()).coerceIn(1.0f, 1.8f)
        }
        adaptiveMicGain = (adaptiveMicGain * 0.70f + targetGain * 0.30f).coerceIn(1.0f, 1.8f)
        val gain = adaptiveMicGain
        i = 0
        while (i + 1 < length) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt()
            val sample = (high shl 8) or low
            val scaled = if (gain <= 1.03f) sample else (sample * gain).toInt()
            // Soft-knee peak protection above 24000 so loud plosives never flat-top clip
            val limited = when {
                scaled > 24000 -> 24000 + (scaled - 24000) / 3
                scaled < -24000 -> -24000 + (scaled + 24000) / 3
                else -> scaled
            }.coerceIn(-32760, 32760)
            out[i] = (limited and 0xFF).toByte()
            out[i + 1] = ((limited shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun stopAudioRecordingOnly() {
        lastOwnMicActivityAtMs = System.currentTimeMillis()
        isRecording = false
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
        Log.d(TAG, "Microphone recording stopped.")
    }

    private fun stopMicrophoneStream() {
        stopAudioRecordingOnly()
        isWsOpenForAudio = false
        hasCompletedCloudTurn = false
        preConnectAudioBuffer.clear()
        try {
            webSocket?.close(1000, "Finished speaking")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing webSocket", e)
        }
        webSocket = null
        Log.d(TAG, "Microphone stream stopped.")
    }

    fun resumeWakeWordListening() {
        if (shouldKeepWakeWordListening() &&
            !isVoskListening &&
            !pausedForExternalRecording &&
            _agentState.value == AgentState.IDLE
        ) {
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
        com.tunemind.app.service.TuneMindListenerService.onPlaybackSnapshotUpdated = null
        com.tunemind.app.provider.TuneMindInAppPlayer.setOnTrackCompletedListener(null)
        wakeWatchdogJob?.cancel()
        noSpeechAfterWakeJob?.cancel()
        postGreetingListenTimeoutJob?.cancel()
        cloudResponseTimeoutJob?.cancel()
        wakeRestartJob?.cancel()
        errorResetJob?.cancel()
        audioRecordingCallback?.let {
            runCatching { audioManager.unregisterAudioRecordingCallback(it) }
        }
        audioRecordingCallback = null
        audioMonitorThread?.quitSafely()
        audioMonitorThread = null
        runCatching { chimeToneGenerator?.release() }
        chimeToneGenerator = null
        stopMicrophoneStream()
        stopNativeSpeechRecognizer()
        releaseAudioDucking()
        stopVoskWakeListener()
        try {
            executionEngine.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down executionEngine", e)
        }
        scope.cancel()
    }

    private fun buildVaultJsonArray(vaultList: List<com.tunemind.app.ui.VaultTrack>): org.json.JSONArray {
        val vaultArr = org.json.JSONArray()
        val nowMs = System.currentTimeMillis()
        for (vt in vaultList) {
            val rec = com.tunemind.app.domain.vault.RestingVaultEngine.computeRecoveryState(
                quarantinedAtMs = vt.quarantinedAtMs,
                totalDays = vt.totalDays,
                baseSatietyScore = vt.satietyScore,
                nowMs = nowMs
            )
            vaultArr.put(JSONObject().apply {
                put("id", vt.id)
                put("title", vt.title)
                put("artist", vt.artist)
                put("trackId", vt.resolvedCanonicalTrackId())
                vt.spotifyUri?.let { put("spotifyUri", it) }
                put("sourcePackage", vt.sourcePackage)
                put("quarantinedAtMs", vt.quarantinedAtMs)
                put("totalDays", vt.totalDays.toDouble())
                put("cooldown_days_remaining", rec.cooldownDaysRemaining.toDouble())
                put("remaining_label", rec.remainingLabel)
                put("recovery_percent", (rec.recoveryProgress * 100f).toInt())
                put("freshness_percent", rec.freshnessPercent)
                put("satiety_score", vt.satietyScore)
                put("entry_reason", vt.entryReason)
                put("is_auto_quarantined", vt.isAutoQuarantined)
            })
        }
        return vaultArr
    }
}
