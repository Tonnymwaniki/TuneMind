package com.tunemind.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import com.tunemind.app.ui.VaultTrack
import com.tunemind.app.ui.components.NeuralSonicActionButton
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
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
    onCycleAtmospherePalette: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    var selectedTab by remember { mutableStateOf(0) } // 0 = Fresh, 1 = Cooling Down
    var activeSyncSong by remember { mutableStateOf<DopamineSongItem?>(null) }

    val allSongs = remember(recentTracks, vaultTracks) {
        val list = mutableListOf<DopamineSongItem>()
        val seenKeys = mutableSetOf<String>()

        for (v in vaultTracks) {
            val key = "${v.title.trim().lowercase()}|${v.artist.trim().lowercase()}"
            if (!seenKeys.add(key)) continue
            val freshness = (v.recoveryProgress * 100f).toInt().coerceIn(15, 75)
            list.add(
                DopamineSongItem(
                    id = v.id,
                    title = v.title,
                    artist = v.artist,
                    freshnessPercent = freshness,
                    playCount = 5,
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

            val plays = maxOf(1, t.distinctAppearanceCount)
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
                    spotifyUri = t.providerTrackId?.takeIf { it.startsWith("spotify:track:") },
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
                                color = if (selectedTab == 0) theme.primaryAccent else Color(0xFFFFB000),
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
                            text = if (selectedTab == 0) "${peakFreshSongs.size} Fresh Tracks" else "${coolingDownSongs.size} Cooling Down",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (selectedTab == 0) "Ready to hit like the first listen. Tap play or Sync for actions."
                            else "Resting from heavy rotation so they feel new again.",
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2-Segment Filter Bar (Fresh vs Cooling Down)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Color(0xFF0C1018))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(23.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Scroll-Safe Song Cards List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 108.dp)
            ) {
                if (displayedSongs.isEmpty()) {
                    item {
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
                        val isCurrentlyPlaying = !nowPlayingTitle.isNullOrBlank() &&
                            song.title.equals(nowPlayingTitle.trim(), ignoreCase = true)

                        DopamineSongCard(
                            song = song,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            primaryAccent = theme.primaryAccent,
                            secondaryAccent = theme.secondaryAccent,
                            onPlayButtonClicked = {
                                onPlayTrack(song.title, song.artist, song.spotifyUri, song.sourcePackage)
                            },
                            onNeuralSyncClicked = {
                                activeSyncSong = song
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
            .padding(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) activeColor else contentColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = title,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun DopamineSongCard(
    song: DopamineSongItem,
    isCurrentlyPlaying: Boolean,
    primaryAccent: Color,
    secondaryAccent: Color,
    onPlayButtonClicked: () -> Unit,
    onNeuralSyncClicked: () -> Unit
) {
    val accentColor = when {
        isCurrentlyPlaying -> Color(0xFF10B981)
        song.isCoolingDown -> Color(0xFFFFB000)
        else -> primaryAccent
    }

    val cardShape = RoundedCornerShape(22.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(
                if (isCurrentlyPlaying) accentColor.copy(alpha = 0.12f)
                else Color(0xFF0C1018).copy(alpha = 0.90f)
            )
            .border(
                width = 1.dp,
                color = if (isCurrentlyPlaying) accentColor.copy(alpha = 0.48f)
                else Color.White.copy(alpha = 0.08f),
                shape = cardShape
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Dedicated 46dp Play Button with freshness arc
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .bouncyClickable { onPlayButtonClicked() }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = accentColor.copy(alpha = 0.14f))
                drawArc(
                    color = accentColor,
                    startAngle = -90f,
                    sweepAngle = 360f * (song.freshnessPercent / 100f),
                    useCenter = false,
                    style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Icon(
                imageVector = if (isCurrentlyPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isCurrentlyPlaying) "Playing" else "Play",
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                color = Color.White.copy(alpha = 0.64f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isCurrentlyPlaying) "Playing"
                    else if (song.isCoolingDown) "Cooling • ${song.playCount}x"
                    else "${song.freshnessPercent}% Fresh",
                    color = accentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        NeuralSonicActionButton(
            accentColor = accentColor,
            secondaryColor = secondaryAccent,
            onClick = onNeuralSyncClicked
        )
    }
}
