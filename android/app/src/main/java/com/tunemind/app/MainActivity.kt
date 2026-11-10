package com.tunemind.app

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import com.tunemind.app.domain.voice.AgentOrchestrator
import com.tunemind.app.domain.voice.AgentState
import com.tunemind.app.domain.voice.AgentVoiceManager
import com.tunemind.app.navigation.TuneMindAppNavigation
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.ui.AppHolder
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.screens.PulseAgentScreen
import com.tunemind.app.ui.theme.TuneMindTheme

open class MainActivity : ComponentActivity() {

    private var startupError: Throwable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppHolder.context = applicationContext

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("MainActivity", "UNCAUGHT EXCEPTION on thread ${thread.name}: ${throwable.message}", throwable)
            startupError = throwable
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Safe rebind attempt without touching component enabled settings
        forceRebindListener(this)

        enableEdgeToEdge()
        try {
            setContent {
                var interceptedError by remember { mutableStateOf(startupError) }

                if (interceptedError != null) {
                    CrashDiagnosticsScreen(
                        error = interceptedError!!,
                        onReset = {
                            interceptedError = null
                            try {
                                com.tunemind.app.data.local.TuneMindDatabase.resetDatabase(applicationContext)
                            } catch (_: Exception) {}
                            recreate()
                        }
                    )
                } else {
                    val context = LocalContext.current
                    val vm: com.tunemind.app.ui.TuneMindViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                        factory = com.tunemind.app.ui.TuneMindViewModelFactory()
                    )
                    val homeState by vm.homeState.collectAsState()
                    val adaptiveState by vm.adaptiveThemeState.collectAsState()

                    // Voice Agent and Orchestrator
                    val agentVoiceManager = remember { AgentVoiceManager(context) }
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

                    // Mode switch: True Hands-Free Agentic UI (Synaptic Obsidian) vs Classic Switchboard
                    var showSwitchboard by remember { mutableStateOf(false) }

                    // Live Spotify / Audio Telemetry
                    val nowPlaying = homeState.nowPlaying
                    val trackTitle = nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "Deep Flow State"
                    val artistName = nowPlaying?.artist?.takeIf { it.isNotBlank() } ?: "Autonomous Intelligence"
                    val distinctPlays = homeState.recentTracks.firstOrNull()?.distinctAppearanceCount ?: 1
                    val calculatedSatiety = minOf(95, maxOf(25, distinctPlays * 22))
                    val energy = if (nowPlaying?.isPlaying == true) 0.85f else 0.45f

                    TuneMindTheme(adaptiveState = adaptiveState) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (!showSwitchboard) {
                                // Pillar 3: Synaptic Obsidian 3-Layer Adaptive UI
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

                                // Soft Switchboard quick toggle icon
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .statusBarsPadding()
                                        .padding(top = 16.dp, end = 20.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .bouncyClickable { showSwitchboard = true }
                                        .padding(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Dashboard,
                                        contentDescription = "Switchboard",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                // Full Navigation & Intel Switchboard
                                val navController = rememberNavController()
                                Box(modifier = Modifier.fillMaxSize()) {
                                    TuneMindAppNavigation(navController = navController)

                                    // Floating button to return to Pulse Agent Core
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .statusBarsPadding()
                                            .padding(top = 10.dp, start = 16.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                                            .bouncyClickable { showSwitchboard = false }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.GraphicEq,
                                                contentDescription = "Return to Agent Core",
                                                tint = Color(0xFF00E5FF),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                "Agent Core",
                                                color = Color(0xFF00E5FF),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Fatal content initialization error: ${t.message}", t)
            setContent {
                CrashDiagnosticsScreen(
                    error = t,
                    onReset = {
                        try {
                            com.tunemind.app.data.local.TuneMindDatabase.resetDatabase(applicationContext)
                        } catch (_: Exception) {}
                        recreate()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        forceRebindListener(this)
    }

    companion object {
        fun forceRebindListener(context: Context) {
            try {
                if (!NotificationAccess.isEnabled(context)) return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationListenerService.requestRebind(
                        ComponentName(context, TuneMindListenerService::class.java)
                    )
                }
            } catch (t: Throwable) {
                Log.w("MainActivity", "forceRebindListener notice: ${t.message}")
            }
        }
    }
}

@Composable
private fun CrashDiagnosticsScreen(error: Throwable, onReset: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(com.tunemind.app.ui.theme.DeepObsidianAmber.AppBackground)
            .padding(24.dp),
        color = com.tunemind.app.ui.theme.DeepObsidianAmber.AppBackground
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "TuneMind Diagnostics",
                color = com.tunemind.app.ui.theme.DeepObsidianAmber.Error,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "An unexpected condition occurred during startup:",
                color = com.tunemind.app.ui.theme.DeepObsidianAmber.TextPrimary,
                fontSize = 13.sp
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = com.tunemind.app.ui.theme.DeepObsidianAmber.CardBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, com.tunemind.app.ui.theme.DeepObsidianAmber.SurfaceBorder),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "${error::class.java.simpleName}: ${error.message}\n\n" +
                            error.stackTrace.take(12).joinToString("\n"),
                    color = com.tunemind.app.ui.theme.DeepObsidianAmber.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(14.dp)
                )
            }
            Button(
                onClick = onReset,
                colors = ButtonDefaults.buttonColors(
                    containerColor = com.tunemind.app.ui.theme.DeepObsidianAmber.PrimaryAccent,
                    contentColor = com.tunemind.app.ui.theme.DeepObsidianAmber.AppBackground
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Restart TuneMind", fontWeight = FontWeight.Bold)
            }
        }
    }
}
