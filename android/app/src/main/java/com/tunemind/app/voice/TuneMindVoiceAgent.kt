package com.tunemind.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.tunemind.app.domain.ai.AgentActionType
import com.tunemind.app.domain.ai.BrainResponse
import com.tunemind.app.domain.ai.TuneMindAutonomousBrain
import com.tunemind.app.domain.recommendation.RecommendationCategory
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.provider.MusicProviderRegistry
import com.tunemind.app.ui.TuneMindViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class AgentPhase {
    IDLE,
    THINKING,
    PROMPTING_VOICE,
    LISTENING_INTENT,
    WAITING_CONFIRMATION,
    LISTENING_CONFIRMATION,
    PROMPTING_PLAYER,
    LISTENING_PLAYER,
    EXECUTING
}

enum class NeuroAcousticIntent {
    DOPAMINE_PEAK,
    AUDITORY_ANCHOR,
    CIRCADIAN_DAYPART,
    AI_CRATE,
    TIMELINE_HISTORY,
    GOSPEL,
    GIKUYU,
    BONGO,
    CHANGE_SONG,
    SWITCH_PLAYER,
    DIRECT_PLAY,
    UNKNOWN
}

data class AgentChatMessage(
    val sender: String, // "agent" or "user"
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class VoiceAgentState(
    val phase: AgentPhase = AgentPhase.IDLE,
    val agentSpeech: String = "",
    val userSpeech: String = "",
    val resolvedTrack: RecommendedTrack? = null,
    val targetProviderId: String = "spotify",
    val targetProviderName: String = "Spotify",
    val rmsLevel: Float = 0f,
    val statusMessage: String = "Tap Mic to activate TuneMind Voice Agent",
    val dialogueHistory: List<AgentChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val pendingConfirmationTrack: RecommendedTrack? = null
)

/**
 * Autonomous Conversational Voice Agent for TuneMind.
 * Powered by TuneMindAutonomousBrain with:
 * - Real reasoning across user's 90+ timeline tracks (Gikuyu, Bongo, Gospel, Afrobeats)
 * - Empathetic negation & cancellation understanding (stops when user says "no")
 * - Proactive morning/afternoon/evening welcome greetings on app open
 * - Hands-free auto-play across Spotify, YouTube Music, Boomplay, Apple Music, and Phone Files.
 */
class TuneMindVoiceAgent(
    private val context: Context,
    private val vm: TuneMindViewModel
) {
    private val tag = "TuneMindVoiceAgent"
    private val scope = CoroutineScope(Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var hasWelcomedThisSession = false

    private val _agentState = MutableStateFlow(
        VoiceAgentState(
            targetProviderId = vm.getPreferredProviderId(context),
            targetProviderName = getProviderName(vm.getPreferredProviderId(context))
        )
    )
    val agentState: StateFlow<VoiceAgentState> = _agentState.asStateFlow()

    private var pendingTrack: RecommendedTrack? = null
    private var lastSuggestedTrack: RecommendedTrack? = null

    init {
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.getDefault())
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.US)
                    }
                    isTtsInitialized = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                when (utteranceId) {
                                    UTTERANCE_INITIAL_PROMPT, UTTERANCE_FOLLOW_UP_PROMPT -> {
                                        startListeningForIntent()
                                    }
                                    UTTERANCE_SUGGESTION_CONFIRM -> {
                                        startListeningForConfirmation()
                                    }
                                    UTTERANCE_FINAL_DISPATCH -> {
                                        executePlayback()
                                    }
                                }
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            Log.e(tag, "TTS Utterance error on $utteranceId")
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "TTS init failure: ${e.message}", e)
        }
    }

    /**
     * Welcomes the user with a personalized spoken greeting upon opening the app.
     */
    fun welcomeOnAppOpen() {
        if (hasWelcomedThisSession) return
        hasWelcomedThisSession = true

        scope.launch {
            val satiety = calculateDynamicSatiety()
            val daypart = vm.currentTimeSlot.label
            val userName = vm.userProfile.value.name
            val greeting = TuneMindAutonomousBrain.generateWelcomeGreeting(userName, daypart, satiety)

            addAgentMessage(greeting)
            _agentState.value = _agentState.value.copy(
                phase = AgentPhase.PROMPTING_VOICE,
                agentSpeech = greeting,
                statusMessage = "Welcoming you to TuneMind..."
            )
            speak(greeting, UTTERANCE_INITIAL_PROMPT)
        }
    }

    /**
     * Starts interactive voice session when mic button or screen is tapped.
     */
    fun startAgentSession() {
        scope.launch {
            _agentState.value = _agentState.value.copy(
                phase = AgentPhase.THINKING,
                isThinking = true,
                statusMessage = "Reasoning across your listening history..."
            )

            val satiety = calculateDynamicSatiety()
            val daypart = vm.currentTimeSlot.label
            val userName = vm.userProfile.value.name
            val nowPlaying = vm.homeState.value.nowPlaying

            val promptText = if (nowPlaying?.isPlaying == true && !nowPlaying.title.isNullOrBlank()) {
                "Listening to '${nowPlaying.title}' at $satiety% satiety. What vibe would you like, $userName? Say Gospel, Gikuyu favorites, Bongo, or 'change that song'."
            } else {
                TuneMindAutonomousBrain.generateWelcomeGreeting(userName, daypart, satiety)
            }

            addAgentMessage(promptText)
            _agentState.value = _agentState.value.copy(
                phase = AgentPhase.PROMPTING_VOICE,
                isThinking = false,
                agentSpeech = promptText,
                userSpeech = "",
                statusMessage = "Agent speaking..."
            )
            speak(promptText, UTTERANCE_INITIAL_PROMPT)
        }
    }

    /**
     * 1-Tap Trigger for specific Cognitive & Cultural Intents (Gospel, Gikuyu, Bongo, Timeline, etc.)
     */
    fun triggerIntentDirectly(intent: NeuroAcousticIntent, bypassVoicePrompt: Boolean = false) {
        val queryText = when (intent) {
            NeuroAcousticIntent.GOSPEL -> "play gospel worship praise song"
            NeuroAcousticIntent.GIKUYU -> "play gikuyu mugithi song"
            NeuroAcousticIntent.BONGO -> "play bongo flava song"
            NeuroAcousticIntent.TIMELINE_HISTORY -> "play song from my timeline"
            NeuroAcousticIntent.DOPAMINE_PEAK -> "play peak dopamine energetic track"
            NeuroAcousticIntent.AUDITORY_ANCHOR -> "play my most repeated sonic anchor"
            NeuroAcousticIntent.CIRCADIAN_DAYPART -> "play music for this time of day"
            NeuroAcousticIntent.CHANGE_SONG -> "change that song right now"
            else -> "play high dopamine"
        }

        handleSpokenIntent(queryText)
    }

    /**
     * Executes the "Change that song" flow immediately.
     */
    fun changeSongImmediately() {
        handleSpokenIntent("change that song right now")
    }

    /**
     * Plays from the user's 90+ timeline archive.
     */
    fun playFromTimeline(query: String? = null) {
        val q = if (query.isNullOrBlank()) "play song from my timeline" else "play $query from timeline"
        handleSpokenIntent(q)
    }

    /**
     * Proactively suggests a song and asks for confirmation.
     */
    fun suggestTrackWithConfirmation() {
        handleSpokenIntent("what do you suggest for me to listen to")
    }

    private fun speak(text: String, utteranceId: String?) {
        if (isTtsInitialized && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId ?: "tunemind_general")
        } else {
            mainHandler.postDelayed({
                when (utteranceId) {
                    UTTERANCE_INITIAL_PROMPT, UTTERANCE_FOLLOW_UP_PROMPT -> startListeningForIntent()
                    UTTERANCE_SUGGESTION_CONFIRM -> startListeningForConfirmation()
                    UTTERANCE_FINAL_DISPATCH -> executePlayback()
                }
            }, 1200)
        }
    }

    private fun startListeningForIntent() {
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.LISTENING_INTENT,
            statusMessage = "Listening for your voice (ask anything)..."
        )
        listenWithRecognizer { spokenText ->
            handleSpokenIntent(spokenText)
        }
    }

    private fun startListeningForConfirmation() {
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.LISTENING_CONFIRMATION,
            statusMessage = "Listening: Say 'Yes', 'Change it', or 'Timeline'..."
        )
        listenWithRecognizer { spokenText ->
            handleSpokenIntent(spokenText)
        }
    }

    private fun listenWithRecognizer(onResult: (String) -> Unit) {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(tag, "Ready for speech")
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        _agentState.value = _agentState.value.copy(rmsLevel = (rmsdB / 10f).coerceIn(0f, 1f))
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        _agentState.value = _agentState.value.copy(rmsLevel = 0f)
                    }
                    override fun onError(error: Int) {
                        Log.w(tag, "SpeechRecognizer error: $error")
                        _agentState.value = _agentState.value.copy(
                            phase = AgentPhase.IDLE,
                            statusMessage = "Tap Mic to talk with TuneMind Agent"
                        )
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d(tag, "Spoken text: $text")
                        addUserMessage(text)
                        _agentState.value = _agentState.value.copy(userSpeech = text)
                        onResult(text)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.firstOrNull()?.let {
                            _agentState.value = _agentState.value.copy(userSpeech = it)
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(tag, "Failed to start SpeechRecognizer: ${e.message}", e)
                _agentState.value = _agentState.value.copy(
                    phase = AgentPhase.IDLE,
                    statusMessage = "Microphone unavailable. Use quick buttons below."
                )
            }
        }
    }

    /**
     * Autonomous reasoning pipeline powered by TuneMindAutonomousBrain.
     */
    fun handleSpokenIntent(text: String) {
        val lower = text.lowercase(Locale.getDefault())

        // Check if player switch was requested
        detectAndApplyPlayerMention(lower)

        scope.launch {
            _agentState.value = _agentState.value.copy(
                phase = AgentPhase.THINKING,
                isThinking = true,
                statusMessage = "Reasoning across 90+ timeline tracks..."
            )

            val history = vm.historyState.value.tracks
            val nowPlaying = vm.homeState.value.nowPlaying
            val satiety = calculateDynamicSatiety()
            val daypart = vm.currentTimeSlot.label
            val providerId = _agentState.value.targetProviderId
            val providerName = _agentState.value.targetProviderName
            val userName = vm.userProfile.value.name

            val brainResponse: BrainResponse = TuneMindAutonomousBrain.reason(
                context = context,
                input = text,
                historyTracks = history,
                nowPlaying = nowPlaying,
                satietyPercent = satiety,
                daypart = daypart,
                currentProviderId = providerId,
                currentProviderName = providerName,
                userName = userName
            )

            addAgentMessage(brainResponse.spokenText)

            when (brainResponse.action) {
                AgentActionType.CANCEL -> {
                    _agentState.value = _agentState.value.copy(
                        phase = AgentPhase.IDLE,
                        isThinking = false,
                        agentSpeech = brainResponse.spokenText,
                        pendingConfirmationTrack = null,
                        statusMessage = "Cancelled. Tap Mic to talk."
                    )
                    speak(brainResponse.spokenText, null)
                }

                AgentActionType.PLAY_TRACK, AgentActionType.CHANGE_TRACK -> {
                    val track = brainResponse.resolvedTrack
                    if (track != null) {
                        pendingTrack = track
                        lastSuggestedTrack = track
                        _agentState.value = _agentState.value.copy(
                            phase = AgentPhase.EXECUTING,
                            isThinking = false,
                            agentSpeech = brainResponse.spokenText,
                            resolvedTrack = track,
                            pendingConfirmationTrack = track,
                            statusMessage = "Auto-playing: ${track.title}"
                        )
                        speak(brainResponse.spokenText, UTTERANCE_FINAL_DISPATCH)
                    } else {
                        _agentState.value = _agentState.value.copy(
                            phase = AgentPhase.IDLE,
                            isThinking = false,
                            agentSpeech = brainResponse.spokenText,
                            statusMessage = "Ready."
                        )
                        speak(brainResponse.spokenText, null)
                    }
                }

                AgentActionType.SPEAK_ONLY -> {
                    _agentState.value = _agentState.value.copy(
                        phase = AgentPhase.IDLE,
                        isThinking = false,
                        agentSpeech = brainResponse.spokenText,
                        statusMessage = "TuneMind Agent Ready"
                    )
                    speak(brainResponse.spokenText, null)
                }

                AgentActionType.SWITCH_PLAYER -> {
                    val msg = "Target player switched to ${_agentState.value.targetProviderName}."
                    addAgentMessage(msg)
                    _agentState.value = _agentState.value.copy(
                        phase = AgentPhase.IDLE,
                        isThinking = false,
                        agentSpeech = msg,
                        statusMessage = msg
                    )
                    speak(msg, null)
                }
            }
        }
    }

    private fun detectAndApplyPlayerMention(text: String): Boolean {
        val registry = MusicProviderRegistry.getInstance(context)
        val installed = registry.getInstalledProviders()

        var matched = false
        when {
            text.contains("spotify") -> { setTargetPlayer("spotify", "Spotify"); matched = true }
            text.contains("youtube") || text.contains("yt music") -> { setTargetPlayer("yt_music", "YouTube Music"); matched = true }
            text.contains("boomplay") -> { setTargetPlayer("boomplay", "Boomplay"); matched = true }
            text.contains("apple") -> { setTargetPlayer("apple_music", "Apple Music"); matched = true }
            text.contains("audiomack") -> { setTargetPlayer("audiomack", "Audiomack"); matched = true }
            text.contains("phone") || text.contains("file") || text.contains("local") -> { setTargetPlayer("local_files", "Phone Files"); matched = true }
            else -> {
                installed.firstOrNull { text.contains(it.displayName.lowercase(Locale.getDefault())) }?.let {
                    setTargetPlayer(it.providerId, it.displayName)
                    matched = true
                }
            }
        }
        return matched
    }

    fun setTargetPlayer(providerId: String, displayName: String) {
        vm.setPreferredProviderId(context, providerId)
        _agentState.value = _agentState.value.copy(
            targetProviderId = providerId,
            targetProviderName = displayName,
            statusMessage = "Target player: $displayName"
        )
    }

    private fun executePlayback() {
        val track = pendingTrack ?: return
        val targetProviderId = _agentState.value.targetProviderId
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.EXECUTING,
            statusMessage = "Auto-playing '${track.title}' in ${_agentState.value.targetProviderName}..."
        )

        scope.launch {
            try {
                vm.playRecommendation(context, track, targetProviderId)
            } catch (t: Throwable) {
                Log.e(tag, "Playback dispatch failed: ${t.message}", t)
            } finally {
                mainHandler.postDelayed({
                    _agentState.value = _agentState.value.copy(
                        phase = AgentPhase.IDLE,
                        statusMessage = "Playing: ${track.title}"
                    )
                }, 3000)
            }
        }
    }

    private fun calculateDynamicSatiety(): Int {
        val history = vm.historyState.value.tracks
        if (history.isEmpty()) return 35
        val topPlays = history.maxOfOrNull { it.distinctAppearanceCount } ?: 1
        return (topPlays * 16).coerceIn(20, 92)
    }

    private fun addAgentMessage(msg: String) {
        val current = _agentState.value.dialogueHistory.toMutableList()
        current.add(AgentChatMessage(sender = "agent", message = msg))
        if (current.size > 10) current.removeAt(0)
        _agentState.value = _agentState.value.copy(dialogueHistory = current)
    }

    private fun addUserMessage(msg: String) {
        val current = _agentState.value.dialogueHistory.toMutableList()
        current.add(AgentChatMessage(sender = "user", message = msg))
        if (current.size > 10) current.removeAt(0)
        _agentState.value = _agentState.value.copy(dialogueHistory = current)
    }

    private fun getProviderName(providerId: String): String {
        return when (providerId) {
            "spotify" -> "Spotify"
            "yt_music" -> "YouTube Music"
            "boomplay" -> "Boomplay"
            "apple_music" -> "Apple Music"
            "audiomack" -> "Audiomack"
            "local_files" -> "Phone Files"
            else -> {
                if (providerId.startsWith("pkg_")) {
                    providerId.removePrefix("pkg_").substringAfterLast('.').replaceFirstChar { it.uppercase() }
                } else "Music Player"
            }
        }
    }

    fun stop() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {}
        _agentState.value = _agentState.value.copy(phase = AgentPhase.IDLE)
    }

    companion object {
        private const val UTTERANCE_INITIAL_PROMPT = "tunemind_init_prompt"
        private const val UTTERANCE_FOLLOW_UP_PROMPT = "tunemind_followup_prompt"
        private const val UTTERANCE_SUGGESTION_CONFIRM = "tunemind_sugg_confirm"
        private const val UTTERANCE_FINAL_DISPATCH = "tunemind_dispatch"
    }
}
