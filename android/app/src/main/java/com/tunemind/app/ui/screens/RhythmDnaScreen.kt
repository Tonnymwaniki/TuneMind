package com.tunemind.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import com.tunemind.app.domain.audio.AudioDeviceObserver
import com.tunemind.app.domain.memory.OverallListeningStats
import com.tunemind.app.domain.memory.UnifiedPlayEvent
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.ui.HearingInsightsState
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
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
    onOpenMoments: () -> Unit = {},
    onOpenAccountAuth: () -> Unit = {}
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
    // Strictly sanitize against phone model / OEM names & prefer live transducer snapshot
    val audioRouteLabel = AudioDeviceObserver.sanitizeOutputDeviceLabel(
        raw = hearingInsights.activeSnapshot?.productName ?: hearingInsights.activeAudioDevice?.deviceName,
        transducerCategory = hearingInsights.activeSnapshot?.transducerCategory
    )

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

    val activeSubDeckAccent = when (activeRhythmSubDeck) {
        1 -> theme.secondaryAccent
        2 -> Color(0xFF1DB954)
        3 -> Color(0xFF00E5FF)
        else -> theme.primaryAccent
    }

    StudioAmbientBackground(accentOverride = activeSubDeckAccent) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = screenTitle,
                    color = Color(0xFFF8FAFC),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp
                )
                Text(
                    text = theme.musicMood.label,
                    color = activeSubDeckAccent.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Spring-sliding capsule tabs (Overview | Stats | Spotify | History)
            MagicalSegmentedTabs(
                tabs = listOf(
                    MagicalTabItem("Overview", theme.primaryAccent),
                    MagicalTabItem("Stats", theme.secondaryAccent),
                    MagicalTabItem("Spotify", Color(0xFF1DB954)),
                    MagicalTabItem("History", Color(0xFF00E5FF))
                ),
                selectedIndex = activeRhythmSubDeck,
                onSelectTab = { activeRhythmSubDeck = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedContent(
                targetState = activeRhythmSubDeck,
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                },
                modifier = Modifier.weight(1f),
                label = "flow_subdeck_transition"
            ) { subDeck ->
                when (subDeck) {
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
                            contentPadding = PaddingValues(top = 4.dp, bottom = 110.dp)
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
                                            color = Color.White.copy(alpha = 0.76f),
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

                                    MagicalSegmentedTabs(
                                        tabs = listOf(
                                            MagicalTabItem("All Apps", Color(0xFF00E5FF)),
                                            MagicalTabItem("Spotify", Color(0xFF1DB954)),
                                            MagicalTabItem("Most Played", theme.secondaryAccent)
                                        ),
                                        selectedIndex = historyFilterMode,
                                        onSelectTab = { historyFilterMode = it },
                                        height = 42.dp
                                    )
                                }
                            }

                            if (historyFilterMode == 2) {
                                val topList = listeningStats.topTracks
                                if (topList.isEmpty()) {
                                    item(key = "history_top_empty") {
                                        SoftGlassCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 14.dp)
                                        ) {
                                            Text(
                                                text = "Play songs in your music apps to rank your most-played tracks.",
                                                color = Color.White.copy(alpha = 0.66f),
                                                fontSize = 14.sp
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
                                        SoftGlassCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 14.dp)
                                        ) {
                                            Text(
                                                text = if (effectiveSpotifyConnected) {
                                                    "No plays recorded yet. Tap Sync above or start a song."
                                                } else {
                                                    "Connect Spotify or play a song to build your timeline."
                                                },
                                                color = Color.White.copy(alpha = 0.66f),
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp
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

                    // TAB 0: OVERVIEW — Thoughtful Dimensional Cards + Clean Spacing
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 112.dp)
                        ) {
                            // 1. Primary Hero Card: Daily Rhythm Ring
                            item(key = "overview_rhythm_ring") {
                                SoftGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    accentTint = activePhase.color
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.size(154.dp)
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
                                                    color = Color.White.copy(alpha = 0.62f),
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(14.dp))

                                        MagicalSegmentedTabs(
                                            tabs = phases.map { MagicalTabItem(it.label, it.color) },
                                            selectedIndex = selectedPhaseIndex,
                                            onSelectTab = { index ->
                                                selectedPhaseIndex = index
                                                onUpdateThemePreferences(
                                                    themePreferences.copy(
                                                        behavior = ThemeBehavior.FIXED,
                                                        fixedPaletteId = phases[index].paletteId
                                                    )
                                                )
                                            },
                                            height = 40.dp
                                        )

                                        if (topArtists.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text(
                                                text = "Top artists · ${topArtists.joinToString(" · ")}",
                                                color = Color.White.copy(alpha = 0.66f),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            // 2. Insights 2x2 Interactive Bento Cards
                            item(key = "overview_insights_hub") {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    StudioSectionHeader(title = "Insights")

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        FlowInsightCard(
                                            title = "Weekly Recap",
                                            value = "${formatListeningDuration(listeningStats.sevenDayListeningMs)} this week",
                                            icon = Icons.Rounded.Insights,
                                            accent = theme.primaryAccent,
                                            onClick = onOpenWeeklyReport,
                                            modifier = Modifier.weight(1f)
                                        )
                                        FlowInsightCard(
                                            title = "Hearing Health",
                                            value = "$dbValue dB · Safe",
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
                                        FlowInsightCard(
                                            title = "Taste Profile",
                                            value = "${listeningStats.uniqueArtistCount} artists",
                                            icon = Icons.Rounded.PersonOutline,
                                            accent = Color(0xFFA855F7),
                                            onClick = onOpenMusicProfile,
                                            modifier = Modifier.weight(1f)
                                        )
                                        FlowInsightCard(
                                            title = "Sound Moments",
                                            value = "Saved hooks",
                                            icon = Icons.Rounded.BookmarkBorder,
                                            accent = Color(0xFFFFB000),
                                            onClick = onOpenMoments,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            // 3. Account & Spotify Card
                            item(key = "overview_spotify_account") {
                                val hasExplicitAccount = authSession.isGoogleConnected || authSession.email.isNotBlank()
                                SoftGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    accentTint = if (effectiveSpotifyConnected) Color(0xFF1DB954) else theme.primaryAccent
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        StudioSectionHeader(
                                            title = "Account & Spotify",
                                            actionLabel = if (effectiveSpotifyConnected) "Library" else "Connect Spotify",
                                            onActionClick = {
                                                if (effectiveSpotifyConnected) {
                                                    onRefreshSpotifyLibrary()
                                                    activeRhythmSubDeck = 2
                                                } else {
                                                    spotifyAuthManager.launchSpotifyAuth(context)
                                                }
                                            }
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
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
                                                    color = Color(0xFFF8FAFC),
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = if (hasExplicitAccount) {
                                                        listOfNotNull(
                                                            authSession.displayName.takeIf { it.isNotBlank() },
                                                            authSession.email.takeIf { it.isNotBlank() }
                                                        ).joinToString(" · ")
                                                    } else {
                                                        "Using guest session — tap Sign In to save your profile"
                                                    },
                                                    color = Color.White.copy(alpha = 0.58f),
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (hasExplicitAccount) "Switch" else "Sign In",
                                                color = theme.primaryAccent,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { onOpenAccountAuth() }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 4. Adaptive Appearance Preset Card
                            item(key = "overview_adaptive_theme") {
                                val isAuto = themePreferences.behavior == ThemeBehavior.ADAPTIVE
                                SoftGlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    accentTint = theme.primaryAccent
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        StudioSectionHeader(
                                            title = "Adaptive Appearance",
                                            actionLabel = if (isAuto) "Auto · ${theme.activePalette.name}" else "Enable Auto",
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
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                                            if (isSelected) color.copy(alpha = 0.20f) else Color(0xFF080C15).copy(alpha = 0.65f)
                                                        )
                                                        .border(
                                                            1.dp,
                                                            if (isSelected) color.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.10f),
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
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                    Text(
                                                        text = label,
                                                        color = if (isSelected) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.74f),
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 5. Top Rotation Track Rows
                            if (powerTracks.isNotEmpty()) {
                                item(key = "overview_top_rotation_header") {
                                    StudioSectionHeader(title = "Top Rotation · ${activePhase.label}")
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
                                        appLabel = track.primaryAppName,
                                        accentColor = activePhase.color,
                                        secondaryAccent = theme.secondaryAccent,
                                        onPlayClick = { onPlayTrack(title, artist, uri, pkg) },
                                        onSyncClick = { activeSyncTrack = track }
                                    )
                                }
                            }

                            // 6. Playback Output Card (clean "Speaker · XX dB" with zero phone model names)
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

                                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        StudioSectionHeader(
                                            title = "Playback Output",
                                            actionLabel = "$audioRouteLabel · $dbValue dB",
                                            onActionClick = onOpenHearingHealth
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        StudioHairlineDivider()

                                        installedProviders.forEachIndexed { idx, provider ->
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
                                                    .padding(vertical = 13.dp, horizontal = 2.dp),
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
                                            if (idx < installedProviders.lastIndex) {
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
private fun FlowInsightCard(
    title: String,
    value: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SoftGlassCard(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        accentTint = accent,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = Color(0xFFF8FAFC),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
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
