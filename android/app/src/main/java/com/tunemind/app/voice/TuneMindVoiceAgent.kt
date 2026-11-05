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
import com.tunemind.app.domain.ai.GeminiReasoningEngine
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
 * Advanced autonomous conversational voice agent for TuneMind.
 * Features:
 * - Dynamic LLM / neural contextual reasoning (no static repetitive phrases)
 * - Multi-turn conversational flow with follow-ups ("change that song", "play from timeline")
 * - Proactive song suggestions with natural voice check ("Would you like this song?")
 * - Instant universal auto-play across Spotify, YouTube Music, Boomplay, Apple Music, Audiomack, and Phone Files.
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
     * Starts the autonomous conversational agent session.
     * Evaluates live telemetry (now playing, satiety, daypart, timeline history)
     * and formulates a dynamic, non-static greeting and proactive proposal.
     */
    fun startAgentSession() {
        scope.launch {
            _agentState.value = _agentState.value.copy(
                phase = AgentPhase.THINKING,
                isThinking = true,
                statusMessage = "Analyzing acoustic context..."
            )

            val greeting = generateDynamicOpeningPrompt()
            addAgentMessage(greeting)

            _agentState.value = _agentState.value.copy(
                phase = AgentPhase.PROMPTING_VOICE,
                isThinking = false,
                agentSpeech = greeting,
                userSpeech = "",
                statusMessage = "Agent speaking..."
            )
            speak(greeting, UTTERANCE_INITIAL_PROMPT)
        }
    }

    /**
     * Generates a context-aware, dynamic conversational opening.
     */
    private suspend fun generateDynamicOpeningPrompt(): String {
        val nowPlaying = vm.homeState.value.nowPlaying
        val isPlaying = nowPlaying?.isPlaying == true
        val title = nowPlaying?.title?.takeIf { it.isNotBlank() }
        val artist = nowPlaying?.artist?.takeIf { it.isNotBlank() }
        val satiety = calculateDynamicSatiety()
        val currentSlot = vm.currentTimeSlot.label
        val historyTracks = vm.historyState.value.tracks.filter { !it.title.isNullOrBlank() }
        val topAnchor = historyTracks.maxByOrNull { it.distinctAppearanceCount }
        val recentTimeline = historyTracks.firstOrNull { it.title != title }
        val targetPlayer = _agentState.value.targetProviderName

        // If Gemini API is configured, use cognitive reasoning
        if (GeminiReasoningEngine.isConfigured(context)) {
            val prompt = buildString {
                append("You are TuneMind Voice Agent, an audiophile cognitive music assistant. ")
                append("Generate a natural, one-sentence opening question for the user. ")
                if (isPlaying && title != null) {
                    append("User is currently listening to '$title' by '${artist ?: "Unknown"}' with satiety at $satiety%. ")
                    append("Offer to either switch to a high-dopamine track, explore timeline tracks, or change player. ")
                } else {
                    append("User is idle during $currentSlot. ")
                    if (topAnchor != null) append("Their top anchor is '${topAnchor.title}'. ")
                    if (recentTimeline != null) append("Recent timeline track is '${recentTimeline.title}'. ")
                    append("Offer a top anchor, timeline nostalgia, or fresh crate discoveries. ")
                }
                append("Target player is $targetPlayer. Keep it under 25 words, intelligent, warm, and natural.")
            }
            val geminiResult = GeminiReasoningEngine.callGemini(
                apiKey = GeminiReasoningEngine.getApiKey(context),
                prompt = prompt,
                temperature = 0.7f,
                maxTokens = 60
            )
            if (geminiResult.isSuccess && !geminiResult.getOrNull().isNullOrBlank()) {
                return geminiResult.getOrNull()!!.trim().removeSurrounding("\"")
            }
        }

        // Dynamic Neural Telemetry Engine (Diverse 12-variant prompt matrix)
        val seed = (System.currentTimeMillis() % 4).toInt()

        if (isPlaying && title != null) {
            return when {
                satiety >= 70 -> when (seed) {
                    0 -> "Your acoustic satiety on '$title' is at $satiety%. Want me to change that song to something fresh, or pull from your timeline?"
                    1 -> "Elevated satiety on '$title'. Should I switch to a peak dopamine track to reset your ears, or explore your timeline?"
                    2 -> "'$title' is nearing fatigue. Say 'change that song' or tell me what vibe you'd like in $targetPlayer."
                    else -> "Noticed high saturation on '$title'. Want me to skip to something higher tempo, or explore Crate AI?"
                }
                else -> when (seed) {
                    0 -> "Currently vibing to '$title' by ${artist ?: "artist"}. Want me to let it roll, change to another track, or pull from your timeline?"
                    1 -> "Playing '$title' in $targetPlayer. Say 'change that song', ask for peak dopamine, or name any track you want."
                    2 -> "'$title' has optimal freshness. Would you like me to switch this up, or queue your top anchor?"
                    else -> "In the groove with '$title'. Want something with more energy, an anchor from your timeline, or switch players?"
                }
            }
        } else {
            return when (seed) {
                0 -> {
                    if (topAnchor != null && recentTimeline != null) {
                        "Good $currentSlot. Should I spin your top anchor '${topAnchor.title}', play '${recentTimeline.title}' from your timeline, or find fresh crate gems?"
                    } else {
                        "Good $currentSlot. What should we stream in $targetPlayer? Say 'peak dopamine', 'play from timeline', or name a song."
                    }
                }
                1 -> {
                    if (recentTimeline != null) {
                        "Your timeline has memorable tracks like '${recentTimeline.title}'. Want me to play that, or explore high-dopamine crate discoveries?"
                    } else {
                        "TuneMind agent online. Say 'play high dopamine', 'play my timeline', or tell me which track you're craving."
                    }
                }
                2 -> "Acoustic calibration complete for $currentSlot. Want a peak dopamine anthem, a timeline favorite, or fresh AI crate music?"
                else -> "Ready when you are. Say 'play my most repeated song', 'play from timeline', or tell me to switch to YouTube Music or Boomplay."
            }
        }
    }

    /**
     * 1-Tap Trigger for specific Neuro-Acoustic Intent.
     */
    fun triggerIntentDirectly(intent: NeuroAcousticIntent, bypassVoicePrompt: Boolean = false) {
        val track = resolveTrackForIntent(intent)
        if (track == null) {
            val msg = "No matching tracks found in local memory. Exploring fresh crate."
            addAgentMessage(msg)
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
        lastSuggestedTrack = track
        val currentProvider = _agentState.value.targetProviderName

        if (bypassVoicePrompt) {
            executePlayback()
        } else {
            val confirmSpeech = "Found '${track.title}' by ${track.artist}. Auto-playing in $currentProvider, or say change to YouTube Music, Boomplay, or Phone Files."
            addAgentMessage(confirmSpeech)
            _agentState.value = _agentState.value.copy(
                phase = AgentPhase.PROMPTING_PLAYER,
                agentSpeech = confirmSpeech,
                resolvedTrack = track,
                statusMessage = "Confirming player routing..."
            )
            speak(confirmSpeech, UTTERANCE_PLAYER_PROMPT)
        }
    }

    /**
     * Executes the "Change that song" flow immediately:
     * Picks a fresh track, immediately auto-plays it, and asks for follow-up feedback.
     */
    fun changeSongImmediately() {
        val nextTrack = resolveAlternativeTrack()
        pendingTrack = nextTrack
        lastSuggestedTrack = nextTrack
        val currentProvider = _agentState.value.targetProviderName

        executePlayback()

        val response = "Changing it right now! Auto-playing '${nextTrack.title}' by ${nextTrack.artist} in $currentProvider. Would you like this song, or want something different?"
        addAgentMessage(response)
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.WAITING_CONFIRMATION,
            agentSpeech = response,
            resolvedTrack = nextTrack,
            pendingConfirmationTrack = nextTrack,
            statusMessage = "Now playing: ${nextTrack.title}"
        )
        speak(response, UTTERANCE_SUGGESTION_CONFIRM)
    }

    /**
     * Plays a track from the user's Timeline screen archive.
     */
    fun playFromTimeline(requestedTitleOrArtist: String? = null) {
        val historyTracks = vm.historyState.value.tracks.filter { !it.title.isNullOrBlank() }
        val nowPlaying = vm.homeState.value.nowPlaying?.title

        val matched = if (!requestedTitleOrArtist.isNullOrBlank()) {
            historyTracks.firstOrNull {
                it.title?.contains(requestedTitleOrArtist, ignoreCase = true) == true ||
                it.artist?.contains(requestedTitleOrArtist, ignoreCase = true) == true
            }
        } else {
            // Find most memorable timeline track not currently playing
            historyTracks.filter { it.title != nowPlaying }
                .maxByOrNull { it.distinctAppearanceCount }
                ?: historyTracks.firstOrNull()
        }

        val track = if (matched != null) {
            RecommendedTrack(
                id = matched.entryId,
                title = matched.title ?: "Timeline Favorite",
                artist = matched.artist ?: "Timeline Artist",
                album = matched.album,
                reason = "Memorable track from your timeline archive (${matched.distinctAppearanceCount} previous plays)",
                confidence = 0.94f,
                category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                suggestedProviderId = _agentState.value.targetProviderId
            )
        } else {
            resolveTrackForIntent(NeuroAcousticIntent.DOPAMINE_PEAK)
        }

        pendingTrack = track
        lastSuggestedTrack = track
        val currentProvider = _agentState.value.targetProviderName

        executePlayback()

        val response = "From your timeline, I picked '${track.title}' by ${track.artist}. Auto-playing in $currentProvider now. Would you like this, or want something else?"
        addAgentMessage(response)
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.WAITING_CONFIRMATION,
            agentSpeech = response,
            resolvedTrack = track,
            pendingConfirmationTrack = track,
            statusMessage = "Timeline track: ${track.title}"
        )
        speak(response, UTTERANCE_SUGGESTION_CONFIRM)
    }

    /**
     * Proactively suggests a song and asks: "Would you like this song?"
     */
    fun suggestTrackWithConfirmation(intent: NeuroAcousticIntent = NeuroAcousticIntent.AI_CRATE) {
        val track = resolveTrackForIntent(intent)
        pendingTrack = track
        lastSuggestedTrack = track
        val currentProvider = _agentState.value.targetProviderName

        val speech = "I recommend '${track.title}' by ${track.artist}. It's calibrated for low auditory fatigue. Would you like this song in $currentProvider?"
        addAgentMessage(speech)
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.WAITING_CONFIRMATION,
            agentSpeech = speech,
            resolvedTrack = track,
            pendingConfirmationTrack = track,
            statusMessage = "Suggested: ${track.title}"
        )
        speak(speech, UTTERANCE_SUGGESTION_CONFIRM)
    }

    private fun speak(text: String, utteranceId: String?) {
        if (isTtsInitialized && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId ?: "tunemind_general")
        } else {
            mainHandler.postDelayed({
                when (utteranceId) {
                    UTTERANCE_INITIAL_PROMPT, UTTERANCE_FOLLOW_UP_PROMPT -> startListeningForIntent()
                    UTTERANCE_SUGGESTION_CONFIRM -> startListeningForConfirmation()
                    UTTERANCE_PLAYER_PROMPT -> startListeningForPlayerChoice()
                    UTTERANCE_FINAL_DISPATCH -> executePlayback()
                }
            }, 1200)
        }
    }

    private fun startListeningForIntent() {
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.LISTENING_INTENT,
            statusMessage = "Listening for your command or vibe..."
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
            handleSpokenConfirmation(spokenText)
        }
    }

    private fun startListeningForPlayerChoice() {
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.LISTENING_PLAYER,
            statusMessage = "Listening for player choice (e.g. 'Spotify', 'YouTube Music', 'Play')..."
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
                        if (_agentState.value.phase == AgentPhase.LISTENING_PLAYER && pendingTrack != null) {
                            executePlayback()
                        } else {
                            _agentState.value = _agentState.value.copy(
                                phase = AgentPhase.IDLE,
                                statusMessage = "Tap Mic to talk with TuneMind Agent"
                            )
                        }
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
     * Parses spoken input with full conversational multi-turn understanding.
     */
    private fun handleSpokenIntent(text: String) {
        val lower = text.lowercase(Locale.getDefault())

        // 1. Check if user commanded player change
        val playerSwitched = detectAndApplyPlayerMention(lower)

        // 2. IMMEDIATE SONG CHANGE / SKIP COMMAND
        if (lower.contains("change") || lower.contains("skip") || lower.contains("next") ||
            lower.contains("don't like") || lower.contains("different") || lower.contains("another") ||
            lower.contains("switch song") || lower.contains("stop this")
        ) {
            changeSongImmediately()
            return
        }

        // 3. TIMELINE SONGS COMMAND
        if (lower.contains("timeline") || lower.contains("history") || lower.contains("earlier") ||
            lower.contains("yesterday") || lower.contains("past")
        ) {
            val queryParam = extractSongMention(lower, listOf("timeline", "history", "earlier", "play", "song"))
            playFromTimeline(queryParam)
            return
        }

        // 4. DIRECT SONG TITLE / ARTIST QUERY
        if (lower.startsWith("play ") || lower.contains("play song ") || lower.contains("stream ")) {
            val songQuery = lower.removePrefix("play ").removePrefix("stream ").removePrefix("song ").trim()
            if (songQuery.isNotBlank() && !isGenericIntentWord(songQuery)) {
                handleDirectSongRequest(songQuery)
                return
            }
        }

        // 5. PROACTIVE RECOMMENDATION REQUEST
        if (lower.contains("suggest") || lower.contains("recommend") || lower.contains("what should i") ||
            lower.contains("what do you think")
        ) {
            suggestTrackWithConfirmation(NeuroAcousticIntent.AI_CRATE)
            return
        }

        // 6. NEURO-ACOUSTIC INTENTS
        val intent = when {
            lower.contains("dopamine") || lower.contains("high") || lower.contains("energy") || lower.contains("upbeat") ->
                NeuroAcousticIntent.DOPAMINE_PEAK
            lower.contains("repeat") || lower.contains("anchor") || lower.contains("favorite") || lower.contains("most played") ->
                NeuroAcousticIntent.AUDITORY_ANCHOR
            lower.contains("time") || lower.contains("day") || lower.contains("morning") || lower.contains("afternoon") ||
            lower.contains("evening") || lower.contains("night") || lower.contains("circadian") ->
                NeuroAcousticIntent.CIRCADIAN_DAYPART
            lower.contains("crate") || lower.contains("discovery") || lower.contains("new") ->
                NeuroAcousticIntent.AI_CRATE
            else -> NeuroAcousticIntent.DOPAMINE_PEAK
        }

        if (playerSwitched) {
            triggerIntentDirectly(intent, bypassVoicePrompt = true)
        } else {
            triggerIntentDirectly(intent, bypassVoicePrompt = false)
        }
    }

    /**
     * Handles follow-up confirmation ("Yes", "Sure", "Play it" vs "No", "Change it").
     */
    private fun handleSpokenConfirmation(text: String) {
        val lower = text.lowercase(Locale.getDefault())

        if (lower.contains("yes") || lower.contains("sure") || lower.contains("play") ||
            lower.contains("yeah") || lower.contains("like it") || lower.contains("keep") ||
            lower.contains("ok") || lower.contains("okay") || lower.contains("great")
        ) {
            val track = _agentState.value.pendingConfirmationTrack ?: pendingTrack
            if (track != null) {
                pendingTrack = track
                val msg = "Playing '${track.title}' in ${_agentState.value.targetProviderName} right now!"
                addAgentMessage(msg)
                _agentState.value = _agentState.value.copy(
                    phase = AgentPhase.EXECUTING,
                    agentSpeech = msg,
                    statusMessage = "Playing: ${track.title}"
                )
                speak(msg, UTTERANCE_FINAL_DISPATCH)
            } else {
                executePlayback()
            }
        } else if (lower.contains("no") || lower.contains("not") || lower.contains("change") ||
            lower.contains("skip") || lower.contains("another") || lower.contains("different")
        ) {
            changeSongImmediately()
        } else if (lower.contains("timeline")) {
            playFromTimeline()
        } else {
            // Treat as fresh intent or player choice
            handleSpokenIntent(text)
        }
    }

    private fun handleDirectSongRequest(query: String) {
        val historyTracks = vm.historyState.value.tracks
        val matchedHistory = historyTracks.firstOrNull {
            it.title?.contains(query, ignoreCase = true) == true ||
            it.artist?.contains(query, ignoreCase = true) == true
        }

        val track = if (matchedHistory != null) {
            RecommendedTrack(
                id = matchedHistory.entryId,
                title = matchedHistory.title ?: query,
                artist = matchedHistory.artist ?: "TuneMind Artist",
                album = matchedHistory.album,
                reason = "Requested track found in your listening history",
                confidence = 0.98f,
                category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                suggestedProviderId = _agentState.value.targetProviderId
            )
        } else {
            RecommendedTrack(
                id = "direct_${System.currentTimeMillis()}",
                title = query.replaceFirstChar { it.uppercase() },
                artist = "Voice Requested",
                reason = "Requested directly via TuneMind Voice Agent",
                confidence = 0.95f,
                category = RecommendationCategory.EXPLORATION_DISCOVERY,
                suggestedProviderId = _agentState.value.targetProviderId
            )
        }

        pendingTrack = track
        lastSuggestedTrack = track
        val targetName = _agentState.value.targetProviderName

        val phrase = "Auto-playing '${track.title}' in $targetName."
        addAgentMessage(phrase)
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.EXECUTING,
            agentSpeech = phrase,
            resolvedTrack = track,
            statusMessage = "Playing: ${track.title}"
        )
        speak(phrase, UTTERANCE_FINAL_DISPATCH)
    }

    private fun isGenericIntentWord(word: String): Boolean {
        val list = listOf("dopamine", "anchor", "crate", "timeline", "daypart", "something", "music", "song")
        return list.any { word.contains(it) }
    }

    private fun extractSongMention(text: String, removeWords: List<String>): String {
        var res = text
        for (w in removeWords) {
            res = res.replace(w, "")
        }
        return res.trim()
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
            "Auto-playing '${track.title}' in $targetName."
        } else {
            "Opening $targetName."
        }

        addAgentMessage(finalPhrase)
        _agentState.value = _agentState.value.copy(
            phase = AgentPhase.EXECUTING,
            agentSpeech = finalPhrase,
            statusMessage = "Dispatching playback..."
        )
        speak(finalPhrase, UTTERANCE_FINAL_DISPATCH)
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
                installed.firstOrNull { text.contains(it.displayName.lowercase()) }?.let {
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

    /**
     * Resolves an alternative track (distinct from current playing and last suggested)
     * for immediate "change that song" switches.
     */
    private fun resolveAlternativeTrack(): RecommendedTrack {
        val nowPlaying = vm.homeState.value.nowPlaying?.title
        val crate = vm.aiDiscoveryCrate.value.filter { !it.isDismissed && it.title != nowPlaying && it.title != lastSuggestedTrack?.title }
        val historyTracks = vm.historyState.value.tracks.filter { !it.title.isNullOrBlank() && it.title != nowPlaying && it.title != lastSuggestedTrack?.title }

        if (crate.isNotEmpty()) {
            val item = crate.first()
            vm.markRecommendationPlayed(item.id)
            return RecommendedTrack(
                id = item.id,
                title = item.title,
                artist = item.artist,
                album = item.album,
                reason = "Alternative high-receptivity discovery",
                confidence = item.confidence,
                category = RecommendationCategory.EXPLORATION_DISCOVERY,
                suggestedProviderId = _agentState.value.targetProviderId
            )
        } else if (historyTracks.isNotEmpty()) {
            val h = historyTracks.random()
            return RecommendedTrack(
                id = h.entryId,
                title = h.title ?: "Fresh Selection",
                artist = h.artist ?: "TuneMind Artist",
                album = h.album,
                reason = "Fresh track from your listening memory",
                confidence = 0.90f,
                category = RecommendationCategory.FAMILIAR_RECEPTIVE,
                suggestedProviderId = _agentState.value.targetProviderId
            )
        }

        return fallbackTrack("Dynamic Neuro-Acoustic Switch")
    }

    /**
     * Resolves tracks based on intent from Room entities & memory.
     */
    fun resolveTrackForIntent(intent: NeuroAcousticIntent): RecommendedTrack {
        val historyTracks = vm.historyState.value.tracks.filter { !it.title.isNullOrBlank() }
        val crate = vm.aiDiscoveryCrate.value.filter { !it.isDismissed }

        return when (intent) {
            NeuroAcousticIntent.DOPAMINE_PEAK -> {
                val best = historyTracks.firstOrNull()
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
                val topAnchor = historyTracks.maxByOrNull { it.distinctAppearanceCount }
                if (topAnchor != null) {
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
                val topCrate = crate.firstOrNull { !it.wasPlayed } ?: crate.firstOrNull()
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
                val recent = historyTracks.take(8).lastOrNull() ?: historyTracks.firstOrNull()
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
        if (current.size > 8) current.removeAt(0)
        _agentState.value = _agentState.value.copy(dialogueHistory = current)
    }

    private fun addUserMessage(msg: String) {
        val current = _agentState.value.dialogueHistory.toMutableList()
        current.add(AgentChatMessage(sender = "user", message = msg))
        if (current.size > 8) current.removeAt(0)
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
        private const val UTTERANCE_PLAYER_PROMPT = "tunemind_player_prompt"
        private const val UTTERANCE_FINAL_DISPATCH = "tunemind_dispatch"
    }
}
