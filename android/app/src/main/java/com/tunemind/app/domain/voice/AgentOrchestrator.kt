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
    private var job: Job? = null

    fun start() {
        if (job != null && job?.isActive == true) return

        job = scope.launch {
            agentVoiceManager.finalTranscriptFlow.collect { transcript ->
                Log.d(TAG, "Received final transcript from on-device recognizer: $transcript")
                agentVoiceManager.processFinalTranscriptWithFallback(transcript)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
