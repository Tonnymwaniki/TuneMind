package com.tunemind.app.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.recommendation.RecommendationCategory
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.model.TuneMindDemoData
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SongProfileData
import com.tunemind.app.ui.components.SongProfileSheet
import com.tunemind.app.ui.theme.*

@Composable
fun HomeScreen(
    vm: TuneMindViewModel,
    onOpenFatigue: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenStudy: () -> Unit = {},
    onOpenMoments: () -> Unit = {},
    onOpenHearing: () -> Unit = {}
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
    val context = androidx.compose.ui.platform.LocalContext.current
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.primaryBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. TOP HARDWARE TELEMETRY BAR
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "TUNEMIND",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primaryText,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                // Live Daemon Pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.cardSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isListenerBound) Color(0xFF10B981) else Color(0xFFF59E0B))
                        )
                        Text(
                            text = if (isListenerBound) "DAEMON // LIVE" else "DAEMON // OFFLINE",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondaryText,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }

            // Tactile Rotary Utility Keys
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onOpenMoments,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colors.cardSurface)
                        .border(1.dp, colors.border.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Outlined.GraphicEq, contentDescription = "Moments", tint = colors.primaryAccent, modifier = Modifier.size(16.dp))
                }

                IconButton(
                    onClick = onOpenHearing,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colors.cardSurface)
                        .border(1.dp, colors.border.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Outlined.Headphones, contentDescription = "Hearing", tint = colors.secondaryAccent, modifier = Modifier.size(16.dp))
                }
            }
        }

        // 2. HERO NOW PLAYING DECK (Outer Machined Rim Vinyl Deck)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.secondaryBackground)
                .border(1.dp, colors.border.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Machined Vinyl Bezel Ring with Radial Sheen
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                colors.elevatedSurface,
                                colors.cardSurface,
                                colors.primaryBackground
                            )
                        )
                    )
                    .border(2.dp, colors.border, CircleShape)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                // Vinyl Groove Inner Disc
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.cardSurface)
                        .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Center Disc Art / Graphic
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(colors.primaryAccent.copy(alpha = 0.2f))
                                .border(1.dp, colors.primaryAccent.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = colors.primaryAccent,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    // Micro Monospace Hardware ID Badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = colors.primaryBackground.copy(alpha = 0.85f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = "VINYL-EMU // 48K",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondaryText,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Acoustic Metadata Strip
            Text(
                text = nowPlayingTitle,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = nowPlayingArtist,
                    fontSize = 13.sp,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(colors.border)
                )
                Text(
                    text = "$nowPlayingSource Stream",
                    fontSize = 12.sp,
                    color = colors.secondaryText
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Vibe & Harmonic Frequency Chip
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.elevatedSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.6f))
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
                            .background(colors.primaryAccent)
                    )
                    Text(
                        text = "REFLECTIVE // 118 BPM",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryAccent,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                        color = if (dynamicSatietyPercent < 70) colors.primaryAccent else Color(0xFFF59E0B)
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
                            else -> Color(0xFF83B9BD)
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
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Machined Transport Controls Deck
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.primaryBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Bedtime Rest Track Toggle
                    IconButton(
                        onClick = { isRestTrackActive = !isRestTrackActive },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.cardSurface)
                    ) {
                        Icon(
                            Icons.Outlined.Bedtime,
                            contentDescription = "Rest Track",
                            tint = if (isRestTrackActive) colors.secondaryAccent else colors.secondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Stepper: Previous
                    IconButton(
                        onClick = { vm.playPrevious() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.cardSurface)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = colors.primaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Centerpiece: Laser Rotary Console Dial
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(colors.primaryAccent)
                            .clickable {
                                if (isPlaying) vm.pauseTrack() else vm.resumeTrack()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = colors.primaryBackground,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Stepper: Next
                    IconButton(
                        onClick = { vm.playNext() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.cardSurface)
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = colors.primaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Dynamic Cycle / Loop Switch
                    IconButton(
                        onClick = { isAcousticLoopActive = !isAcousticLoopActive },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.cardSurface)
                    ) {
                        Icon(
                            Icons.Outlined.AllInclusive,
                            contentDescription = "Loop",
                            tint = if (isAcousticLoopActive) colors.primaryAccent else colors.secondaryText,
                            modifier = Modifier.size(18.dp)
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
                shape = RoundedCornerShape(12.dp),
                color = colors.secondaryBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        recommendations.firstOrNull()?.let { vm.launchTrackPlayback(it) }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Cassette Pin Visual
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.primaryBackground)
                                .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.GraphicEq,
                                contentDescription = null,
                                tint = colors.primaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Midnight City",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryText
                            )
                            Text(
                                text = "M83 • Warm vintage synth palette • 94% harmonic match",
                                fontSize = 11.sp,
                                color = colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = colors.primaryAccent.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "DOPAMINE RE-ENGAGE",
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.primaryAccent,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = "Δ -12dB FATIGUE",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF83B9BD)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            recommendations.firstOrNull()?.let { vm.launchTrackPlayback(it) }
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.cardSurface)
                    ) {
                        Icon(
                            Icons.Outlined.QueueMusic,
                            contentDescription = "Queue",
                            tint = colors.primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Modular Sound Cartridge 2: Calm Spectral Recovery
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.secondaryBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        recommendations.getOrNull(1)?.let { vm.launchTrackPlayback(it) }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.primaryBackground)
                                .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Bedtime,
                                contentDescription = null,
                                tint = colors.secondaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Resonance",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryText
                            )
                            Text(
                                text = "HOME • Chilled spectral rest • 88% acoustic parity",
                                fontSize = 11.sp,
                                color = colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = colors.secondaryAccent.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "CALM RECOVERY",
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.secondaryAccent,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = "NEURO-DRIFT: 0.12",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.secondaryText
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            recommendations.getOrNull(1)?.let { vm.launchTrackPlayback(it) }
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.cardSurface)
                    ) {
                        Icon(
                            Icons.Outlined.QueueMusic,
                            contentDescription = "Queue",
                            tint = colors.secondaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // 4. GEMINI REASONING SYNAPSE & TELEMETRY
        if (isGeminiConfigured) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.secondaryBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
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
                                text = "GEMINI REASONING CORE",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryAccent,
                                letterSpacing = 1.sp
                            )
                        }
                        if (isReasoningLoading || isGeneratingReport) {
                            CircularProgressIndicator(color = colors.primaryAccent, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }

                    val reportSummary = reasoningReport?.executiveSummary
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
            data = data,
            onDismiss = { selectedProfileData = null },
            onAddToCooldown = { vm.addToVault(data.title, data.artist, 5) }
        )
    }
}
