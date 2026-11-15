package com.tunemind.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun WeeklyReportScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit = { _, _, _, _ -> }
) {
    val stats by vm.listeningStats.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    val homeState by vm.homeState.collectAsState()
    val liveSpotify by vm.spotifyLiveState.collectAsState()
    val theme = LocalAdaptiveTheme.current

    val sevenDayDuration = formatListeningDuration(stats.sevenDayListeningMs)
    val totalPlays = stats.totalVerifiedPlays

    val avgFreshness = if (stats.topTracks.isNotEmpty()) {
        stats.topTracks.map { it.freshnessPercent }.average().toInt().coerceIn(20, 99)
    } else 88

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header
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
                    text = "Your Week in Music",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
            }

            // Single Hero Summary Card (includes 3 unboxed metric columns)
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "$sevenDayDuration across $totalPlays plays",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "Built from your verified plays across ${stats.appBreakdown.size.coerceAtLeast(1)} music ${if (stats.appBreakdown.size == 1) "app" else "apps"}, tracking repeat habits and fresh discoveries.",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.62f),
                            lineHeight = 19.sp
                        )
                    }

                    StudioHairlineDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        RecapMetricColumn(
                            label = "Freshness",
                            value = "$avgFreshness%",
                            note = if (avgFreshness >= 65) "Balanced" else "High repeat",
                            accent = Color(0xFF10B981),
                            modifier = Modifier.weight(1f)
                        )
                        RecapMetricColumn(
                            label = "Peak Hours",
                            value = stats.peakWindowLabel.substringBefore(" "),
                            note = stats.peakWindowLabel.substringAfter("(", "").removeSuffix(")").ifBlank { "Active" },
                            accent = theme.primaryAccent,
                            modifier = Modifier.weight(1f)
                        )
                        RecapMetricColumn(
                            label = "Completion",
                            value = "${stats.completionRatePercent}%",
                            note = "Full listens",
                            accent = theme.secondaryAccent,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Top Tracks This Week (Hairline rows)
            if (stats.topTracks.isNotEmpty()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Top Tracks",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "${stats.uniqueTrackCount} songs total",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.56f)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    stats.topTracks.take(5).forEachIndexed { index, track ->
                        val _np = homeState.nowPlaying
                        val _ls = liveSpotify
                        val isPlaying = vm.isTrackCurrentlyPlaying(track.title, track.artist, track.spotifyUri)
                        val strictCount = vm.resolvePlayCount(track.title, track.artist, track.spotifyUri)
                            .takeIf { it > 0 } ?: track.verifiedPlayCount

                        TuneMindTrackCard(
                            title = track.title,
                            artist = track.artist,
                            subtitle = track.album,
                            playCount = strictCount,
                            isCurrentlyPlaying = isPlaying,
                            appLabel = track.primaryAppName,
                            freshnessPercent = track.freshnessPercent,
                            isCoolingDown = track.isCoolingDown,
                            leadingIndex = index + 1,
                            onPlayClick = {
                                onPlayTrack(track.title, track.artist, track.spotifyUri, track.primarySourcePackage)
                            }
                        )
                    }
                }
            }

            // Top Artists (Open Canvas Section)
            if (stats.topArtists.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudioSectionHeader(title = "Top Artists")
                    val maxPlays = stats.topArtists.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
                    stats.topArtists.take(4).forEach { artist ->
                        val frac = (artist.playCount.toFloat() / maxPlays.toFloat()).coerceIn(0.1f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = artist.artistName,
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${artist.playCount} ${if (artist.playCount == 1) "play" else "plays"}",
                                    color = theme.primaryAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = theme.primaryAccent,
                                trackColor = Color.White.copy(alpha = 0.08f)
                            )
                        }
                    }
                    StudioHairlineDivider()
                }
            }

            // Practical Guidance Section (Open Canvas)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Next Week Focus",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF10B981)
                )
                Text(
                    text = buildString {
                        append("• Keep ${vaultTracks.size} resting tracks in your Vault a little longer so their freshness rebounds.\n")
                        append("• Peak listening energy lands around ${stats.peakWindowLabel} — great time for upbeat sets.\n")
                        append("• Mix in songs from For You picks to raise your ${stats.discoveryRatePercent}% fresh discovery balance.")
                    },
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RecapMetricColumn(
    label: String,
    value: String,
    note: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.54f)
        )
        Text(
            text = value,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = accent
        )
        Text(
            text = note,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.58f),
            maxLines = 1
        )
    }
}
