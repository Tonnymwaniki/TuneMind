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
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.domain.memory.StatsTimeWindow
import com.tunemind.app.domain.memory.UnifiedTrackStats
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.SpotifyAvatarCircle
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.components.rememberResolvedArtistPhoto
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun WeeklyReportScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit = { _, _, _, _ -> }
) {
    val stats by vm.listeningStats.collectAsState()
    val cleanPlayHistory by vm.cleanPlayHistory.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    val homeState by vm.homeState.collectAsState()
    val theme = LocalAdaptiveTheme.current
    var inspectedWeeklyTrack by remember { mutableStateOf<UnifiedTrackStats?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshLiveListeningStatsNow()
    }

    val weekStats = remember(cleanPlayHistory, stats) {
        ListeningPlayDeduplicator.buildWindowedStats(
            cleanPlayHistory = cleanPlayHistory,
            overallStats = stats,
            window = StatsTimeWindow.SEVEN_DAYS
        )
    }

    val sevenDayDuration = formatListeningDuration(weekStats.totalListeningMs)
    val totalPlays = weekStats.totalVerifiedPlays

    val avgFreshness = if (weekStats.topTracks.isNotEmpty()) {
        weekStats.topTracks.map { it.freshnessPercent }.average().toInt().coerceIn(20, 99)
    } else 0

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
                    color = theme.cardSurface,
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.10f)),
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
                            text = weekStats.windowRangeLabel.ifBlank { "Last 7 Days" },
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.56f)
                        )
                        Text(
                            text = "$sevenDayDuration across $totalPlays ${if (totalPlays == 1) "play" else "plays"}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = if (weekStats.appBreakdown.isEmpty()) {
                                "Play music in Boomplay, YouTube Music, Spotify, Audiomack, or phone audio to populate your 7-day recap."
                            } else {
                                "Built from your verified 7-day plays across ${weekStats.appBreakdown.size} music ${if (weekStats.appBreakdown.size == 1) "app" else "apps"}, tracking repeat habits and fresh discoveries."
                            },
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
                            value = if (avgFreshness > 0) "$avgFreshness%" else "--",
                            note = when {
                                avgFreshness == 0 -> "No plays yet"
                                avgFreshness >= 65 -> "Balanced"
                                else -> "High repeat"
                            },
                            accent = Color(0xFF10B981),
                            modifier = Modifier.weight(1f)
                        )
                        RecapMetricColumn(
                            label = "Peak Hours",
                            value = weekStats.peakWindowLabel.substringBefore(" "),
                            note = weekStats.peakWindowLabel.substringAfter("(", "").removeSuffix(")").ifBlank { "Active" },
                            accent = theme.primaryAccent,
                            modifier = Modifier.weight(1f)
                        )
                        RecapMetricColumn(
                            label = "Completion",
                            value = if (weekStats.totalVerifiedPlays > 0) "${weekStats.completionRatePercent}%" else "--",
                            note = "Full listens",
                            accent = theme.secondaryAccent,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Top Tracks This Week (Hairline rows)
            if (weekStats.topTracks.isNotEmpty()) {
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
                            text = "${weekStats.uniqueTrackCount} songs this week",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.56f)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    weekStats.topTracks.take(5).forEachIndexed { index, track ->
                        val _np = homeState.nowPlaying
                        val isPlaying = vm.isTrackCurrentlyPlaying(track.title, track.artist, track.spotifyUri)
                        val weekSubtitle = listOfNotNull(
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
                        val vStatus = vm.resolveTrackVaultStatus(track.title, track.artist, track.spotifyUri, canonicalId)
                        TuneMindTrackCard(
                            title = track.title,
                            artist = track.artist,
                            subtitle = weekSubtitle.ifBlank { track.album },
                            playCount = track.verifiedPlayCount,
                            showUnplayedLabel = true,
                            isCurrentlyPlaying = isPlaying,
                            appLabel = track.primaryAppName,
                            freshnessPercent = if (vStatus.isInVault || vStatus.satietyScore > 0) vStatus.freshnessPercent else track.freshnessPercent,
                            isCoolingDown = vStatus.isInVault || vStatus.isCoolingDown || track.isCoolingDown,
                            cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                            recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                            leadingIndex = index + 1,
                            spotifyUri = track.spotifyUri,
                            trackId = canonicalId,
                            onPlayClick = {
                                onPlayTrack(track.title, track.artist, track.spotifyUri, track.primarySourcePackage)
                            },
                            onSyncClick = {
                                inspectedWeeklyTrack = track
                            }
                        )
                    }
                }
            }

            // Top Artists Card (with End Artist Profile Photos)
            if (weekStats.topArtists.isNotEmpty()) {
                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StudioSectionHeader(title = "Top Artists", actionLabel = "7 Days")
                        val maxPlays = weekStats.topArtists.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
                        weekStats.topArtists.take(4).forEach { artist ->
                            val frac = (artist.playCount.toFloat() / maxPlays.toFloat()).coerceIn(0.1f, 1f)
                            val artistPhotoUrl = rememberResolvedArtistPhoto(artist.artistName)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
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
                                            text = com.tunemind.app.ui.components.formatPlaysLabel(artist.playCount),
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
                                        trackColor = Color.White.copy(alpha = 0.10f)
                                    )
                                }
                                SpotifyAvatarCircle(
                                    imageUrl = artistPhotoUrl,
                                    fallbackInitial = artist.artistName,
                                    size = 38.dp,
                                    accentColor = theme.primaryAccent
                                )
                            }
                        }
                    }
                }
            }

            // Practical Guidance Card
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = Color(0xFF10B981)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Next Week Focus",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF10B981)
                    )
                    val heavyRepeatTrack = weekStats.topTracks.firstOrNull { it.verifiedPlayCount >= 4 }
                    Text(
                        text = buildString {
                            when {
                                vaultTracks.isNotEmpty() -> {
                                    append("• Keep ${vaultTracks.size} resting ${if (vaultTracks.size == 1) "track" else "tracks"} in your Vault a little longer so freshness rebounds.\n")
                                }
                                heavyRepeatTrack != null -> {
                                    append("• Consider resting \"${heavyRepeatTrack.title}\" (${com.tunemind.app.ui.components.formatPlaysLabel(heavyRepeatTrack.verifiedPlayCount)} this week) in the Vault to prevent ear fatigue.\n")
                                }
                                else -> {
                                    append("• Your 7-day rotation has zero overplayed tracks — keep mixing in fresh discoveries.\n")
                                }
                            }
                            if (weekStats.totalVerifiedPlays > 0) {
                                append("• Peak listening lands around ${weekStats.peakWindowLabel} — great window for longer sessions.\n")
                                append("• Mix in picks from For You to keep your ${weekStats.discoveryRatePercent}% fresh balance steady.")
                            } else {
                                append("• Start playing music across Spotify or other players to unlock peak window & freshness insights.")
                            }
                        },
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.70f),
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        inspectedWeeklyTrack?.let { tr ->
            val vStatus = vm.resolveTrackVaultStatus(tr.title, tr.artist, tr.spotifyUri, tr.trackId)
            NeuralTrackActionDialog(
                title = tr.title,
                artist = tr.artist,
                freshnessPercent = vStatus.freshnessPercent,
                playCount = tr.verifiedPlayCount,
                isCoolingDown = vStatus.isCoolingDown,
                isInFresh = vStatus.isInFresh && !vStatus.isCoolingDown,
                spotifyUri = tr.spotifyUri,
                trackId = tr.trackId,
                cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                vaultReason = vStatus.entryReason,
                onDismiss = { inspectedWeeklyTrack = null },
                onPlayNow = { onPlayTrack(tr.title, tr.artist, tr.spotifyUri, tr.primarySourcePackage) },
                onAgentNeuralBreakdown = { onBack() },
                onSyncAtmosphereToTrack = { vm.syncAtmosphereToTrack(tr.title, tr.artist) },
                onToggleCooldownVault = {
                    vm.toggleTrackVaultStatus(tr.title, tr.artist, tr.primarySourcePackage, tr.spotifyUri, tr.trackId)
                },
                onToggleFreshList = {
                    vm.toggleTrackFreshStatus(tr.title, tr.artist, tr.primarySourcePackage, tr.spotifyUri, tr.trackId)
                }
            )
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
