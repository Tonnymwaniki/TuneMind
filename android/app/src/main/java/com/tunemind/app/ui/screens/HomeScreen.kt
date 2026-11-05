package com.tunemind.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.recommendation.RecommendationCategory
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.model.TuneMindDemoData
import com.tunemind.app.provider.MusicProvider
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SongProfileData
import com.tunemind.app.ui.components.SongProfileSheet
import com.tunemind.app.ui.theme.*
import com.tunemind.app.voice.AgentPhase
import com.tunemind.app.voice.NeuroAcousticIntent
import com.tunemind.app.voice.TuneMindVoiceAgent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: TuneMindViewModel,
    onOpenFatigue: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenStudy: () -> Unit = {},
    onOpenMoments: () -> Unit = {},
    onOpenHearing: () -> Unit = {},
    onOpenAiDiscovery: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val state by vm.homeState.collectAsState()
    val scrollState = rememberScrollState()
    var selectedProfileData by remember { mutableStateOf<SongProfileData?>(null) }
    var isRestTrackActive by remember { mutableStateOf(false) }
    var isAcousticLoopActive by remember { mutableStateOf(false) }
    
    val momentsState by vm.musicalMomentsState.collectAsState()
    val hearingState by vm.hearingInsightsState.collectAsState()
    val studyState by vm.studyState.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    val recommendations by vm.recommendations.collectAsState()
    val playbackFeedback by vm.playbackLaunchFeedback.collectAsState()
    val geminiInsight by vm.geminiSessionInsight.collectAsState()
    val isGeminiConfigured by vm.isGeminiConfigured.collectAsState()
    val isReasoningLoading by vm.isReasoningLoading.collectAsState()
    val reasoningReport by vm.reasoningReport.collectAsState()
    val isGeneratingReport by vm.isGeneratingReport.collectAsState()

    val context = LocalContext.current
    val voiceAgent = remember { TuneMindVoiceAgent(context, vm) }
    val agentState by voiceAgent.agentState.collectAsState()
    var showPlayerSelectorSheet by remember { mutableStateOf(false) }
    val installedProviders = remember(context) { vm.getInstalledProviders(context) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceAgent.startAgentSession()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceAgent.stop()
        }
    }

    // Real Live Media Playback telemetry from Spotify / active players
    val nowPlaying = state.nowPlaying
    val isPlaying = nowPlaying?.isPlaying ?: false
    val reportedPositionMs = nowPlaying?.positionMs ?: 0L
    val reportedDurationMs = nowPlaying?.durationMs?.takeIf { it > 0L } ?: 180_000L

    var currentPositionMs by remember(nowPlaying?.positionMs, isPlaying) {
        mutableStateOf(reportedPositionMs)
    }

    // Timer that advances when Spotify is actually PLAYING
    LaunchedEffect(isPlaying, nowPlaying?.updatedAtMs) {
        if (!isPlaying) {
            currentPositionMs = reportedPositionMs
            return@LaunchedEffect
        }
        val startWallTime = System.currentTimeMillis()
        val initialPos = reportedPositionMs
        val speed = nowPlaying?.speed ?: 1.0f
        while (true) {
            kotlinx.coroutines.delay(200)
            val elapsed = (System.currentTimeMillis() - startWallTime) * speed
            currentPositionMs = (initialPos + elapsed).toLong()
        }
    }

    val progressFraction = if (reportedDurationMs > 0) {
        (currentPositionMs.toFloat() / reportedDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    fun formatDuration(ms: Long): String {
        val totalSec = maxOf(0L, ms / 1000)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    // Determine current track: prefer real observed track from phone, or fallback to demo
    val latestRealTrack = state.recentTracks.firstOrNull()
    val nowPlayingTitle = nowPlaying?.title?.takeIf { it.isNotBlank() }
        ?: latestRealTrack?.title
        ?: TuneMindDemoData.currentNowPlaying.title
    val nowPlayingArtist = nowPlaying?.artist?.takeIf { it.isNotBlank() }
        ?: latestRealTrack?.artist
        ?: TuneMindDemoData.currentNowPlaying.artist
    val nowPlayingSource = (nowPlaying?.sourcePackage ?: latestRealTrack?.sourcePackage)
        ?.substringAfterLast(".")?.replaceFirstChar { it.uppercase() } ?: "Spotify"

    // Dynamic Satiety based on real play occurrences
    val currentTrackPlays = latestRealTrack?.distinctAppearanceCount ?: 1
    val dynamicSatietyPercent = minOf(95, maxOf(18, currentTrackPlays * 22))

    val colors = TuneMindTheme.colors
    val hasAccess = com.tunemind.app.service.NotificationAccess.isEnabled(context)
    val isListenerBound = com.tunemind.app.service.TuneMindListenerService.isConnected

    val hasCompletedOnboarding by vm.hasCompletedOnboarding.collectAsState()
    if (!hasCompletedOnboarding) {
        PersonalOnboardingDialog(
            vm = vm,
            onDismiss = { }
        )
        return
    }

    LaunchedEffect(Unit) {
        if (hasAccess) {
            com.tunemind.app.MainActivity.forceRebindListener(context)
        }
    }

    // Dynamic Audiophile Micro-Animations (Vinyl 33 RPM Platter & Real-Time Dynamic DSP EQ)
    val infiniteTransition = rememberInfiniteTransition(label = "audioAnimations")
    val vinylRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinylSpin"
    )
    val eqPulse by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eqPulse"
    )
    val tonearmAngle by animateFloatAsState(
        targetValue = if (isPlaying) 22f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "tonearmAngle"
    )
    val cassetteRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cassetteSpin"
    )
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepObsidianAmber.SecondaryBackground,
                        DeepObsidianAmber.AppBackground,
                        DeepObsidianAmber.RecessedBackground
                    )
                )
            )
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. DYNAMIC PULSE TELEMETRY & KINETIC SOUNDWAVE HERO (INTERCHANGED WITH TOP BAR)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DeepObsidianAmber.CardElevated,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        DeepObsidianAmber.EQBassCyan.copy(alpha = 0.6f),
                        DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.4f),
                        DeepObsidianAmber.CopperAccent.copy(alpha = 0.3f)
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Pulse Soundwave Animation & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val pulseWaveAnim = rememberInfiniteTransition(label = "pulseHeroAnim")
                    val pulseWave by pulseWaveAnim.animateFloat(
                        initialValue = 0.45f,
                        targetValue = 1.35f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseWave"
                    )

                    // Kinetic Pulse Waveform Graphic Capsule
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        DeepObsidianAmber.EQBassCyan.copy(alpha = 0.25f),
                                        DeepObsidianAmber.RecessedBackground,
                                        DeepObsidianAmber.AppBackground
                                    )
                                )
                            )
                            .border(1.2.dp, DeepObsidianAmber.EQBassCyan.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        // Live EQ Pulse Visualizer
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val pulseBars = listOf(8, 18, 26, 15, 9)
                            pulseBars.forEachIndexed { idx, h ->
                                val barH = (h * (0.45f + 0.55f * kotlin.math.abs(kotlin.math.sin(idx * 0.8f + pulseWave * 3.14f)))).coerceIn(4f, 26f)
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(barH.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(
                                            if (idx == 2) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.EQBassCyan
                                        )
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "PULSE",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = DeepObsidianAmber.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.8.sp
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DeepObsidianAmber.RecessedBackground,
                                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.EQBassCyan.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = if (state.nowPlaying != null) "STREAMING" else "MONITORING",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepObsidianAmber.EQBassCyan,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "LIVE NEURO-ACOUSTIC TELEMETRY",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.SecondaryAccent,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                // Right: Redesigned, Machined Daemon Status Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DeepObsidianAmber.RecessedBackground,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isListenerBound) DeepObsidianAmber.Success.copy(alpha = 0.5f)
                        else DeepObsidianAmber.Warning.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.clickable {
                        if (!isListenerBound) {
                            com.tunemind.app.MainActivity.forceRebindListener(context)
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isListenerBound) DeepObsidianAmber.Success else DeepObsidianAmber.Warning)
                        )
                        Text(
                            text = if (isListenerBound) "DAEMON LIVE" else "REBIND DAEMON",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isListenerBound) DeepObsidianAmber.Success else DeepObsidianAmber.Warning,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }

        // 1.5. PROMINENT CHRONO AI CRATE HERO ACTION BANNER
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DeepObsidianAmber.CardElevated,
            border = androidx.compose.foundation.BorderStroke(
                1.2.dp,
                Brush.horizontalGradient(
                    listOf(
                        DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.85f),
                        DeepObsidianAmber.BrandSoulAmber.copy(alpha = 0.6f),
                        DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)
                    )
                )
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenAiDiscovery() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        DeepObsidianAmber.PrimaryAccent,
                                        DeepObsidianAmber.BrandSoulAmber
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = "AI Crate",
                            tint = DeepObsidianAmber.AppBackground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "CHRONO AI CRATE",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.PrimaryAccent,
                                letterSpacing = 1.sp
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DeepObsidianAmber.RecessedBackground,
                                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.Success.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "DAYPART SYNCED",
                                    fontSize = 7.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepObsidianAmber.Success,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Auto-evolving songs for ${vm.currentTimeSlot.label} - Tap to Open Crate",
                            fontSize = 11.sp,
                            color = DeepObsidianAmber.TextSecondary
                        )
                    }
                }
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = DeepObsidianAmber.PrimaryAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // 2. HERO NOW PLAYING DECK (Machined Vinyl Deck with Concentric Grooves & Equalizer)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(DeepObsidianAmber.CardElevated)
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            DeepObsidianAmber.CopperAccent.copy(alpha = 0.6f),
                            DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.35f),
                            DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)
                        )
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 0. NEURO-ACOUSTIC AGENT STATUS & PLAYER ROUTE STRIP
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Player Route Target Pill (1-Tap Change)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DeepObsidianAmber.RecessedBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.6f)),
                    modifier = Modifier.clickable { showPlayerSelectorSheet = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Outlined.NearMe,
                            contentDescription = "Target Player",
                            tint = DeepObsidianAmber.PrimaryAccent,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "ROUTE: ${agentState.targetProviderName.uppercase()}",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.PrimaryAccent,
                            letterSpacing = 0.6.sp
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Change Player",
                            tint = DeepObsidianAmber.PrimaryAccent,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Right: Voice Agent Activity Badge
                val isAgentActive = agentState.phase != AgentPhase.IDLE
                val agentBadgeColor = when (agentState.phase) {
                    AgentPhase.IDLE -> DeepObsidianAmber.SurfaceBorder
                    AgentPhase.THINKING -> DeepObsidianAmber.NeuralViolet
                    AgentPhase.PROMPTING_VOICE, AgentPhase.PROMPTING_PLAYER -> DeepObsidianAmber.PrimaryAccent
                    AgentPhase.LISTENING_INTENT, AgentPhase.LISTENING_PLAYER -> DeepObsidianAmber.EQBassCyan
                    AgentPhase.WAITING_CONFIRMATION, AgentPhase.LISTENING_CONFIRMATION -> DeepObsidianAmber.CircadianMorning
                    AgentPhase.EXECUTING -> DeepObsidianAmber.Success
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DeepObsidianAmber.RecessedBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, agentBadgeColor.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isAgentActive) agentBadgeColor else DeepObsidianAmber.TextSecondary)
                        )
                        Text(
                            text = when (agentState.phase) {
                                AgentPhase.IDLE -> "VOICE AGENT READY"
                                AgentPhase.THINKING -> "AGENT REASONING..."
                                AgentPhase.PROMPTING_VOICE -> "AGENT SPEAKING"
                                AgentPhase.LISTENING_INTENT -> "LISTENING TO VIBE..."
                                AgentPhase.WAITING_CONFIRMATION -> "AWAITING CONFIRMATION"
                                AgentPhase.LISTENING_CONFIRMATION -> "LISTENING (YES/CHANGE)..."
                                AgentPhase.PROMPTING_PLAYER -> "CONFIRMING PLAYER"
                                AgentPhase.LISTENING_PLAYER -> "LISTENING TO PLAYER..."
                                AgentPhase.EXECUTING -> "AUTO-PLAYING AUDIO"
                            },
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isAgentActive) agentBadgeColor else DeepObsidianAmber.TextSecondary,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Concentric Vinyl Turntable Platter & Machined Pivoting Tonearm
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
                contentAlignment = Alignment.Center
            ) {
                // Platter & Vinyl Record
                Box(
                    modifier = Modifier
                        .size(196.dp)
                        .offset(x = (-16).dp)
                        .rotate(if (isPlaying) vinylRotation else 0f)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    DeepObsidianAmber.CardBackground,
                                    DeepObsidianAmber.RecessedBackground,
                                    DeepObsidianAmber.AppBackground
                                )
                            )
                        )
                        .border(2.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.5f), CircleShape)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Microgroove Ring 1
                    Box(
                        modifier = Modifier
                            .size(166.dp)
                            .border(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.25f), CircleShape)
                    )
                    // Microgroove Ring 2
                    Box(
                        modifier = Modifier
                            .size(134.dp)
                            .border(1.dp, DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.2f), CircleShape)
                    )
                    // Microgroove Ring 3
                    Box(
                        modifier = Modifier
                            .size(102.dp)
                            .border(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.35f), CircleShape)
                    )

                    // Golden Center Vinyl Label (Branded Deep Obsidian Amber Soul)
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        DeepObsidianAmber.BrandSoulAmber,
                                        DeepObsidianAmber.PrimaryAccent
                                    )
                                )
                            )
                            .border(1.5.dp, DeepObsidianAmber.SecondaryAccent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = DeepObsidianAmber.AccentSoft,
                                modifier = Modifier.size(22.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(DeepObsidianAmber.AppBackground)
                            )
                        }
                    }

                    // Micro Monospace Hardware ID Badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DeepObsidianAmber.RecessedBackground.copy(alpha = 0.9f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = "VINYL // 33⅓ RPM",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.SecondaryAccent,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                // Machined Audiophile Tonearm with Gimbal Bearing & Stylus
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .width(76.dp)
                        .height(180.dp)
                        .graphicsLayer {
                            rotationZ = tonearmAngle
                            transformOrigin = TransformOrigin(0.75f, 0.12f)
                        }
                ) {
                    // Gimbal Bearing Base (Top Pivot)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        DeepObsidianAmber.PrimaryAccent,
                                        DeepObsidianAmber.CardElevated,
                                        DeepObsidianAmber.AppBackground
                                    )
                                )
                            )
                            .border(1.5.dp, DeepObsidianAmber.CopperAccent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        // Counterweight inner disc
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(DeepObsidianAmber.BrandSoulAmber)
                                .border(1.dp, DeepObsidianAmber.SecondaryAccent, CircleShape)
                        )
                    }

                    // Tonearm Shaft (Machined brushed brass/copper alloy)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 26.dp, end = 15.dp)
                            .width(4.dp)
                            .height(115.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        DeepObsidianAmber.SecondaryAccent,
                                        DeepObsidianAmber.CopperAccent,
                                        DeepObsidianAmber.BrandSoulAmber
                                    )
                                )
                            )
                    )

                    // Cartridge Headshell & Stylus Needle Tip
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 12.dp)
                            .size(width = 24.dp, height = 14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(DeepObsidianAmber.RecessedBackground)
                            .border(1.dp, DeepObsidianAmber.PrimaryAccent, RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(if (isPlaying) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning)
                            )
                            Text(
                                text = "MC",
                                fontSize = 7.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.SecondaryAccent
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Acoustic Metadata Strip
            Text(
                text = nowPlayingTitle,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = DeepObsidianAmber.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = nowPlayingArtist,
                    fontSize = 13.sp,
                    color = DeepObsidianAmber.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(DeepObsidianAmber.CopperAccent)
                )
                Text(
                    text = "$nowPlayingSource Stream",
                    fontSize = 12.sp,
                    color = DeepObsidianAmber.PrimaryAccent
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dynamic 16-Band Live Equalizer Spectrum Visualizer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DeepObsidianAmber.RecessedBackground)
                    .border(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                val eqHeights = listOf(14, 22, 10, 26, 18, 24, 16, 28, 20, 15, 25, 12, 22, 18, 26, 14)
                eqHeights.forEachIndexed { idx, h ->
                    // Tri-Spectrum Audiophile DSP Visualization
                    val barColor = when {
                        idx < 5 -> DeepObsidianAmber.EQBassCyan   // Sub-bass & Bass (20-250Hz)
                        idx < 11 -> DeepObsidianAmber.EQMidAmber  // Warm Mids (250-4000Hz)
                        else -> DeepObsidianAmber.EQHighRose      // Crisp Highs (4k-16kHz)
                    }
                    val phase = (idx * 0.45f)
                    val dynamicMult = if (isPlaying) {
                        (0.6f + 0.65f * kotlin.math.abs(kotlin.math.sin(phase + eqPulse * 3.1415f))).toFloat()
                    } else 0.35f
                    val animatedHeight = (h * dynamicMult).coerceIn(4f, 26f)

                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(animatedHeight.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(barColor)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Vibe & Harmonic Frequency Chip
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DeepObsidianAmber.RecessedBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(DeepObsidianAmber.PrimaryAccent)
                    )
                    Text(
                        text = "REFLECTIVE // 118 BPM",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.PrimaryAccent,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Satiety Meter: 30-Microcell Segmented LED Bar
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            Icons.Outlined.BatteryChargingFull,
                            contentDescription = null,
                            tint = colors.secondaryAccent,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "ACOUSTIC SATIETY: $dynamicSatietyPercent%",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondaryText,
                            letterSpacing = 0.8.sp
                        )
                    }
                    Text(
                        text = if (dynamicSatietyPercent < 70) "[OPTIMAL FRESHNESS]" else "[ELEVATED SATURATION]",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (dynamicSatietyPercent < 70) colors.primaryAccent else DeepObsidianAmber.Warning
                    )
                }

                // 24 Segmented Cells
                val totalCells = 24
                val activeCells = ((dynamicSatietyPercent / 100f) * totalCells).toInt().coerceIn(1, totalCells)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.primaryBackground)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (i in 0 until totalCells) {
                        val isActive = i < activeCells
                        val cellColor = when {
                            !isActive -> colors.cardSurface.copy(alpha = 0.4f)
                            i < 8 -> colors.secondaryAccent
                            i < 16 -> colors.primaryAccent
                            else -> DeepObsidianAmber.AccentLight
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.dp))
                                .background(cellColor)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "DEGRADATION RISK: LOW",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.secondaryText.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "FATIGUE THRESHOLD: 85%",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.secondaryText.copy(alpha = 0.7f)
                    )
                }

                // Dedicated Satiety Lab & Cooldown Entrypoint
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DeepObsidianAmber.RecessedBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SatietyEmber.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFatigue() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                Icons.Outlined.LocalFireDepartment,
                                contentDescription = null,
                                tint = DeepObsidianAmber.SatietyEmber,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "RECEPTOR SATIETY LAB // COOLDOWN",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.SatietyEmber,
                                letterSpacing = 0.8.sp
                            )
                        }
                        Text(
                            text = "DIAGNOSE >",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.PrimaryAccent
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. CONVERSATIONAL VOICE AGENT READOUT & LIVE SPEECH CHAMBER
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DeepObsidianAmber.RecessedBackground,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (agentState.phase == AgentPhase.LISTENING_INTENT || agentState.phase == AgentPhase.LISTENING_PLAYER || agentState.phase == AgentPhase.LISTENING_CONFIRMATION)
                        DeepObsidianAmber.EQBassCyan.copy(alpha = 0.75f)
                    else if (agentState.phase == AgentPhase.PROMPTING_VOICE || agentState.phase == AgentPhase.PROMPTING_PLAYER)
                        DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.75f)
                    else if (agentState.phase == AgentPhase.THINKING)
                        DeepObsidianAmber.NeuralViolet.copy(alpha = 0.75f)
                    else DeepObsidianAmber.CopperAccent.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val hasAudioPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasAudioPerm) {
                            voiceAgent.startAgentSession()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                if (agentState.phase == AgentPhase.LISTENING_INTENT || agentState.phase == AgentPhase.LISTENING_PLAYER || agentState.phase == AgentPhase.LISTENING_CONFIRMATION)
                                    Icons.Default.Mic
                                else if (agentState.phase == AgentPhase.PROMPTING_VOICE || agentState.phase == AgentPhase.PROMPTING_PLAYER)
                                    Icons.Outlined.GraphicEq
                                else if (agentState.phase == AgentPhase.THINKING)
                                    Icons.Outlined.Psychology
                                else Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = if (agentState.phase == AgentPhase.LISTENING_INTENT || agentState.phase == AgentPhase.LISTENING_PLAYER || agentState.phase == AgentPhase.LISTENING_CONFIRMATION)
                                    DeepObsidianAmber.EQBassCyan
                                else if (agentState.phase == AgentPhase.THINKING)
                                    DeepObsidianAmber.NeuralViolet
                                else DeepObsidianAmber.PrimaryAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = when (agentState.phase) {
                                    AgentPhase.IDLE -> "TUNEMIND AGENT // CHAT READY"
                                    AgentPhase.THINKING -> "AGENT REASONING..."
                                    AgentPhase.PROMPTING_VOICE -> "AGENT SPEAKING..."
                                    AgentPhase.LISTENING_INTENT -> "LISTENING FOR VIBE..."
                                    AgentPhase.WAITING_CONFIRMATION -> "WOULD YOU LIKE THIS SONG?"
                                    AgentPhase.LISTENING_CONFIRMATION -> "LISTENING: SAY 'YES' OR 'CHANGE'..."
                                    AgentPhase.PROMPTING_PLAYER -> "CONFIRMING TARGET PLAYER..."
                                    AgentPhase.LISTENING_PLAYER -> "LISTENING FOR PLAYER..."
                                    AgentPhase.EXECUTING -> "AUTO-PLAYING TO ${agentState.targetProviderName.uppercase()}"
                                },
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (agentState.phase == AgentPhase.LISTENING_INTENT || agentState.phase == AgentPhase.LISTENING_PLAYER || agentState.phase == AgentPhase.LISTENING_CONFIRMATION)
                                    DeepObsidianAmber.EQBassCyan
                                else if (agentState.phase == AgentPhase.THINKING)
                                    DeepObsidianAmber.NeuralViolet
                                else DeepObsidianAmber.PrimaryAccent,
                                letterSpacing = 0.8.sp
                            )
                        }

                        // Subtle instruction tag
                        Text(
                            text = if (agentState.phase == AgentPhase.IDLE) "TAP TO SPEAK" else "LIVE DIALOGUE",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondaryText
                        )
                    }

                    // Multi-Turn Conversational Dialogue Chamber
                    if (agentState.dialogueHistory.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            agentState.dialogueHistory.takeLast(3).forEach { item ->
                                val isAgent = item.sender == "agent"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isAgent) Arrangement.Start else Arrangement.End
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isAgent) DeepObsidianAmber.CardElevated else DeepObsidianAmber.EQBassCyan.copy(alpha = 0.18f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            0.8.dp,
                                            if (isAgent) DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)
                                            else DeepObsidianAmber.EQBassCyan.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Text(
                                            text = if (isAgent) "Agent: ${item.message}" else "You: \"${item.message}\"",
                                            fontSize = 11.sp,
                                            color = if (isAgent) DeepObsidianAmber.TextPrimary else DeepObsidianAmber.EQBassCyan,
                                            fontWeight = if (isAgent) FontWeight.Normal else FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    } else if (agentState.agentSpeech.isNotBlank() || agentState.userSpeech.isNotBlank()) {
                        if (agentState.agentSpeech.isNotBlank()) {
                            Text(
                                text = "Agent: \"${agentState.agentSpeech}\"",
                                fontSize = 11.sp,
                                color = DeepObsidianAmber.TextPrimary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (agentState.userSpeech.isNotBlank()) {
                            Text(
                                text = "You: \"${agentState.userSpeech}\"",
                                fontSize = 11.sp,
                                color = DeepObsidianAmber.EQBassCyan,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            text = "Say: 'Change that song', 'Play from my timeline', 'What do you suggest?', or 'Play in YouTube Music'",
                            fontSize = 10.sp,
                            color = colors.secondaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // PROACTIVE SONG PROPOSAL & CONFIRMATION STRIP (1-Tap Follow-ups)
                    if (agentState.pendingConfirmationTrack != null || agentState.phase == AgentPhase.WAITING_CONFIRMATION || agentState.phase == AgentPhase.LISTENING_CONFIRMATION) {
                        val track = agentState.pendingConfirmationTrack ?: agentState.resolvedTrack
                        if (track != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DeepObsidianAmber.CardElevated,
                                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.BrandSoulAmber.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "WOULD YOU LIKE THIS SONG?",
                                        fontSize = 8.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepObsidianAmber.BrandSoulAmber,
                                        letterSpacing = 0.6.sp
                                    )
                                    Text(
                                        text = "${track.title} // ${track.artist}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepObsidianAmber.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // Play Button
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = DeepObsidianAmber.PrimaryAccent,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    voiceAgent.triggerIntentDirectly(NeuroAcousticIntent.DIRECT_PLAY, bypassVoicePrompt = true)
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 5.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DeepObsidianAmber.AppBackground, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text("PLAY NOW", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.AppBackground)
                                            }
                                        }

                                        // Change Song Button
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = DeepObsidianAmber.RecessedBackground,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CopperAccent),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    voiceAgent.changeSongImmediately()
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 5.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Refresh, contentDescription = null, tint = DeepObsidianAmber.CopperAccent, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text("CHANGE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.CopperAccent)
                                            }
                                        }

                                        // Timeline Button
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = DeepObsidianAmber.RecessedBackground,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.NeuralViolet),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    voiceAgent.playFromTimeline()
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 5.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Outlined.History, contentDescription = null, tint = DeepObsidianAmber.NeuralViolet, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text("TIMELINE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.NeuralViolet)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. MACHINED AGENTIC VOICE & DUAL ROTARY TRANSPORT CONSOLE
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.primaryBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Stepper: Previous
                    IconButton(
                        onClick = { vm.skipPrev() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.cardSurface)
                            .border(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = colors.primaryText,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Centerpiece 1: Interactive Agent Voice Mic
                    val isListening = agentState.phase == AgentPhase.LISTENING_INTENT || agentState.phase == AgentPhase.LISTENING_PLAYER
                    val isSpeaking = agentState.phase == AgentPhase.PROMPTING_VOICE || agentState.phase == AgentPhase.PROMPTING_PLAYER
                    val micAnim = rememberInfiniteTransition(label = "micPulseAnim")
                    val micPulse by micAnim.animateFloat(
                        initialValue = 0.85f,
                        targetValue = 1.25f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "micPulse"
                    )

                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .scale(if (isListening || isSpeaking) micPulse else 1f)
                            .clip(CircleShape)
                            .background(
                                if (isListening) DeepObsidianAmber.EQBassCyan
                                else if (isSpeaking) DeepObsidianAmber.BrandSoulAmber
                                else DeepObsidianAmber.RecessedBackground
                            )
                            .border(
                                1.5.dp,
                                if (isListening) DeepObsidianAmber.EQBassCyan
                                else DeepObsidianAmber.PrimaryAccent,
                                CircleShape
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val hasAudioPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasAudioPerm) {
                                    voiceAgent.startAgentSession()
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isSpeaking) Icons.Outlined.GraphicEq else Icons.Default.Mic,
                            contentDescription = "Voice Agent",
                            tint = if (isListening || isSpeaking) DeepObsidianAmber.AppBackground else DeepObsidianAmber.PrimaryAccent,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Centerpiece 2: Laser Rotary Console Dial (Play / Pause)
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(colors.primaryAccent)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.togglePlayback()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = DeepObsidianAmber.AppBackground,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Stepper: Next
                    IconButton(
                        onClick = { vm.skipNext() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.cardSurface)
                            .border(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = colors.primaryText,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Player Route Switcher Button
                    IconButton(
                        onClick = { showPlayerSelectorSheet = true },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.cardSurface)
                            .border(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
                    ) {
                        Icon(
                            Icons.Outlined.NearMe,
                            contentDescription = "Switch Music Player",
                            tint = DeepObsidianAmber.PrimaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5. ONE-TAP NEURO-ACOUSTIC INTENT BANK (FAST ACCESSIBLE CHIPS)
            Text(
                text = "NEURO-ACOUSTIC AGENT SHORTCUTS",
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colors.secondaryText,
                letterSpacing = 0.8.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, end = 2.dp, bottom = 4.dp)
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Instant Change That Song (Real-time autonomous switch)
                item {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = DeepObsidianAmber.CardElevated,
                        border = androidx.compose.foundation.BorderStroke(1.2.dp, DeepObsidianAmber.CopperAccent),
                        modifier = Modifier.clickable {
                            voiceAgent.changeSongImmediately()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = DeepObsidianAmber.CopperAccent, modifier = Modifier.size(13.dp))
                            Text("CHANGE SONG", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.CopperAccent)
                        }
                    }
                }

                // 2. Play from Timeline Screen Archive
                item {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(1.2.dp, DeepObsidianAmber.NeuralViolet),
                        modifier = Modifier.clickable {
                            voiceAgent.playFromTimeline()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Outlined.History, contentDescription = null, tint = DeepObsidianAmber.NeuralViolet, modifier = Modifier.size(13.dp))
                            Text("TIMELINE SONGS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.NeuralViolet)
                        }
                    }
                }

                // 3. Proactive Song Suggestion ("Would you like this song?")
                item {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(1.2.dp, DeepObsidianAmber.BrandSoulAmber),
                        modifier = Modifier.clickable {
                            voiceAgent.suggestTrackWithConfirmation()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = DeepObsidianAmber.BrandSoulAmber, modifier = Modifier.size(13.dp))
                            Text("SUGGEST SONG", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.BrandSoulAmber)
                        }
                    }
                }

                // 4. Peak Dopamine
                item {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.8f)),
                        modifier = Modifier.clickable {
                            voiceAgent.triggerIntentDirectly(NeuroAcousticIntent.DOPAMINE_PEAK, bypassVoicePrompt = false)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = DeepObsidianAmber.PrimaryAccent, modifier = Modifier.size(13.dp))
                            Text("DOPAMINE PEAK", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.PrimaryAccent)
                        }
                    }
                }

                // 5. Auditory Anchor (Most Repeated)
                item {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.8f)),
                        modifier = Modifier.clickable {
                            voiceAgent.triggerIntentDirectly(NeuroAcousticIntent.AUDITORY_ANCHOR, bypassVoicePrompt = false)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Outlined.Repeat, contentDescription = null, tint = DeepObsidianAmber.CopperAccent, modifier = Modifier.size(13.dp))
                            Text("SONIC ANCHOR", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.CopperAccent)
                        }
                    }
                }

                // 6. Circadian Daypart
                item {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CircadianMorning.copy(alpha = 0.8f)),
                        modifier = Modifier.clickable {
                            voiceAgent.triggerIntentDirectly(NeuroAcousticIntent.CIRCADIAN_DAYPART, bypassVoicePrompt = false)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Outlined.WbSunny, contentDescription = null, tint = DeepObsidianAmber.CircadianMorning, modifier = Modifier.size(13.dp))
                            Text("DAYPART SYNC", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.CircadianMorning)
                        }
                    }
                }

                // 7. Chrono AI Crate
                item {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.EQBassCyan.copy(alpha = 0.8f)),
                        modifier = Modifier.clickable {
                            voiceAgent.triggerIntentDirectly(NeuroAcousticIntent.AI_CRATE, bypassVoicePrompt = false)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = DeepObsidianAmber.EQBassCyan, modifier = Modifier.size(13.dp))
                            Text("CRATE GEMS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.EQBassCyan)
                        }
                    }
                }
            }
        }

        // CONTINUOUS LISTENING INTELLIGENCE LIVE TILE (Unlocked & Evolving)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DeepObsidianAmber.CardElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenStudy() }
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(DeepObsidianAmber.Success)
                        )
                        Text(
                            text = "CONTINUOUS ACOUSTIC INTELLIGENCE",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.TextPrimary,
                            letterSpacing = 1.sp
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "ACTIVE LEARNING",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.Success,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Neural Plasticity: 94.2%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.TextPrimary
                        )
                        Text(
                            text = "Actively learning and adapting across all listening sessions.",
                            fontSize = 11.sp,
                            color = DeepObsidianAmber.TextSecondary
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "VIEW MATRIX",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.PrimaryAccent
                        )
                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = DeepObsidianAmber.PrimaryAccent,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }

        // CHRONO-RECEPTIVE AI DISCOVERY CRATE TILE (Gemini 3.8 Auto-Evolving Discoveries)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DeepObsidianAmber.CardElevated,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.6f),
                        DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)
                    )
                )
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenAiDiscovery() }
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = DeepObsidianAmber.PrimaryAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "CHRONO AI CRATE // AUTO-EVOLVING",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.PrimaryAccent,
                            letterSpacing = 1.sp
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.Success.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "DAYPART SYNCED",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.Success,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${vm.currentTimeSlot.label} Discoveries",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.TextPrimary
                        )
                        Text(
                            text = "Songs your ears and dopamine crave based on your daily data growth.",
                            fontSize = 11.sp,
                            color = DeepObsidianAmber.TextSecondary
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "OPEN CRATE",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.PrimaryAccent
                        )
                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = DeepObsidianAmber.PrimaryAccent,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }

        // 3. CONTEXTUAL INTELLIGENCE & ANTI-FATIGUE QUEUE RIBBON
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CONTEXTUAL INTELLIGENCE // ANTI-FATIGUE QUEUE",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "PREDICTIVE",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.primaryAccent,
                    letterSpacing = 1.sp
                )
            }

            // Modular Sound Cartridge 1: Dopamine Re-engage
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DeepObsidianAmber.CardElevated,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            DeepObsidianAmber.CopperAccent.copy(alpha = 0.6f),
                            DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.3f)
                        )
                    )
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        recommendations.firstOrNull()?.let { vm.playRecommendation(context, it) }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Animated Cassette Spool Well
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DeepObsidianAmber.RecessedBackground)
                                .border(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .rotate(if (isPlaying) cassetteRotation else 0f)
                                    .clip(CircleShape)
                                    .background(DeepObsidianAmber.CardElevated)
                                    .border(1.2.dp, DeepObsidianAmber.CopperAccent, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                for (spoke in 0..2) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .height(26.dp)
                                            .rotate(spoke * 60f)
                                            .background(DeepObsidianAmber.SecondaryAccent)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(DeepObsidianAmber.RecessedBackground)
                                        .border(1.dp, DeepObsidianAmber.AccentLight, CircleShape)
                                )
                            }
                        }

                        val rec0 = recommendations.firstOrNull()
                        val rec0Title = rec0?.title ?: latestRealTrack?.title ?: "Midnight City"
                        val rec0Artist = rec0?.artist ?: latestRealTrack?.artist ?: "M83"
                        val rec0Reason = rec0?.reason ?: "Warm vintage synth palette • 94% harmonic match"

                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = rec0Title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "$rec0Artist • $rec0Reason",
                                fontSize = 11.sp,
                                color = DeepObsidianAmber.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = DeepObsidianAmber.RecessedBackground,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CryoCyan.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = rec0?.category?.name ?: "DOPAMINE RE-ENGAGE",
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepObsidianAmber.CryoCyan,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = "Δ -12dB FATIGUE",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepObsidianAmber.DecibelSafe
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            recommendations.firstOrNull()?.let { vm.playRecommendation(context, it) }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DeepObsidianAmber.RecessedBackground)
                            .border(1.dp, DeepObsidianAmber.CryoCyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            Icons.Outlined.QueueMusic,
                            contentDescription = "Queue",
                            tint = DeepObsidianAmber.CryoCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Modular Sound Cartridge 2: Calm Spectral Recovery
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DeepObsidianAmber.CardElevated,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            DeepObsidianAmber.NeuralViolet.copy(alpha = 0.6f),
                            DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.3f)
                        )
                    )
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        recommendations.getOrNull(1)?.let { vm.playRecommendation(context, it) }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Animated Cassette Spool Well (Reverse Spin)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DeepObsidianAmber.RecessedBackground)
                                .border(1.dp, DeepObsidianAmber.NeuralViolet.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .rotate(if (isPlaying) -cassetteRotation else 0f)
                                    .clip(CircleShape)
                                    .background(DeepObsidianAmber.CardElevated)
                                    .border(1.2.dp, DeepObsidianAmber.NeuralViolet, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                for (spoke in 0..2) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .height(26.dp)
                                            .rotate(spoke * 60f)
                                            .background(DeepObsidianAmber.NeuralViolet)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(DeepObsidianAmber.RecessedBackground)
                                        .border(1.dp, DeepObsidianAmber.TextSecondary, CircleShape)
                                )
                            }
                        }

                        val rec1 = recommendations.getOrNull(1)
                        val rec1Title = rec1?.title ?: state.recentTracks.getOrNull(1)?.title ?: "Resonance"
                        val rec1Artist = rec1?.artist ?: state.recentTracks.getOrNull(1)?.artist ?: "HOME"
                        val rec1Reason = rec1?.reason ?: "Chilled spectral rest • 88% acoustic parity"

                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = rec1Title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "$rec1Artist • $rec1Reason",
                                fontSize = 11.sp,
                                color = DeepObsidianAmber.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = DeepObsidianAmber.RecessedBackground,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.NeuralViolet.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = rec1?.category?.name ?: "CALM RECOVERY",
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepObsidianAmber.NeuralViolet,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = "NEURO-DRIFT: 0.12",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = DeepObsidianAmber.TextSecondary
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            recommendations.getOrNull(1)?.let { vm.playRecommendation(context, it) }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DeepObsidianAmber.RecessedBackground)
                            .border(1.dp, DeepObsidianAmber.SecondaryAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            Icons.Outlined.QueueMusic,
                            contentDescription = "Queue",
                            tint = DeepObsidianAmber.SecondaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // 4. GEMINI REASONING SYNAPSE & TELEMETRY
        if (isGeminiConfigured) {
            val isLiveGemini = reasoningReport?.isGeminiGenerated == true
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.secondaryBackground,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isLiveGemini) DeepObsidianAmber.Success.copy(alpha = 0.5f) else colors.border.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = colors.primaryAccent, modifier = Modifier.size(16.dp))
                            Text(
                                text = "NEURO-REASONING CORE",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryAccent,
                                letterSpacing = 1.sp
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DeepObsidianAmber.RecessedBackground,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isLiveGemini) DeepObsidianAmber.Success.copy(alpha = 0.5f) else DeepObsidianAmber.CopperAccent.copy(alpha = 0.4f)
                                )
                            ) {
                                Text(
                                    text = if (isLiveGemini) "INTELLIGENCE ACTIVE" else "NEURAL BASELINE",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isLiveGemini) DeepObsidianAmber.Success else DeepObsidianAmber.SecondaryAccent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (isReasoningLoading || isGeneratingReport) {
                                CircularProgressIndicator(color = colors.primaryAccent, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            }
                        }
                    }

                    val reportSummary = reasoningReport?.flowStateDescription
                        ?: reasoningReport?.fatigueRiskLabel
                        ?: geminiInsight
                        ?: "Real-time acoustic patterns indicate stable receptivity across current listening cycle."

                    Text(
                        text = reportSummary,
                        fontSize = 12.sp,
                        color = colors.secondaryText,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // 5. HARDWARE MASTER TELEMETRY MICRO-READOUT FOOTER
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DSP MOTOR // D-887",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.secondaryText.copy(alpha = 0.6f)
            )
            Text(
                text = "PEAK HEADROOM: -2.4 dB",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.secondaryText.copy(alpha = 0.6f)
            )
            Text(
                text = "BIT-PERFECT 24b/96k",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.secondaryText.copy(alpha = 0.6f)
            )
        }
    }

    selectedProfileData?.let { data ->
        SongProfileSheet(
            profile = data,
            vm = vm,
            onDismiss = { selectedProfileData = null }
        )
    }

    if (showPlayerSelectorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPlayerSelectorSheet = false },
            containerColor = DeepObsidianAmber.CardElevated,
            contentColor = DeepObsidianAmber.TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "AUDIO ROUTING SWITCHBOARD",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            color = DeepObsidianAmber.PrimaryAccent,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Select the music app or local audio player TuneMind dispatches to",
                            fontSize = 11.sp,
                            color = DeepObsidianAmber.TextSecondary
                        )
                    }

                    IconButton(onClick = { showPlayerSelectorSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = DeepObsidianAmber.TextSecondary)
                    }
                }

                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f))

                // List all discovered music providers
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    installedProviders.forEach { provider ->
                        val isSelected = provider.providerId == agentState.targetProviderId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) DeepObsidianAmber.RecessedBackground else DeepObsidianAmber.CardBackground,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    voiceAgent.setTargetPlayer(provider.providerId, provider.displayName)
                                    showPlayerSelectorSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.2f)
                                                else DeepObsidianAmber.RecessedBackground
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            if (provider.providerId == "local_files") Icons.Outlined.Folder
                                            else Icons.Outlined.MusicNote,
                                            contentDescription = null,
                                            tint = if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = provider.displayName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextPrimary
                                        )
                                        Text(
                                            text = if (provider.providerId == "local_files") "On-device MP3 / FLAC / WAV audio files"
                                            else "External Streaming & Media Provider",
                                            fontSize = 10.sp,
                                            color = DeepObsidianAmber.TextSecondary
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.2f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.PrimaryAccent)
                                    ) {
                                        Text(
                                            text = "ACTIVE",
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = DeepObsidianAmber.PrimaryAccent,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
