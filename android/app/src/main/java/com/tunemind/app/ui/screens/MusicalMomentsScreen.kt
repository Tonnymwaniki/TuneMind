package com.tunemind.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.voice.AgentSettingsStore
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicalMomentsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val momentsState by vm.musicalMomentsState.collectAsState()
    val homeState by vm.homeState.collectAsState()
    val isLoopActive by AgentSettingsStore.loopMomentsEnabled.collectAsState()
    val theme = LocalAdaptiveTheme.current

    val confirmed = momentsState.confirmedMoments
    val nowPlaying = homeState.nowPlaying
    val currentTrackTitle = nowPlaying?.title?.takeIf { it.isNotBlank() }
        ?: homeState.recentTracks.firstOrNull()?.title?.takeIf { it.isNotBlank() }
        ?: "No active track"
    val currentTrackArtist = nowPlaying?.artist?.takeIf { it.isNotBlank() }
        ?: homeState.recentTracks.firstOrNull()?.artist?.takeIf { it.isNotBlank() }
        ?: "Play any song to bookmark a moment"
    val canSaveCurrent = currentTrackTitle != "No active track"

    val totalTrackDurationMs = remember(nowPlaying?.title, nowPlaying?.durationMs) {
        (nowPlaying?.durationMs?.takeIf { it >= 30_000L } ?: 210_000L).coerceAtMost(600_000L)
    }

    var activeScrubStartMs by remember(nowPlaying?.title) {
        val livePos = nowPlaying?.positionMs?.coerceIn(0L, totalTrackDurationMs - 15_000L)
        mutableStateOf(livePos?.takeIf { it > 0L } ?: 45_000L)
    }
    var activeScrubEndMs by remember(nowPlaying?.title) {
        val start = activeScrubStartMs
        mutableStateOf(minOf(totalTrackDurationMs, start + 30_000L))
    }

    val clipDurationSec = maxOf(5L, (activeScrubEndMs - activeScrubStartMs) / 1000L)

    fun formatDuration(ms: Long): String {
        val totalSec = maxOf(0L, ms / 1000L)
        val m = totalSec / 60L
        val s = totalSec % 60L
        return "%02d:%02d".format(m, s)
    }

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
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
                            text = "SOUND MOMENTS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFB000),
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            text = "Saved Highlights",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF0E121B).copy(alpha = 0.90f))
                        .border(1.dp, Color(0xFFFFB000).copy(alpha = 0.35f), RoundedCornerShape(50))
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${confirmed.size} Saved",
                        color = Color(0xFFFFB000),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Current Track Strip
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.primaryAccent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = theme.primaryAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (nowPlaying?.isPlaying == true) "Playing Now" else "Selected Track",
                            fontSize = 11.sp,
                            color = if (nowPlaying?.isPlaying == true) Color(0xFF10B981) else Color.White.copy(alpha = 0.56f),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "$currentTrackTitle — $currentTrackArtist",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (nowPlaying != null && nowPlaying.positionMs > 0L) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(theme.primaryAccent.copy(alpha = 0.16f))
                                .border(1.dp, theme.primaryAccent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .bouncyClickable {
                                    val pos = nowPlaying.positionMs.coerceIn(0L, totalTrackDurationMs - 10_000L)
                                    activeScrubStartMs = pos
                                    activeScrubEndMs = minOf(totalTrackDurationMs, pos + 30_000L)
                                }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MyLocation,
                                    contentDescription = "Mark Current Time",
                                    tint = theme.primaryAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = formatDuration(nowPlaying.positionMs),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = theme.primaryAccent
                                )
                            }
                        }
                    }
                }
            }

            // Moment Selector Card with real RangeSlider
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Clip Window",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${formatDuration(activeScrubStartMs)} – ${formatDuration(activeScrubEndMs)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.primaryAccent
                        )
                    }

                    val bars = remember {
                        listOf(
                            18, 26, 42, 35, 60, 48, 70, 85, 92, 68, 88, 96, 72, 85, 92,
                            98, 88, 92, 95, 86, 78, 88, 76, 65, 52, 40, 32, 22
                        )
                    }
                    val startFraction = (activeScrubStartMs.toFloat() / totalTrackDurationMs.toFloat()).coerceIn(0f, 1f)
                    val endFraction = (activeScrubEndMs.toFloat() / totalTrackDurationMs.toFloat()).coerceIn(startFraction, 1f)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(94.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF06080F))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            bars.forEachIndexed { index, height ->
                                val posFrac = index.toFloat() / maxOf(1, bars.size - 1).toFloat()
                                val isInside = posFrac in startFraction..endFraction
                                val barColor = when {
                                    !isInside -> Color.White.copy(alpha = 0.14f)
                                    index % 2 == 0 -> theme.primaryAccent
                                    else -> theme.secondaryAccent
                                }
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height((height * 0.74f).dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(barColor)
                                )
                            }
                        }
                    }

                    RangeSlider(
                        value = activeScrubStartMs.toFloat()..activeScrubEndMs.toFloat(),
                        onValueChange = { range ->
                            val minGapMs = 10_000f
                            val newStart = range.start.coerceIn(0f, totalTrackDurationMs.toFloat() - minGapMs)
                            val newEnd = range.endInclusive.coerceIn(newStart + minGapMs, totalTrackDurationMs.toFloat())
                            activeScrubStartMs = newStart.toLong()
                            activeScrubEndMs = newEnd.toLong()
                        },
                        valueRange = 0f..totalTrackDurationMs.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = theme.primaryAccent,
                            activeTrackColor = theme.primaryAccent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("00:00", fontSize = 10.sp, color = Color.White.copy(alpha = 0.48f))
                        Text("Drag handles to trim clip", fontSize = 10.sp, color = Color.White.copy(alpha = 0.56f))
                        Text(formatDuration(totalTrackDurationMs), fontSize = 10.sp, color = Color.White.copy(alpha = 0.48f))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${clipDurationSec}s highlight selected",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.68f)
                        )

                        Button(
                            enabled = canSaveCurrent,
                            onClick = {
                                vm.saveMusicalMoment(
                                    title = currentTrackTitle,
                                    artist = currentTrackArtist,
                                    album = nowPlaying?.album,
                                    sourcePkg = nowPlaying?.sourcePackage,
                                    startMs = activeScrubStartMs,
                                    endMs = activeScrubEndMs,
                                    customName = "Highlight (${formatDuration(activeScrubStartMs)} – ${formatDuration(activeScrubEndMs)})"
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = theme.primaryAccent),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "Save Moment",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }
            }

            // Saved Moments List
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Saved Moments",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isLoopActive) theme.primaryAccent.copy(alpha = 0.18f)
                            else Color.White.copy(alpha = 0.06f)
                        )
                        .border(
                            1.dp,
                            if (isLoopActive) theme.primaryAccent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(10.dp)
                        )
                        .bouncyClickable {
                            AgentSettingsStore.setLoopMomentsEnabled(context, !isLoopActive)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Repeat,
                        contentDescription = null,
                        tint = if (isLoopActive) theme.primaryAccent else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (isLoopActive) "Loop Clip On" else "Loop Clip Off",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isLoopActive) theme.primaryAccent else Color.White.copy(alpha = 0.65f)
                    )
                }
            }

            if (confirmed.isEmpty()) {
                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BookmarkBorder,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.56f),
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "No saved moments yet",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Drag the clip range above and tap Save Moment while listening.",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    }
                }
            } else {
                confirmed.forEach { moment ->
                    SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(theme.primaryAccent.copy(alpha = 0.16f))
                                        .border(1.dp, theme.primaryAccent.copy(alpha = 0.35f), CircleShape)
                                        .bouncyClickable {
                                            onPlayTrack(
                                                moment.trackTitle,
                                                moment.trackArtist,
                                                null,
                                                moment.sourcePackage ?: "com.spotify.music"
                                            )
                                            vm.configureAndSeekMomentPlayback(
                                                startMs = moment.startPositionMs,
                                                endMs = moment.endPositionMs,
                                                loopEnabled = isLoopActive
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Play Moment",
                                        tint = theme.primaryAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = moment.customName ?: moment.trackTitle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${moment.trackTitle} • ${moment.trackArtist}",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.64f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${formatDuration(moment.startPositionMs)} – ${formatDuration(moment.endPositionMs)} (${moment.preferredPlaybackDurationMs / 1000}s)",
                                        fontSize = 11.sp,
                                        color = theme.primaryAccent
                                    )
                                }
                            }

                            IconButton(onClick = { vm.deleteMusicalMoment(moment.momentId) }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete",
                                    tint = Color.White.copy(alpha = 0.60f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
