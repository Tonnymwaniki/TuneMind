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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.voice.AgentOrchestrator
import com.tunemind.app.domain.voice.AgentVoiceManager
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.ui.AppHolder
import com.tunemind.app.ui.onboarding.OnboardingScreen
import com.tunemind.app.ui.screens.PulseAgentScreen
import com.tunemind.app.ui.theme.TuneMindTheme
import com.tunemind.app.util.PermissionUtils

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

                    // Check onboarding permissions
                    var hasAllPermissions by remember {
                        mutableStateOf(
                            PermissionUtils.hasMicrophonePermission(context) &&
                            PermissionUtils.hasNotificationListenerPermission(context) &&
                            PermissionUtils.isIgnoringBatteryOptimizations(context)
                        )
                    }

                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                hasAllPermissions = PermissionUtils.hasMicrophonePermission(context) &&
                                                   PermissionUtils.hasNotificationListenerPermission(context) &&
                                                   PermissionUtils.isIgnoringBatteryOptimizations(context)
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }

                    // Voice Agent and Orchestrator
                    val agentVoiceManager = remember {
                        AgentVoiceManager(
                            context = context,
                            getNowPlaying = { vm.homeState.value.nowPlaying },
                            getRecentTracks = { vm.homeState.value.recentTracks }
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

                    DisposableEffect(orchestrator, hasAllPermissions) {
                        if (hasAllPermissions) {
                            orchestrator.start()
                        }
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

                    // Live Spotify / Audio Telemetry
                    val nowPlaying = homeState.nowPlaying
                    val trackTitle = nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "Deep Flow State"
                    val artistName = nowPlaying?.artist?.takeIf { it.isNotBlank() } ?: "Autonomous Intelligence"
                    val distinctPlays = homeState.recentTracks.firstOrNull()?.distinctAppearanceCount ?: 1
                    val calculatedSatiety = minOf(95, maxOf(25, distinctPlays * 22))
                    val energy = if (nowPlaying?.isPlaying == true) 0.85f else 0.45f

                    TuneMindTheme(adaptiveState = adaptiveState) {
                        if (!hasAllPermissions) {
                            // Step through required permissions
                            OnboardingScreen(
                                onComplete = {
                                    hasAllPermissions = true
                                }
                            )
                        } else {
                            // Pure Agent-First Synaptic Obsidian Screen
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
            .background(Color(0xFF06050A))
            .padding(24.dp),
        color = Color(0xFF06050A)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "TuneMind Diagnostics",
                color = Color(0xFFFF2A55),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "An unexpected condition occurred during startup:",
                color = Color.White,
                fontSize = 13.sp
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1E1014),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "${error::class.java.simpleName}: ${error.message}\n\n" +
                            error.stackTrace.take(12).joinToString("\n"),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(14.dp)
                )
            }
            Button(
                onClick = onReset,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Restart TuneMind", fontWeight = FontWeight.Bold)
            }
        }
    }
}
