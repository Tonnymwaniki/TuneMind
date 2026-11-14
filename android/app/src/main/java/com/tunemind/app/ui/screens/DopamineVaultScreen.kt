package com.tunemind.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.recommendation.AiRecommendationEntity
import com.tunemind.app.ui.VaultTrack
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

data class DopamineSongItem(
    val id: String,
    val title: String,
    val artist: String,
    val freshnessPercent: Int,
    val playCount: Int,
    val spotifyUri: String?,
    val sourcePackage: String,
    val isCoolingDown: Boolean,
    val isFromVault: Boolean = false
)

@Composable
fun DopamineVaultScreen(
    recentTracks: List<TrackMemoryEntry>,
    vaultTracks: List<VaultTrack>,
    nowPlayingTitle: String?,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onAskAgentAboutTrack: (title: String, artist: String, freshnessPercent: Int, spotifyUri: String?) -> Unit,
    onSyncAtmosphereToTrack: (title: String, artist: String) -> Unit,
    onQuarantineTrack: (title: String, artist: String) -> Unit,
    onRestoreVaultTrack: (id: String) -> Unit,
    onCycleAtmospherePalette: () -> Unit,
    forYouPicks: List<AiRecommendationEntity> = emptyList(),
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int = { _, _, _ -> 0 },
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean = { t, _, _ ->
        !nowPlayingTitle.isNullOrBlank() && !t.isNullOrBlank() && t.equals(nowPlayingTitle.trim(), ignoreCase = true)
    },
    onRefreshForYou: () -> Unit = {}
) {
    val theme = LocalAdaptiveTheme.current
    var selectedTab by remember { mutableStateOf(0) } // 0 = Fresh, 1 = Cooling Down, 2 = For You
    var activeSyncSong by remember { mutableStateOf<DopamineSongItem?>(null) }

    val allSongs = remember(recentTracks, vaultTracks, resolvePlayCount) {
        val list = mutableListOf<DopamineSongItem>()
        val seenKeys = mutableSetOf<String>()

        for (v in vaultTracks) {
            val key = "${v.title.trim().lowercase()}|${v.artist.trim().lowercase()}"
            if (!seenKeys.add(key)) continue
            val freshness = (v.recoveryProgress * 100f).toInt().coerceIn(15, 75)
            val resolvedPlays = resolvePlayCount(v.title, v.artist, null).takeIf { it > 0 } ?: 4
            list.add(
                DopamineSongItem(
                    id = v.id,
                    title = v.title,
                    artist = v.artist,
                    freshnessPercent = freshness,
                    playCount = resolvedPlays,
                    spotifyUri = null,
                    sourcePackage = "com.spotify.music",
                    isCoolingDown = true,
                    isFromVault = true
                )
            )
        }

        for (t in recentTracks) {
            val title = t.title?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val artist = t.artist?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown Artist"
            val key = "${title.lowercase()}|${artist.lowercase()}"
            if (!seenKeys.add(key)) continue

            val uri = t.providerTrackId?.takeIf { it.startsWith("spotify:track:") }
            val plays = resolvePlayCount(title, artist, uri).takeIf { it > 0 }
                ?: maxOf(1, t.distinctAppearanceCount)
            val satiety = minOf(94, plays * 19)
            val freshness = (100 - satiety).coerceIn(12, 98)
            val cooling = plays >= 4 || freshness < 45

            list.add(
                DopamineSongItem(
                    id = t.entryId,
                    title = title,
                    artist = artist,
                    freshnessPercent = freshness,
                    playCount = plays,
                    spotifyUri = uri,
                    sourcePackage = t.sourcePackage?.ifBlank { "com.spotify.music" } ?: "com.spotify.music",
                    isCoolingDown = cooling,
                    isFromVault = false
                )
            )
        }

        list
    }

    val peakFreshSongs = remember(allSongs) {
        allSongs.filter { !it.isCoolingDown }.sortedByDescending { it.freshnessPercent }
            .ifEmpty { allSongs.sortedByDescending { it.freshnessPercent }.take(5) }
    }

    val coolingDownSongs = remember(allSongs) {
        allSongs.filter { it.isCoolingDown }.sortedBy { it.freshnessPercent }
    }

    val averageFreshness = remember(allSongs) {
        if (allSongs.isEmpty()) 92
        else allSongs.map { it.freshnessPercent }.average().toInt().coerceIn(20, 99)
    }

    val displayedSongs = if (selectedTab == 0) peakFreshSongs else coolingDownSongs

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            // Top Header with Theme Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DOPAMINE",
                        color = theme.primaryAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Vault",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF0E121B).copy(alpha = 0.90f))
                        .border(1.dp, theme.primaryAccent.copy(alpha = 0.30f), RoundedCornerShape(50))
                        .bouncyClickable { onCycleAtmospherePalette() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = theme.primaryAccent,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = theme.activePalette.name,
                            color = Color.White.copy(alpha = 0.90f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Dopamine Readiness Hero Card
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(68.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawArc(
                                color = Color.White.copy(alpha = 0.08f),
                                startAngle = 135f,
                                sweepAngle = 270f,
                                useCenter = false,
                                style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = when (selectedTab) {
                                    1 -> Color(0xFFFFB000)
                                    2 -> theme.secondaryAccent
                                    else -> theme.primaryAccent
                                },
                                startAngle = 135f,
                                sweepAngle = 270f * (averageFreshness / 100f),
                                useCenter = false,
                                style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        Text(
                            text = "$averageFreshness%",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (selectedTab) {
                                1 -> "${coolingDownSongs.size} Cooling Down"
                                2 -> "${forYouPicks.size} Picks For You"
                                else -> "${peakFreshSongs.size} Fresh Tracks"
                            },
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (selectedTab) {
                                1 -> "Resting from heavy rotation so they feel new again."
                                2 -> "New discoveries tuned to your daily listening pattern."
                                else -> "Ready to hit like the first listen. Tap play or Sync for actions."
                            },
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3-Segment Filter Bar (Fresh | Cooling | For You)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Color(0xFF0C1018))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(23.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterTogglePill(
                    title = "Fresh (${peakFreshSongs.size})",
                    icon = Icons.Rounded.Bolt,
                    isSelected = selectedTab == 0,
                    activeColor = theme.primaryAccent,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f)
                )
                FilterTogglePill(
                    title = "Cooling (${coolingDownSongs.size})",
                    icon = Icons.Rounded.Schedule,
                    isSelected = selectedTab == 1,
                    activeColor = Color(0xFFFFB000),
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f)
                )
                FilterTogglePill(
                    title = "For You",
                    icon = Icons.Rounded.Explore,
                    isSelected = selectedTab == 2,
                    activeColor = theme.secondaryAccent,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.weight(0.92f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Scroll-Safe Song Cards List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 108.dp)
            ) {
                if (selectedTab == 2) {
                    val activeRecs = forYouPicks.filter { !it.isDismissed }
                    if (activeRecs.isEmpty()) {
                        item(key = "vault_foryou_empty") {
                            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Building personalized recommendations from your listening history.",
                                        color = Color.White.copy(alpha = 0.70f),
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(theme.secondaryAccent.copy(alpha = 0.20f))
                                            .border(1.dp, theme.secondaryAccent.copy(alpha = 0.50f), RoundedCornerShape(12.dp))
                                            .bouncyClickable { onRefreshForYou() }
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "Refresh Picks",
                                            color = theme.secondaryAccent,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        items(activeRecs, key = { "foryou_${it.id}" }) { rec ->
                            val plays = resolvePlayCount(rec.title, rec.artist, null)
                            val isPlaying = isTrackCurrentlyPlaying(rec.title, rec.artist, null)
                            TuneMindTrackCard(
                                title = rec.title,
                                artist = rec.artist,
                                subtitle = rec.reason,
                                playCount = plays,
                                showUnplayedLabel = true,
                                isCurrentlyPlaying = isPlaying,
                                freshnessPercent = (rec.confidence * 100f).toInt().coerceIn(75, 99),
                                accentColor = theme.secondaryAccent,
                                secondaryAccent = theme.primaryAccent,
                                onPlayClick = {
                                    onPlayTrack(rec.title, rec.artist, null, "com.spotify.music")
                                },
                                onSyncClick = {
                                    activeSyncSong = DopamineSongItem(
                                        id = rec.id,
                                        title = rec.title,
                                        artist = rec.artist,
                                        freshnessPercent = (rec.confidence * 100f).toInt().coerceIn(75, 99),
                                        playCount = plays,
                                        spotifyUri = null,
                                        sourcePackage = "com.spotify.music",
                                        isCoolingDown = false,
                                        isFromVault = false
                                    )
                                }
                            )
                        }
                    }
                } else if (displayedSongs.isEmpty()) {
                    item(key = "vault_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0xFF0C1018))
                                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (selectedTab == 0) "Play a song in Spotify or YouTube Music to build your Fresh rotation."
                                else "Nothing cooling down right now — your rotation is completely fresh.",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                } else {
                    items(displayedSongs, key = { it.id }) { song ->
                        val isCurrentlyPlaying = isTrackCurrentlyPlaying(song.title, song.artist, song.spotifyUri)
                        val accurateCount = resolvePlayCount(song.title, song.artist, song.spotifyUri)
                            .takeIf { it > 0 } ?: song.playCount

                        TuneMindTrackCard(
                            title = song.title,
                            artist = song.artist,
                            playCount = accurateCount,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            freshnessPercent = song.freshnessPercent,
                            isCoolingDown = song.isCoolingDown,
                            accentColor = theme.primaryAccent,
                            secondaryAccent = theme.secondaryAccent,
                            onPlayClick = {
                                onPlayTrack(song.title, song.artist, song.spotifyUri, song.sourcePackage)
                            },
                            onSyncClick = {
                                activeSyncSong = song.copy(playCount = accurateCount)
                            }
                        )
                    }
                }
            }
        }
    }

    activeSyncSong?.let { selectedSong ->
        NeuralTrackActionDialog(
            title = selectedSong.title,
            artist = selectedSong.artist,
            freshnessPercent = selectedSong.freshnessPercent,
            playCount = selectedSong.playCount,
            isCoolingDown = selectedSong.isCoolingDown,
            onDismiss = { activeSyncSong = null },
            onPlayNow = {
                onPlayTrack(selectedSong.title, selectedSong.artist, selectedSong.spotifyUri, selectedSong.sourcePackage)
            },
            onAgentNeuralBreakdown = {
                onAskAgentAboutTrack(
                    selectedSong.title,
                    selectedSong.artist,
                    selectedSong.freshnessPercent,
                    selectedSong.spotifyUri
                )
            },
            onSyncAtmosphereToTrack = {
                onSyncAtmosphereToTrack(selectedSong.title, selectedSong.artist)
            },
            onToggleCooldownVault = {
                if (selectedSong.isFromVault) {
                    onRestoreVaultTrack(selectedSong.id)
                } else {
                    onQuarantineTrack(selectedSong.title, selectedSong.artist)
                    selectedTab = 1
                }
            }
        )
    }
}

@Composable
private fun FilterTogglePill(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) activeColor.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(200),
        label = "FilterBg"
    )
    val contentColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.56f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(19.dp))
            .background(bgColor)
            .then(
                if (isSelected) Modifier.border(1.dp, activeColor.copy(alpha = 0.45f), RoundedCornerShape(19.dp))
                else Modifier
            )
            .bouncyClickable { onClick() }
            .padding(horizontal = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) activeColor else contentColor,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
