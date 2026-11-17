package com.tunemind.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.data.ingestion.SpotifyTasteShiftSummary
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.domain.memory.OverallListeningStats
import com.tunemind.app.domain.memory.StatsTimeWindow
import com.tunemind.app.domain.memory.UnifiedPlayEvent
import com.tunemind.app.domain.memory.UnifiedTrackStats
import com.tunemind.app.domain.memory.WindowedListeningStats
import com.tunemind.app.ui.TrackVaultUiStatus
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.SpotifyAvatarCircle
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.components.rememberResolvedArtistPhoto
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import java.util.Locale

/**
 * Fullscreen Cross-App Listening Stats screen opened from the header shortcut.
 */
@Composable
fun StatsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenHearingHealth: () -> Unit,
    onOpenMusicProfile: () -> Unit
) {
    val stats by vm.listeningStats.collectAsState()
    val cleanPlayHistory by vm.cleanPlayHistory.collectAsState()
    val tasteShifts by vm.tasteShiftSummary.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    val nowPlaying = vm.homeState.collectAsState().value.nowPlaying
    val liveSpotify by vm.spotifyLiveState.collectAsState()

    var selectedWindow by rememberSaveable { mutableStateOf(StatsTimeWindow.SEVEN_DAYS) }
    var liveTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var inspectedTrack by remember { mutableStateOf<UnifiedTrackStats?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshLiveListeningStatsNow()
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            vm.refreshLiveListeningStatsNow()
            liveTickMs = System.currentTimeMillis()
        }
    }

    val windowStats = remember(selectedWindow, cleanPlayHistory, stats, liveTickMs) {
        ListeningPlayDeduplicator.buildWindowedStats(
            cleanPlayHistory = cleanPlayHistory,
            overallStats = stats,
            window = selectedWindow
        )
    }

    StudioAmbientBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp)
        ) {
            item(key = "stats_screen_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val theme = LocalAdaptiveTheme.current
                        Surface(
                            shape = CircleShape,
                            color = theme.cardSurface,
                            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.10f)),
                            modifier = Modifier
                                .size(40.dp)
                                .clickable { onBack() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color(0xFFFAFAFA),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Text(
                            text = "Stats",
                            color = Color(0xFFFAFAFA),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "${windowStats.appBreakdown.size} ${if (windowStats.appBreakdown.size == 1) "app" else "apps"}",
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            val _vt = vaultTracks
            statsDashboardItems(
                windowStats = windowStats,
                selectedWindow = selectedWindow,
                onSelectWindow = { selectedWindow = it },
                tasteShiftSummary = tasteShifts,
                isTrackCurrentlyPlaying = { t, a, uri ->
                    // Reactive read on nowPlaying & liveSpotify ensures immediate live highlight
                    val _np = nowPlaying
                    val _ls = liveSpotify
                    vm.isTrackCurrentlyPlaying(t, a, uri)
                },
                onPlayTrack = onPlayTrack,
                onOpenWeeklyReport = onOpenWeeklyReport,
                onOpenHearingHealth = onOpenHearingHealth,
                onOpenMusicProfile = onOpenMusicProfile,
                resolveTrackVaultStatus = { t, a, uri, id -> vm.resolveTrackVaultStatus(t, a, uri, id) },
                onInspectTrack = { inspectedTrack = it }
            )
        }

        inspectedTrack?.let { tr ->
            val vStatus = vm.resolveTrackVaultStatus(tr.title, tr.artist, tr.spotifyUri, tr.trackId)
            NeuralTrackActionDialog(
                title = tr.title,
                artist = tr.artist,
                freshnessPercent = vStatus.freshnessPercent,
                playCount = tr.verifiedPlayCount,
                isCoolingDown = vStatus.isCoolingDown,
                spotifyUri = tr.spotifyUri,
                trackId = tr.trackId,
                cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                vaultReason = vStatus.entryReason,
                onDismiss = { inspectedTrack = null },
                onPlayNow = { onPlayTrack(tr.title, tr.artist, tr.spotifyUri, tr.primarySourcePackage) },
                onAgentNeuralBreakdown = { onBack() },
                onSyncAtmosphereToTrack = { vm.syncAtmosphereToTrack(tr.title, tr.artist) },
                onToggleCooldownVault = {
                    vm.toggleTrackVaultStatus(tr.title, tr.artist, tr.primarySourcePackage, tr.spotifyUri, tr.trackId)
                }
            )
        }
    }
}

