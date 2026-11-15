package com.tunemind.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.data.ingestion.SpotifyLikedTrackEntity
import com.tunemind.app.data.ingestion.SpotifyLiveTrackState
import com.tunemind.app.data.ingestion.SpotifyPlaylistEntity
import com.tunemind.app.data.ingestion.SpotifyPodcastEntity
import com.tunemind.app.domain.memory.OverallListeningStats
import com.tunemind.app.domain.memory.UnifiedPlayEvent
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.ui.HearingInsightsState
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.ui.theme.ThemeBehavior
import com.tunemind.app.ui.theme.ThemePreferences
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
    onQuarantineTrack: (title: String, artist: String) -> Unit,
    listeningStats: OverallListeningStats = OverallListeningStats(),
    cleanPlayHistory: List<UnifiedPlayEvent> = emptyList(),
    spotifyLiveState: SpotifyLiveTrackState? = null,
    likedTracks: List<SpotifyLikedTrackEntity> = emptyList(),
    playlists: List<SpotifyPlaylistEntity> = emptyList(),
    podcasts: List<SpotifyPodcastEntity> = emptyList(),
    isLibrarySyncing: Boolean = false,
    needsLibraryScopeUpgrade: Boolean = false,
    loadingPlaylistIds: Set<String> = emptySet(),
    loadedPlaylistIds: Set<String> = emptySet(),
    playlistTrackErrors: Map<String, String> = emptyMap(),
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int = { _, _, _ -> 1 },
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean = { _, _, _ -> false },
    onRefreshSpotifyLibrary: () -> Unit = {},
    onLoadPlaylistTracks: (playlistId: String) -> Unit = {},
    onOpenWeeklyReport: () -> Unit = {},
    onOpenHearingHealth: () -> Unit = {},
    onOpenMusicProfile: () -> Unit = {},
    onOpenMoments: () -> Unit = {}
) {
    val theme = LocalAdaptiveTheme.current
    val context = LocalContext.current
    val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }

    val phases = remember {
        listOf(
            CircadianPhase(0, "morning", "Morning", "06:00 – 12:00", 112, Color(0xFF00E5FF), -90f, 82f),
            CircadianPhase(1, "afternoon", "Midday", "12:00 – 17:00", 118, Color(0xFF10B981), 0f, 82f),
            CircadianPhase(2, "evening", "Evening", "17:00 – 22:00", 98, Color(0xFFFFB000), 90f, 82f),
            CircadianPhase(3, "night", "Night", "22:00 – 06:00", 86, Color(0xFFA855F7), 180f, 82f)
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

    val phaseActivityMap = remember(cleanPlayHistory) {
        val counts = IntArray(4)
        val durationsMs = LongArray(4)
        val cal = Calendar.getInstance()
        for (ev in cleanPlayHistory) {
            cal.timeInMillis = ev.playedAtMs
            val hr = cal.get(Calendar.HOUR_OF_DAY)
            val idx = when (hr) {
                in 6..11 -> 0
                in 12..16 -> 1
                in 17..21 -> 2
                else -> 3
            }
            counts[idx]++
            durationsMs[idx] += ev.listenedMs.coerceAtLeast(0L)
        }
        (0..3).associateWith { idx -> counts[idx] to ((durationsMs[idx] / 60_000L).toInt()) }
    }
    val (activePhasePlays, activePhaseMins) = phaseActivityMap[selectedPhaseIndex] ?: (0 to 0)

    var activeSyncTrack by remember { mutableStateOf<TrackMemoryEntry?>(null) }

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

    val topArtists = remember(listeningStats.topArtists, recentTracks) {
        if (listeningStats.topArtists.isNotEmpty()) {
            listeningStats.topArtists.take(3).map { it.artistName }
        } else {
            recentTracks
                .mapNotNull { it.artist?.trim()?.takeIf { a -> a.isNotBlank() && !a.equals("Unknown Artist", ignoreCase = true) } }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(3)
        }
    }

    val dbValue = hearingInsights.estimatedAverageDb.toInt().coerceIn(45, 95)
    val rawRouteName = hearingInsights.activeAudioDevice?.deviceName?.trim().orEmpty()
    val audioRouteLabel = when {
        rawRouteName.isBlank() -> "Phone Speaker"
        rawRouteName.contains("SM-", ignoreCase = true) ||
            rawRouteName.contains("Pixel", ignoreCase = true) ||
            rawRouteName.contains("Galaxy", ignoreCase = true) ||
            rawRouteName.contains("Redmi", ignoreCase = true) -> "Phone Speaker"
        else -> rawRouteName
    }

    val spotifyRepo = remember {
        com.tunemind.app.data.ingestion.SpotifyOAuthRepository.getInstance(context)
    }
    val isSpotifyConnected by spotifyRepo.isConnectedFlow.collectAsState()
    val spotifyAuthManager = remember {
        com.tunemind.app.data.ingestion.SpotifyAuthManager(context)
    }
    val authSessionManager = remember {
        com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(context)
    }
    val authSession by authSessionManager.sessionFlow.collectAsState()

    // 0 = Overview, 1 = Stats, 2 = Spotify Library, 3 = History
    var activeRhythmSubDeck by remember { mutableStateOf(0) }
    var historyFilterMode by remember { mutableStateOf(0) } // 0 = All Apps, 1 = Spotify, 2 = Most Played

    val effectiveSpotifyConnected = isSpotifyConnected || authSession.isSpotifyConnected

    LaunchedEffect(effectiveSpotifyConnected, activeRhythmSubDeck) {
        if (effectiveSpotifyConnected &&
            activeRhythmSubDeck == 2 &&
            likedTracks.isEmpty() && playlists.isEmpty() && podcasts.isEmpty() && !isLibrarySyncing
        ) {
            onRefreshSpotifyLibrary()
        }
    }

    val screenTitle = when (activeRhythmSubDeck) {
        1 -> "Stats"
        2 -> "Spotify"
        3 -> "History"
        else -> "Flow"
    }

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Single clean header title — no "Server" button, no eyebrow clutter
            Text(
                text = screenTitle,
                color = Color(0xFFF8FAFC),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Clean underline tab bar (Overview | Stats | Spotify | History)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                val subTabs = listOf(
                    Triple("Overview", theme.primaryAccent, 0),
                    Triple("Stats", theme.secondaryAccent, 1),
                    Triple("Spotify", Color(0xFF1DB954), 2),
                    Triple("History", Color(0xFF00E5FF), 3)
                )
                subTabs.forEach { (label, tabAccent, idx) ->
                    val isSelected = activeRhythmSubDeck == idx
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { activeRhythmSubDeck = idx }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.50f),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(2.dp)
                                .background(if (isSelected) tabAccent else Color.Transparent)
                        )
                    }
                }
            }
            StudioHairlineDivider()

            Box(modifier = Modifier.weight(1f)) {
                when (activeRhythmSubDeck) {
                    1 -> {
                        StatsDashboardContent(
                            stats = listeningStats,
                            resolvePlayCount = resolvePlayCount,
                            isTrackCurrentlyPlaying = isTrackCurrentlyPlaying,
                            onPlayTrack = onPlayTrack,
                            onOpenWeeklyReport = onOpenWeeklyReport,
                            onOpenHearingHealth = onOpenHearingHealth,
                            onOpenMusicProfile = onOpenMusicProfile,
                            onOpenMoments = onOpenMoments
                        )
                    }

                    2 -> {
                        SpotifyLibrarySection(
                            isSpotifyConnected = effectiveSpotifyConnected,
                            needsScopeUpgrade = needsLibraryScopeUpgrade,
                            isSyncing = isLibrarySyncing,
                            liveState = spotifyLiveState,
                            likedTracks = likedTracks,
                            playlists = playlists,
                            podcasts = podcasts,
                            loadingPlaylistIds = loadingPlaylistIds,
                            loadedPlaylistIds = loadedPlaylistIds,
                            playlistTrackErrors = playlistTrackErrors,
                            resolvePlayCount = resolvePlayCount,
                            isTrackCurrentlyPlaying = isTrackCurrentlyPlaying,
                            onRefreshLibrary = onRefreshSpotifyLibrary,
                            onRequestSpotifyAuth = { spotifyAuthManager.launchSpotifyAuth(context) },
                            onLoadPlaylistTracks = onLoadPlaylistTracks,
                            onPlayTrack = onPlayTrack
                        )
                    }

                    3 -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)
                        ) {
                            item(key = "history_controls") {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${listeningStats.totalVerifiedPlays} plays · ${listeningStats.uniqueTrackCount} songs",
                                            color = Color.White.copy(alpha = 0.72f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = if (isLibrarySyncing) "Syncing…" else "Sync",
                                            color = Color(0xFF00E5FF),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { onRefreshSpotifyLibrary() }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        val filters = listOf("All Apps", "Spotify", "Most Played")
                                        filters.forEachIndexed { idx, label ->
                                            val sel = historyFilterMode == idx
                                            Text(
                                                text = label,
                                                color = if (sel) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.50f),
                                                fontSize = 13.sp,
                                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { historyFilterMode = idx }
                                                    .padding(vertical = 4.dp)
                                            )
                                        }
                                    }
                                    StudioHairlineDivider()
                                }
                            }

                            if (historyFilterMode == 2) {
                                val topList = listeningStats.topTracks
                                if (topList.isEmpty()) {
                                    item(key = "history_top_empty") {
                                        Text(
                                            text = "Play songs in your music apps to rank your most-played tracks.",
                                            color = Color.White.copy(alpha = 0.60f),
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(vertical = 36.dp)
                                        )
                                    }
                                } else {
                                    itemsIndexed(
                                        items = topList,
                                        key = { _, t -> "hist_top_${t.trackKey}" }
                                    ) { idx, track ->
                                        val plays = resolvePlayCount(track.title, track.artist, track.spotifyUri)
                                            .takeIf { it > 0 } ?: track.verifiedPlayCount
                                        val isPlaying = isTrackCurrentlyPlaying(track.title, track.artist, track.spotifyUri)
                                        TuneMindTrackCard(
                                            title = track.title,
                                            artist = track.artist,
                                            subtitle = track.album,
                                            playCount = plays,
                                            isCurrentlyPlaying = isPlaying,
                                            appLabel = track.primaryAppName,
                                            freshnessPercent = track.freshnessPercent,
                                            isCoolingDown = track.isCoolingDown,
                                            leadingIndex = idx + 1,
                                            onPlayClick = {
                                                onPlayTrack(track.title, track.artist, track.spotifyUri, track.primarySourcePackage)
                                            },
                                            onSyncClick = {
                                                activeSyncTrack = TrackMemoryEntry(
                                                    entryId = track.entryId,
                                                    sourcePackage = track.primarySourcePackage,
                                                    providerTrackId = track.spotifyUri,
                                                    title = track.title,
                                                    artist = track.artist,
                                                    album = track.album,
                                                    distinctAppearanceCount = plays,
                                                    firstObservedAppearanceAtMs = track.firstPlayedAtMs,
                                                    lastObservedAppearanceAtMs = track.lastPlayedAtMs,
                                                    supportingObservationIds = emptyList(),
                                                    identityBasis = com.tunemind.app.domain.model.TrackIdentityBasis.METADATA_FINGERPRINT,
                                                    evidenceQuality = com.tunemind.app.domain.model.MemoryEvidenceQuality.SUPPORTED_IDENTITY,
                                                    historyMayBeIncomplete = false,
                                                    appearances = emptyList()
                                                )
                                            }
                                        )
                                    }
                                }
                            } else {
                                val timeline = if (historyFilterMode == 1) {
                                    cleanPlayHistory.filter { it.sourcePackage.contains("spotify", ignoreCase = true) }
                                } else {
                                    cleanPlayHistory
                                }

                                if (timeline.isEmpty()) {
                                    item(key = "history_timeline_empty") {
                                        Text(
                                            text = if (effectiveSpotifyConnected) {
                                                "No plays recorded yet. Tap Sync above or start a song."
                                            } else {
                                                "Connect Spotify or play a song to build your timeline."
                                            },
                                            color = Color.White.copy(alpha = 0.60f),
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            modifier = Modifier.padding(vertical = 36.dp)
                                        )
                                    }
                                } else {
                                    itemsIndexed(
                                        items = timeline,
                                        key = { _, ev -> ev.playId }
                                    ) { _, ev ->
                                        val isPlaying = ev.isNowPlaying || isTrackCurrentlyPlaying(ev.title, ev.artist, ev.spotifyUri)
                                        val plays = resolvePlayCount(ev.title, ev.artist, ev.spotifyUri).coerceAtLeast(1)
                                        val timeLabel = if (isPlaying) "Now" else formatRelativeAgo(ev.playedAtMs)
                                        val durText = formatMmSs(ev.durationMs)

                                        TuneMindTrackCard(
                                            title = ev.title,
                                            artist = ev.artist,
                                            subtitle = listOfNotNull(timeLabel, durText.takeIf { it.isNotBlank() }).joinToString(" · "),
                                            playCount = plays,
                                            isCurrentlyPlaying = isPlaying,
                                            appLabel = ev.appDisplayName,
                                            onPlayClick = {
                                                onPlayTrack(ev.title, ev.artist, ev.spotifyUri, ev.sourcePackage)
                                            },
                                            onSyncClick = {
                                                activeSyncTrack = TrackMemoryEntry(
                                                    entryId = ev.playId,
                                                    sourcePackage = ev.sourcePackage,
                                                    providerTrackId = ev.spotifyUri,
                                                    title = ev.title,
                                                    artist = ev.artist,
                                                    album = ev.album,
                                                    distinctAppearanceCount = plays,
                                                    firstObservedAppearanceAtMs = ev.playedAtMs,
                                                    lastObservedAppearanceAtMs = ev.playedAtMs,
                                                    supportingObservationIds = emptyList(),
                                                    identityBasis = com.tunemind.app.domain.model.TrackIdentityBasis.METADATA_FINGERPRINT,
                                                    evidenceQuality = com.tunemind.app.domain.model.MemoryEvidenceQuality.SUPPORTED_IDENTITY,
                                                    historyMayBeIncomplete = false,
                                                    appearances = emptyList()
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // TAB 0: OVERVIEW — 1 Hero Rhythm Card + Clean Hairline Sections
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
                        ) {
                            // 1. Primary Hero Block (Only SoftGlassCard on this screen): Daily Rhythm Ring
                            item(key = "overview_rhythm_ring") {
                                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.size(156.dp)
                                        ) {
                                            Canvas(modifier = Modifier.fillMaxSize()) {
                                                phases.forEachIndexed { index, phase ->
                                                    val isSelected = index == selectedPhaseIndex
                                                    drawArc(
                                                        color = phase.color.copy(alpha = if (isSelected) 0.95f else 0.18f),
                                                        startAngle = phase.startAngle,
                                                        sweepAngle = phase.sweepAngle,
                                                        useCenter = false,
                                                        style = Stroke(
                                                            width = if (isSelected) 11.dp.toPx() else 6.dp.toPx(),
                                                            cap = StrokeCap.Round
                                                        )
                                                    )
                                                }
                                            }

                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = activePhase.label,
                                                    color = activePhase.color,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = if (activePhasePlays > 0) "$activePhasePlays plays" else "${activePhase.targetBpm} BPM",
                                                    color = Color(0xFFF8FAFC),
                                                    fontSize = 21.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = if (activePhasePlays > 0) "${activePhaseMins}m · $dbValue dB" else "${activePhase.hoursLabel} · $dbValue dB",
                                                    color = Color.White.copy(alpha = 0.60f),
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(14.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly
                                        ) {
                                            phases.forEachIndexed { index, phase ->
                                                val isSelected = index == selectedPhaseIndex
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            selectedPhaseIndex = index
                                                            onUpdateThemePreferences(
                                                                themePreferences.copy(
                                                                    behavior = ThemeBehavior.FIXED,
                                                                    fixedPaletteId = phase.paletteId
                                                                )
                                                            )
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        text = phase.label,
                                                        color = if (isSelected) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.50f),
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .width(20.dp)
                                                            .height(2.dp)
                                                            .background(if (isSelected) phase.color else Color.Transparent)
                                                    )
                                                }
                                            }
                                        }

                                        if (topArtists.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Top artists · ${topArtists.joinToString(" · ")}",
                                                color = Color.White.copy(alpha = 0.62f),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            // 2. Insights Hairline Rows
                            item(key = "overview_insights_hub") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 24.dp)
                                ) {
                                    StudioSectionHeader(title = "Insights")
                                    Spacer(modifier = Modifier.height(6.dp))
                                    StudioHairlineDivider()

                                    FlowNavRow(
                                        title = "Weekly Recap",
                                        trailingValue = "${formatListeningDuration(listeningStats.sevenDayListeningMs)} this week",
                                        icon = Icons.Rounded.Insights,
                                        accent = theme.primaryAccent,
                                        onClick = onOpenWeeklyReport
                                    )
                                    FlowNavRow(
                                        title = "Hearing Health",
                                        trailingValue = "$dbValue dB",
                                        icon = Icons.Rounded.Headphones,
                                        accent = Color(0xFF10B981),
                                        onClick = onOpenHearingHealth
                                    )
                                    FlowNavRow(
                                        title = "Taste Profile",
                                        trailingValue = "${listeningStats.uniqueArtistCount} artists",
                                        icon = Icons.Rounded.PersonOutline,
                                        accent = Color(0xFFA855F7),
                                        onClick = onOpenMusicProfile
                                    )
                                    FlowNavRow(
                                        title = "Sound Moments",
                                        trailingValue = "Saved hooks",
                                        icon = Icons.Rounded.BookmarkBorder,
                                        accent = Color(0xFFFFB000),
                                        onClick = onOpenMoments
                                    )
                                }
                            }

                            // 3. Spotify Account Hairline Section
                            item(key = "overview_spotify_account") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 24.dp)
                                ) {
                                    StudioSectionHeader(
                                        title = "Spotify",
                                        actionLabel = if (effectiveSpotifyConnected) "Open Library" else "Connect",
                                        onActionClick = {
                                            if (effectiveSpotifyConnected) {
                                                onRefreshSpotifyLibrary()
                                                activeRhythmSubDeck = 2
                                            } else {
                                                spotifyAuthManager.launchSpotifyAuth(context)
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (effectiveSpotifyConnected) {
                                                    "${likedTracks.size} liked songs · ${playlists.size} playlists"
                                                } else {
                                                    "Connect Spotify for full library & history sync"
                                                },
                                                color = Color.White.copy(alpha = 0.72f),
                                                fontSize = 13.sp
                                            )
                                            if (authSession.email.isNotBlank() || authSession.displayName.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = listOfNotNull(
                                                        authSession.displayName.takeIf { it.isNotBlank() },
                                                        authSession.email.takeIf { it.isNotBlank() }
                                                    ).joinToString(" · "),
                                                    color = Color.White.copy(alpha = 0.50f),
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                        if (authSession.isLoggedIn || effectiveSpotifyConnected) {
                                            Text(
                                                text = "Sign out",
                                                color = Color.White.copy(alpha = 0.56f),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { authSessionManager.signOut() }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    StudioHairlineDivider()
                                }
                            }

                            // 4. Appearance Preset Selector
                            item(key = "overview_adaptive_theme") {
                                val isAuto = themePreferences.behavior == ThemeBehavior.ADAPTIVE
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    StudioSectionHeader(
                                        title = "Appearance",
                                        actionLabel = if (isAuto) "Auto On" else "Use Auto",
                                        onActionClick = {
                                            onUpdateThemePreferences(
                                                themePreferences.copy(
                                                    behavior = ThemeBehavior.ADAPTIVE,
                                                    enableMoodAdaptation = true,
                                                    enableEnergyAdaptation = true
                                                )
                                            )
                                        }
                                    )

                                    val atmospherePresets = listOf(
                                        Triple("morning", "Glacier", Color(0xFF00E5FF)),
                                        Triple("afternoon", "Emerald", Color(0xFF10B981)),
                                        Triple("evening", "Ember", Color(0xFFFFB000)),
                                        Triple("night", "Violet", Color(0xFFA855F7))
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        atmospherePresets.forEach { (id, label, color) ->
                                            val isSelected = theme.activePalette.id == id && themePreferences.behavior == ThemeBehavior.FIXED
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (isSelected) color.copy(alpha = 0.16f) else Color(0xFF0E131D)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) color.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.08f),
                                                        RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable {
                                                        onUpdateThemePreferences(
                                                            themePreferences.copy(
                                                                behavior = ThemeBehavior.FIXED,
                                                                fixedPaletteId = id
                                                            )
                                                        )
                                                    }
                                                    .padding(vertical = 10.dp, horizontal = 6.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(color)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = label,
                                                    color = if (isSelected) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.72f),
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    StudioHairlineDivider()
                                }
                            }

                            // 5. Top Rotation Track Rows
                            if (powerTracks.isNotEmpty()) {
                                item(key = "overview_top_rotation_header") {
                                    StudioSectionHeader(
                                        title = "Top Rotation · ${activePhase.label}",
                                        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
                                    )
                                }

                                itemsIndexed(
                                    items = powerTracks,
                                    key = { _, track -> "rot_${track.entryId}" }
                                ) { _, track ->
                                    val title = track.title?.trim() ?: "Unknown Track"
                                    val artist = track.artist?.trim() ?: "Unknown Artist"
                                    val uri = track.providerTrackId?.takeIf { it.startsWith("spotify:track:") }
                                    val pkg = selectedTargetPlayer.ifBlank {
                                        track.sourcePackage?.ifBlank { "com.spotify.music" } ?: "com.spotify.music"
                                    }
                                    val strictPlays = resolvePlayCount(title, artist, uri)
                                        .takeIf { it > 0 } ?: track.distinctAppearanceCount.coerceAtLeast(1)
                                    val isPlaying = isTrackCurrentlyPlaying(title, artist, uri)

                                    TuneMindTrackCard(
                                        title = title,
                                        artist = artist,
                                        subtitle = track.album,
                                        playCount = strictPlays,
                                        isCurrentlyPlaying = isPlaying,
                                        accentColor = activePhase.color,
                                        secondaryAccent = theme.secondaryAccent,
                                        onPlayClick = { onPlayTrack(title, artist, uri, pkg) },
                                        onSyncClick = { activeSyncTrack = track }
                                    )
                                }
                            }

                            // 6. Playback Output Hairline Rows (clean "Phone Speaker · XX dB")
                            item(key = "overview_playback_output") {
                                val registry = remember(context) {
                                    com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
                                }
                                val installedProviders = remember(registry) {
                                    registry.getInstalledProviders()
                                }
                                var preferredProviderId by remember(registry) {
                                    mutableStateOf(registry.getPreferredProviderId())
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 24.dp)
                                ) {
                                    StudioSectionHeader(
                                        title = "Playback Output",
                                        actionLabel = "$audioRouteLabel · $dbValue dB",
                                        onActionClick = onOpenHearingHealth
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    StudioHairlineDivider()

                                    installedProviders.forEach { provider ->
                                        val isSelected = preferredProviderId == provider.providerId ||
                                            (provider.packageName != "*" && selectedTargetPlayer == provider.packageName)
                                        val shortName = when (provider.providerId) {
                                            "local_files" -> "Local Audio"
                                            "tunemind_inapp" -> "TuneMind Player"
                                            else -> provider.displayName
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    preferredProviderId = provider.providerId
                                                    registry.setPreferredProviderId(provider.providerId)
                                                    val pkg = provider.packageName.takeIf { it != "*" } ?: provider.providerId
                                                    onSelectTargetPlayer(pkg)
                                                }
                                                .padding(vertical = 14.dp, horizontal = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = shortName,
                                                color = if (isSelected) theme.primaryAccent else Color(0xFFF8FAFC),
                                                fontSize = 15.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = "Selected",
                                                    tint = theme.primaryAccent,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        StudioHairlineDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    activeSyncTrack?.let { selected ->
        val title = selected.title?.trim() ?: "Unknown Track"
        val artist = selected.artist?.trim() ?: "Unknown Artist"
        val uri = selected.providerTrackId?.takeIf { it.startsWith("spotify:track:") }
        val plays = resolvePlayCount(title, artist, uri).takeIf { it > 0 }
            ?: maxOf(1, selected.distinctAppearanceCount)
        val freshness = (100 - minOf(94, plays * 19)).coerceIn(15, 98)
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
            onPlayNow = { onPlayTrack(title, artist, uri, pkg) },
            onAgentNeuralBreakdown = { onAskAgentAboutTrack(title, artist, freshness, uri) },
            onSyncAtmosphereToTrack = { onSyncAtmosphereToTrack(title, artist) },
            onToggleCooldownVault = { onQuarantineTrack(title, artist) }
        )
    }
}

@Composable
private fun FlowNavRow(
    title: String,
    trailingValue: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 14.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    color = Color(0xFFF8FAFC),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = trailingValue,
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 13.sp
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.36f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        StudioHairlineDivider()
    }
}

private fun formatRelativeAgo(epochMs: Long): String {
    val minsAgo = ((System.currentTimeMillis() - epochMs).coerceAtLeast(0L) / 60_000L)
    return when {
        minsAgo < 1L -> "Just now"
        minsAgo < 60L -> "${minsAgo}m ago"
        minsAgo < 1440L -> "${minsAgo / 60L}h ago"
        else -> "${minsAgo / 1440L}d ago"
    }
}

private fun formatMmSs(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSec = ms / 1000L
    return String.format("%d:%02d", totalSec / 60L, totalSec % 60L)
}
