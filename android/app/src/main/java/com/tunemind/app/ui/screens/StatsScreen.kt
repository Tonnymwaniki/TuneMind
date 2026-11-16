package com.tunemind.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import com.tunemind.app.data.ingestion.SpotifyTasteShiftSummary
import com.tunemind.app.domain.memory.OverallListeningStats
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
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
    val tasteShifts by vm.tasteShiftSummary.collectAsState()
    val nowPlaying = vm.homeState.collectAsState().value.nowPlaying
    val liveSpotify by vm.spotifyLiveState.collectAsState()

    StudioAmbientBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp)
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
                            color = Color(0xFF0E131D),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier
                                .size(40.dp)
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
                        Text(
                            text = "Stats & Habits",
                            color = Color(0xFFF8FAFC),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "${stats.appBreakdown.size.coerceAtLeast(1)} ${if (stats.appBreakdown.size == 1) "app" else "apps"}",
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            statsDashboardItems(
                stats = stats,
                tasteShiftSummary = tasteShifts,
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
    onOpenMoments: () -> Unit,
    tasteShiftSummary: SpotifyTasteShiftSummary = SpotifyTasteShiftSummary()
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        statsDashboardItems(
            stats = stats,
            tasteShiftSummary = tasteShiftSummary,
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
    onOpenMoments: () -> Unit,
    tasteShiftSummary: SpotifyTasteShiftSummary = SpotifyTasteShiftSummary()
) {
    // 1. Single Hero Card: Time Window + Listening Summary
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
                MagicalSegmentedTabs(
                    tabs = StatsTimeWindow.entries.map { MagicalTabItem(it.label, theme.primaryAccent) },
                    selectedIndex = StatsTimeWindow.entries.indexOf(window),
                    onSelectTab = { idx -> window = StatsTimeWindow.entries[idx] },
                    height = 42.dp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "Listening Time (${window.label})",
                            color = Color.White.copy(alpha = 0.56f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = activeDurationText,
                            color = Color(0xFFF8FAFC),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Peak · ${stats.peakWindowLabel}",
                        color = Color(0xFF10B981),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                StudioHairlineDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricMiniColumn(
                        label = "Verified Plays",
                        value = "${stats.totalVerifiedPlays}",
                        accent = theme.primaryAccent,
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniColumn(
                        label = "Songs",
                        value = "${stats.uniqueTrackCount}",
                        accent = theme.secondaryAccent,
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniColumn(
                        label = "Artists",
                        value = "${stats.uniqueArtistCount}",
                        accent = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // 2. Listening Habits (Paired Metric Cards)
    item(key = "stats_habits_row") {
        val theme = LocalAdaptiveTheme.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SoftGlassCard(
                modifier = Modifier.weight(1f),
                accentTint = Color(0xFF10B981),
                contentPadding = PaddingValues(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Completion Rate",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 12.sp
                    )
                    Text(
                        text = "${stats.completionRatePercent}%",
                        color = Color(0xFF10B981),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { stats.completionRatePercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF10B981),
                        trackColor = Color.White.copy(alpha = 0.10f)
                    )
                    Text(
                        text = if (stats.skippedCount == 0) "Low skip rate" else "${stats.skippedCount} quick skips filtered",
                        color = Color.White.copy(alpha = 0.54f),
                        fontSize = 11.sp
                    )
                }
            }

            SoftGlassCard(
                modifier = Modifier.weight(1f),
                accentTint = theme.primaryAccent,
                contentPadding = PaddingValues(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Fresh Mix Ratio",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 12.sp
                    )
                    Text(
                        text = "${stats.discoveryRatePercent}%",
                        color = theme.primaryAccent,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { stats.discoveryRatePercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = theme.primaryAccent,
                        trackColor = Color.White.copy(alpha = 0.10f)
                    )
                    Text(
                        text = "${100 - stats.discoveryRatePercent}% on repeat",
                        color = Color.White.copy(alpha = 0.54f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    // 3. Cross-App Breakdown Card
    item(key = "stats_apps_breakdown") {
        val theme = LocalAdaptiveTheme.current
        SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StudioSectionHeader(title = "Music Apps Used")

                if (stats.appBreakdown.isEmpty()) {
                    Text(
                        text = "Play music in Spotify, YouTube Music, Boomplay, or any player to see your cross-app breakdown.",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                } else {
                    stats.appBreakdown.forEach { app ->
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
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 13.sp,
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
                                trackColor = Color.White.copy(alpha = 0.10f)
                            )
                        }
                    }
                }
            }
        }
    }

    // 4. 7-Day Listening Rhythm Chart Card
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
                        color = Color(0xFFF8FAFC),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = formatListeningDuration(stats.sevenDayListeningMs),
                        color = theme.primaryAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                val maxDayMs = (stats.dailyBuckets.maxOfOrNull { it.listeningMs } ?: 1L).coerceAtLeast(1L)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp),
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
                                color = Color.White.copy(alpha = 0.60f),
                                fontSize = 10.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.50f)
                                    .height((64f * frac).dp.coerceAtLeast(5.dp))
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        if (isToday) theme.primaryAccent
                                        else if (bucket.playCount > 0) theme.secondaryAccent.copy(alpha = 0.78f)
                                        else Color.White.copy(alpha = 0.10f)
                                    )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = bucket.label,
                                color = if (isToday) Color.White else Color.White.copy(alpha = 0.56f),
                                fontSize = 10.sp,
                                fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }

    // 5. 24-Hour Peak Activity Clock Card
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
                        color = Color(0xFFF8FAFC),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stats.peakWindowLabel,
                        color = Color(0xFF10B981),
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
                    stats.hourlyPlayCounts.forEachIndexed { hour, count ->
                        val frac = (count.toFloat() / maxHourCount.toFloat()).coerceIn(0.08f, 1f)
                        val isPeak = hour == stats.peakHourOfDay && count > 0
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height((44f * frac).dp.coerceAtLeast(4.dp))
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

    // 6. Top Artists (Hairline List with End Artist Profile Photos)
    if (stats.topArtists.isNotEmpty()) {
        item(key = "stats_top_artists") {
            val theme = LocalAdaptiveTheme.current
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StudioSectionHeader(title = "Top Artists")
                stats.topArtists.take(5).forEachIndexed { index, artist ->
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
                                text = "${artist.uniqueTrackCount} ${if (artist.uniqueTrackCount == 1) "track" else "tracks"}",
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

    // 6b. Real Spotify Genre Breakdown (Zero AI Tokens)
    if (tasteShiftSummary.topGenres.isNotEmpty()) {
        item(key = "stats_spotify_genres") {
            val theme = LocalAdaptiveTheme.current
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = Color(0xFF1DB954)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioSectionHeader(
                        title = "Genre DNA · Spotify",
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
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 13.sp,
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
                }
            }
        }
    }

    // 7. Top Songs (with live Playing highlight & strict play count)
    if (stats.topTracks.isNotEmpty()) {
        item(key = "stats_top_tracks_header") {
            StudioSectionHeader(title = "Most Played Songs")
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
                spotifyUri = track.spotifyUri,
                onPlayClick = {
                    onPlayTrack(track.title, track.artist, track.spotifyUri, track.primarySourcePackage)
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
                accent = Color(0xFFA855F7),
                onClick = onOpenMusicProfile
            )
            StudioHairlineDivider()

            InsightHubTile(
                title = "Sound Moments",
                subtitle = "Saved song highlights",
                icon = Icons.Rounded.BookmarkBorder,
                accent = Color(0xFFFFB000),
                onClick = onOpenMoments
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
