package com.tunemind.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tunemind.app.data.local.CrossAppTasteShiftSummary
import com.tunemind.app.data.local.CrossAppTrackEntity
import com.tunemind.app.data.local.DetectedPlayerTab
import com.tunemind.app.data.local.TuneMindPlaylistWithTracks
import com.tunemind.app.domain.audio.AudioDeviceObserver
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.domain.memory.OverallListeningStats
import com.tunemind.app.domain.memory.UnifiedPlayEvent
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.ui.HearingInsightsState
import com.tunemind.app.ui.TrackVaultUiStatus
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.SpotifyAvatarCircle
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.components.rememberResolvedArtistPhoto
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
    crossAppLibraryTracks: List<CrossAppTrackEntity> = emptyList(),
    detectedPlayerTabs: List<DetectedPlayerTab> = emptyList(),
    tuneMindPlaylists: List<TuneMindPlaylistWithTracks> = emptyList(),
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int = { _, _, _ -> 0 },
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean = { _, _, _ -> false },
    onToggleLibraryLike: (canonicalTrackKey: String, liked: Boolean) -> Unit = { _, _ -> },
    onOpenPlaylistsManager: () -> Unit = {},
    onOpenPlaylistByName: ((String) -> Unit)? = null,
    onOpenWeeklyReport: () -> Unit = {},
    onOpenHearingHealth: () -> Unit = {},
    onOpenMusicProfile: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    tasteShiftSummary: CrossAppTasteShiftSummary = CrossAppTasteShiftSummary(),
    onRefreshStats: () -> Unit = {},
    requestedSubDeck: Int? = null,
    onConsumeRequestedSubDeck: () -> Unit = {},
    resolveTrackVaultStatus: (title: String?, artist: String?, uri: String?, trackId: String?) -> TrackVaultUiStatus = { _, _, _, _ -> TrackVaultUiStatus() },
    onToggleTrackVault: ((title: String, artist: String, sourcePkg: String?, uri: String?, trackId: String?) -> Unit)? = null,
    onToggleTrackFresh: ((title: String, artist: String, sourcePkg: String?, uri: String?, trackId: String?) -> Unit)? = null
) {
    val theme = LocalAdaptiveTheme.current
    val context = LocalContext.current
    var clockEpochMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            clockEpochMs = System.currentTimeMillis()
        }
    }
    val nowCalendar = remember(clockEpochMs) {
        Calendar.getInstance().apply { timeInMillis = clockEpochMs }
    }
    val currentHour = nowCalendar.get(Calendar.HOUR_OF_DAY)
    val currentMinute = nowCalendar.get(Calendar.MINUTE)
    val liveClockLabel = "%02d:%02d".format(currentHour, currentMinute)

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
    LaunchedEffect(livePhaseIndex) {
        selectedPhaseIndex = livePhaseIndex
    }
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
        (0..3).associateWith { idx -> counts[idx] to durationsMs[idx] }
    }
    val (activePhasePlays, activePhaseMs) = phaseActivityMap[selectedPhaseIndex] ?: (0 to 0L)

    var activeSyncTrack by remember { mutableStateOf<TrackMemoryEntry?>(null) }

    val powerTracks = remember(recentTracks, selectedPhaseIndex) {
        val unique = mutableListOf<TrackMemoryEntry>()
        for (entry in recentTracks) {
            if (entry.title.isNullOrBlank()) continue
            val dup = unique.any { prev ->
                com.tunemind.app.domain.memory.ListeningPlayDeduplicator.isSameTrack(
                    prev.title, prev.artist, prev.providerTrackId,
                    entry.title, entry.artist, entry.providerTrackId
                )
            }
            if (!dup) unique.add(entry)
        }
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

    val dbValue = hearingInsights.estimatedAverageDb.toInt().coerceIn(40, 108)
    val (hearingStatus, hearingColor) = when {
        dbValue >= 92 -> "High Risk" to Color(0xFFFF4D6D)
        dbValue >= 85 -> "Loud" to Color(0xFFFFB000)
        dbValue >= 80 -> "Moderate" to theme.primaryAccent
        else -> "Safe" to Color(0xFF10B981)
    }
    // Strictly sanitize against phone model / OEM names & prefer live transducer snapshot
    val audioRouteLabel = AudioDeviceObserver.sanitizeOutputDeviceLabel(
        raw = hearingInsights.activeSnapshot?.productName ?: hearingInsights.activeAudioDevice?.deviceName,
        transducerCategory = hearingInsights.activeSnapshot?.transducerCategory
    )

    // 0 = Overview, 1 = Cross-App Library, 2 = History
    var activeRhythmSubDeck by rememberSaveable { mutableStateOf(0) }
    var historyFilterMode by rememberSaveable { mutableStateOf(0) } // 0 = All Apps, 1 = Recent 24h, 2 = Most Played

    LaunchedEffect(requestedSubDeck) {
        if (requestedSubDeck != null && requestedSubDeck in 0..2) {
            activeRhythmSubDeck = requestedSubDeck
            onConsumeRequestedSubDeck()
        }
    }

    LaunchedEffect(activeRhythmSubDeck) {
        if (activeRhythmSubDeck == 0 || activeRhythmSubDeck == 2) {
            onRefreshStats()
        }
    }

    val screenTitle = when (activeRhythmSubDeck) {
        1 -> "Library"
        2 -> "History"
        else -> "Flow"
    }

    val activeSubDeckAccent = when (activeRhythmSubDeck) {
        1 -> Color(0xFF38BDF8)
        2 -> Color(0xFF00E5FF)
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${activePhase.label} · ${activePhase.hoursLabel} · $liveClockLabel",
                        color = activeSubDeckAccent.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    IconButton(
                        onClick = onOpenSearch,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search Music",
                            tint = Color(0xFFF8FAFC),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Spring-sliding capsule tabs (Overview | Library | History)
            MagicalSegmentedTabs(
                tabs = listOf(
                    MagicalTabItem("Overview", theme.primaryAccent),
                    MagicalTabItem("Library", Color(0xFF38BDF8)),
                    MagicalTabItem("History", Color(0xFF00E5FF))
                ),
                selectedIndex = activeRhythmSubDeck.coerceIn(0, 2),
                onSelectTab = { activeRhythmSubDeck = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedContent(
                targetState = activeRhythmSubDeck.coerceIn(0, 2),
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                },
                modifier = Modifier.weight(1f),
                label = "flow_subdeck_transition"
            ) { subDeck ->
                when (subDeck) {
                    1 -> {
                        CrossAppLibrarySection(
                            libraryTracks = crossAppLibraryTracks,
                            detectedPlayerTabs = detectedPlayerTabs,
                            playlists = tuneMindPlaylists,
                            resolvePlayCount = resolvePlayCount,
                            isTrackCurrentlyPlaying = { t, a -> isTrackCurrentlyPlaying(t, a, null) },
                            resolveTrackVaultStatus = { t, a ->
                                val st = resolveTrackVaultStatus(t, a, null, null)
                                (st.isInVault || st.isCoolingDown) to st.freshnessPercent
                            },
                            onPlayTrack = { title, artist, preferredPkg ->
                                onPlayTrack(title, artist, null, preferredPkg ?: selectedTargetPlayer.ifBlank { "unknown_player" })
                            },
                            onToggleLike = onToggleLibraryLike,
                            onToggleTrackVault = { title, artist, sourcePkg ->
                                if (onToggleTrackVault != null) {
                                    onToggleTrackVault(title, artist, sourcePkg, null, null)
                                } else {
                                    onQuarantineTrack(title, artist)
                                }
                            },
                            onAskAgentAboutTrack = { title, artist ->
                                onAskAgentAboutTrack(title, artist, 100, null)
                            },
                            onOpenPlaylistsManager = onOpenPlaylistsManager,
                            onOpenPlaylistByName = onOpenPlaylistByName
                        )
                    }

                    2 -> {
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
                                            text = "Refresh",
                                            color = Color(0xFF00E5FF),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { onRefreshStats() }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }

                                    val historyChips = listOf(
                                        0 to ("All Apps" to Color(0xFF00E5FF)),
                                        1 to ("Today" to Color(0xFF10B981)),
                                        2 to ("Most Played" to theme.secondaryAccent)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        historyChips.forEach { (idx, pair) ->
                                            val (label, accent) = pair
                                            val isSelected = historyFilterMode == idx
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (isSelected) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f),
                                                border = BorderStroke(
                                                    width = 1.dp,
                                                    color = if (isSelected) accent.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.10f)
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { historyFilterMode = idx }
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 8.dp, horizontal = 8.dp),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    if (isSelected) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(5.dp)
                                                                .clip(CircleShape)
                                                                .background(accent)
                                                        )
                                                        Spacer(modifier = Modifier.width(5.dp))
                                                    }
                                                    Text(
                                                        text = label,
                                                        color = if (isSelected) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.65f),
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
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
                                            .takeIf { it > 0 } ?: track.verifiedPlayCount.coerceAtLeast(0)
                                        val isPlaying = isTrackCurrentlyPlaying(track.title, track.artist, track.spotifyUri)
                                        val canonicalId = track.trackId.ifBlank {
                                            com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalTrackId(
                                                spotifyUri = track.spotifyUri,
                                                title = track.title,
                                                artist = track.artist
                                            )
                                        }
                                        val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(track.spotifyUri)
                                        val vStatus = resolveTrackVaultStatus(track.title, track.artist, cleanUri, canonicalId)
                                        TuneMindTrackCard(
                                            title = track.title,
                                            artist = track.artist,
                                            subtitle = track.album,
                                            playCount = plays,
                                            showUnplayedLabel = true,
                                            isCurrentlyPlaying = isPlaying,
                                            appLabel = track.primaryAppName,
                                            freshnessPercent = if (vStatus.isInVault || vStatus.satietyScore > 0) vStatus.freshnessPercent else track.freshnessPercent,
                                            isCoolingDown = vStatus.isInVault || vStatus.isCoolingDown || track.isCoolingDown,
                                            cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                                            recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                                            leadingIndex = idx + 1,
                                            spotifyUri = cleanUri,
                                            trackId = canonicalId,
                                            onPlayClick = {
                                                onPlayTrack(track.title, track.artist, cleanUri, track.primarySourcePackage)
                                            },
                                            onSyncClick = {
                                                activeSyncTrack = TrackMemoryEntry(
                                                    entryId = track.entryId,
                                                    sourcePackage = track.primarySourcePackage,
                                                    providerTrackId = cleanUri,
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
                                                    appearances = emptyList(),
                                                    trackId = canonicalId
                                                )
                                            }
                                        )
                                    }
                                }
                            } else {
                                val cutoff24h = System.currentTimeMillis() - 86_400_000L
                                val timeline = if (historyFilterMode == 1) {
                                    cleanPlayHistory.filter { it.playedAtMs >= cutoff24h }
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
                                                text = "No plays recorded yet. Start a song in Boomplay, YouTube Music, Spotify, Audiomack, or phone audio to build your permanent timeline.",
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
                                        val plays = resolvePlayCount(ev.title, ev.artist, ev.spotifyUri).coerceAtLeast(0)
                                        val timeLabel = if (isPlaying) "Now" else formatRelativeAgo(ev.playedAtMs)
                                        val durText = formatMmSs(ev.durationMs)
                                        val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(ev.spotifyUri)
                                        val canonicalId = ev.trackId.ifBlank {
                                            com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalTrackId(
                                                spotifyUri = cleanUri,
                                                title = ev.title,
                                                artist = ev.artist
                                            )
                                        }

                                        val vStatus = resolveTrackVaultStatus(ev.title, ev.artist, cleanUri, canonicalId)
                                        TuneMindTrackCard(
                                            title = ev.title,
                                            artist = ev.artist,
                                            subtitle = listOfNotNull(timeLabel, durText.takeIf { it.isNotBlank() }).joinToString(" · "),
                                            playCount = plays,
                                            showUnplayedLabel = true,
                                            isCurrentlyPlaying = isPlaying,
                                            appLabel = ev.appDisplayName,
                                            freshnessPercent = if (vStatus.isInVault || vStatus.satietyScore > 0) vStatus.freshnessPercent else null,
                                            isCoolingDown = vStatus.isInVault || vStatus.isCoolingDown,
                                            cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                                            recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                                            spotifyUri = cleanUri,
                                            trackId = canonicalId,
                                            onPlayClick = {
                                                onPlayTrack(ev.title, ev.artist, cleanUri, ev.sourcePackage)
                                            },
                                            onSyncClick = {
                                                activeSyncTrack = TrackMemoryEntry(
                                                    entryId = ev.playId,
                                                    sourcePackage = ev.sourcePackage,
                                                    providerTrackId = cleanUri,
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
                                                    appearances = emptyList(),
                                                    trackId = canonicalId
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
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 124.dp)
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
                                                    text = if (activePhasePlays > 0) "$activePhasePlays plays" else "0 plays",
                                                    color = Color(0xFFF8FAFC),
                                                    fontSize = 21.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = if (activePhasePlays > 0) "${formatListeningDuration(activePhaseMs)} · $dbValue dB" else "${activePhase.hoursLabel} · $dbValue dB",
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
                                            },
                                            height = 40.dp
                                        )

                                        if (topArtists.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                                    topArtists.take(3).forEach { artistName ->
                                                        val avatarUrl = rememberResolvedArtistPhoto(artistName)
                                                        SpotifyAvatarCircle(
                                                            imageUrl = avatarUrl,
                                                            fallbackInitial = artistName,
                                                            size = 24.dp,
                                                            accentColor = activePhase.color,
                                                            borderColor = Color(0xFF0A0F1A)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = topArtists.joinToString(" · "),
                                                    color = Color.White.copy(alpha = 0.66f),
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                            }
                                        }
                                        if (tasteShiftSummary.topGenres.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Top genres · ${tasteShiftSummary.topGenres.take(3).joinToString(" · ") { it.genre.replaceFirstChar { c -> c.uppercase() } }}",
                                                color = activePhase.color.copy(alpha = 0.85f),
                                                fontSize = 11.5.sp,
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
                                            value = "$dbValue dB · $hearingStatus",
                                            icon = Icons.Rounded.Headphones,
                                            accent = hearingColor,
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
                                            title = "Listening Stats",
                                            value = "${listeningStats.totalVerifiedPlays} plays",
                                            icon = Icons.Rounded.BarChart,
                                            accent = Color(0xFF38BDF8),
                                            onClick = onOpenStats,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            // 3. Top Rotation Track Rows
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
                                    val uri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(track.providerTrackId)
                                    val canonicalId = track.canonicalTrackId()
                                    val pkg = selectedTargetPlayer.ifBlank {
                                        track.sourcePackage?.ifBlank { "unknown_player" } ?: "unknown_player"
                                    }
                                    val strictPlays = resolvePlayCount(title, artist, uri)
                                        .takeIf { it > 0 } ?: track.distinctAppearanceCount.coerceAtLeast(0)
                                    val isPlaying = isTrackCurrentlyPlaying(title, artist, uri)
                                    val vStatus = resolveTrackVaultStatus(title, artist, uri, canonicalId)

                                    TuneMindTrackCard(
                                        title = title,
                                        artist = artist,
                                        subtitle = track.album,
                                        playCount = strictPlays,
                                        showUnplayedLabel = true,
                                        isCurrentlyPlaying = isPlaying,
                                        appLabel = ListeningPlayDeduplicator.resolveAppDisplayName(track.sourcePackage),
                                        freshnessPercent = if (vStatus.isInVault || vStatus.satietyScore > 0) vStatus.freshnessPercent else null,
                                        isCoolingDown = vStatus.isInVault || vStatus.isCoolingDown,
                                        cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                                        recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                                        accentColor = activePhase.color,
                                        secondaryAccent = theme.secondaryAccent,
                                        spotifyUri = uri,
                                        trackId = canonicalId,
                                        onPlayClick = { onPlayTrack(title, artist, uri, pkg) },
                                        onSyncClick = { activeSyncTrack = track }
                                    )
                                }
                            }

                            // 6. Playback Output (Compact 2-Column Music Player Selector)
                            item(key = "overview_playback_output") {
                                val registry = remember(context) {
                                    com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
                                }
                                val curatedProviders = remember(registry) {
                                    com.tunemind.app.ui.components.MusicPlayerUiHelper.filterMusicPlayers(
                                        context = context,
                                        rawProviders = registry.getInstalledProviders()
                                    )
                                }
                                var preferredProviderId by remember(registry) {
                                    mutableStateOf(registry.getPreferredProviderId())
                                }

                                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        StudioSectionHeader(
                                            title = "Playback Output",
                                            actionLabel = "$audioRouteLabel · $dbValue dB",
                                            onActionClick = onOpenHearingHealth
                                        )

                                        com.tunemind.app.ui.components.CompactMusicPlayerGrid(
                                            providers = curatedProviders,
                                            isProviderSelected = { provider ->
                                                preferredProviderId == provider.providerId ||
                                                    (provider.packageName != "*" && selectedTargetPlayer == provider.packageName)
                                            },
                                            onSelectProvider = { provider ->
                                                preferredProviderId = provider.providerId
                                                registry.setPreferredProviderId(provider.providerId)
                                                val pkg = provider.packageName.takeIf { it != "*" } ?: provider.providerId
                                                onSelectTargetPlayer(pkg)
                                            },
                                            context = context
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
        val uri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(selected.providerTrackId)
        val canonicalId = selected.canonicalTrackId()
        val plays = resolvePlayCount(title, artist, uri).takeIf { it > 0 }
            ?: selected.distinctAppearanceCount.coerceAtLeast(0)
        val vStatus = resolveTrackVaultStatus(title, artist, uri, canonicalId)
        val freshness = if (vStatus.isInVault || vStatus.satietyScore > 0) vStatus.freshnessPercent else (100 - minOf(94, plays * 19)).coerceIn(15, 98)
        val pkg = selectedTargetPlayer.ifBlank {
            selected.sourcePackage?.ifBlank { "unknown_player" } ?: "unknown_player"
        }

        NeuralTrackActionDialog(
            title = title,
            artist = artist,
            freshnessPercent = freshness,
            playCount = plays,
            isCoolingDown = vStatus.isInVault || vStatus.isCoolingDown,
            isInFresh = vStatus.isInFresh && !(vStatus.isInVault || vStatus.isCoolingDown),
            spotifyUri = uri,
            trackId = canonicalId,
            cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
            recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
            vaultReason = vStatus.entryReason,
            onDismiss = { activeSyncTrack = null },
            onPlayNow = { onPlayTrack(title, artist, uri, pkg) },
            onAgentNeuralBreakdown = { onAskAgentAboutTrack(title, artist, freshness, uri) },
            onSyncAtmosphereToTrack = { onSyncAtmosphereToTrack(title, artist) },
            onToggleCooldownVault = {
                if (onToggleTrackVault != null) {
                    onToggleTrackVault(title, artist, pkg, uri, canonicalId)
                } else {
                    onQuarantineTrack(title, artist)
                }
            },
            onToggleFreshList = if (onToggleTrackFresh != null) {
                { onToggleTrackFresh(title, artist, pkg, uri, canonicalId) }
            } else null
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
    val theme = LocalAdaptiveTheme.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(theme.cardSurface.copy(alpha = 0.72f))
            .border(0.8.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(17.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = Color(0xFFFAFAFA),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
