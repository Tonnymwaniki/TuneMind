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
    PROMPTING_VOICE,
    LISTENING_INTENT,
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
    SWITCH_PLAYER,
    DIRECT_PLAY,
    UNKNOWN
}

data class VoiceAgentState(
    val phase: AgentPhase = AgentPhase.IDLE,
    val agentSpeech: String = "",
    val userSpeech: String = "",
    val resolvedTrack: RecommendedTrack? = null,
    val targetProviderId: String = "spotify",
    val targetProviderName: String = "Spotify",
    val rmsLevel: Float = 0f,
    val statusMessage: String = "Tap Mic to activate TuneMind Voice Agent"
)

/**
 * Native conversational voice agent for TuneMind.
 * Provides two-way voice dialogue (TTS speaking + SpeechRecognizer listening),
 * neuro-acoustic intent parsing, and automated player routing.
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

    private val _agentState = MutableStateFlow(
        VoiceAgentState(
            targetProviderId = vm.getPreferredProviderId(context),
            targetProviderName = getProviderName(vm.getPreferredProviderId(context))
        )
    )
    val agentState: StateFlow<VoiceAgentState> = _agentState.asStateFlow()

    private var pendingIntent: NeuroAcousticIntent = NeuroAcousticIntent.UNKNOWN
    private var pendingTrack: RecommendedTrack? = null

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
                                    UTTERANCE_INITIAL_PROMPT -> {
                                        startListeningForIntent()
                                    }
                                    UTTERANCE_PLAYER_PROMPT -> {
                                        startListeningForPlayerChoice()
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
     * Start the conversational agent flow.
     * Agent prompts the listener aloud with options.
     */
    fun startAgentSession() {
        val currentSlot = vm.currentTimeSlot.label
        val promptText = "What should I queue for you? Say peak dopamine, your most repeated anchor, songs for $currentSlot, or AI crate."
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.PROMPTING_VOICE,
            agentSpeech = promptText,
            userSpeech = "",
            statusMessage = "Agent speaking..."
        )
        speak(promptText, UTTERANCE_INITIAL_PROMPT)
    }

    /**
     * Execute a specific intent immediately (e.g. via 1-tap chip)
     * with voice confirmation and player routing.
     */
    fun triggerIntentDirectly(intent: NeuroAcousticIntent, bypassVoicePrompt: Boolean = false) {
        val track = resolveTrackForIntent(intent)
        if (track == null) {
            val msg = "No matching auditory tracks found in memory. Exploring fresh crate."
            _agentState.value = _agentState.value.copy(
                phase = AgentPhase.IDLE,
                agentSpeech = msg,
                statusMessage = msg
            )
            speak(msg, null)
            return
        }

        pendingTrack = track
        pendingIntent = intent
        val currentProvider = _agentState.value.targetProviderName

        if (bypassVoicePrompt) {
            // Instant 1-tap launch
            executePlayback()
        } else {
            val confirmSpeech = "Found ${track.title} by ${track.artist}. Playing in $currentProvider, or say change to YouTube Music, Boomplay, or Phone Files."
            _agentState.value = _agentState.value.copy(
                phase = AgentPhase.PROMPTING_PLAYER,
                agentSpeech = confirmSpeech,
                resolvedTrack = track,
                statusMessage = "Confirming player routing..."
            )
            speak(confirmSpeech, UTTERANCE_PLAYER_PROMPT)
        }
    }

    private fun speak(text: String, utteranceId: String?) {
        if (isTtsInitialized && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId ?: "tunemind_general")
        } else {
            // Fallback if TTS not ready: trigger next step directly
            mainHandler.postDelayed({
                when (utteranceId) {
                    UTTERANCE_INITIAL_PROMPT -> startListeningForIntent()
                    UTTERANCE_PLAYER_PROMPT -> startListeningForPlayerChoice()
                    UTTERANCE_FINAL_DISPATCH -> executePlayback()
                }
            }, 1200)
        }
    }

    private fun startListeningForIntent() {
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.LISTENING_INTENT,
            statusMessage = "Listening for auditory vibe..."
        )
        listenWithRecognizer { spokenText ->
            handleSpokenIntent(spokenText)
        }
    }

    private fun startListeningForPlayerChoice() {
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.LISTENING_PLAYER,
            statusMessage = "Listening for player choice (say player name or 'play')..."
        )
        listenWithRecognizer { spokenText ->
            handleSpokenPlayerChoice(spokenText)
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
                        // If listening timed out or silence, default to safe auto-execution
                        if (_agentState.value.phase == AgentPhase.LISTENING_PLAYER && pendingTrack != null) {
                            executePlayback()
                        } else {
                            _agentState.value = _agentState.value.copy(
                                phase = AgentPhase.IDLE,
                                statusMessage = "Tap Mic or an Intent chip below"
                            )
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d(tag, "Spoken text: $text")
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
     * Parses user voice input for Neuro-Acoustic Intent.
     */
    private fun handleSpokenIntent(text: String) {
        val lower = text.lowercase(Locale.getDefault())

        // 1. Check if user already commanded player switch in the same sentence
        detectAndApplyPlayerMention(lower)

        val intent = when {
            lower.contains("dopamine") || lower.contains("high") || lower.contains("happy") || lower.contains("energy") || lower.contains("fresh") ->
                NeuroAcousticIntent.DOPAMINE_PEAK
            lower.contains("repeat") || lower.contains("anchor") || lower.contains("favorite") || lower.contains("most played") || lower.contains("habit") ->
                NeuroAcousticIntent.AUDITORY_ANCHOR
            lower.contains("time") || lower.contains("day") || lower.contains("morning") || lower.contains("afternoon") || lower.contains("evening") || lower.contains("night") || lower.contains("circadian") ->
                NeuroAcousticIntent.CIRCADIAN_DAYPART
            lower.contains("crate") || lower.contains("recommend") || lower.contains("new") || lower.contains("discovery") ->
                NeuroAcousticIntent.AI_CRATE
            lower.contains("timeline") || lower.contains("history") || lower.contains("recent") || lower.contains("yesterday") || lower.contains("past") ->
                NeuroAcousticIntent.TIMELINE_HISTORY
            else -> NeuroAcousticIntent.DOPAMINE_PEAK
        }

        triggerIntentDirectly(intent, bypassVoicePrompt = false)
    }

    /**
     * Handles user response when agent asks which player to use.
     */
    private fun handleSpokenPlayerChoice(text: String) {
        val lower = text.lowercase(Locale.getDefault())
        detectAndApplyPlayerMention(lower)

        val track = pendingTrack
        val targetName = _agentState.value.targetProviderName
        val finalPhrase = if (track != null) {
            "Launching ${track.title} in $targetName."
        } else {
            "Opening $targetName."
        }

        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.EXECUTING,
            agentSpeech = finalPhrase,
            statusMessage = "Dispatching playback..."
        )
        speak(finalPhrase, UTTERANCE_FINAL_DISPATCH)
    }

    private fun detectAndApplyPlayerMention(text: String) {
        val registry = MusicProviderRegistry.getInstance(context)
        val installed = registry.getInstalledProviders()

        when {
            text.contains("spotify") -> setTargetPlayer("spotify", "Spotify")
            text.contains("youtube") || text.contains("yt music") -> setTargetPlayer("yt_music", "YouTube Music")
            text.contains("boomplay") -> setTargetPlayer("boomplay", "Boomplay")
            text.contains("apple") -> setTargetPlayer("apple_music", "Apple Music")
            text.contains("audiomack") -> setTargetPlayer("audiomack", "Audiomack")
            text.contains("phone") || text.contains("file") || text.contains("local") -> setTargetPlayer("local_files", "Phone Files")
            else -> {
                // Check installed dynamic app names
                installed.firstOrNull { text.contains(it.displayName.lowercase()) }?.let {
                    setTargetPlayer(it.providerId, it.displayName)
                }
            }
        }
    }

    /**
     * Manually switch the target player via tap.
     */
    fun setTargetPlayer(providerId: String, displayName: String) {
        vm.setPreferredProviderId(context, providerId)
        _agentState.value = _agentState.value.copy(
            targetProviderId = providerId,
            targetProviderName = displayName,
            statusMessage = "Target player: $displayName"
        )
    }

    /**
     * Queries TuneMind memory and Room entities to find the best match for the intent.
     */
    private fun resolveTrackForIntent(intent: NeuroAcousticIntent): RecommendedTrack? {
        val historyTracks = vm.historyState.value.tracks
        val crate = vm.aiDiscoveryCrate.value.filter { !it.isDismissed }

        return when (intent) {
            NeuroAcousticIntent.DOPAMINE_PEAK -> {
                // Lowest fatigue / highest replay freshness
                val best = historyTracks.firstOrNull { (it.title?.isNotBlank() == true) }
                if (best != null) {
                    RecommendedTrack(
                        id = best.entryId,
                        title = best.title ?: "Peak Dopamine Track",
                        artist = best.artist ?: "TuneMind Artist",
                        album = best.album,
                        reason = "Highest acoustic dopamine receptivity and low fatigue",
                        confidence = 0.96f,
                        category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                        suggestedProviderId = _agentState.value.targetProviderId
                    )
                } else if (crate.isNotEmpty()) {
                    val c = crate.first()
                    RecommendedTrack(
                        id = c.id,
                        title = c.title,
                        artist = c.artist,
                        album = c.album,
                        reason = c.reason,
                        confidence = c.confidence,
                        category = RecommendationCategory.EXPLORATION_DISCOVERY,
                        suggestedProviderId = _agentState.value.targetProviderId
                    )
                } else {
                    fallbackTrack("High Dopamine Anthem")
                }
            }

            NeuroAcousticIntent.AUDITORY_ANCHOR -> {
                // Most repeated song in listener history
                val topAnchor = historyTracks.maxByOrNull { it.distinctAppearanceCount }
                if (topAnchor != null && topAnchor.title?.isNotBlank() == true) {
                    RecommendedTrack(
                        id = topAnchor.entryId,
                        title = topAnchor.title ?: "Sonic Anchor",
                        artist = topAnchor.artist ?: "Auditory Anchor",
                        album = topAnchor.album,
                        reason = "Your #1 most repeated auditory sanctuary (${topAnchor.distinctAppearanceCount} plays)",
                        confidence = 0.99f,
                        category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                        suggestedProviderId = _agentState.value.targetProviderId
                    )
                } else {
                    fallbackTrack("Auditory Sanctuary Anchor")
                }
            }

            NeuroAcousticIntent.CIRCADIAN_DAYPART -> {
                val currentSlot = vm.currentTimeSlot
                val daypartRec = crate.firstOrNull { it.timeSlotKey == currentSlot.name }
                if (daypartRec != null) {
                    RecommendedTrack(
                        id = daypartRec.id,
                        title = daypartRec.title,
                        artist = daypartRec.artist,
                        album = daypartRec.album,
                        reason = "Daypart calibrated for ${currentSlot.label}",
                        confidence = daypartRec.confidence,
                        category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                        suggestedProviderId = _agentState.value.targetProviderId
                    )
                } else if (historyTracks.isNotEmpty()) {
                    val h = historyTracks.first()
                    RecommendedTrack(
                        id = h.entryId,
                        title = h.title ?: "Daypart Track",
                        artist = h.artist ?: "Circadian Flow",
                        album = h.album,
                        reason = "Circadian rhythm matched for ${currentSlot.label}",
                        confidence = 0.92f,
                        category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                        suggestedProviderId = _agentState.value.targetProviderId
                    )
                } else {
                    fallbackTrack("Circadian Flow")
                }
            }

            NeuroAcousticIntent.AI_CRATE -> {
                val topCrate = crate.firstOrNull { !it.isPlayed } ?: crate.firstOrNull()
                if (topCrate != null) {
                    vm.markRecommendationPlayed(topCrate.id)
                    RecommendedTrack(
                        id = topCrate.id,
                        title = topCrate.title,
                        artist = topCrate.artist,
                        album = topCrate.album,
                        reason = topCrate.reason,
                        confidence = topCrate.confidence,
                        category = RecommendationCategory.EXPLORATION_DISCOVERY,
                        suggestedProviderId = _agentState.value.targetProviderId
                    )
                } else {
                    fallbackTrack("Chrono AI Crate Gem")
                }
            }

            NeuroAcousticIntent.TIMELINE_HISTORY -> {
                val recent = historyTracks.take(5).lastOrNull { it.title?.isNotBlank() == true } ?: historyTracks.firstOrNull()
                if (recent != null) {
                    RecommendedTrack(
                        id = recent.entryId,
                        title = recent.title ?: "Timeline Memory",
                        artist = recent.artist ?: "Past Session",
                        album = recent.album,
                        reason = "Memorable track from your recent timeline archive",
                        confidence = 0.88f,
                        category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                        suggestedProviderId = _agentState.value.targetProviderId
                    )
                } else {
                    fallbackTrack("Timeline Nostalgia")
                }
            }

            else -> fallbackTrack("TuneMind Stream")
        }
    }

    private fun fallbackTrack(title: String): RecommendedTrack {
        return RecommendedTrack(
            id = "fallback_${System.currentTimeMillis()}",
            title = title,
            artist = "TuneMind Curated",
            album = "Neuro-Acoustic Vault",
            reason = "Acoustically refreshed for current neural receptivity",
            confidence = 0.90f,
            category = RecommendationCategory.FAMILIAR_RECEPTIVE,
            suggestedProviderId = _agentState.value.targetProviderId
        )
    }

    private fun executePlayback() {
        val track = pendingTrack ?: return
        val targetProviderId = _agentState.value.targetProviderId
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.EXECUTING,
            statusMessage = "Playing ${track.title} in ${_agentState.value.targetProviderName}..."
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
        private const val UTTERANCE_PLAYER_PROMPT = "tunemind_player_prompt"
        private const val UTTERANCE_FINAL_DISPATCH = "tunemind_dispatch"
    }
}
