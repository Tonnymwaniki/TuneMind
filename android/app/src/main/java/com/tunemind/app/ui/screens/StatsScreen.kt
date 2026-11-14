package com.tunemind.app.ui.screens

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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.memory.OverallListeningStats
import com.tunemind.app.domain.memory.UnifiedTrackStats
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import java.util.Locale

private enum class StatsTimeWindow(val label: String) {
    TODAY("Today"),
    SEVEN_DAYS("7 Days"),
    THIRTY_DAYS("30 Days"),
    ALL_TIME("All Time")
}

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
    onOpenMusicProfile: () -> Unit,
    onOpenMoments: () -> Unit
) {
    val stats by vm.listeningStats.collectAsState()
    val theme = LocalAdaptiveTheme.current
    val nowPlaying = vm.homeState.collectAsState().value.nowPlaying
    val liveSpotify by vm.spotifyLiveState.collectAsState()

    StudioAmbientBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 36.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF0C1018).copy(alpha = 0.92f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier
                                .size(38.dp)
                                .clickable { onBack() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "LISTENING INSIGHTS",
                                color = theme.primaryAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.4.sp
                            )
                            Text(
                                text = "Stats & Habits",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF0E121B).copy(alpha = 0.90f))
                            .border(1.dp, theme.primaryAccent.copy(alpha = 0.30f), RoundedCornerShape(50))
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${stats.appBreakdown.size.coerceAtLeast(1)} Apps",
                            color = theme.primaryAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            statsDashboardItems(
                stats = stats,
                resolvePlayCount = { t, a, uri -> vm.resolvePlayCount(t, a, uri) },
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
                onOpenMoments = onOpenMoments
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
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenHearingHealth: () -> Unit,
    onOpenMusicProfile: () -> Unit,
    onOpenMoments: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        statsDashboardItems(
            stats = stats,
            resolvePlayCount = resolvePlayCount,
            isTrackCurrentlyPlaying = isTrackCurrentlyPlaying,
            onPlayTrack = onPlayTrack,
            onOpenWeeklyReport = onOpenWeeklyReport,
            onOpenHearingHealth = onOpenHearingHealth,
            onOpenMusicProfile = onOpenMusicProfile,
            onOpenMoments = onOpenMoments
        )
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.statsDashboardItems(
    stats: OverallListeningStats,
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenHearingHealth: () -> Unit,
    onOpenMusicProfile: () -> Unit,
    onOpenMoments: () -> Unit
) {
    // 1. Time Window Selector + Hero Hours Card
    item(key = "stats_hero_card") {
        val theme = LocalAdaptiveTheme.current
        var window by remember { mutableStateOf(StatsTimeWindow.SEVEN_DAYS) }

        val activeMs = when (window) {
            StatsTimeWindow.TODAY -> stats.todayListeningMs
            StatsTimeWindow.SEVEN_DAYS -> stats.sevenDayListeningMs
            StatsTimeWindow.THIRTY_DAYS -> stats.thirtyDayListeningMs
            StatsTimeWindow.ALL_TIME -> stats.allTimeListeningMs
        }
        val activeDurationText = formatListeningDuration(activeMs)

        SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StatsTimeWindow.entries.forEach { w ->
                        val selected = window == w
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) theme.primaryAccent.copy(alpha = 0.20f)
                                    else Color.Transparent
                                )
                                .border(
                                    1.dp,
                                    if (selected) theme.primaryAccent.copy(alpha = 0.45f) else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .bouncyClickable { window = w }
                                .padding(vertical = 7.dp)
                        ) {
                            Text(
                                text = w.label,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.56f),
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "Listening Time (${window.label})",
                            color = Color.White.copy(alpha = 0.60f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = activeDurationText,
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.16f))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Peak: ${stats.peakWindowLabel}",
                            color = Color(0xFF10B981),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricMiniBox(
                        label = "Verified Plays",
                        value = "${stats.totalVerifiedPlays}",
                        accent = theme.primaryAccent,
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniBox(
                        label = "Songs",
                        value = "${stats.uniqueTrackCount}",
                        accent = theme.secondaryAccent,
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniBox(
                        label = "Artists",
                        value = "${stats.uniqueArtistCount}",
                        accent = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // 2. Listening Habits (Completion Rate & Fresh Discovery)
    item(key = "stats_habits_row") {
        val theme = LocalAdaptiveTheme.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SoftGlassCard(modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Completion Rate",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "${stats.completionRatePercent}%",
                        color = Color(0xFF10B981),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { stats.completionRatePercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF10B981),
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )
                    Text(
                        text = if (stats.skippedCount == 0) "Low skip rate" else "${stats.skippedCount} quick skips filtered",
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 11.sp
                    )
                }
            }

            SoftGlassCard(modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Fresh Mix Ratio",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "${stats.discoveryRatePercent}%",
                        color = theme.primaryAccent,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { stats.discoveryRatePercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = theme.primaryAccent,
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )
                    Text(
                        text = "${100 - stats.discoveryRatePercent}% on repeat",
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    // 3. Cross-App Breakdown (Spotify, YouTube Music, Boomplay, Apple Music, etc.)
    item(key = "stats_apps_breakdown") {
        val theme = LocalAdaptiveTheme.current
        SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Music Apps Used",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Listening time & plays across every detected player",
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 11.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.Apps,
                        contentDescription = null,
                        tint = theme.primaryAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (stats.appBreakdown.isEmpty()) {
                    Text(
                        text = "Play music in Spotify, YouTube Music, Boomplay, or any player to see your cross-app breakdown.",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                } else {
                    stats.appBreakdown.forEach { app ->
                        val appColor = resolveAppAccentColor(app.packageName, theme.primaryAccent)
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                                        text = app.displayName,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${app.playCount} plays",
                                        color = Color.White.copy(alpha = 0.55f),
                                        fontSize = 11.sp
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
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = appColor,
                                trackColor = Color.White.copy(alpha = 0.08f)
                            )
                        }
                    }
                }
            }
        }
    }

    // 4. 7-Day Listening Rhythm Chart
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
                        text = "Last 7 Days",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatListeningDuration(stats.sevenDayListeningMs),
                        color = theme.primaryAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                val maxDayMs = (stats.dailyBuckets.maxOfOrNull { it.listeningMs } ?: 1L).coerceAtLeast(1L)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    stats.dailyBuckets.forEach { bucket ->
                        val frac = (bucket.listeningMs.toFloat() / maxDayMs.toFloat()).coerceIn(0.06f, 1f)
                        val isToday = bucket.label == "Today"
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Text(
                                text = if (bucket.playCount > 0) "${bucket.playCount}" else "",
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 10.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.55f)
                                    .height((68f * frac).dp.coerceAtLeast(6.dp))
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(
                                        if (isToday) theme.primaryAccent
                                        else if (bucket.playCount > 0) theme.secondaryAccent.copy(alpha = 0.72f)
                                        else Color.White.copy(alpha = 0.08f)
                                    )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = bucket.label,
                                color = if (isToday) Color.White else Color.White.copy(alpha = 0.56f),
                                fontSize = 10.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    // 5. 24-Hour Peak Activity Clock
    item(key = "stats_hourly_clock") {
        val theme = LocalAdaptiveTheme.current
        val maxHourCount = (stats.hourlyPlayCounts.maxOrNull() ?: 1).coerceAtLeast(1)
        SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Listening Hours",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stats.peakWindowLabel,
                        color = Color(0xFF10B981),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    stats.hourlyPlayCounts.forEachIndexed { hour, count ->
                        val frac = (count.toFloat() / maxHourCount.toFloat()).coerceIn(0.08f, 1f)
                        val isPeak = hour == stats.peakHourOfDay && count > 0
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height((48f * frac).dp.coerceAtLeast(4.dp))
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    when {
                                        isPeak -> Color(0xFF10B981)
                                        count > 0 -> theme.primaryAccent.copy(alpha = 0.70f)
                                        else -> Color.White.copy(alpha = 0.08f)
                                    }
                                )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("12 AM", color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp)
                    Text("6 AM", color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp)
                    Text("12 PM", color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp)
                    Text("6 PM", color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp)
                    Text("11 PM", color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp)
                }
            }
        }
    }

    // 6. Top Artists
    if (stats.topArtists.isNotEmpty()) {
        item(key = "stats_top_artists") {
            val theme = LocalAdaptiveTheme.current
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Top Artists",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    stats.topArtists.take(5).forEachIndexed { index, artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.04f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(theme.primaryAccent.copy(alpha = 0.16f))
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        color = theme.primaryAccent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column {
                                    Text(
                                        text = artist.artistName,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${artist.uniqueTrackCount} tracks",
                                        color = Color.White.copy(alpha = 0.56f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Text(
                                text = "${artist.playCount} ${if (artist.playCount == 1) "play" else "plays"}",
                                color = Color(0xFF10B981),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    // 7. Top Songs (with live Playing highlight & strict play count)
    if (stats.topTracks.isNotEmpty()) {
        item(key = "stats_top_tracks_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Most Played Songs",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap play anytime",
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 11.sp
                )
            }
        }

        itemsIndexed(
            items = stats.topTracks.take(12),
            key = { _, track -> "stats_top_${track.trackKey}" }
        ) { index, track ->
            val strictCount = resolvePlayCount(track.title, track.artist, track.spotifyUri)
                .takeIf { it > 0 } ?: track.verifiedPlayCount
            val playingNow = isTrackCurrentlyPlaying(track.title, track.artist, track.spotifyUri)
            TuneMindTrackCard(
                title = track.title,
                artist = track.artist,
                subtitle = track.album,
                playCount = strictCount,
                isCurrentlyPlaying = playingNow,
                appLabel = track.primaryAppName,
                leadingIndex = index + 1,
                onPlayClick = {
                    onPlayTrack(track.title, track.artist, track.spotifyUri, track.primarySourcePackage)
                }
            )
        }
    }

    // 8. Explore More Insights Hub (Keeps Bottom Bar strictly 3 tabs)
    item(key = "stats_more_insights_hub") {
        val theme = LocalAdaptiveTheme.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "More Insights",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InsightHubTile(
                    title = "Weekly Recap",
                    subtitle = "Your 7-day highlights",
                    icon = Icons.Rounded.Insights,
                    accent = theme.primaryAccent,
                    onClick = onOpenWeeklyReport,
                    modifier = Modifier.weight(1f)
                )
                InsightHubTile(
                    title = "Hearing Health",
                    subtitle = "Safe dB & volume dose",
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
                    subtitle = "Genres & favorite artists",
                    icon = Icons.Rounded.PersonOutline,
                    accent = Color(0xFFA855F7),
                    onClick = onOpenMusicProfile,
                    modifier = Modifier.weight(1f)
                )
                InsightHubTile(
                    title = "Sound Moments",
                    subtitle = "Saved song highlights",
                    icon = Icons.Rounded.BookmarkBorder,
                    accent = Color(0xFFFFB000),
                    onClick = onOpenMoments,
                    modifier = Modifier.weight(1f)
                )
            }
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
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0C1018).copy(alpha = 0.92f))
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(20.dp))
            .bouncyClickable { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.16f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MetricMiniBox(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.54f),
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

fun formatListeningDuration(ms: Long): String {
    val totalMins = (ms / 60_000L).coerceAtLeast(0L)
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
