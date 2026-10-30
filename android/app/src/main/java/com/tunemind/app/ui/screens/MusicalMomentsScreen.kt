package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.moment.MusicalMomentEntity
import com.tunemind.app.domain.recommendation.RecommendationCategory
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SongProfileData
import com.tunemind.app.ui.components.SongProfileSheet
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicalMomentsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val momentsState by vm.musicalMomentsState.collectAsState()
    val homeState by vm.homeState.collectAsState()
    val context = LocalContext.current

    var selectedTab by remember { mutableStateOf(0) } // 0: Studio Moments, 1: Autonomous Discoveries
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingMoment by remember { mutableStateOf<MusicalMomentEntity?>(null) }
    var selectedProfileData by remember { mutableStateOf<SongProfileData?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    val confirmed = momentsState.confirmedMoments
    val candidates = momentsState.candidateMoments

    val nowPlaying = homeState.nowPlaying
    val currentTrackTitle = nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "Active Audio Track"
    val currentTrackArtist = nowPlaying?.artist?.takeIf { it.isNotBlank() } ?: "Observed Media Session"
    val totalSongDurationMs = maxOf(60_000L, nowPlaying?.durationMs?.takeIf { it > 0L } ?: 220_000L)

    var newMomentName by remember { mutableStateOf("Chorus: $currentTrackTitle") }
    var newStartMs by remember { mutableStateOf(0L) }
    var newEndMs by remember { mutableStateOf(minOf(60_000L, totalSongDurationMs)) }
    var selectedVibeTag by remember { mutableStateOf("Chorus") }

    fun formatDuration(ms: Long): String {
        val totalSec = maxOf(0L, ms / 1000)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    fun formatReadableDuration(ms: Long): String {
        val totalSec = maxOf(0L, ms / 1000)
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // High-End Studio Header Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF06B6D4).copy(alpha = 0.3f), Color(0xFF3B82F6).copy(alpha = 0.15f))
                                    )
                                )
                                .border(1.dp, AccentCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                        }
                        Column {
                            Text(
                                text = "ACOUSTIC INTELLIGENCE • STUDIO",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "My Musical Moments",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }

                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                    }
                }

                Text(
                    text = "Preserve and loop exact sections of songs you enjoy — guitar solos, bass drops, or vocal hooks. Configure custom segment lengths up to full song duration.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 16.sp
                )

                // 3 Studio Metric Gauges
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF020617),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("SAVED", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            Text("${confirmed.size}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                            Text("Moments", fontSize = 9.sp, color = Color(0xFF64748B))
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF020617),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("AVG LENGTH", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            val avgSec = if (confirmed.isNotEmpty()) (confirmed.map { it.preferredPlaybackDurationMs }.average() / 1000).toInt() else 0
                            Text(if (avgSec > 0) "${avgSec}s" else "--", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                            Text("Per Section", fontSize = 9.sp, color = Color(0xFF64748B))
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF020617),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("SEEK-BACKS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            Text("${candidates.size}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentAmber)
                            Text("Loop Patterns", fontSize = 9.sp, color = Color(0xFF64748B))
                        }
                    }
                }
            }
        }

        // Action Bar: Tabs & Create Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedTab == 0) AccentCyan.copy(alpha = 0.2f) else Color(0xFF0F172A),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedTab == 0) AccentCyan else Color(0xFF1E293B)),
                    modifier = Modifier.clickable { selectedTab = 0 }
                ) {
                    Text(
                        text = "Studio Moments (${confirmed.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedTab == 0) AccentCyanBright else Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedTab == 1) AccentAmber.copy(alpha = 0.2f) else Color(0xFF0F172A),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedTab == 1) AccentAmber else Color(0xFF1E293B)),
                    modifier = Modifier.clickable { selectedTab = 1 }
                ) {
                    Text(
                        text = "Discoveries (${candidates.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedTab == 1) AccentAmber else Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Button(
                onClick = {
                    newMomentName = "Chorus: $currentTrackTitle"
                    newStartMs = 0L
                    newEndMs = minOf(60_000L, totalSongDurationMs)
                    selectedVibeTag = "Chorus"
                    showCreateDialog = true
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF020617)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New Moment", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Notification Snackbar
        snackbarMessage?.let { msg ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(msg, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { snackbarMessage = null }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // Content List
        val currentList = if (selectedTab == 0) confirmed else candidates

        if (currentList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0F172A), RoundedCornerShape(18.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF020617))
                            .border(1.dp, Color(0xFF1E293B), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (selectedTab == 0) Icons.Outlined.BookmarkBorder else Icons.Outlined.Search,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Text(
                        if (selectedTab == 0) "No Saved Moments in Studio" else "No Autonomous Discoveries Yet",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        if (selectedTab == 0) "Create a moment with flexible start/end durations up to the full song length, or tap 'Moment' while playing a song."
                        else "TuneMind autonomously detects seek-back loops when you rewind and replay specific sections in Spotify or YouTube Music.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(currentList, key = { it.momentId }) { moment ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Top Row: Vinyl avatar + Title + Section Badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.radialGradient(listOf(Color(0xFF1E293B), Color(0xFF020617)))
                                            )
                                            .border(1.dp, AccentCyan.copy(alpha = 0.5f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(SongProfileData.pickEmoji(moment.trackTitle, moment.trackArtist), fontSize = 20.sp)
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = moment.customName ?: "${moment.trackTitle} Moment",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${moment.trackTitle} • ${moment.trackArtist}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF94A3B8),
                                            maxLines = 1
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (moment.userConfirmed) Color(0x3306B6D4) else Color(0x33F59E0B),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (moment.userConfirmed) AccentCyan else AccentAmber)
                                ) {
                                    Text(
                                        text = if (moment.userConfirmed) "STUDIO MOMENT" else "LOOP DISCOVERY",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (moment.userConfirmed) AccentCyanBright else AccentAmber,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Visual Waveform / Track Position Minimap Bar
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val estTrackTotal = maxOf(moment.endPositionMs + 30_000L, 210_000L)
                                val startFrac = (moment.startPositionMs.toFloat() / estTrackTotal.toFloat()).coerceIn(0f, 0.95f)
                                val endFrac = (moment.endPositionMs.toFloat() / estTrackTotal.toFloat()).coerceIn(startFrac + 0.05f, 1f)

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF020617))
                                ) {
                                    // Highlighted active moment section on the track timeline
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(fraction = endFrac - startFrac)
                                            .offset(x = (startFrac * 260).dp) // approximate visual position
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(AccentCyan, AccentCyanBright)
                                                ),
                                                RoundedCornerShape(4.dp)
                                            )
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Starts: ${formatDuration(moment.startPositionMs)}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AccentCyan
                                    )
                                    Text(
                                        "Segment: ${formatReadableDuration(moment.preferredPlaybackDurationMs)}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        "Ends: ${formatDuration(moment.endPositionMs)}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AccentCyanBright
                                    )
                                }
                            }

                            // Controls Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = {
                                            val fakeRec = RecommendedTrack(
                                                id = "moment-${moment.momentId}",
                                                title = moment.trackTitle,
                                                artist = moment.trackArtist,
                                                album = moment.trackAlbum,
                                                category = RecommendationCategory.PALATE_CLEANSER,
                                                reason = "Playing saved musical moment section (${formatDuration(moment.startPositionMs)} - ${formatDuration(moment.endPositionMs)})",
                                                confidence = 0.95f
                                            )
                                            vm.playRecommendation(context, fakeRec)
                                            snackbarMessage = "Launching '${moment.trackTitle}' in Spotify at ${formatDuration(moment.startPositionMs)}"
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF020617)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Play Section", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    FilledTonalButton(
                                        onClick = { editingMoment = moment },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF1E293B), contentColor = Color(0xFF94A3B8)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Tune, contentDescription = "Edit Duration", modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Edit Duration", fontSize = 11.sp)
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!moment.userConfirmed) {
                                        Button(
                                            onClick = { vm.confirmCandidateMoment(moment.momentId) },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color(0xFF020617)),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text("Save", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    IconButton(
                                        onClick = { vm.deleteMusicalMoment(moment.momentId) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color(0xFF64748B), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Studio Moment Creator Dialog (with dynamic song duration & presets)
    if (showCreateDialog) {
        val maxDuration = totalSongDurationMs

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = Color(0xFF0F172A),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = AccentCyan)
                    Text("Define Musical Moment", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Customize the exact section of '$currentTrackTitle' to preserve. Set any duration up to the full song length (${formatDuration(maxDuration)}).",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 16.sp
                    )

                    OutlinedTextField(
                        value = newMomentName,
                        onValueChange = { newMomentName = it },
                        label = { Text("Moment Label") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Section Tag Chips
                    Text("SECTION TYPE", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Chorus", "Solo", "Drop", "Bridge", "Full Song").forEach { tag ->
                            val isSelected = selectedVibeTag == tag
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) AccentCyan.copy(alpha = 0.25f) else Color(0xFF020617),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AccentCyan else Color(0xFF334155)),
                                modifier = Modifier.clickable {
                                    selectedVibeTag = tag
                                    newMomentName = "$tag: $currentTrackTitle"
                                    if (tag == "Full Song") {
                                        newStartMs = 0L
                                        newEndMs = maxDuration
                                    }
                                }
                            ) {
                                Text(
                                    tag,
                                    fontSize = 10.sp,
                                    color = if (isSelected) AccentCyanBright else Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Interactive Start / End Slider
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Start: ${formatDuration(newStartMs)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentCyan)
                            Text("End: ${formatDuration(newEndMs)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentCyanBright)
                        }

                        Slider(
                            value = newStartMs.toFloat(),
                            onValueChange = {
                                newStartMs = it.toLong()
                                if (newEndMs <= newStartMs + 5_000L) {
                                    newEndMs = minOf(maxDuration, newStartMs + 30_000L)
                                }
                            },
                            valueRange = 0f..(maxDuration - 5_000L).toFloat(),
                            colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentCyan)
                        )

                        Slider(
                            value = newEndMs.toFloat(),
                            onValueChange = {
                                newEndMs = it.toLong()
                                if (newStartMs >= newEndMs - 5_000L) {
                                    newStartMs = maxOf(0L, newEndMs - 30_000L)
                                }
                            },
                            valueRange = 5_000f..maxDuration.toFloat(),
                            colors = SliderDefaults.colors(thumbColor = AccentCyanBright, activeTrackColor = AccentCyanBright)
                        )
                    }

                    // Quick Duration Length Presets
                    Text("QUICK LENGTH PRESETS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            30_000L to "30s",
                            60_000L to "1m",
                            90_000L to "1m 30s",
                            120_000L to "2m",
                            maxDuration to "Max"
                        ).forEach { (len, label) ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF020617),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier.clickable {
                                    newEndMs = minOf(maxDuration, newStartMs + len)
                                }
                            ) {
                                Text(
                                    label,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    // Calculated Playback Length Banner
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF020617),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Total Section Playback:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text(
                                formatReadableDuration(newEndMs - newStartMs),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentGreen
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.saveMusicalMoment(
                            title = currentTrackTitle,
                            artist = currentTrackArtist,
                            sourcePkg = nowPlaying?.sourcePackage,
                            startMs = newStartMs,
                            endMs = newEndMs,
                            customName = newMomentName.takeIf { it.isNotBlank() }
                        )
                        showCreateDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF020617))
                ) {
                    Text("Save Moment", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    // Edit Existing Moment Dialog
    editingMoment?.let { moment ->
        var editStart by remember { mutableStateOf(moment.startPositionMs) }
        var editEnd by remember { mutableStateOf(moment.endPositionMs) }
        val editMax = maxOf(moment.endPositionMs + 60_000L, 240_000L)

        AlertDialog(
            onDismissRequest = { editingMoment = null },
            containerColor = Color(0xFF0F172A),
            title = {
                Text("Adjust Moment Duration", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${moment.trackTitle} • ${moment.trackArtist}",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Start: ${formatDuration(editStart)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentCyan)
                        Text("End: ${formatDuration(editEnd)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentCyanBright)
                    }

                    Slider(
                        value = editEnd.toFloat(),
                        onValueChange = { editEnd = it.toLong() },
                        valueRange = (editStart + 5_000L).toFloat()..editMax.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentCyan)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30_000L to "+30s", 60_000L to "+1m", 120_000L to "+2m").forEach { (extra, label) ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF020617),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier.clickable { editEnd = minOf(editMax, editEnd + extra) }
                            ) {
                                Text(label, fontSize = 10.sp, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }

                    Text(
                        "New Duration: ${formatReadableDuration(editEnd - editStart)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.saveMusicalMoment(
                            title = moment.trackTitle,
                            artist = moment.trackArtist,
                            sourcePkg = moment.sourcePackage,
                            startMs = editStart,
                            endMs = editEnd,
                            customName = moment.customName
                        )
                        editingMoment = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF020617))
                ) {
                    Text("Apply Duration", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMoment = null }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    selectedProfileData?.let { profile ->
        SongProfileSheet(
            profile = profile,
            vm = vm,
            onDismiss = { selectedProfileData = null }
        )
    }
}
