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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.tunemind.app.ui.components.FloatingGlassDock
import com.tunemind.app.ui.onboarding.OnboardingScreen
import com.tunemind.app.ui.screens.DopamineVaultScreen
import com.tunemind.app.ui.screens.PulseAgentScreen
import com.tunemind.app.ui.screens.RhythmDnaScreen
import com.tunemind.app.ui.theme.TuneMindTheme
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.launch

open class MainActivity : ComponentActivity() {

    private var startupError: Throwable? = null

    @OptIn(ExperimentalFoundationApi::class)
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

        // Handle Spotify OAuth 2.0 Authorization Code + PKCE callback if launched via tunemind://spotify-callback
        intent?.let { incoming ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.tunemind.app.data.ingestion.SpotifyOAuthRepository.getInstance(applicationContext)
                    .handleAuthRedirectIntent(incoming)
            }
        }

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
                    val historyState by vm.historyState.collectAsState()
                    val vaultState by vm.vaultState.collectAsState()
                    val hearingInsights by vm.hearingInsightsState.collectAsState()
                    val adaptiveState by vm.adaptiveThemeState.collectAsState()
                    val themePreferences by vm.themePreferences.collectAsState()

                    val playerPrefs = remember {
                        context.getSharedPreferences("tunemind_player_prefs", Context.MODE_PRIVATE)
                    }
                    var selectedTargetPlayer by remember {
                        mutableStateOf(
                            playerPrefs.getString("target_player_pkg", "com.spotify.music") ?: "com.spotify.music"
                        )
                    }

                    val authSessionManager = remember {
                        com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(context)
                    }
                    val authSession by authSessionManager.sessionFlow.collectAsState()

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
                                authSessionManager.refreshSessionState()
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

                    val coroutineScope = rememberCoroutineScope()
                    var requestedScreenPage by remember { mutableStateOf<Int?>(null) }
                    var showSettingsScreen by remember { mutableStateOf(false) }
                    var currentPagerPage by remember { mutableStateOf(1) }

                    val autonomousDjEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.autonomousDjEnabled.collectAsState()
                    val stayInTuneMindEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.stayInTuneMindEnabled.collectAsState()
                    val strictNoAppSwitchEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.strictNoAppSwitchEnabled.collectAsState()
                    val appRemoteConnected by com.tunemind.app.provider.SpotifyAppRemoteManager.isConnectedFlow.collectAsState()
                    val playbackLaunchFeedback by vm.playbackLaunchFeedback.collectAsState()
                    val spotifyAuthManager = remember { com.tunemind.app.data.ingestion.SpotifyAuthManager(context) }

                    LaunchedEffect(Unit) {
                        com.tunemind.app.domain.voice.AgentSettingsStore.ensureLoaded(context)
                    }

                    fun executeSingleAppToolAction(tool: com.tunemind.app.domain.execution.AgentToolCallPayload) {
                        when (tool.action) {
                            "PAUSE_PLAYBACK" -> {
                                vm.setAgentPlaybackPaused()
                            }
                            "RESUME_PLAYBACK" -> {
                                vm.setAgentPlaybackResumed()
                            }
                            "QUARANTINE_TO_VAULT" -> {
                                val qTitle = tool.track_title ?: vm.homeState.value.nowPlaying?.title
                                val qArtist = tool.track_artist ?: vm.homeState.value.nowPlaying?.artist ?: ""
                                if (!qTitle.isNullOrBlank()) {
                                    vm.quarantineTrack(qTitle, qArtist)
                                }
                            }
                            "RESTORE_FROM_VAULT" -> {
                                val rTitle = tool.track_title ?: ""
                                vm.restoreTrackFromVaultByTitle(rTitle)
                            }
                            "SHIFT_ATMOSPHERE" -> {
                                val pid = tool.palette_id
                                if (!pid.isNullOrBlank()) {
                                    vm.setAtmospherePaletteById(pid)
                                } else {
                                    vm.cycleAtmospherePalette()
                                }
                            }
                            "SET_THEME_COLOR" -> {
                                val hex = tool.custom_accent_hex
                                val behavior = when (tool.theme_behavior?.uppercase()) {
                                    "FIXED" -> com.tunemind.app.ui.theme.ThemeBehavior.FIXED
                                    "ADAPTIVE" -> com.tunemind.app.ui.theme.ThemeBehavior.ADAPTIVE
                                    "TIME_BASED" -> com.tunemind.app.ui.theme.ThemeBehavior.TIME_BASED
                                    else -> null
                                }
                                vm.setCustomThemeAccentHex(hex, behavior)
                            }
                            "TOGGLE_AUTONOMOUS_DJ" -> {
                                val target = tool.autonomous_dj_enabled
                                    ?: !com.tunemind.app.domain.voice.AgentSettingsStore.isAutonomousDjEnabled(context)
                                com.tunemind.app.domain.voice.AgentSettingsStore.setAutonomousDjEnabled(context, target)
                            }
                            "TOGGLE_STAY_IN_TUNEMIND" -> {
                                val target = tool.stay_in_tunemind_enabled
                                    ?: !com.tunemind.app.domain.voice.AgentSettingsStore.isStayInTuneMindEnabled(context)
                                com.tunemind.app.domain.voice.AgentSettingsStore.setStayInTuneMindEnabled(context, target)
                            }
                            "TOGGLE_STRICT_NO_APP_SWITCH" -> {
                                val target = tool.strict_no_app_switch_enabled
                                    ?: !com.tunemind.app.domain.voice.AgentSettingsStore.isStrictNoAppSwitchEnabled(context)
                                com.tunemind.app.domain.voice.AgentSettingsStore.setStrictNoAppSwitchEnabled(context, target)
                            }
                            "OPEN_SETTINGS" -> {
                                showSettingsScreen = true
                            }
                            "NAVIGATE_SCREEN" -> {
                                val screen = tool.target_screen?.uppercase() ?: ""
                                if (screen.contains("SETTING")) {
                                    showSettingsScreen = true
                                } else {
                                    showSettingsScreen = false
                                    requestedScreenPage = when {
                                        screen.contains("DOPAMINE") || screen.contains("VAULT") -> 0
                                        screen.contains("RHYTHM") || screen.contains("DNA") -> 2
                                        else -> 1
                                    }
                                }
                            }
                        }
                    }

                    // Voice Agent and Orchestrator with Full Telemetry + Multi-Tool Execution Bridge
                    val agentVoiceManager = remember {
                        AgentVoiceManager(
                            context = context,
                            getNowPlaying = { vm.homeState.value.nowPlaying },
                            getRecentTracks = {
                                vm.historyState.value.tracks.ifEmpty { vm.homeState.value.recentTracks }
                            },
                            getHearingInsights = { vm.hearingInsightsState.value },
                            getVaultTracks = { vm.vaultState.value },
                            getDopamineCapsule = { vm.compileDopamineCapsule() },
                            getAppState = {
                                val prefs = vm.themePreferences.value
                                val screenName = when {
                                    showSettingsScreen -> "SETTINGS"
                                    currentPagerPage == 0 -> "DOPAMINE_VAULT"
                                    currentPagerPage == 2 -> "RHYTHM_DNA"
                                    else -> "PULSE_AGENT"
                                }
                                org.json.JSONObject().apply {
                                    put("current_screen", screenName)
                                    put("theme_behavior", prefs.behavior.name)
                                    put("palette_id", vm.adaptiveThemeState.value.activePalette.id)
                                    put("custom_accent_hex", prefs.customAccentHex ?: "")
                                    put("autonomous_dj_enabled", com.tunemind.app.domain.voice.AgentSettingsStore.isAutonomousDjEnabled(context))
                                    put("stay_in_tunemind_enabled", com.tunemind.app.domain.voice.AgentSettingsStore.isStayInTuneMindEnabled(context))
                                    put("strict_no_app_switch_enabled", com.tunemind.app.domain.voice.AgentSettingsStore.isStrictNoAppSwitchEnabled(context))
                                    put("prefer_in_app_player_enabled", com.tunemind.app.domain.voice.AgentSettingsStore.isPreferInAppPlayerEnabled(context))
                                    put("has_overlay_permission", PermissionUtils.hasOverlayPermission(context))
                                    put("inapp_player_active", com.tunemind.app.provider.TuneMindInAppPlayer.isPlaying())
                                    put("spotify_connected", spotifyAuthManager.isAuthenticated())
                                    put("app_remote_connected", com.tunemind.app.provider.SpotifyAppRemoteManager.isConnected())
                                }
                            },
                            onTrackDispatched = { dispatchedTitle, dispatchedArtist, dispatchedPkg ->
                                vm.setAgentActiveTrack(dispatchedTitle, dispatchedArtist, dispatchedPkg)
                            },
                            onToolActionExecuted = { tool ->
                                executeSingleAppToolAction(tool)
                                tool.extra_actions.forEach { extra ->
                                    executeSingleAppToolAction(extra)
                                }
                            },
                            onPlaybackLaunchResult = { launchRes ->
                                vm.reportPlaybackLaunchResult(launchRes)
                            }
                        )
                    }
                    val agentState by agentVoiceManager.agentState.collectAsState()
                    val flowAnalysis by agentVoiceManager.flowAnalysisFlow.collectAsState()
                    val agentSatiety by agentVoiceManager.agentSatietyFlow.collectAsState()
                    val pendingProposedTrack by agentVoiceManager.pendingProposedTrackFlow.collectAsState()
                    var liveTranscript by remember { mutableStateOf("") }
                    var hasCompletedLaunchStartFlow by remember { mutableStateOf(false) }

                    val orchestrator = remember(agentVoiceManager) {
                        AgentOrchestrator(
                            context = context,
                            agentVoiceManager = agentVoiceManager,
                            getNowPlaying = { vm.homeState.value.nowPlaying },
                            scope = coroutineScope
                        )
                    }

                    val isMainAppActive = hasCompletedLaunchStartFlow && authSession.hasCompletedStartFlow && hasAllPermissions

                    LaunchedEffect(isMainAppActive) {
                        agentVoiceManager.setForegroundActive(isMainAppActive)
                        if (isMainAppActive && spotifyAuthManager.isAuthenticated()) {
                            com.tunemind.app.provider.SpotifyAppRemoteManager.connectAsync(
                                context = context,
                                showAuthView = false
                            )
                        }
                    }

                    DisposableEffect(lifecycleOwner, agentVoiceManager, isMainAppActive) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            when (event) {
                                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                                    if (isMainAppActive) {
                                        agentVoiceManager.setForegroundActive(true)
                                    }
                                }
                                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                                    agentVoiceManager.setForegroundActive(false)
                                }
                                else -> {}
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            agentVoiceManager.setForegroundActive(false)
                        }
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
                    val trackTitle = nowPlaying?.title?.takeIf { it.isNotBlank() }
                    val artistName = nowPlaying?.artist?.takeIf { it.isNotBlank() }
                    val isMusicPlaying = nowPlaying?.isPlaying == true
                    val energy = if (isMusicPlaying) 0.85f else 0.55f

                    val combinedRecentTracks = remember(historyState.tracks, homeState.recentTracks) {
                        historyState.tracks.ifEmpty { homeState.recentTracks }
                    }

                    val dopamineCapsule = remember(combinedRecentTracks, vaultState, nowPlaying?.title) {
                        vm.compileDopamineCapsule()
                    }

                    TuneMindTheme(adaptiveState = adaptiveState) {
                        if (!isMainAppActive) {
                            OnboardingScreen(
                                onComplete = {
                                    hasCompletedLaunchStartFlow = true
                                    authSessionManager.markStartFlowCompleted()
                                    hasAllPermissions = true
                                }
                            )
                        } else {
                            // 3-Screen Horizontal Pager: 0 = DOPAMINE, 1 = AGENT (Default Center Home), 2 = RHYTHM
                            val pagerState = rememberPagerState(
                                initialPage = 1,
                                pageCount = { 3 }
                            )

                            LaunchedEffect(pagerState.currentPage) {
                                currentPagerPage = pagerState.currentPage
                            }

                            LaunchedEffect(requestedScreenPage) {
                                val target = requestedScreenPage
                                if (target != null && target in 0..2) {
                                    pagerState.animateScrollToPage(target)
                                    requestedScreenPage = null
                                }
                            }

                            androidx.activity.compose.BackHandler(enabled = showSettingsScreen) {
                                showSettingsScreen = false
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(adaptiveState.primaryBackground)
                            ) {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    when (page) {
                                        0 -> {
                                            DopamineVaultScreen(
                                                recentTracks = combinedRecentTracks,
                                                vaultTracks = vaultState,
                                                nowPlayingTitle = nowPlaying?.title,
                                                onPlayTrack = { title, artist, spotifyUri, sourcePkg ->
                                                    val targetPkg = selectedTargetPlayer.ifBlank { sourcePkg }
                                                    vm.setAgentActiveTrack(title, artist, targetPkg)
                                                    agentVoiceManager.playTrackImmediatelyWithVoice(
                                                        title = title,
                                                        artist = artist,
                                                        spotifyUri = spotifyUri,
                                                        targetPlayer = targetPkg
                                                    )
                                                },
                                                onAskAgentAboutTrack = { title, artist, freshness, spotifyUri ->
                                                    coroutineScope.launch {
                                                        pagerState.animateScrollToPage(1)
                                                        agentVoiceManager.askAgentAboutTrack(
                                                            title = title,
                                                            artist = artist,
                                                            freshnessPercent = freshness,
                                                            spotifyUri = spotifyUri,
                                                            targetPlayer = selectedTargetPlayer
                                                        )
                                                    }
                                                },
                                                onSyncAtmosphereToTrack = { title, artist ->
                                                    vm.syncAtmosphereToTrack(title, artist)
                                                },
                                                onQuarantineTrack = { title, artist ->
                                                    vm.quarantineTrack(title, artist)
                                                },
                                                onRestoreVaultTrack = { vaultId ->
                                                    vm.restoreFromVault(vaultId)
                                                },
                                                onCycleAtmospherePalette = {
                                                    vm.cycleAtmospherePalette()
                                                }
                                            )
                                        }
                                        1 -> {
                                            PulseAgentScreen(
                                                agentState = agentState,
                                                satietyPercentage = agentSatiety,
                                                currentTrackName = trackTitle,
                                                artistName = artistName,
                                                isMusicPlaying = isMusicPlaying,
                                                energy = energy,
                                                liveTranscript = liveTranscript,
                                                flowAnalysis = flowAnalysis,
                                                recentTracks = combinedRecentTracks,
                                                dopamineCapsule = dopamineCapsule,
                                                pendingProposedTrack = pendingProposedTrack,
                                                onApproveProposedTrack = {
                                                    agentVoiceManager.approvePendingProposedTrack()
                                                },
                                                onRejectProposedTrack = {
                                                    agentVoiceManager.rejectPendingProposedTrack()
                                                },
                                                onOrbTapped = {
                                                    agentVoiceManager.simulateWakeWordDetected()
                                                },
                                                onLaunchDopamineCapsule = {
                                                    agentVoiceManager.launchDopamineCapsuleBriefing(
                                                        capsule = dopamineCapsule,
                                                        targetPlayer = selectedTargetPlayer
                                                    )
                                                },
                                                onCycleAtmospherePalette = {
                                                    vm.cycleAtmospherePalette()
                                                },
                                                onOpenSettings = {
                                                    showSettingsScreen = true
                                                },
                                                autonomousDjEnabled = autonomousDjEnabled
                                            )
                                        }
                                        2 -> {
                                            RhythmDnaScreen(
                                                recentTracks = combinedRecentTracks,
                                                hearingInsights = hearingInsights,
                                                selectedTargetPlayer = selectedTargetPlayer,
                                                themePreferences = themePreferences,
                                                onUpdateThemePreferences = { newPrefs ->
                                                    vm.updateThemePreferences(newPrefs)
                                                },
                                                onSelectTargetPlayer = { newPkg ->
                                                    selectedTargetPlayer = newPkg
                                                    playerPrefs.edit().putString("target_player_pkg", newPkg).apply()
                                                },
                                                onPlayTrack = { title, artist, spotifyUri, sourcePkg ->
                                                    val targetPkg = selectedTargetPlayer.ifBlank { sourcePkg }
                                                    vm.setAgentActiveTrack(title, artist, targetPkg)
                                                    agentVoiceManager.playTrackImmediatelyWithVoice(
                                                        title = title,
                                                        artist = artist,
                                                        spotifyUri = spotifyUri,
                                                        targetPlayer = targetPkg
                                                    )
                                                },
                                                onAskAgentAboutTrack = { title, artist, freshness, spotifyUri ->
                                                    coroutineScope.launch {
                                                        pagerState.animateScrollToPage(1)
                                                        agentVoiceManager.askAgentAboutTrack(
                                                            title = title,
                                                            artist = artist,
                                                            freshnessPercent = freshness,
                                                            spotifyUri = spotifyUri,
                                                            targetPlayer = selectedTargetPlayer
                                                        )
                                                    }
                                                },
                                                onSyncAtmosphereToTrack = { title, artist ->
                                                    vm.syncAtmosphereToTrack(title, artist)
                                                },
                                                onQuarantineTrack = { title, artist ->
                                                    vm.quarantineTrack(title, artist)
                                                }
                                            )
                                        }
                                    }
                                }

                                // Centered Floating Glass Pill Dock
                                FloatingGlassDock(
                                    currentPage = pagerState.currentPage,
                                    agentState = agentState,
                                    freshTrackCount = combinedRecentTracks.count { it.distinctAppearanceCount <= 3 },
                                    onSelectPage = { targetPage ->
                                        coroutineScope.launch {
                                            if (targetPage == 1 && pagerState.currentPage == 1) {
                                                // Tapping AGENT while already on the center screen wakes the Voice Orb
                                                agentVoiceManager.simulateWakeWordDetected()
                                            } else {
                                                pagerState.animateScrollToPage(targetPage)
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .navigationBarsPadding()
                                        .padding(bottom = 18.dp)
                                )

                                // Playback Launch / Strict Never-Leave-TuneMind Diagnostic Banner
                                val currentFeedback = playbackLaunchFeedback
                                if (!showSettingsScreen && currentFeedback != null &&
                                    (currentFeedback is com.tunemind.app.provider.PlaybackLaunchResult.LaunchFailed ||
                                        (currentFeedback is com.tunemind.app.provider.PlaybackLaunchResult.DirectPlaybackLaunched &&
                                            currentFeedback.providerId == "tunemind_inapp"))
                                ) {
                                    val isFailure = currentFeedback is com.tunemind.app.provider.PlaybackLaunchResult.LaunchFailed
                                    LaunchedEffect(currentFeedback) {
                                        kotlinx.coroutines.delay(if (isFailure) 9500L else 4500L)
                                        vm.clearPlaybackLaunchFeedback()
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isFailure) Color(0xFF281218) else Color(0xFF102420),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isFailure) Color(0xFFFF5A79).copy(alpha = 0.7f) else adaptiveState.primaryAccent.copy(alpha = 0.65f)
                                        ),
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .statusBarsPadding()
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                            .fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = currentFeedback.message,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                if (isFailure) {
                                                    Button(
                                                        onClick = {
                                                            vm.clearPlaybackLaunchFeedback()
                                                            showSettingsScreen = true
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = adaptiveState.primaryAccent),
                                                        shape = RoundedCornerShape(8.dp),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            "Open Settings",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.Black
                                                        )
                                                    }
                                                }
                                                TextButton(
                                                    onClick = { vm.clearPlaybackLaunchFeedback() },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text("Dismiss", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                                                }
                                            }
                                        }
                                    }
                                }

                                // Fullscreen TuneMind Settings Overlay
                                if (showSettingsScreen) {
                                    com.tunemind.app.ui.screens.SettingsScreen(
                                        vm = vm,
                                        onBack = { showSettingsScreen = false },
                                        onOpenCrossApp = {
                                            showSettingsScreen = false
                                            coroutineScope.launch { pagerState.animateScrollToPage(2) }
                                        },
                                        onOpenWeeklyReport = {
                                            showSettingsScreen = false
                                            coroutineScope.launch { pagerState.animateScrollToPage(2) }
                                        }
                                    )
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.tunemind.app.data.ingestion.SpotifyOAuthRepository.getInstance(applicationContext)
                .handleAuthRedirectIntent(intent)
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