/**
 * Reusable embedded Stats content placed both inside StatsScreen and RhythmDnaScreen's Stats tab.
 */
@Composable
fun StatsDashboardContent(
    stats: OverallListeningStats,
    cleanPlayHistory: List<UnifiedPlayEvent> = emptyList(),
    selectedWindow: StatsTimeWindow = StatsTimeWindow.SEVEN_DAYS,
    onSelectWindow: ((StatsTimeWindow) -> Unit)? = null,
    onRefreshStats: () -> Unit = {},
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenHearingHealth: () -> Unit,
    onOpenMusicProfile: () -> Unit,
    tasteShiftSummary: SpotifyTasteShiftSummary = SpotifyTasteShiftSummary(),
    resolveTrackVaultStatus: (title: String?, artist: String?, uri: String?, trackId: String?) -> TrackVaultUiStatus = { _, _, _, _ -> TrackVaultUiStatus() },
    onToggleTrackVault: ((title: String, artist: String, sourcePkg: String?, uri: String?, trackId: String?) -> Unit)? = null,
    onAskAgentAboutTrack: ((title: String, artist: String, freshness: Int, uri: String?) -> Unit)? = null,
    onSyncAtmosphereToTrack: ((title: String, artist: String) -> Unit)? = null
) {
    var internalWindow by rememberSaveable { mutableStateOf(selectedWindow) }
    val activeWindow = if (onSelectWindow != null) selectedWindow else internalWindow
    val handleWindowSelect: (StatsTimeWindow) -> Unit = onSelectWindow ?: { internalWindow = it }
    var inspectedTrack by remember { mutableStateOf<UnifiedTrackStats?>(null) }

    LaunchedEffect(Unit) {
        onRefreshStats()
    }

    val windowStats = remember(activeWindow, cleanPlayHistory, stats) {
        ListeningPlayDeduplicator.buildWindowedStats(
            cleanPlayHistory = cleanPlayHistory,
            overallStats = stats,
            window = activeWindow
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 124.dp)
    ) {
        statsDashboardItems(
            windowStats = windowStats,
            selectedWindow = activeWindow,
            onSelectWindow = handleWindowSelect,
            tasteShiftSummary = tasteShiftSummary,
            isTrackCurrentlyPlaying = isTrackCurrentlyPlaying,
            onPlayTrack = onPlayTrack,
            onOpenWeeklyReport = onOpenWeeklyReport,
            onOpenHearingHealth = onOpenHearingHealth,
            onOpenMusicProfile = onOpenMusicProfile,
            resolveTrackVaultStatus = resolveTrackVaultStatus,
            onInspectTrack = if (onToggleTrackVault != null) { { inspectedTrack = it } } else null
        )
    }

    inspectedTrack?.let { tr ->
        val vStatus = resolveTrackVaultStatus(tr.title, tr.artist, tr.spotifyUri, tr.trackId)
        NeuralTrackActionDialog(
            title = tr.title,
            artist = tr.artist,
            freshnessPercent = vStatus.freshnessPercent,
            playCount = tr.verifiedPlayCount,
            isCoolingDown = vStatus.isCoolingDown,
            spotifyUri = tr.spotifyUri,
            trackId = tr.trackId,
            cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
            recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
            vaultReason = vStatus.entryReason,
            onDismiss = { inspectedTrack = null },
            onPlayNow = { onPlayTrack(tr.title, tr.artist, tr.spotifyUri, tr.primarySourcePackage) },
            onAgentNeuralBreakdown = {
                onAskAgentAboutTrack?.invoke(tr.title, tr.artist, vStatus.freshnessPercent, tr.spotifyUri)
            },
            onSyncAtmosphereToTrack = {
                onSyncAtmosphereToTrack?.invoke(tr.title, tr.artist)
            },
            onToggleCooldownVault = {
                onToggleTrackVault?.invoke(tr.title, tr.artist, tr.primarySourcePackage, tr.spotifyUri, tr.trackId)
            }
        )
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.statsDashboardItems(
    windowStats: WindowedListeningStats,
    selectedWindow: StatsTimeWindow,
    onSelectWindow: (StatsTimeWindow) -> Unit,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenHearingHealth: () -> Unit,
    onOpenMusicProfile: () -> Unit,
    tasteShiftSummary: SpotifyTasteShiftSummary = SpotifyTasteShiftSummary(),
    resolveTrackVaultStatus: (title: String?, artist: String?, uri: String?, trackId: String?) -> TrackVaultUiStatus = { _, _, _, _ -> TrackVaultUiStatus() },
    onInspectTrack: ((UnifiedTrackStats) -> Unit)? = null
) {
    // 1. Open-Canvas Hero Listening Summary (No outer card box)
    item(key = "stats_hero_card") {
        val theme = LocalAdaptiveTheme.current
        val activeDurationText = formatListeningDuration(windowStats.totalListeningMs)

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MagicalSegmentedTabs(
                tabs = StatsTimeWindow.entries.map { MagicalTabItem(it.label, theme.primaryAccent) },
                selectedIndex = StatsTimeWindow.entries.indexOf(selectedWindow),
                onSelectTab = { idx -> onSelectWindow(StatsTimeWindow.entries[idx]) },
                height = 42.dp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = windowStats.windowRangeLabel.ifBlank { "Listening Time (${selectedWindow.label})" },
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = activeDurationText,
                        color = Color(0xFFFAFAFA),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                }

                Text(
                    text = if (windowStats.totalVerifiedPlays > 0) "Peak · ${windowStats.peakWindowLabel}" else windowStats.peakWindowLabel,
                    color = if (windowStats.totalVerifiedPlays > 0) Color(0xFF10B981) else Color.White.copy(alpha = 0.50f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (selectedWindow == StatsTimeWindow.ALL_TIME &&
                windowStats.allTimeMatchesThirtyDays &&
                windowStats.totalVerifiedPlays > 0
            ) {
                Text(
                    text = "Matches 30 Days because all tracked listening began ${windowStats.trackedSpanDays} ${if (windowStats.trackedSpanDays == 1) "day" else "days"} ago.",
                    color = Color.White.copy(alpha = 0.52f),
                    fontSize = 11.5.sp
                )
            }

            StudioHairlineDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricMiniColumn(
                    label = "Verified Plays",
                    value = "${windowStats.totalVerifiedPlays}",
                    accent = theme.primaryAccent,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniColumn(
                    label = "Songs",
                    value = "${windowStats.uniqueTrackCount}",
                    accent = theme.secondaryAccent,
                    modifier = Modifier.weight(1f)
                )
                MetricMiniColumn(
                    label = "Artists",
                    value = "${windowStats.uniqueArtistCount}",
                    accent = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // 2. Listening Habits (Sleek Hairline Tiles)
    item(key = "stats_habits_row") {
        val theme = LocalAdaptiveTheme.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(theme.cardSurface.copy(alpha = 0.72f))
                    .border(0.8.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Completion Rate",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp
                )
                Text(
                    text = if (windowStats.totalVerifiedPlays > 0) "${windowStats.completionRatePercent}%" else "--",
                    color = Color(0xFF10B981),
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
                LinearProgressIndicator(
                    progress = { windowStats.completionRatePercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF10B981),
                    trackColor = Color.White.copy(alpha = 0.10f)
                )
                Text(
                    text = when {
                        windowStats.totalVerifiedPlays == 0 -> "No plays in window"
                        windowStats.skippedCount == 0 -> "Low skip rate"
                        else -> "${windowStats.skippedCount} quick skips filtered"
                    },
                    color = Color.White.copy(alpha = 0.54f),
                    fontSize = 11.sp
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(theme.cardSurface.copy(alpha = 0.72f))
                    .border(0.8.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Fresh Mix Ratio",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp
                )
                Text(
                    text = if (windowStats.totalVerifiedPlays > 0) "${windowStats.discoveryRatePercent}%" else "--",
                    color = theme.primaryAccent,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
                LinearProgressIndicator(
                    progress = { windowStats.discoveryRatePercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = theme.primaryAccent,
                    trackColor = Color.White.copy(alpha = 0.10f)
                )
                Text(
                    text = if (windowStats.totalVerifiedPlays > 0) "${100 - windowStats.discoveryRatePercent}% on repeat" else "No plays in window",
                    color = Color.White.copy(alpha = 0.54f),
                    fontSize = 11.sp
                )
            }
        }
    }

    // 3. Open-Canvas Cross-App Breakdown
    item(key = "stats_apps_breakdown") {
        val theme = LocalAdaptiveTheme.current
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StudioSectionHeader(
                title = "Music Apps Used",
                actionLabel = selectedWindow.label
            )

            if (windowStats.appBreakdown.isEmpty()) {
                Text(
                    text = "No playback recorded for ${selectedWindow.label.lowercase()} yet.",
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            } else {
                windowStats.appBreakdown.forEach { app ->
                    val appColor = resolveAppAccentColor(app.packageName, theme.primaryAccent)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(appColor)
                                )
                                Text(
                                    text = "${app.displayName} · ${app.playCount} ${if (app.playCount == 1) "play" else "plays"}",
                                    color = Color(0xFFFAFAFA),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text = "${formatListeningDuration(app.listeningMs)} (${(app.shareFraction * 100).toInt()}%)",
                                color = appColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { app.shareFraction.coerceIn(0.03f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = appColor,
                            trackColor = Color.White.copy(alpha = 0.08f)
                        )
                    }
                }
            }
            StudioHairlineDivider()
        }
    }

    // 4. Dynamic Activity Bar Chart (adapts chart type & bars to Today / 7 Days / 30 Days / All Time)
    item(key = "stats_daily_chart") {
        val theme = LocalAdaptiveTheme.current
        SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = windowStats.chartTitle,
                        color = Color(0xFFF8FAFC),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${formatListeningDuration(windowStats.totalListeningMs)} · ${windowStats.totalVerifiedPlays} plays",
                        color = theme.primaryAccent,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                val maxBucketMs = (windowStats.chartBuckets.maxOfOrNull { it.listeningMs } ?: 1L).coerceAtLeast(1L)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(106.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    windowStats.chartBuckets.forEachIndexed { idx, bucket ->
                        val targetHeightDp = if (bucket.playCount <= 0) {
                            5.dp
                        } else {
                            val frac = (bucket.listeningMs.toFloat() / maxBucketMs.toFloat()).coerceIn(0.14f, 1f)
                            (64f * frac).dp.coerceAtLeast(10.dp)
                        }
                        val animatedHeight by animateDpAsState(
                            targetValue = targetHeightDp,
                            animationSpec = tween(durationMillis = 280),
                            label = "stats_bar_$idx"
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Text(
                                text = if (bucket.playCount > 0) "${bucket.playCount}" else "",
                                color = if (bucket.isHighlighted) theme.primaryAccent else Color.White.copy(alpha = 0.65f),
                                fontSize = 10.sp,
                                fontWeight = if (bucket.isHighlighted) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.50f)
                                    .height(animatedHeight)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        when {
                                            bucket.isHighlighted && bucket.playCount > 0 -> theme.primaryAccent
                                            bucket.isHighlighted -> theme.primaryAccent.copy(alpha = 0.25f)
                                            bucket.playCount > 0 -> theme.secondaryAccent.copy(alpha = 0.82f)
                                            else -> Color.White.copy(alpha = 0.10f)
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = bucket.label,
                                color = if (bucket.isHighlighted) Color.White else Color.White.copy(alpha = 0.56f),
                                fontSize = 9.5.sp,
                                fontWeight = if (bucket.isHighlighted) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                }
            }
        }
    }

    // 5. 24-Hour Peak Activity Clock Card (live for selected window)
    item(key = "stats_hourly_clock") {
        val theme = LocalAdaptiveTheme.current
        val maxHourCount = (windowStats.hourlyPlayCounts.maxOrNull() ?: 1).coerceAtLeast(1)
        SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Listening Hours",
                        color = Color(0xFFF8FAFC),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = windowStats.peakWindowLabel,
                        color = if (windowStats.totalVerifiedPlays > 0) Color(0xFF10B981) else Color.White.copy(alpha = 0.52f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    windowStats.hourlyPlayCounts.forEachIndexed { hour, count ->
                        val isPeak = hour == windowStats.peakHourOfDay && count > 0
                        val targetHrHeight = if (count <= 0) {
                            4.dp
                        } else {
                            val frac = (count.toFloat() / maxHourCount.toFloat()).coerceIn(0.16f, 1f)
                            (44f * frac).dp.coerceAtLeast(8.dp)
                        }
                        val animatedHrHeight by animateDpAsState(
                            targetValue = targetHrHeight,
                            animationSpec = tween(durationMillis = 260),
                            label = "stats_hr_$hour"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(animatedHrHeight)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    when {
                                        isPeak -> Color(0xFF10B981)
                                        count > 0 -> theme.primaryAccent.copy(alpha = 0.72f)
                                        else -> Color.White.copy(alpha = 0.10f)
                                    }
                                )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("12 AM", color = Color.White.copy(alpha = 0.50f), fontSize = 10.sp)
                    Text("6 AM", color = Color.White.copy(alpha = 0.50f), fontSize = 10.sp)
                    Text("12 PM", color = Color.White.copy(alpha = 0.50f), fontSize = 10.sp)
                    Text("6 PM", color = Color.White.copy(alpha = 0.50f), fontSize = 10.sp)
                    Text("11 PM", color = Color.White.copy(alpha = 0.50f), fontSize = 10.sp)
                }
            }
        }
    }

    // 6. Top Artists (Hairline List for selected window)
    if (windowStats.topArtists.isNotEmpty()) {
        item(key = "stats_top_artists") {
            val theme = LocalAdaptiveTheme.current
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StudioSectionHeader(
                    title = "Top Artists",
                    actionLabel = selectedWindow.label
                )
                windowStats.topArtists.take(5).forEachIndexed { index, artist ->
                    val artistPhotoUrl = rememberResolvedArtistPhoto(artist.artistName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = Color.White.copy(alpha = 0.46f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = artist.artistName,
                                color = Color(0xFFF8FAFC),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${artist.uniqueTrackCount} ${if (artist.uniqueTrackCount == 1) "track" else "tracks"} · ${formatListeningDuration(artist.listeningMs)}",
                                color = Color.White.copy(alpha = 0.54f),
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "${artist.playCount} ${if (artist.playCount == 1) "play" else "plays"}",
                            color = theme.primaryAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        SpotifyAvatarCircle(
                            imageUrl = artistPhotoUrl,
                            fallbackInitial = artist.artistName,
                            size = 42.dp,
                            accentColor = theme.primaryAccent
                        )
                    }
                    StudioHairlineDivider()
                }
            }
        }
    }

    // 6b. Open-Canvas Genre Breakdown
    if (tasteShiftSummary.topGenres.isNotEmpty()) {
        item(key = "stats_spotify_genres") {
            val theme = LocalAdaptiveTheme.current
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioSectionHeader(
                    title = "Top Genres",
                    actionLabel = "Taste Profile",
                    onActionClick = onOpenMusicProfile
                )
                tasteShiftSummary.topGenres.take(5).forEach { g ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = g.genre.replaceFirstChar { it.uppercase() },
                                color = Color(0xFFFAFAFA),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${g.sharePercent}%",
                                color = theme.secondaryAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (g.sharePercent / 100f).coerceIn(0.06f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF1DB954),
                            trackColor = Color.White.copy(alpha = 0.08f)
                        )
                    }
                }
                StudioHairlineDivider()
            }
        }
    }

    // 7. Top Songs for selected window (with live Playing highlight & window-accurate play count)
    if (windowStats.topTracks.isNotEmpty()) {
        item(key = "stats_top_tracks_header") {
            StudioSectionHeader(
                title = "Most Played Songs",
                actionLabel = selectedWindow.label
            )
        }

        itemsIndexed(
            items = windowStats.topTracks.take(12),
            key = { index, track -> "stats_top_${selectedWindow.name}_${index}_${track.trackKey}" }
        ) { index, track ->
            val playingNow = isTrackCurrentlyPlaying(track.title, track.artist, track.spotifyUri)
            val windowSubtitle = listOfNotNull(
                track.album?.takeIf { it.isNotBlank() },
                formatListeningDuration(track.totalListeningMs)
            ).joinToString(" · ")
            val canonicalId = track.trackId.ifBlank {
                com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalTrackId(
                    spotifyUri = track.spotifyUri,
                    title = track.title,
                    artist = track.artist
                )
            }
            val vStatus = resolveTrackVaultStatus(track.title, track.artist, track.spotifyUri, canonicalId)
            val effectiveFreshness = if (vStatus.isInVault || vStatus.satietyScore > 0) vStatus.freshnessPercent else track.freshnessPercent
            val effectiveCooling = vStatus.isInVault || vStatus.isCoolingDown || track.isCoolingDown
            TuneMindTrackCard(
                title = track.title,
                artist = track.artist,
                subtitle = windowSubtitle.ifBlank { track.album },
                playCount = track.verifiedPlayCount,
                showUnplayedLabel = true,
                isCurrentlyPlaying = playingNow,
                appLabel = track.primaryAppName,
                freshnessPercent = effectiveFreshness,
                isCoolingDown = effectiveCooling,
                cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                leadingIndex = index + 1,
                spotifyUri = track.spotifyUri,
                trackId = canonicalId,
                onPlayClick = {
                    onPlayTrack(track.title, track.artist, track.spotifyUri, track.primarySourcePackage)
                },
                onSyncClick = if (onInspectTrack != null) {
                    { onInspectTrack(track) }
                } else {
                    null
                }
            )
        }
    }

    // 8. Explore More Insights Hub (Hairline Navigation List)
    item(key = "stats_more_insights_hub") {
        val theme = LocalAdaptiveTheme.current
        Column {
            StudioSectionHeader(title = "More Insights")
            Spacer(modifier = Modifier.height(6.dp))

            InsightHubTile(
                title = "Weekly Recap",
                subtitle = "Your 7-day highlights",
                icon = Icons.Rounded.Insights,
                accent = theme.primaryAccent,
                onClick = onOpenWeeklyReport
            )
            StudioHairlineDivider()

            InsightHubTile(
                title = "Hearing Health",
                subtitle = "Safe dB & volume dose",
                icon = Icons.Rounded.Headphones,
                accent = Color(0xFF10B981),
                onClick = onOpenHearingHealth
            )
            StudioHairlineDivider()

            InsightHubTile(
                title = "Taste Profile",
                subtitle = "Genres & favorite artists",
                icon = Icons.Rounded.PersonOutline,
                accent = theme.secondaryAccent,
                onClick = onOpenMusicProfile
            )
            StudioHairlineDivider()
        }
    }
}

@Composable
fun InsightHubTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.14f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color(0xFFF8FAFC),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.42f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun MetricMiniColumn(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.54f),
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = accent,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

fun formatListeningDuration(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    if (safeMs in 1_000L until 60_000L) {
        return "${safeMs / 1000L}s"
    }
    val totalMins = safeMs / 60_000L
    if (totalMins < 60L) {
        return "${totalMins}m"
    }
    val hours = totalMins / 60.0
    return if (hours >= 10.0) {
        String.format(Locale.US, "%.0f hrs", hours)
    } else {
        String.format(Locale.US, "%.1f hrs", hours)
    }
}

fun resolveAppAccentColor(packageName: String, fallback: Color): Color {
    val p = packageName.lowercase(Locale.ROOT)
    return when {
        p.contains("spotify") -> Color(0xFF1DB954)
        p.contains("youtube") -> Color(0xFFFF4D6D)
        p.contains("boomplay") -> Color(0xFF00E5FF)
        p.contains("apple") -> Color(0xFFFA2D48)
        p.contains("audiomack") -> Color(0xFFFFA500)
        else -> fallback
    }
}
