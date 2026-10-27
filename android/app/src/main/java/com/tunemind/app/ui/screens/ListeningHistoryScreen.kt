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
                                text = "Listening History",
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
                            text = "${filtered.size} Tracks",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyanBright,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = "Raw playback observations reconstructed into chronologically grounded media episodes across Spotify, YouTube Music, and Android players.",
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
                items(filtered, key = { it.canonicalTrackId }) { track ->
                    val appName = track.sourcePackage?.substringAfterLast(".")?.replaceFirstChar { it.uppercase() } ?: "Music"
                    val appColor = when {
                        track.sourcePackage?.contains("spotify") == true -> Color(0xFF1DB954)
                        track.sourcePackage?.contains("youtube") == true -> Color(0xFFEF4444)
                        track.sourcePackage?.contains("apple") == true -> Color(0xFFFA243C)
                        else -> AccentCyan
                    }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
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
                                            listOf(Color(0xFF1E293B), Color(0xFF334155))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = AccentCyan,
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
                                        text = "${track.appearanceCount} plays",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF64748B)
                                    )

                                    if (track.lastObservedAppearanceAtMs > 0) {
                                        Text(
                                            text = fmt.format(Date(track.lastObservedAppearanceAtMs)),
                                            fontSize = 10.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }
                            }

                            // Satiety delta indicator
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (track.appearanceCount > 4) Color(0xFFEF4444).copy(alpha = 0.15f) else Color(0xFF10B981).copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (track.appearanceCount > 4) Color(0xFFEF4444).copy(alpha = 0.3f) else Color(0xFF10B981).copy(alpha = 0.3f)
                                )
                            ) {
                                Text(
                                    text = if (track.appearanceCount > 4) "+0.72 Vel" else "+0.28 Vel",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (track.appearanceCount > 4) Color(0xFFF87171) else Color(0xFF34D399),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
