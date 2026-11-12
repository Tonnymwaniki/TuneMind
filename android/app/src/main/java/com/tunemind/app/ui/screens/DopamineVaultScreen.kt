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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.ui.VaultTrack
import com.tunemind.app.ui.components.NeuralSonicActionButton
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
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
    var selectedTab by remember { mutableStateOf(0) } // 0 = Peak Dopamine (Fresh), 1 = Cooling Down (Overplayed)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        theme.primaryBackground,
                        theme.secondaryBackground,
                        theme.recessedBackground
                    )
                )
            )
    ) {
        // Ambient Adaptive Atmosphere Glow Orb in Top-Right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = (-30).dp)
                .size(220.dp)
                .blur(75.dp)
                .background(theme.primaryAccent.copy(alpha = 0.20f), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            // Top Header with Live Adaptive Atmosphere Switcher Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DOPAMINE VAULT",
                        color = theme.primaryAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Fresh vs. Overplayed",
                        color = theme.primaryText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Tap to cycle live Adaptive Atmosphere Palette
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(theme.primaryAccent.copy(alpha = 0.14f))
                        .border(1.dp, theme.primaryAccent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .bouncyClickable { onCycleAtmospherePalette() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(theme.primaryAccent)
                        )
                        Text(
                            text = theme.activePalette.name.uppercase(),
                            color = theme.primaryText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Visual Dopamine Readiness Hero Gauge
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
                        modifier = Modifier.size(70.dp)
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
                            text = if (selectedTab == 0) "${peakFreshSongs.size} Peak Dopamine Tracks" else "${coolingDownSongs.size} Tracks Cooling Down",
                            color = theme.primaryText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (selectedTab == 0) "Tap ▶ to play a song, or tap SYNC for neural track actions."
                                   else "Resting from heavy rotation so they hit like the first time.",
                            color = theme.secondaryText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2-Segment Filter Bar (Peak Dopamine vs Cooling Down)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(theme.cardSurface.copy(alpha = 0.55f))
                    .border(1.dp, theme.border.copy(alpha = 0.6f), RoundedCornerShape(23.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterTogglePill(
                    title = "⚡ PEAK DOPAMINE (${peakFreshSongs.size})",
                    isSelected = selectedTab == 0,
                    activeColor = theme.primaryAccent,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f)
                )
                FilterTogglePill(
                    title = "⏳ COOLING DOWN (${coolingDownSongs.size})",
                    isSelected = selectedTab == 1,
                    activeColor = Color(0xFFFFB000),
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Song Cards List (Scroll-Safe: row is NOT clickable, only ▶ Play and SYNC buttons respond to taps!)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 106.dp)
            ) {
                if (displayedSongs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(theme.cardSurface.copy(alpha = 0.4f))
                                .border(1.dp, theme.border.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (selectedTab == 0) "Play a song on Spotify or YouTube Music to populate your Peak Dopamine rotation."
                                       else "No overplayed songs right now — your rotation is 100% fresh!",
                                color = theme.secondaryText,
                                fontSize = 13.sp
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
                            cardSurface = theme.cardSurface,
                            borderColor = theme.border,
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

    // Neural Track Action Sheet Modal when tapping SYNC on any song
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
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) activeColor.copy(alpha = 0.20f) else Color.Transparent,
        animationSpec = tween(200),
        label = "FilterBg"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(19.dp))
            .background(bgColor)
            .then(
                if (isSelected) Modifier.border(1.dp, activeColor.copy(alpha = 0.55f), RoundedCornerShape(19.dp))
                else Modifier
            )
            .bouncyClickable { onClick() }
    ) {
        Text(
            text = title,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
private fun DopamineSongCard(
    song: DopamineSongItem,
    isCurrentlyPlaying: Boolean,
    primaryAccent: Color,
    secondaryAccent: Color,
    cardSurface: Color,
    borderColor: Color,
    onPlayButtonClicked: () -> Unit,
    onNeuralSyncClicked: () -> Unit
) {
    val accentColor = when {
        isCurrentlyPlaying -> Color(0xFF10B981)
        song.isCoolingDown -> Color(0xFFFFB000)
        else -> primaryAccent
    }

    val cardShape = RoundedCornerShape(24.dp)

    // Scroll-Safe Row: No click listener on the row container so scrolling never accidentally plays a song!
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(
                if (isCurrentlyPlaying) accentColor.copy(alpha = 0.14f)
                else cardSurface.copy(alpha = 0.62f)
            )
            .border(
                width = 1.dp,
                color = if (isCurrentlyPlaying) accentColor.copy(alpha = 0.55f)
                        else borderColor.copy(alpha = 0.45f),
                shape = cardShape
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Left: Dedicated 48dp Play Button (ONLY clicking this button plays the song immediately)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .bouncyClickable { onPlayButtonClicked() }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = accentColor.copy(alpha = 0.18f)
                )
                drawArc(
                    color = accentColor,
                    startAngle = -90f,
                    sweepAngle = 360f * (song.freshnessPercent / 100f),
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Text(
                text = if (isCurrentlyPlaying) "❚❚" else "▶",
                color = accentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Center: Song Title + Artist + Dopamine Badge
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isCurrentlyPlaying) "NOW PLAYING"
                               else if (song.isCoolingDown) "COOLING • ${song.playCount}x"
                               else "${song.freshnessPercent}% FRESH",
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }
            }
        }

        // Right: Upgraded Sculpted Neural Sonic Action Button (Opens Track Intelligence & Actions)
        NeuralSonicActionButton(
            accentColor = accentColor,
            secondaryColor = secondaryAccent,
            onClick = onNeuralSyncClicked
        )
    }
}
