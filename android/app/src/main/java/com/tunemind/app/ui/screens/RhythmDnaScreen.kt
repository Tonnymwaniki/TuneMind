package com.tunemind.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import com.tunemind.app.ui.components.TuneMindTrackCard
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

    val topArtists = remember(listeningStats.topArtists, recentTracks) {
        if (listeningStats.topArtists.isNotEmpty()) {
            listeningStats.topArtists.take(4).map { it.artistName }
        } else {
            recentTracks
                .mapNotNull { it.artist?.trim()?.takeIf { a -> a.isNotBlank() && !a.equals("Unknown Artist", ignoreCase = true) } }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(4)
        }
    }

    val dbValue = hearingInsights.estimatedAverageDb.toInt().coerceIn(45, 95)
    val audioRouteLabel = hearingInsights.activeAudioDevice?.deviceName?.takeIf { it.isNotBlank() }
        ?: "Speaker / Wireless"

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
    val coroutineScope = rememberCoroutineScope()

    // 4 sub-tabs while keeping main bottom bar strictly 3 tabs:
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

    val eyebrowTitle = when (activeRhythmSubDeck) {
        1 -> "ANALYTICS"
        2 -> "SPOTIFY"
        3 -> "TIMELINE"
        else -> "RHYTHM"
    }
    val screenTitle = when (activeRhythmSubDeck) {
        1 -> "Listening Stats"
        2 -> "Spotify Library"
        3 -> "Play History"
        else -> "Daily Flow"
    }

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = eyebrowTitle,
                        color = if (activeRhythmSubDeck == 2) Color(0xFF1DB954) else theme.primaryAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = screenTitle,
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

            Spacer(modifier = Modifier.height(12.dp))

            // 4-Tab Segmented Switcher: Overview | Stats | Spotify | History
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0C1018))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                RhythmSubTabPill(
                    label = "Overview",
                    icon = Icons.Rounded.GraphicEq,
                    isSelected = activeRhythmSubDeck == 0,
                    activeColor = theme.primaryAccent,
                    onClick = { activeRhythmSubDeck = 0 },
                    modifier = Modifier.weight(1f)
                )
                RhythmSubTabPill(
                    label = "Stats",
                    icon = Icons.Rounded.BarChart,
                    isSelected = activeRhythmSubDeck == 1,
                    activeColor = theme.secondaryAccent,
                    onClick = { activeRhythmSubDeck = 1 },
                    modifier = Modifier.weight(1f)
                )
                RhythmSubTabPill(
                    label = "Spotify",
                    icon = Icons.Rounded.LibraryMusic,
                    isSelected = activeRhythmSubDeck == 2,
                    activeColor = Color(0xFF1DB954),
                    onClick = { activeRhythmSubDeck = 2 },
                    modifier = Modifier.weight(1f)
                )
                RhythmSubTabPill(
                    label = "History",
                    icon = Icons.Rounded.History,
                    isSelected = activeRhythmSubDeck == 3,
                    activeColor = Color(0xFF00E5FF),
                    onClick = { activeRhythmSubDeck = 3 },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.weight(1f)) {
                when (activeRhythmSubDeck) {
                    // TAB 1: STATS
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

                    // TAB 2: SPOTIFY LIBRARY (Liked Songs, Playlists, Podcasts, Live State)
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

                    // TAB 3: DEDUPLICATED CROSS-APP PLAY HISTORY
                    3 -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 110.dp)
                        ) {
                            item(key = "history_controls") {
                                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = "Verified Play Log",
                                                    color = Color(0xFF00E5FF),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "${listeningStats.totalVerifiedPlays} plays • ${listeningStats.uniqueTrackCount} unique songs",
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
                                                    .background(Color(0xFF00E5FF))
                                                    .bouncyClickable { onRefreshSpotifyLibrary() }
                                                    .padding(horizontal = 11.dp, vertical = 7.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Refresh,
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = if (isLibrarySyncing) "Syncing…" else "Sync",
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
                                                .padding(4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            val filters = listOf("All Apps", "Spotify", "Most Played")
                                            filters.forEachIndexed { idx, label ->
                                                val sel = historyFilterMode == idx
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(9.dp))
                                                        .background(
                                                            if (sel) Color(0xFF00E5FF).copy(alpha = 0.18f)
                                                            else Color.Transparent
                                                        )
                                                        .bouncyClickable { historyFilterMode = idx }
                                                        .padding(vertical = 7.dp)
                                                ) {
                                                    Text(
                                                        text = label,
                                                        color = if (sel) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.60f),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (historyFilterMode == 2) {
                                val topList = listeningStats.topTracks
                                if (topList.isEmpty()) {
                                    item(key = "history_top_empty") {
                                        SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = "Play songs across your music apps to rank your most-played tracks.",
                                                color = Color.White.copy(alpha = 0.68f),
                                                fontSize = 13.sp
                                            )
                                        }
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
                                        SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = if (effectiveSpotifyConnected) {
                                                    "No plays recorded yet — tap Sync above or start a song in any music app."
                                                } else {
                                                    "Connect Spotify or play a track in any player to start building your timeline."
                                                },
                                                color = Color.White.copy(alpha = 0.68f),
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp
                                            )
                                        }
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
                                            subtitle = listOfNotNull(timeLabel, durText.takeIf { it.isNotBlank() }).joinToString(" • "),
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

                    // TAB 0: OVERVIEW (Daily Rhythm, Adaptive Theme, Hub Cards, Top Rotation, Playback Output)
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(bottom = 110.dp)
                        ) {
                            // 1. Insights Hub Quick Links (Keeps Bottom Bar 3 tabs while exposing rich screens)
                            item(key = "overview_insights_hub") {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        InsightHubTile(
                                            title = "Weekly Recap",
                                            subtitle = "${formatListeningDuration(listeningStats.sevenDayListeningMs)} this week",
                                            icon = Icons.Rounded.Insights,
                                            accent = theme.primaryAccent,
                                            onClick = onOpenWeeklyReport,
                                            modifier = Modifier.weight(1f)
                                        )
                                        InsightHubTile(
                                            title = "Hearing Health",
                                            subtitle = "$dbValue dBA • Safe",
                                            icon = Icons.Rounded.Headphones,
                                            accent = Color(0xFF10B981),
                                            onClick = onOpenHearingHealth,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        InsightHubTile(
                                            title = "Taste Profile",
                                            subtitle = "${listeningStats.uniqueArtistCount} artists tracked",
                                            icon = Icons.Rounded.PersonOutline,
                                            accent = Color(0xFFA855F7),
                                            onClick = onOpenMusicProfile,
                                            modifier = Modifier.weight(1f)
                                        )
                                        InsightHubTile(
                                            title = "Sound Moments",
                                            subtitle = "Saved song hooks",
                                            icon = Icons.Rounded.BookmarkBorder,
                                            accent = Color(0xFFFFB000),
                                            onClick = onOpenMoments,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            // 2. Spotify Account Quick Status
                            item(key = "overview_spotify_account") {
                                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Spotify Sync",
                                                color = Color(0xFF1DB954),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (effectiveSpotifyConnected) {
                                                    "Connected • ${likedTracks.size} liked • ${playlists.size} playlists"
                                                } else {
                                                    "Connect Spotify for full library & history sync"
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
                                                        if (effectiveSpotifyConnected) Color(0xFF1DB954).copy(alpha = 0.18f)
                                                        else Color(0xFF1DB954)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        Color(0xFF1DB954).copy(alpha = 0.65f),
                                                        RoundedCornerShape(12.dp)
                                                    )
                                                    .bouncyClickable {
                                                        if (effectiveSpotifyConnected) {
                                                            onRefreshSpotifyLibrary()
                                                            activeRhythmSubDeck = 2
                                                        } else {
                                                            spotifyAuthManager.launchSpotifyAuth(context)
                                                        }
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Text(
                                                    text = if (effectiveSpotifyConnected) "Library" else "Connect",
                                                    color = if (effectiveSpotifyConnected) Color(0xFF1DB954) else Color.Black,
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
                                                    .bouncyClickable { authSessionManager.signOut() }
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

                            // 3. Live Adaptive Theme Card
                            item(key = "overview_adaptive_theme") {
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

                            // 4. 24-Hour Daily Rhythm Ring Card
                            item(key = "overview_rhythm_ring") {
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
                                                    text = if (activePhasePlays > 0) {
                                                        "$activePhasePlays plays"
                                                    } else {
                                                        "${activePhase.targetBpm} BPM"
                                                    },
                                                    color = Color.White,
                                                    fontSize = 22.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = if (activePhasePlays > 0) {
                                                        "${activePhaseMins}m • $dbValue dB"
                                                    } else {
                                                        "${activePhase.hoursLabel} • $dbValue dB"
                                                    },
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

                            // 5. Section Header: Top Rotation
                            item(key = "overview_top_rotation_header") {
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

                            // 6. Top Rotation Song Cards with live Playing state & strict Play Counts
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

                            // 7. Output & Target Player Selector Card
                            item(key = "overview_playback_output") {
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

                                        val registry = remember(context) {
                                            com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
                                        }
                                        val installedProviders = remember(registry) {
                                            registry.getInstalledProviders()
                                        }
                                        var preferredProviderId by remember(registry) {
                                            mutableStateOf(registry.getPreferredProviderId())
                                        }

                                        installedProviders.chunked(2).forEach { rowProviders ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                rowProviders.forEach { provider ->
                                                    val isSelected = preferredProviderId == provider.providerId ||
                                                        (provider.packageName != "*" && selectedTargetPlayer == provider.packageName)
                                                    val shortName = when (provider.providerId) {
                                                        "local_files" -> "Local Audio"
                                                        "tunemind_inapp" -> "TuneMind Player"
                                                        else -> provider.displayName
                                                    }
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
                                                            .bouncyClickable {
                                                                preferredProviderId = provider.providerId
                                                                registry.setPreferredProviderId(provider.providerId)
                                                                val pkg = provider.packageName.takeIf { it != "*" } ?: provider.providerId
                                                                onSelectTargetPlayer(pkg)
                                                            }
                                                            .padding(vertical = 10.dp, horizontal = 8.dp)
                                                    ) {
                                                        Text(
                                                            text = shortName,
                                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.62f),
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                                if (rowProviders.size == 1) {
                                                    Spacer(modifier = Modifier.weight(1f))
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

@Composable
private fun RhythmSubTabPill(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (isSelected) activeColor.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                1.dp,
                if (isSelected) activeColor.copy(alpha = 0.48f) else Color.Transparent,
                RoundedCornerShape(13.dp)
            )
            .bouncyClickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) activeColor else Color.White.copy(alpha = 0.56f),
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.58f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
