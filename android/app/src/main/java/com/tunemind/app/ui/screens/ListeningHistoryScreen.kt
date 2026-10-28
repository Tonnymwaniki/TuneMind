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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningHistoryScreen(vm: TuneMindViewModel) {
    val state by vm.historyState.collectAsState()
    val filtered = remember(state.tracks, state.filterMode) { vm.getFilteredTracks() }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    var selectedTrack by remember { mutableStateOf<TrackMemoryEntry?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Header Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF38BDF8).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "ACOUSTIC LOG",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Playback Timeline",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            text = "${filtered.size} Captured",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyanBright,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = "Raw playback observations reconstructed into chronologically grounded media episodes across Spotify, YouTube Music, and Android audio sessions. Tap any track to inspect fatigue or quarantine.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
            }
        }

        // Filter Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("all" to "All Tracks", "identified" to "Identified Only", "recurring" to "Recurring").forEach { (mode, label) ->
                val isSelected = state.filterMode == mode
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) AccentCyan.copy(alpha = 0.15f) else Color(0xFF0F172A),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) AccentCyan.copy(alpha = 0.5f) else Color(0xFF1E293B)
                    ),
                    modifier = Modifier.clickable { vm.setHistoryFilter(mode) }
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) AccentCyanBright else Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Notification Snackbar
        snackbarMessage?.let { msg ->
            Card(
                shape = RoundedCornerShape(10.dp),
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

        // List
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else if (filtered.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.Headphones,
                        contentDescription = null,
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No tracks observed yet",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Play music in Spotify, YouTube Music, or any audio app on this phone to start passive listening.",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { it.entryId }) { track ->
                    val appName = track.sourcePackage?.substringAfterLast(".")?.replaceFirstChar { it.uppercase() } ?: "Music"
                    val appColor = when {
                        track.sourcePackage?.contains("spotify") == true -> Color(0xFF1DB954)
                        track.sourcePackage?.contains("youtube") == true -> Color(0xFFEF4444)
                        track.sourcePackage?.contains("apple") == true -> Color(0xFFFA243C)
                        else -> AccentCyan
                    }
                    val isHighFatigue = track.distinctAppearanceCount >= 3

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isHighFatigue) AccentAmber.copy(alpha = 0.4f) else Color(0xFF1E293B)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTrack = track }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Note icon avatar with gradient
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.linearGradient(
                                            if (isHighFatigue)
                                                listOf(Color(0xFF78350F), Color(0xFF9A3412))
                                            else
                                                listOf(Color(0xFF1E293B), Color(0xFF334155))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isHighFatigue) Icons.Default.Speed else Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = if (isHighFatigue) AccentAmber else AccentCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = track.title ?: "Unknown Title",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = track.artist ?: "Unknown Artist",
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // App badge
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = appColor.copy(alpha = 0.15f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, appColor.copy(alpha = 0.3f))
                                    ) {
                                        Text(
                                            text = appName,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = appColor,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }

                                    Text(
                                        text = "${track.distinctAppearanceCount} play(s)",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isHighFatigue) AccentAmber else Color(0xFF64748B)
                                    )

                                    val lastMs = track.lastObservedAppearanceAtMs
                                    if (lastMs != null && lastMs > 0) {
                                        Text(
                                            text = fmt.format(Date(lastMs)),
                                            fontSize = 10.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }
                            }

                            // Satiety delta indicator
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isHighFatigue) Color(0xFFEF4444).copy(alpha = 0.15f) else Color(0xFF10B981).copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isHighFatigue) Color(0xFFEF4444).copy(alpha = 0.3f) else Color(0xFF10B981).copy(alpha = 0.3f)
                                )
                            ) {
                                Text(
                                    text = if (isHighFatigue) "+0.84 Vel" else "+0.42 Vel",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isHighFatigue) Color(0xFFF87171) else Color(0xFF34D399),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Episode & Track Detail Bottom Sheet
    selectedTrack?.let { track ->
        val trackSatiety = minOf(95, track.distinctAppearanceCount * 22)
        val isFatigued = track.distinctAppearanceCount >= 3

        ModalBottomSheet(
            onDismissRequest = { selectedTrack = null },
            containerColor = Color(0xFF0F172A),
            tonalElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Track Title & Artist Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    if (isFatigued)
                                        listOf(Color(0xFF78350F), Color(0xFFEA580C))
                                    else
                                        listOf(Color(0xFF0891B2), Color(0xFF4F46E5))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isFatigued) Icons.Default.Speed else Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title ?: "Unknown Track",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = track.artist ?: "Unknown Artist",
                            fontSize = 13.sp,
                            color = Color(0xFF94A3B8)
                        )
                        track.album?.takeIf { it.isNotBlank() }?.let { albumName ->
                            Text(
                                text = albumName,
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                }

                // Satiety and Biological Receptivity Gauge
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "ACOUSTIC SATIETY SCORE",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan
                            )
                            Text(
                                "$trackSatiety%",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isFatigued) AccentAmber else AccentCyanBright
                            )
                        }

                        LinearProgressIndicator(
                            progress = { trackSatiety / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = if (isFatigued) AccentAmber else AccentCyan,
                            trackColor = Color(0xFF1E293B)
                        )

                        Text(
                            text = if (isFatigued)
                                "High repetition velocity detected. Continuous exposure may desensitize auditory receptors."
                            else if (track.distinctAppearanceCount == 2)
                                "Moderate familiarity. Song is entering optimal appreciation phase."
                            else
                                "Fresh capture. Biological receptor potentiation is at peak sensitivity.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                // Longitudinal Evidence Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("TOTAL PLAYS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            Text("${track.distinctAppearanceCount} Times", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("SOURCE APP", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            Text(
                                track.sourcePackage?.substringAfterLast(".")?.replaceFirstChar { it.uppercase() } ?: "Spotify",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan
                            )
                        }
                    }
                }

                // Timestamps Breakdown
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    track.firstObservedAppearanceAtMs?.let { firstMs ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("First Captured:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text(fmt.format(Date(firstMs)), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                        }
                    }
                    track.lastObservedAppearanceAtMs?.let { lastMs ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Last Observed:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text(fmt.format(Date(lastMs)), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            vm.quarantineTrack(track.title ?: "Unknown", track.artist ?: "Unknown", 5f)
                            selectedTrack = null
                            snackbarMessage = "Quarantined '${track.title}' to Cooldown Vault."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF78350F).copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.AcUnit, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Quarantine Song", color = AccentAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = { selectedTrack = null },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Dismiss", color = Color(0xFF94A3B8))
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
