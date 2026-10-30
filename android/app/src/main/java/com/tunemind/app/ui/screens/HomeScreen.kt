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
    var showBookmarkMomentDialog by remember { mutableStateOf(false) }
    var bookmarkStartMs by remember { mutableStateOf(0L) }
    var bookmarkEndMs by remember { mutableStateOf(30_000L) }
    var bookmarkCustomName by remember { mutableStateOf("") }
    val momentsState by vm.musicalMomentsState.collectAsState()
    val hearingState by vm.hearingInsightsState.collectAsState()
    val studyState by vm.studyState.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    val recommendations by vm.recommendations.collectAsState()
    val playbackFeedback by vm.playbackLaunchFeedback.collectAsState()

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
    val isLiveObserved = nowPlaying != null || latestRealTrack != null

    // Dynamic Satiety based on real play occurrences
    val currentTrackPlays = latestRealTrack?.distinctAppearanceCount ?: 1
    val dynamicSatietyPercent = minOf(95, maxOf(18, currentTrackPlays * 22))

    val elapsedDays = studyState.startDateMs?.let { start ->
        val elapsed = System.currentTimeMillis() - start
        (elapsed / 86_400_000f).coerceIn(0f, 7f)
    } ?: 0f
    val currentDayNumber = (elapsedDays + 1).toInt().coerceAtMost(7)

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
            vm.rebindListener()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950 canvas
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Sleek Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isPlaying) AccentGreen else AccentCyan)
                )
                Text(
                    text = "TuneMind",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0F172A),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Text(
                        text = if (isPlaying) "LIVE • $nowPlayingSource" else "OBSERVER READY",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isPlaying) AccentGreen else AccentCyanBright,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            // Quick Studio Shortcuts
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenMoments, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Moments", tint = AccentCyan, modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onOpenHearing, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Headphones, contentDescription = "Hearing", tint = AccentCyanBright, modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onOpenWeeklyReport, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Insights, contentDescription = "Insights", tint = Color(0xFF94A3B8), modifier = Modifier.size(19.dp))
                }
            }
        }

        // Notification Access Alert (Compact & non-intrusive)
        if (!hasAccess || !isListenerBound) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF451A03).copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(16.dp))
                        Text(
                            text = if (!hasAccess) "Enable Notification Access to capture audio" else "Tap Rebind to reconnect listener",
                            fontSize = 11.sp,
                            color = Color(0xFFFED7AA)
                        )
                    }
                    Button(
                        onClick = {
                            if (!hasAccess) {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            } else {
                                vm.rebindListener()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color(0xFF020617)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(if (!hasAccess) "Enable" else "Rebind", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Playback Toast / Alert Banner
        playbackFeedback?.let { feedback ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (feedback.isSuccess) Color(0xFF064E3B) else Color(0xFF451A03),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (feedback.isSuccess) AccentGreen else AccentAmber),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(feedback.message, fontSize = 11.sp, color = Color.White)
                    IconButton(onClick = { vm.clearPlaybackFeedback() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // 2. NOW PLAYING HERO (Seamless Spotify-Inspired Ambient Canvas)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0E7490).copy(alpha = 0.28f),
                            Color(0xFF0F172A),
                            Color(0xFF020617)
                        )
                    )
                )
                .border(
                    1.dp,
                    if (isPlaying) Color(0xFF06B6D4).copy(alpha = 0.35f) else Color(0xFF1E293B),
                    RoundedCornerShape(22.dp)
                )
                .clickable {
                    selectedProfileData = SongProfileData.fromNowPlaying(nowPlaying, latestRealTrack)
                }
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Top Row: Artwork + Track details
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val nowPlayingEmoji = remember(nowPlayingTitle, nowPlayingArtist) {
                        SongProfileData.pickEmoji(nowPlayingTitle, nowPlayingArtist)
                    }
                    val nowPlayingVibe = remember(nowPlayingTitle, nowPlayingArtist, dynamicSatietyPercent) {
                        SongProfileData.pickVibeTag(nowPlayingTitle, nowPlayingArtist, dynamicSatietyPercent)
                    }

                    // Album Art Emoji Tile
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF0284C7), Color(0xFF4F46E5), Color(0xFF0F172A))
                                )
                            )
                            .border(1.dp, Brush.linearGradient(listOf(AccentCyan, PrimaryViolet)), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = nowPlayingEmoji, fontSize = 34.sp)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nowPlayingTitle,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = nowPlayingArtist,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0x3306B6D4)
                            ) {
                                Text(
                                    text = nowPlayingVibe,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentCyanBright,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0x26F59E0B)
                            ) {
                                Text(
                                    text = "$dynamicSatietyPercent% Satiety",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentAmber,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Scrub Bar
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = AccentCyan,
                        trackColor = Color(0xFF1E293B)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(currentPositionMs),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = formatDuration(reportedDurationMs),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                // Transport Controls & Moment Bookmark Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick Action: Bookmark Moment
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0x2606B6D4),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x6606B6D4)),
                        modifier = Modifier.clickable {
                            bookmarkStartMs = maxOf(0L, currentPositionMs - 15_000L)
                            bookmarkEndMs = minOf(reportedDurationMs, bookmarkStartMs + 45_000L)
                            bookmarkCustomName = "Section: $nowPlayingTitle"
                            showBookmarkMomentDialog = true
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(14.dp))
                            Text("Bookmark Moment", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentCyanBright)
                        }
                    }

                    // Playback Controls
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { vm.addToVault(nowPlayingTitle, nowPlayingArtist) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Outlined.AcUnit, contentDescription = "Rest Track", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                        }

                        Surface(
                            shape = CircleShape,
                            color = AccentCyan,
                            modifier = Modifier
                                .size(38.dp)
                                .clickable { vm.togglePlayback() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color(0xFF020617),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = { vm.skipNext() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // 3. UNIFIED INTELLIGENCE RIBBON (Single 3-Column Glass Capsule replacing 4 stacked cards)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F172A),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Satiety & Fatigue Capsule
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenFatigue() }
                ) {
                    Text("SATIETY", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                    Text(
                        "$dynamicSatietyPercent%",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (dynamicSatietyPercent >= 70) AccentAmber else AccentGreen
                    )
                    Text(
                        if (dynamicSatietyPercent >= 70) "High Repetition" else "Fresh Flow",
                        fontSize = 9.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color(0xFF1E293B)))

                // Hearing Exposure Capsule
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenHearing() }
                ) {
                    Text("HEARING", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                    Text(
                        "~${hearingState.estimatedAverageDb.toInt()} dB",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (hearingState.estimatedAverageDb > 85f) AccentAmber else AccentCyan
                    )
                    Text(
                        if (hearingState.estimatedAverageDb > 85f) "High Volume" else "Safe Level",
                        fontSize = 9.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color(0xFF1E293B)))

                // Neuro-Acoustic Study Capsule
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenStudy() }
                ) {
                    Text("NEURO STUDY", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                    Text(
                        "Day $currentDayNumber / 7",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = PrimaryVioletBright
                    )
                    Text(
                        "${studyState.observationCount} Observations",
                        fontSize = 9.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        // 4. HORIZONTAL SHELF: FOR YOU • COGNITIVE PLAYLIST
        if (recommendations.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentCyanBright, modifier = Modifier.size(15.dp))
                        Text(
                            text = "FOR YOU • COGNITIVE FLOW",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyanBright,
                            letterSpacing = 1.sp
                        )
                    }
                    IconButton(onClick = { vm.refreshRecommendations() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(recommendations.take(6)) { rec ->
                        CompactRecommendationTile(
                            track = rec,
                            onPlay = { vm.playRecommendation(context, rec) },
                            onProfile = { selectedProfileData = SongProfileData.fromRecommendedTrack(rec) }
                        )
                    }
                }
            }
        }

        // 5. HORIZONTAL SHELF: MY MUSICAL MOMENTS
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                    Text(
                        text = "MY MUSICAL MOMENTS",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan,
                        letterSpacing = 1.sp
                    )
                }
                TextButton(
                    onClick = onOpenMoments,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("Studio →", fontSize = 11.sp, color = AccentCyanBright, fontWeight = FontWeight.Bold)
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // First Item: Create Moment Chip
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0F172A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier
                            .width(130.dp)
                            .height(115.dp)
                            .clickable {
                                bookmarkStartMs = maxOf(0L, currentPositionMs - 15_000L)
                                bookmarkEndMs = minOf(reportedDurationMs, bookmarkStartMs + 45_000L)
                                bookmarkCustomName = "Moment: $nowPlayingTitle"
                                showBookmarkMomentDialog = true
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(AccentCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("New Moment", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Save section", fontSize = 9.sp, color = Color(0xFF64748B))
                        }
                    }
                }

                // Saved Moments
                items(momentsState.confirmedMoments) { moment ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0F172A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier
                            .width(160.dp)
                            .height(115.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = moment.customName ?: "${moment.trackTitle} Moment",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = moment.trackTitle,
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0x3306B6D4)
                                ) {
                                    Text(
                                        text = "${formatDuration(moment.startPositionMs)} - ${formatDuration(moment.endPositionMs)}",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentCyanBright,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { vm.deleteMusicalMoment(moment.momentId) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color(0xFF64748B), modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. RECENT ACOUSTIC TIMELINE (Sleek Spotify-Style List Rows, NOT heavy cards)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                    Text(
                        text = "RECENT AUDIO TIMELINE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8),
                        letterSpacing = 1.sp
                    )
                }
                TextButton(
                    onClick = onOpenHistory,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("Full Timeline →", fontSize = 11.sp, color = AccentCyanBright, fontWeight = FontWeight.Bold)
                }
            }

            if (state.recentTracks.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
                ) {
                    state.recentTracks.take(4).forEachIndexed { index, track ->
                        SleekTrackRow(
                            track = track,
                            onClick = { selectedProfileData = SongProfileData.fromTrackMemory(track) }
                        )
                        if (index < minOf(3, state.recentTracks.size - 1)) {
                            HorizontalDivider(color = Color(0xFF1E293B), thickness = 0.7.dp)
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0F172A),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        Text(
                            "Awaiting live audio from Spotify or YouTube Music...",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // Bookmark Musical Moment Dialog (Dynamic full-song duration range)
    if (showBookmarkMomentDialog) {
        val totalTrackMs = maxOf(60_000L, reportedDurationMs)

        AlertDialog(
            onDismissRequest = { showBookmarkMomentDialog = false },
            containerColor = Color(0xFF0F172A),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = null, tint = AccentCyan)
                    Text("Bookmark Musical Moment", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Mark your favorite section of '$nowPlayingTitle' by $nowPlayingArtist. Length up to ${formatDuration(totalTrackMs)}.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )

                    OutlinedTextField(
                        value = bookmarkCustomName,
                        onValueChange = { bookmarkCustomName = it },
                        label = { Text("Moment Label (e.g. Guitar Solo, Drop)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Dual range controls
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Start: ${formatDuration(bookmarkStartMs)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentCyan)
                            Text("End: ${formatDuration(bookmarkEndMs)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentCyanBright)
                        }

                        Slider(
                            value = bookmarkStartMs.toFloat(),
                            onValueChange = {
                                bookmarkStartMs = it.toLong()
                                if (bookmarkEndMs <= bookmarkStartMs + 5_000L) {
                                    bookmarkEndMs = minOf(totalTrackMs, bookmarkStartMs + 30_000L)
                                }
                            },
                            valueRange = 0f..(totalTrackMs - 5_000L).toFloat(),
                            colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentCyan)
                        )

                        Slider(
                            value = bookmarkEndMs.toFloat(),
                            onValueChange = {
                                bookmarkEndMs = it.toLong()
                                if (bookmarkStartMs >= bookmarkEndMs - 5_000L) {
                                    bookmarkStartMs = maxOf(0L, bookmarkEndMs - 30_000L)
                                }
                            },
                            valueRange = 5_000f..totalTrackMs.toFloat(),
                            colors = SliderDefaults.colors(thumbColor = AccentCyanBright, activeTrackColor = AccentCyanBright)
                        )
                    }

                    // Quick length chips
                    Text("QUICK LENGTH PRESETS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            30_000L to "30s",
                            60_000L to "1m",
                            90_000L to "1m 30s",
                            120_000L to "2m",
                            totalTrackMs to "Full Track"
                        ).forEach { (len, label) ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF020617),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier.clickable {
                                    if (len == totalTrackMs) {
                                        bookmarkStartMs = 0L
                                        bookmarkEndMs = totalTrackMs
                                    } else {
                                        bookmarkEndMs = minOf(totalTrackMs, bookmarkStartMs + len)
                                    }
                                }
                            ) {
                                Text(
                                    label,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    val dur = bookmarkEndMs - bookmarkStartMs
                    val durLabel = if (dur >= 60_000L) "${dur / 60_000L}m ${(dur % 60_000L) / 1000L}s" else "${dur / 1000L}s"

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF020617),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Total Section Playback:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text(
                                durLabel,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentGreen
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.saveMusicalMoment(
                            title = nowPlayingTitle,
                            artist = nowPlayingArtist,
                            sourcePkg = nowPlaying?.sourcePackage,
                            startMs = bookmarkStartMs,
                            endMs = bookmarkEndMs,
                            customName = bookmarkCustomName.takeIf { it.isNotBlank() }
                        )
                        showBookmarkMomentDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF020617))
                ) {
                    Text("Save Moment", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBookmarkMomentDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    selectedProfileData?.let { profile ->
        SongProfileSheet(
            profile = profile,
            vm = vm,
            onDismiss = { selectedProfileData = null }
        )
    }
}

// Compact Recommendation Tile for the Horizontal Shelf
@Composable
private fun CompactRecommendationTile(
    track: RecommendedTrack,
    onPlay: () -> Unit,
    onProfile: () -> Unit
) {
    val categoryBadgeColor = when (track.category) {
        RecommendationCategory.EXPLORATION_DISCOVERY -> Color(0xFF8B5CF6)
        RecommendationCategory.COOLDOWN_RESTORED -> AccentGreen
        RecommendationCategory.PALATE_CLEANSER -> Color(0xFF06B6D4)
        RecommendationCategory.FAMILIAR_RECEPTIVE -> AccentAmber
    }
    val categoryLabel = when (track.category) {
        RecommendationCategory.EXPLORATION_DISCOVERY -> "DISCOVERY"
        RecommendationCategory.COOLDOWN_RESTORED -> "RESTORED"
        RecommendationCategory.PALATE_CLEANSER -> "CLEANSER"
        RecommendationCategory.FAMILIAR_RECEPTIVE -> "FAMILIAR"
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0F172A),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = Modifier
            .width(165.dp)
            .height(145.dp)
            .clickable { onProfile() }
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = categoryBadgeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = categoryLabel,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = categoryBadgeColor,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = "${(track.confidence * 100).toInt()}% Match",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
            }

            Column {
                Text(
                    text = track.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    fontSize = 10.sp,
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onPlay,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF020617)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.fillMaxWidth().height(26.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("Play", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Sleek Spotify-Style Timeline Row (No heavy nested card borders)
@Composable
private fun SleekTrackRow(
    track: TrackMemoryEntry,
    onClick: () -> Unit
) {
    val trackEmoji = remember(track.title, track.artist) {
        SongProfileData.pickEmoji(track.title ?: "", track.artist ?: "")
    }
    val trackSatiety = minOf(95, track.distinctAppearanceCount * 22)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mini Artwork Tile
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E293B)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = trackEmoji, fontSize = 18.sp)
        }

        // Title and Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title ?: "Unknown Track",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${track.artist ?: "Unknown Artist"} • ${track.distinctAppearanceCount} play(s)",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Satiety Pill
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (trackSatiety >= 60) Color(0xFFF59E0B).copy(alpha = 0.15f) else Color(0xFF06B6D4).copy(alpha = 0.15f)
        ) {
            Text(
                text = "$trackSatiety% Sat.",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (trackSatiety >= 60) AccentAmber else AccentCyanBright,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}


