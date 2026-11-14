package com.tunemind.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.ui.HearingInsightsState
import com.tunemind.app.ui.components.NeuralSonicActionButton
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.ui.theme.ThemeBehavior
import com.tunemind.app.ui.theme.ThemePreferences
import kotlinx.coroutines.launch
import java.util.Calendar

private data class CircadianPhase(
    val id: Int,
    val paletteId: String,
    val label: String,
    val hoursLabel: String,
    val targetBpm: Int,
    val color: Color,
    val startAngle: Float,
    val sweepAngle: Float
)

@Composable
fun RhythmDnaScreen(
    recentTracks: List<TrackMemoryEntry>,
    hearingInsights: HearingInsightsState,
    selectedTargetPlayer: String,
    themePreferences: ThemePreferences,
    onUpdateThemePreferences: (ThemePreferences) -> Unit,
    onSelectTargetPlayer: (String) -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onAskAgentAboutTrack: (title: String, artist: String, freshnessPercent: Int, spotifyUri: String?) -> Unit,
    onSyncAtmosphereToTrack: (title: String, artist: String) -> Unit,
    onQuarantineTrack: (title: String, artist: String) -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val context = LocalContext.current
    val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }

    val phases = remember {
        listOf(
            CircadianPhase(0, "morning", "Morning Focus", "06:00 – 12:00", 112, Color(0xFF00E5FF), -90f, 82f),
            CircadianPhase(1, "afternoon", "Midday Drive", "12:00 – 17:00", 118, Color(0xFF10B981), 0f, 82f),
            CircadianPhase(2, "evening", "Evening Unwind", "17:00 – 22:00", 98, Color(0xFFFFB000), 90f, 82f),
            CircadianPhase(3, "night", "Late Night", "22:00 – 06:00", 86, Color(0xFFA855F7), 180f, 82f)
        )
    }

    val livePhaseIndex = when (currentHour) {
        in 6..11 -> 0
        in 12..16 -> 1
        in 17..21 -> 2
        else -> 3
    }

    var selectedPhaseIndex by remember { mutableStateOf(livePhaseIndex) }
    val activePhase = phases[selectedPhaseIndex]

    var activeSyncTrack by remember { mutableStateOf<TrackMemoryEntry?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    var serverUrlInput by remember {
        mutableStateOf(com.tunemind.app.domain.network.BackendEndpointManager.getCustomBaseUrl(context))
    }
    var connectionLabel by remember {
        mutableStateOf(com.tunemind.app.domain.network.BackendEndpointManager.getDisplayConnectionLabel(context))
    }

    val powerTracks = remember(recentTracks, selectedPhaseIndex) {
        val unique = recentTracks
            .filter { !it.title.isNullOrBlank() }
            .distinctBy { "${it.title?.trim()?.lowercase()}|${it.artist?.trim()?.lowercase()}" }
        if (unique.size <= 5) unique
        else {
            val offset = (selectedPhaseIndex * 2) % maxOf(1, unique.size)
            (unique.drop(offset) + unique.take(offset)).take(5)
        }
    }

    val topArtists = remember(recentTracks) {
        recentTracks
            .mapNotNull { it.artist?.trim()?.takeIf { a -> a.isNotBlank() && !a.equals("Unknown Artist", ignoreCase = true) } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(4)
    }

    val dbValue = hearingInsights.estimatedAverageDb.toInt().coerceIn(45, 95)
    val audioRouteLabel = hearingInsights.activeAudioDevice?.deviceName?.takeIf { it.isNotBlank() }
        ?: "Speaker / Wireless"

    val spotifyRepo = remember {
        com.tunemind.app.data.ingestion.SpotifyOAuthRepository.getInstance(context)
    }
    val isSpotifyConnected by spotifyRepo.isConnectedFlow.collectAsState()
    val syncedRecentHistory by spotifyRepo.observeLocalRecentlyPlayed(100).collectAsState(initial = emptyList())
    val syncedAllTimeTracks by spotifyRepo.observeLocalAggregatedTracks(100).collectAsState(initial = emptyList())
    val spotifyAuthManager = remember {
        com.tunemind.app.data.ingestion.SpotifyAuthManager(context)
    }
    val authSessionManager = remember {
        com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(context)
    }
    val authSession by authSessionManager.sessionFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // 0 = Daily Rhythm, 1 = Listening History
    var activeRhythmSubDeck by remember { mutableStateOf(0) }
    // 0 = Recent, 1 = All Time
    var archiveTimeFilter by remember { mutableStateOf(0) }
    var isSyncingNow by remember { mutableStateOf(false) }

    LaunchedEffect(isSpotifyConnected, authSession.isSpotifyConnected) {
        if ((isSpotifyConnected || authSession.isSpotifyConnected) && syncedRecentHistory.isEmpty()) {
            isSyncingNow = true
            spotifyRepo.syncRecentlyPlayedToLocalRoom()
            isSyncingNow = false
        }
    }

    StudioAmbientBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 108.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (activeRhythmSubDeck == 0) "RHYTHM" else "SPOTIFY",
                            color = if (activeRhythmSubDeck == 0) theme.primaryAccent else Color(0xFF1DB954),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (activeRhythmSubDeck == 0) "Daily Flow" else "Listening History",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF0E121B).copy(alpha = 0.90f))
                            .border(1.dp, theme.primaryAccent.copy(alpha = 0.28f), RoundedCornerShape(50))
                            .bouncyClickable {
                                serverUrlInput = com.tunemind.app.domain.network.BackendEndpointManager.getCustomBaseUrl(context)
                                showServerDialog = true
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudQueue,
                            contentDescription = "Server Settings",
                            tint = theme.primaryAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Server",
                            color = Color.White.copy(alpha = 0.90f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Segmented Switcher: Daily Rhythm vs History
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0C1018))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (activeRhythmSubDeck == 0) theme.primaryAccent.copy(alpha = 0.18f)
                                else Color.Transparent
                            )
                            .border(
                                1.dp,
                                if (activeRhythmSubDeck == 0) theme.primaryAccent.copy(alpha = 0.45f)
                                else Color.Transparent,
                                RoundedCornerShape(14.dp)
                            )
                            .bouncyClickable { activeRhythmSubDeck = 0 }
                            .padding(vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = if (activeRhythmSubDeck == 0) theme.primaryAccent else Color.White.copy(alpha = 0.56f),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Daily Rhythm",
                            color = if (activeRhythmSubDeck == 0) Color.White else Color.White.copy(alpha = 0.60f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (activeRhythmSubDeck == 1) Color(0xFF1DB954).copy(alpha = 0.18f)
                                else Color.Transparent
                            )
                            .border(
                                1.dp,
                                if (activeRhythmSubDeck == 1) Color(0xFF1DB954).copy(alpha = 0.55f)
                                else Color.Transparent,
                                RoundedCornerShape(14.dp)
                            )
                            .bouncyClickable { activeRhythmSubDeck = 1 }
                            .padding(vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = if (activeRhythmSubDeck == 1) Color(0xFF1DB954) else Color.White.copy(alpha = 0.56f),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "History (${syncedRecentHistory.size})",
                            color = if (activeRhythmSubDeck == 1) Color(0xFF1DB954) else Color.White.copy(alpha = 0.60f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (activeRhythmSubDeck == 1) {
                item {
                    SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Spotify Library",
                                        color = Color(0xFF1DB954),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isSyncingNow) {
                                            "Syncing recent plays…"
                                        } else {
                                            "${syncedRecentHistory.size} recent plays • ${syncedAllTimeTracks.size} unique tracks"
                                        },
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF1DB954))
                                        .bouncyClickable {
                                            if (isSpotifyConnected || authSession.isSpotifyConnected) {
                                                coroutineScope.launch {
                                                    isSyncingNow = true
                                                    spotifyRepo.syncRecentlyPlayedToLocalRoom()
                                                    isSyncingNow = false
                                                }
                                            } else {
                                                spotifyAuthManager.launchSpotifyAuth(context)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (isSyncingNow) "Syncing…"
                                        else if (isSpotifyConnected || authSession.isSpotifyConnected) "Sync Now"
                                        else "Link Spotify",
                                        color = Color.Black,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (archiveTimeFilter == 0) Color(0xFF1DB954).copy(alpha = 0.20f)
                                            else Color.Transparent
                                        )
                                        .bouncyClickable { archiveTimeFilter = 0 }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Schedule,
                                        contentDescription = null,
                                        tint = if (archiveTimeFilter == 0) Color(0xFF1DB954) else Color.White.copy(alpha = 0.58f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "Recent (${syncedRecentHistory.size})",
                                        color = if (archiveTimeFilter == 0) Color(0xFF1DB954) else Color.White.copy(alpha = 0.65f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (archiveTimeFilter == 1) Color(0xFF00E5FF).copy(alpha = 0.18f)
                                            else Color.Transparent
                                        )
                                        .bouncyClickable { archiveTimeFilter = 1 }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AllInclusive,
                                        contentDescription = null,
                                        tint = if (archiveTimeFilter == 1) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.58f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "All Time (${syncedAllTimeTracks.size})",
                                        color = if (archiveTimeFilter == 1) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.65f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                if (archiveTimeFilter == 0) {
                    if (syncedRecentHistory.isEmpty()) {
                        item {
                            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = if (isSpotifyConnected || authSession.isSpotifyConnected) {
                                            "No synced history yet — tap Sync Now above to load your recent Spotify tracks."
                                        } else {
                                            "Connect your Spotify account to keep your listening history synced automatically."
                                        },
                                        color = Color.White.copy(alpha = 0.70f),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = syncedRecentHistory,
                            key = { _, item -> item.historyId }
                        ) { idx, historyItem ->
                            val minsAgo = ((System.currentTimeMillis() - historyItem.playedAtEpochMs).coerceAtLeast(0L) / 60_000L)
                            val relativeLabel = when {
                                minsAgo < 1L -> "Just now"
                                minsAgo < 60L -> "${minsAgo}m ago"
                                minsAgo < 1440L -> "${minsAgo / 60L}h ago"
                                else -> "${minsAgo / 1440L}d ago"
                            }
                            val durationSec = (historyItem.durationMs / 1000L).coerceAtLeast(0L)
                            val durationFormatted = String.format("%d:%02d", durationSec / 60L, durationSec % 60L)

                            SoftGlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bouncyClickable {
                                        onPlayTrack(
                                            historyItem.trackTitle,
                                            historyItem.artistName,
                                            historyItem.spotifyUri,
                                            "com.spotify.music"
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF1DB954).copy(alpha = 0.16f))
                                                .border(1.dp, Color(0xFF1DB954).copy(alpha = 0.40f), RoundedCornerShape(10.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                color = Color(0xFF1DB954),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = historyItem.trackTitle,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = buildString {
                                                    append(historyItem.artistName)
                                                    if (historyItem.albumName.isNotBlank()) {
                                                        append(" • ${historyItem.albumName}")
                                                    }
                                                },
                                                color = Color.White.copy(alpha = 0.65f),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "$relativeLabel • $durationFormatted",
                                                color = Color(0xFF1DB954).copy(alpha = 0.88f),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF1DB954))
                                            .bouncyClickable {
                                                onPlayTrack(
                                                    historyItem.trackTitle,
                                                    historyItem.artistName,
                                                    historyItem.spotifyUri,
                                                    "com.spotify.music"
                                                )
                                            }
                                            .padding(horizontal = 11.dp, vertical = 7.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = "Play",
                                            tint = Color.Black,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "Play",
                                            color = Color.Black,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val allTimeItems = if (syncedAllTimeTracks.isNotEmpty()) {
                        syncedAllTimeTracks
                    } else {
                        recentTracks.map { entry ->
                            com.tunemind.app.data.ingestion.SpotifyTrackAggregate(
                                trackId = entry.entryId,
                                spotifyUri = entry.providerTrackId ?: "",
                                trackTitle = entry.title ?: "Unknown Track",
                                artistName = entry.artist ?: "Unknown Artist",
                                albumName = entry.album ?: "",
                                albumArtUrl = null,
                                durationMs = 195_000L,
                                playCount = entry.distinctAppearanceCount.coerceAtLeast(1),
                                firstPlayedEpochMs = entry.firstObservedAppearanceAtMs ?: System.currentTimeMillis(),
                                lastPlayedEpochMs = entry.lastObservedAppearanceAtMs ?: System.currentTimeMillis()
                            )
                        }
                    }

                    itemsIndexed(
                        items = allTimeItems,
                        key = { _, agg -> "alltime_${agg.trackId}" }
                    ) { idx, agg ->
                        val totalMinutes = ((agg.durationMs * agg.playCount) / 60_000L).coerceAtLeast(1L)
                        SoftGlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bouncyClickable {
                                    onPlayTrack(
                                        agg.trackTitle,
                                        agg.artistName,
                                        agg.spotifyUri.ifBlank { null },
                                        "com.spotify.music"
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF00E5FF).copy(alpha = 0.14f))
                                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.42f), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "#${idx + 1}",
                                            color = Color(0xFF00E5FF),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = agg.trackTitle,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = agg.artistName,
                                            color = Color.White.copy(alpha = 0.65f),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${agg.playCount}x plays • ~${totalMinutes}m listening",
                                            color = Color(0xFF00E5FF),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF00E5FF))
                                        .bouncyClickable {
                                            onPlayTrack(
                                                agg.trackTitle,
                                                agg.artistName,
                                                agg.spotifyUri.ifBlank { null },
                                                "com.spotify.music"
                                            )
                                        }
                                        .padding(horizontal = 11.dp, vertical = 7.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.Black,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Play",
                                        color = Color.Black,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Spotify Account Card
                item {
                    SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Spotify Account",
                                        color = Color(0xFF1DB954),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isSpotifyConnected || authSession.isSpotifyConnected) {
                                            "Connected • ${syncedRecentHistory.size} tracks synced"
                                        } else {
                                            "Connect Spotify for seamless sync"
                                        },
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (authSession.email.isNotBlank() || authSession.displayName.isNotBlank()) {
                                        Text(
                                            text = "${authSession.displayName}${if (authSession.email.isNotBlank()) " • ${authSession.email}" else ""}",
                                            color = Color.White.copy(alpha = 0.62f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSpotifyConnected || authSession.isSpotifyConnected) Color(0xFF1DB954).copy(alpha = 0.18f)
                                                else Color(0xFF1DB954)
                                            )
                                            .border(
                                                1.dp,
                                                Color(0xFF1DB954).copy(alpha = 0.65f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .bouncyClickable {
                                                if (isSpotifyConnected || authSession.isSpotifyConnected) {
                                                    coroutineScope.launch {
                                                        isSyncingNow = true
                                                        spotifyRepo.syncRecentlyPlayedToLocalRoom()
                                                        isSyncingNow = false
                                                    }
                                                    activeRhythmSubDeck = 1
                                                } else {
                                                    spotifyAuthManager.launchSpotifyAuth(context)
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = if (isSpotifyConnected || authSession.isSpotifyConnected) "History" else "Connect",
                                            color = if (isSpotifyConnected || authSession.isSpotifyConnected) Color(0xFF1DB954) else Color.Black,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.White.copy(alpha = 0.07f))
                                            .border(
                                                1.dp,
                                                Color.White.copy(alpha = 0.16f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .bouncyClickable {
                                                authSessionManager.signOut()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "Sign Out",
                                            color = Color.White.copy(alpha = 0.80f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Live Adaptive Theme Card
                item {
                    SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = theme.primaryAccent,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "Adaptive Theme",
                                            color = theme.primaryAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${theme.activePalette.name} • ${theme.musicMood.label}",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                val isAuto = themePreferences.behavior == ThemeBehavior.ADAPTIVE
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isAuto) Color(0xFF10B981).copy(alpha = 0.18f)
                                            else Color.White.copy(alpha = 0.06f)
                                        )
                                        .border(
                                            1.dp,
                                            if (isAuto) Color(0xFF10B981).copy(alpha = 0.55f)
                                            else Color.White.copy(alpha = 0.14f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .bouncyClickable {
                                            onUpdateThemePreferences(
                                                themePreferences.copy(
                                                    behavior = ThemeBehavior.ADAPTIVE,
                                                    enableMoodAdaptation = true,
                                                    enableEnergyAdaptation = true
                                                )
                                            )
                                        }
                                        .padding(horizontal = 11.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (isAuto) "Auto On" else "Set Auto",
                                        color = if (isAuto) Color(0xFF10B981) else Color.White.copy(alpha = 0.78f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            val atmospherePresets = listOf(
                                Triple("morning", "Glacier", Color(0xFF00E5FF)),
                                Triple("afternoon", "Emerald", Color(0xFF10B981)),
                                Triple("evening", "Ember", Color(0xFFFFB000)),
                                Triple("night", "Violet", Color(0xFFA855F7))
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                atmospherePresets.forEach { (id, label, color) ->
                                    val isSelected = theme.activePalette.id == id && themePreferences.behavior == ThemeBehavior.FIXED
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 40.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                if (isSelected) color.copy(alpha = 0.22f)
                                                else color.copy(alpha = 0.08f)
                                            )
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) color else color.copy(alpha = 0.26f),
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                            .bouncyClickable {
                                                onUpdateThemePreferences(
                                                    themePreferences.copy(
                                                        behavior = ThemeBehavior.FIXED,
                                                        fixedPaletteId = id
                                                    )
                                                )
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) Color.White else color,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 24-Hour Daily Rhythm Ring Card
                item {
                    SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(164.dp)
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    phases.forEachIndexed { index, phase ->
                                        val isSelected = index == selectedPhaseIndex
                                        drawArc(
                                            color = phase.color.copy(alpha = if (isSelected) 0.95f else 0.20f),
                                            startAngle = phase.startAngle,
                                            sweepAngle = phase.sweepAngle,
                                            useCenter = false,
                                            style = Stroke(
                                                width = if (isSelected) 12.dp.toPx() else 7.dp.toPx(),
                                                cap = StrokeCap.Round
                                            )
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = activePhase.label,
                                        color = activePhase.color,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${activePhase.targetBpm} BPM",
                                        color = Color.White,
                                        fontSize = 23.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${activePhase.hoursLabel} • $dbValue dB",
                                        color = Color.White.copy(alpha = 0.62f),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                phases.forEachIndexed { index, phase ->
                                    val isSelected = index == selectedPhaseIndex
                                    val shortTitle = when (index) {
                                        0 -> "Morning"
                                        1 -> "Midday"
                                        2 -> "Evening"
                                        else -> "Night"
                                    }
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 40.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                if (isSelected) phase.color.copy(alpha = 0.18f)
                                                else Color.White.copy(alpha = 0.04f)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) phase.color.copy(alpha = 0.50f)
                                                else Color.White.copy(alpha = 0.08f),
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                            .bouncyClickable {
                                                selectedPhaseIndex = index
                                                onUpdateThemePreferences(
                                                    themePreferences.copy(
                                                        behavior = ThemeBehavior.FIXED,
                                                        fixedPaletteId = phase.paletteId
                                                    )
                                                )
                                            }
                                            .padding(vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = shortTitle,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.56f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            if (topArtists.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Top:",
                                        color = Color.White.copy(alpha = 0.58f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    topArtists.take(3).forEach { artist ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(theme.primaryAccent.copy(alpha = 0.12f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = artist,
                                                color = Color.White.copy(alpha = 0.90f),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section Header: Top Rotation
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Top Rotation • ${activePhase.label}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Tap play to listen",
                            color = activePhase.color,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Power Rotation Song Rows
                itemsIndexed(powerTracks) { _, track ->
                    val title = track.title?.trim() ?: "Unknown Track"
                    val artist = track.artist?.trim() ?: "Unknown Artist"
                    val uri = track.providerTrackId?.takeIf { it.startsWith("spotify:track:") }
                    val pkg = selectedTargetPlayer.ifBlank {
                        track.sourcePackage?.ifBlank { "com.spotify.music" } ?: "com.spotify.music"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFF0C1018).copy(alpha = 0.90f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(activePhase.color.copy(alpha = 0.16f))
                                .border(1.dp, activePhase.color.copy(alpha = 0.45f), CircleShape)
                                .bouncyClickable {
                                    onPlayTrack(title, artist, uri, pkg)
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Play",
                                tint = activePhase.color,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = artist,
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        NeuralSonicActionButton(
                            accentColor = activePhase.color,
                            secondaryColor = theme.secondaryAccent,
                            onClick = { activeSyncTrack = track }
                        )
                    }
                }

                // Output & Target Player Selector Card
                item {
                    SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                        imageVector = Icons.Rounded.Headphones,
                                        contentDescription = null,
                                        tint = theme.primaryAccent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Playback Output",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = "$audioRouteLabel • $dbValue dB",
                                    color = Color(0xFF10B981),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            val playerOptions = listOf(
                                "com.spotify.music" to "Spotify",
                                "com.google.android.apps.youtube.music" to "YouTube Music",
                                "com.apple.android.music" to "Apple Music"
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                playerOptions.forEach { (pkg, label) ->
                                    val isSelected = selectedTargetPlayer == pkg
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minHeight = 42.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                if (isSelected) theme.primaryAccent.copy(alpha = 0.18f)
                                                else Color.White.copy(alpha = 0.04f)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) theme.primaryAccent.copy(alpha = 0.55f)
                                                else Color.White.copy(alpha = 0.08f),
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                            .bouncyClickable { onSelectTargetPlayer(pkg) }
                                            .padding(vertical = 10.dp, horizontal = 6.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.58f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            Text(
                                text = connectionLabel,
                                color = theme.primaryAccent.copy(alpha = 0.78f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }

    activeSyncTrack?.let { selected ->
        val title = selected.title?.trim() ?: "Unknown Track"
        val artist = selected.artist?.trim() ?: "Unknown Artist"
        val plays = maxOf(1, selected.distinctAppearanceCount)
        val freshness = (100 - minOf(94, plays * 19)).coerceIn(15, 98)
        val uri = selected.providerTrackId?.takeIf { it.startsWith("spotify:track:") }
        val pkg = selectedTargetPlayer.ifBlank {
            selected.sourcePackage?.ifBlank { "com.spotify.music" } ?: "com.spotify.music"
        }

        NeuralTrackActionDialog(
            title = title,
            artist = artist,
            freshnessPercent = freshness,
            playCount = plays,
            isCoolingDown = plays >= 4,
            onDismiss = { activeSyncTrack = null },
            onPlayNow = {
                onPlayTrack(title, artist, uri, pkg)
            },
            onAgentNeuralBreakdown = {
                onAskAgentAboutTrack(title, artist, freshness, uri)
            },
            onSyncAtmosphereToTrack = {
                onSyncAtmosphereToTrack(title, artist)
            },
            onToggleCooldownVault = {
                onQuarantineTrack(title, artist)
            }
        )
    }

    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            containerColor = Color(0xFF10131C),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.85f),
            title = {
                Text("Server Connection", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Connected to TuneMind Cloud by default. Enter a custom URL only if you run your own server:",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        placeholder = {
                            Text(
                                "https://tunemind-backend-iudp.onrender.com",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = theme.primaryAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        com.tunemind.app.domain.network.BackendEndpointManager.setCustomBaseUrl(context, serverUrlInput)
                        connectionLabel = com.tunemind.app.domain.network.BackendEndpointManager.getDisplayConnectionLabel(context)
                        showServerDialog = false
                    }
                ) {
                    Text("Save", color = theme.primaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }
}
