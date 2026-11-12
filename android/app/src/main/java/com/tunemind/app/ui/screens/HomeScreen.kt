package com.tunemind.app.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.tunemind.app.domain.voice.AgentOrchestrator
import com.tunemind.app.domain.voice.AgentVoiceManager
import com.tunemind.app.ui.TuneMindViewModel

/**
 * HomeScreen delegates completely to PulseAgentScreen (Pillar 3: Synaptic Obsidian Agentic UI).
 * All old legacy Material screens and controls have been replaced by the Living Breathing Orb,
 * Circular Satiety Gauge, and AssemblyAI/Claude/ElevenLabs voice loop.
 */
@Composable
fun HomeScreen(
    vm: TuneMindViewModel,
    onOpenFatigue: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenWeeklyReport: () -> Unit = {},
    onOpenStudy: () -> Unit = {},
    onOpenMoments: () -> Unit = {},
    onOpenHearing: () -> Unit = {},
    onOpenAiDiscovery: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val homeState by vm.homeState.collectAsState()

    val agentVoiceManager = remember {
        AgentVoiceManager(
            context = context,
            getNowPlaying = { vm.homeState.value.nowPlaying },
            getRecentTracks = {
                vm.historyState.value.tracks.ifEmpty { vm.homeState.value.recentTracks }
            }
        )
    }
    val agentState by agentVoiceManager.agentState.collectAsState()
    var liveTranscript by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val orchestrator = remember(agentVoiceManager) {
        AgentOrchestrator(
            context = context,
            agentVoiceManager = agentVoiceManager,
            getNowPlaying = { vm.homeState.value.nowPlaying },
            scope = coroutineScope
        )
    }

    DisposableEffect(orchestrator) {
        orchestrator.start()
        onDispose {
            orchestrator.stop()
            agentVoiceManager.destroy()
        }
    }

    LaunchedEffect(agentVoiceManager) {
        agentVoiceManager.transcriptionFlow.collect { transcript ->
            liveTranscript = transcript
        }
    }

    // Telemetry from live Spotify/Media Session
    val nowPlaying = homeState.nowPlaying
    val trackTitle = nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "Deep Flow State"
    val artistName = nowPlaying?.artist?.takeIf { it.isNotBlank() } ?: "Autonomous Intelligence"
    val distinctPlays = homeState.recentTracks.firstOrNull()?.distinctAppearanceCount ?: 1
    val calculatedSatiety = minOf(95, maxOf(25, distinctPlays * 22))
    val energy = if (nowPlaying?.isPlaying == true) 0.85f else 0.45f

    // Render the new Synaptic Obsidian Adaptive Screen
    PulseAgentScreen(
        agentState = agentState,
        satietyPercentage = calculatedSatiety,
        currentTrackName = trackTitle,
        artistName = artistName,
        energy = energy,
        liveTranscript = liveTranscript,
        onOrbTapped = {
            agentVoiceManager.simulateWakeWordDetected()
        }
    )
}
