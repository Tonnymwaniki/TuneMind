package com.tunemind.app.domain.voice

import android.content.Context
import android.util.Log
import com.tunemind.app.BuildConfig
import com.tunemind.app.domain.ai.ReasoningApiClient
import com.tunemind.app.domain.execution.TuneMindExecutionEngine
import com.tunemind.app.ui.NowPlayingObservation
import kotlinx.coroutines.*

/**
 * AgentOrchestrator closes the execution loop for TuneMind's Voice Agent:
 * 1. Collects speech transcriptionFlow from AgentVoiceManager.
 * 2. Transitions agent to THINKING state.
 * 3. Dispatches transcript + active NowPlaying media telemetry to ReasoningApiClient.
 * 4. Sets agent to SPEAKING state and triggers TuneMindExecutionEngine to duck/pause, speak TTS, and deep-link.
 * 5. Returns to IDLE and releases audio ducking.
 */
class AgentOrchestrator(
    private val context: Context,
    private val agentVoiceManager: AgentVoiceManager,
    private val getNowPlaying: () -> NowPlayingObservation?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    private val TAG = "AgentLoop"
    private val reasoningApiClient: ReasoningApiClient by lazy {
        val baseUrl = BuildConfig.TUNEMIND_BASE_URL.trim().trimEnd('/').ifBlank { "http://10.0.2.2:3000" }
        ReasoningApiClient(baseUrl)
    }
    private val tuneMindExecutionEngine: TuneMindExecutionEngine by lazy {
        TuneMindExecutionEngine(context)
    }
    private var job: Job? = null

    fun start() {
        if (job != null && job?.isActive == true) return

        job = scope.launch {
            agentVoiceManager.finalTranscriptFlow.collect { transcript ->
                Log.d(TAG, "Received final transcript: $transcript")

                // Ensure agent UI is in THINKING state
                agentVoiceManager.setThinkingState()

                // 1. Send transcript to Claude Backend with actual playback observation metadata
                val nowPlaying = getNowPlaying()
                val currentTrackTitle = nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "Unknown Track"
                val currentTrackArtist = nowPlaying?.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                val sourcePlayer = nowPlaying?.sourcePackage?.takeIf { it.isNotBlank() } ?: "com.spotify.music"

                Log.d(TAG, "Evaluating with track: '$currentTrackTitle' by '$currentTrackArtist' ($sourcePlayer)")

                val aiResponse = reasoningApiClient.evaluateSatiety(
                    transcript = transcript,
                    currentTrackTitle = currentTrackTitle,
                    currentTrackArtist = currentTrackArtist,
                    sourcePlayer = sourcePlayer
                )

                if (aiResponse != null) {
                    // 2. Set UI to SPEAKING
                    agentVoiceManager.setSpeakingState()

                    // 3. Execute OS Commands (Pause music -> Speak -> Release focus -> Launch new track)
                    tuneMindExecutionEngine.executeBackendCommand(
                        response = aiResponse,
                        onBeforeLaunch = {
                            agentVoiceManager.abandonAssistantAudioFocusOnly()
                        },
                        onFinished = {
                            agentVoiceManager.releaseAudioDucking()
                            agentVoiceManager.resumeWakeWordListening()
                        }
                    )
                } else {
                    Log.e(TAG, "AI Reasoning failed.")
                    agentVoiceManager.releaseAudioDucking()
                    agentVoiceManager.resumeWakeWordListening()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        tuneMindExecutionEngine.shutdown()
    }
}
