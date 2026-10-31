package com.tunemind.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.moment.MusicalMomentEntity
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SongProfileData
import com.tunemind.app.ui.components.SongProfileSheet
import com.tunemind.app.ui.theme.*

@Composable
fun MusicalMomentsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val momentsState by vm.musicalMomentsState.collectAsState()
    val homeState by vm.homeState.collectAsState()
    val colors = TuneMindTheme.colors

    var selectedTab by remember { mutableStateOf(0) } // 0: Studio Ledger, 1: Autonomous Hooks
    var selectedFilter by remember { mutableStateOf("ALL") }
    var activeScrubStartMs by remember { mutableStateOf(74_000L) } // 01:14
    var activeScrubEndMs by remember { mutableStateOf(104_000L) } // 01:44
    var isShuffleLoopActive by remember { mutableStateOf(false) }

    val confirmed = momentsState.confirmedMoments
    val candidates = momentsState.candidateMoments

    val nowPlaying = homeState.nowPlaying
    val currentTrackTitle = nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "Starboy"
    val currentTrackArtist = nowPlaying?.artist?.takeIf { it.isNotBlank() } ?: "The Weeknd"

    fun formatDuration(ms: Long): String {
        val totalSec = maxOf(0L, ms / 1000)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.primaryBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. CONSOLE TELEMETRY STATUS BAR
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.secondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.cardSurface)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", tint = colors.secondaryText, modifier = Modifier.size(18.dp))
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981))
                        )
                        Text(
                            text = "DSP BUFFER // LIVE",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondaryText,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        text = "MOMENT CAPTURE CONSOLE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText,
                        letterSpacing = 0.8.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = colors.cardSurface,
                    modifier = Modifier.padding(2.dp)
                ) {
                    Text(
                        text = "48kHz / 24B",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryAccent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // 2. TRACK CONTEXT SUBHEADER RIBBON
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.secondaryBackground.copy(alpha = 0.7f),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.cardSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = colors.primaryAccent, modifier = Modifier.size(16.dp))
                    }
                    Column {
                        Text(
                            text = "TRACK SOURCE",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.secondaryText,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$currentTrackTitle — $currentTrackArtist",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = colors.elevatedSurface
                ) {
                    Text(
                        text = "118 BPM // REFLECTIVE",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryAccent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // 3. STEREO WAVEFORM VISUALIZER & DUAL-PIN MOMENT SCRUBBER
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.secondaryBackground)
                .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "STEREO SPECTRAL FLUX",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = colors.cardSurface
                    ) {
                        Text(
                            text = "CH-A/B",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryAccent,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = "PEAK: -1.8 dBFS",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
            }

            // Waveform Bay Chassis with Dual-Needle Markers
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primaryBackground)
                    .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                // Waveform Simulated Harmonic Spectral Bars
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val bars = listOf(
                        18, 26, 42, 35, 60, 48, 70, 85, 95, 68, 88, 100, 72, 85, 98,
                        110, 88, 95, 105, 90, 78, 92, 80, 65, 52, 40, 32, 22
                    )
                    bars.forEachIndexed { index, height ->
                        val isInsideActiveRegion = index in 7..21
                        val barColor = when {
                            !isInsideActiveRegion -> colors.cardSurface
                            index % 2 == 0 -> colors.primaryAccent
                            else -> Color(0xFF83B9BD)
                        }
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height((height * 0.9f).dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(barColor)
                        )
                    }
                }

                // Start Marker Scrubber Pin (CH-A)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 65.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = colors.primaryAccent
                        ) {
                            Text(
                                text = formatDuration(activeScrubStartMs),
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryBackground,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.5.dp)
                                .weight(1f)
                                .background(colors.primaryAccent)
                        )
                    }
                }

                // End Marker Scrubber Pin (CH-B)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 65.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = Color(0xFF83B9BD)
                        ) {
                            Text(
                                text = formatDuration(activeScrubEndMs),
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryBackground,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.5.dp)
                                .weight(1f)
                                .background(Color(0xFF83B9BD))
                        )
                    }
                }
            }

            // Scrubber Controls & Bookmark Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SELECTION: 30.0s [STEREO HOOK]",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText
                )

                Button(
                    onClick = {
                        vm.bookmarkCurrentMoment(
                            title = currentTrackTitle,
                            artist = currentTrackArtist,
                            startMs = activeScrubStartMs,
                            endMs = activeScrubEndMs,
                            customName = "Hook: $currentTrackTitle",
                            vibeTag = "Chorus"
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primaryAccent),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = colors.primaryBackground, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "LOCK MOMENT",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryBackground
                    )
                }
            }
        }

        // 4. ARCHIVE REEL & MUSICAL MOMENTS LEDGER
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MOMENTS LEDGER // ${confirmed.size} STORED HOOKS",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText,
                    letterSpacing = 1.sp
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.cardSurface)
                        .clickable { isShuffleLoopActive = !isShuffleLoopActive }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isShuffleLoopActive) colors.primaryAccent else colors.secondaryText)
                    )
                    Text(
                        text = "SHUFFLE LOOP",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isShuffleLoopActive) colors.primaryAccent else colors.secondaryText
                    )
                }
            }

            // Ledger Entries (Tape-Reel Anti-Card list items)
            if (confirmed.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.secondaryBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = colors.secondaryText, modifier = Modifier.size(28.dp))
                        Text(
                            text = "NO LOCKED MOMENTS YET",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                        Text(
                            text = "Use the spectral scrubber above to isolate and bookmark memorable hooks.",
                            fontSize = 11.sp,
                            color = colors.secondaryText
                        )
                    }
                }
            } else {
                confirmed.forEach { moment ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.secondaryBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.cardSurface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = colors.primaryAccent, modifier = Modifier.size(20.dp))
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = moment.momentName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.primaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${moment.trackTitle} • ${moment.artist}",
                                        fontSize = 11.sp,
                                        color = colors.secondaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = colors.cardSurface
                                        ) {
                                            Text(
                                                text = "${formatDuration(moment.startMs)} - ${formatDuration(moment.endMs)}",
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF83B9BD),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                        Text(
                                            text = "PLAYED ${moment.loopCount}x",
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.secondaryText
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = { vm.deleteMoment(moment.id) },
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.cardSurface)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = colors.secondaryText, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
