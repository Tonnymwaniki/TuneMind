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
import com.tunemind.app.ui.components.SongProfileData
import com.tunemind.app.ui.components.SongProfileSheet
import com.tunemind.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningHistoryScreen(vm: TuneMindViewModel) {
    val state by vm.historyState.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    val rawTracks = remember(state.tracks) { state.tracks }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val timeOnlyFmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    var searchQuery by remember { mutableStateOf("") }
    var filterChip by remember { mutableStateOf("all") } // "all", "satiated", "recurring", "fresh"
    var selectedProfileData by remember { mutableStateOf<SongProfileData?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    // Dynamic metrics computed strictly from observed data
    val totalCaptured = rawTracks.size
    val distinctArtists = remember(rawTracks) {
        rawTracks.mapNotNull { it.artist }.filter { it.isNotBlank() }.distinct().size
    }
    val avgSatiety = remember(rawTracks) {
        if (rawTracks.isNotEmpty()) {
            rawTracks.map { minOf(95, maxOf(15, it.distinctAppearanceCount * 22)) }.average().toInt()
        } else 0
    }
    val activeSources = remember(rawTracks) {
        rawTracks.mapNotNull { it.sourcePackage }.distinct()
    }
    val satiatedCount = remember(rawTracks) {
        rawTracks.count { it.distinctAppearanceCount >= 3 }
    }

    // Filtered & searched tracks
    val displayedTracks = remember(rawTracks, filterChip, searchQuery) {
        rawTracks.filter { track ->
            val matchesFilter = when (filterChip) {
                "satiated" -> track.distinctAppearanceCount >= 3
                "recurring" -> track.distinctAppearanceCount >= 2
                "fresh" -> track.distinctAppearanceCount <= 1
                else -> true
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                (track.title ?: "").contains(searchQuery, ignoreCase = true) ||
                (track.artist ?: "").contains(searchQuery, ignoreCase = true) ||
                (track.album ?: "").contains(searchQuery, ignoreCase = true)
            }
            matchesFilter && matchesSearch
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Chrono-Radar Header
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0284C7).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "ACOUSTIC TELEMETRY",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Playback Timeline",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF020617),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (totalCaptured > 0) AccentGreen else AccentAmber)
                            )
                            Text(
                                text = "$totalCaptured Episodes",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyanBright
                            )
                        }
                    }
                }

                // 3 Telemetry Metrics Deck (Dynamic from real data)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("ARTISTS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            Text(
                                text = if (totalCaptured > 0) "$distinctArtists" else "0",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text("Identified", fontSize = 9.sp, color = Color(0xFF64748B))
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("AVG SATIETY", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            Text(
                                text = if (totalCaptured > 0) "$avgSatiety%" else "--",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (avgSatiety >= 70) AccentAmber else AccentCyan
                            )
                            Text(if (avgSatiety >= 70) "High Tolerance" else "Balanced", fontSize = 9.sp, color = Color(0xFF64748B))
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("LOOP RISK", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            Text(
                                text = "$satiatedCount Songs",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (satiatedCount > 0) Color(0xFFEF4444) else AccentGreen
                            )
                            Text("Burnout Watch", fontSize = 9.sp, color = Color(0xFF64748B))
                        }
                    }
                }
            }
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter timeline by title or artist…", fontSize = 12.sp, color = Color(0xFF64748B)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF0F172A),
                unfocusedContainerColor = Color(0xFF0F172A),
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = Color(0xFF1E293B),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // Filter Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "all" to "All Timeline",
                "satiated" to "Fatigue ($satiatedCount)",
                "recurring" to "Loops",
                "fresh" to "Fresh"
            ).forEach { (mode, label) ->
                val isSelected = filterChip == mode
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) AccentCyan.copy(alpha = 0.2f) else Color(0xFF0F172A),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) AccentCyan else Color(0xFF1E293B)
                    ),
                    modifier = Modifier.clickable { filterChip = mode }
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) AccentCyanBright else Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
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

        // Timeline Feed List
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else if (displayedTracks.isEmpty()) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No tracks matched '$searchQuery'" else "Listening Timeline Standby",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Play songs in Spotify, YouTube Music, or any media player on your phone. TuneMind automatically reconstructs and profiles each playback episode.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(displayedTracks, key = { it.entryId }) { track ->
                    val appName = track.sourcePackage?.substringAfterLast(".")?.replaceFirstChar { it.uppercase() } ?: "Spotify"
                    val appColor = when {
                        track.sourcePackage?.contains("spotify") == true -> Color(0xFF1DB954)
                        track.sourcePackage?.contains("youtube") == true -> Color(0xFFEF4444)
                        track.sourcePackage?.contains("apple") == true -> Color(0xFFFA243C)
                        else -> AccentCyan
                    }
                    val isHighFatigue = track.distinctAppearanceCount >= 3
                    val satietyScore = minOf(95, maxOf(15, track.distinctAppearanceCount * 22))
                    val isTrackInVault = vaultTracks.any { it.title.equals(track.title, ignoreCase = true) }

                    // Chronotrack Row Item
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isTrackInVault) Color(0xFFF59E0B)
                            else if (isHighFatigue) Color(0xFFEF4444).copy(alpha = 0.45f)
                            else Color(0xFF1E293B)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedProfileData = SongProfileData.fromTrackMemory(track, isInVault = isTrackInVault)
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Top Row: App Source badge, Time badge, Satiety Pill
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = appColor.copy(alpha = 0.15f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, appColor.copy(alpha = 0.35f))
                                    ) {
                                        Text(
                                            text = appName,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = appColor,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    track.lastObservedAppearanceAtMs?.let { lastMs ->
                                        Text(
                                            text = timeOnlyFmt.format(Date(lastMs)),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isTrackInVault) Color(0xFF78350F).copy(alpha = 0.4f)
                                    else if (isHighFatigue) Color(0xFFEF4444).copy(alpha = 0.15f)
                                    else AccentGreen.copy(alpha = 0.15f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isTrackInVault) AccentAmber
                                        else if (isHighFatigue) Color(0xFFEF4444).copy(alpha = 0.4f)
                                        else AccentGreen.copy(alpha = 0.4f)
                                    )
                                ) {
                                    Text(
                                        text = if (isTrackInVault) "In Vault" else "$satietyScore% Satiety",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isTrackInVault) AccentAmber else if (isHighFatigue) Color(0xFFF87171) else Color(0xFF34D399),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Middle Row: Avatar Icon + Title & Artist
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val trackEmoji = remember(track.title, track.artist) {
                                    SongProfileData.pickEmoji(track.title ?: "", track.artist ?: "")
                                }
                                val trackVibe = remember(track.title, track.artist, satietyScore) {
                                    SongProfileData.pickVibeTag(track.title ?: "", track.artist ?: "", satietyScore)
                                }

                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            Brush.radialGradient(
                                                if (isTrackInVault) listOf(Color(0xFFB45309), Color(0xFF451A03))
                                                else if (isHighFatigue) listOf(Color(0xFFDC2626), Color(0xFF7F1D1D))
                                                else listOf(Color(0xFF0284C7), Color(0xFF0F172A))
                                            )
                                        )
                                        .border(
                                            1.dp,
                                            if (isTrackInVault) Color(0xFFF59E0B)
                                            else if (isHighFatigue) Color(0xFFEF4444)
                                            else Color(0xFF38BDF8).copy(alpha = 0.5f),
                                            RoundedCornerShape(14.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = trackEmoji, fontSize = 24.sp)
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title ?: "Unknown Title",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = track.artist ?: "Unknown Artist",
                                        fontSize = 12.sp,
                                        color = Color(0xFF94A3B8),
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFF020617)
                                        ) {
                                            Text(
                                                text = trackVibe,
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = AccentCyanBright,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                        track.album?.takeIf { it.isNotBlank() }?.let { album ->
                                            Text(
                                                text = "• $album",
                                                fontSize = 10.sp,
                                                color = Color(0xFF64748B),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${track.distinctAppearanceCount}x",
                                        fontSize = 15.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = if (isHighFatigue) "+0.84/play" else "+0.42/play",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isHighFatigue) Color(0xFFF87171) else Color(0xFF64748B)
                                    )
                                }
                            }

                            // Satiety Bar
                            LinearProgressIndicator(
                                progress = { (satietyScore / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape),
                                color = if (isHighFatigue) Color(0xFFEF4444) else AccentCyan,
                                trackColor = Color(0xFF1E293B)
                            )

                            // Action Footer: "Song Profile & Insights" Trigger
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Insights,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "View Song Profile & Satiety Dossier",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AccentCyan
                                    )
                                }

                                if (!isTrackInVault && isHighFatigue) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF78350F).copy(alpha = 0.6f),
                                        modifier = Modifier.clickable {
                                            vm.quarantineTrack(track.title ?: "Unknown", track.artist ?: "Unknown", 5f)
                                            snackbarMessage = "Quarantined '${track.title}' to Cooldown Vault."
                                        }
                                    ) {
                                        Text(
                                            text = "Cooldown",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentAmber,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Comprehensive Song Profile Bottom Sheet
    selectedProfileData?.let { profileData ->
        SongProfileSheet(
            profile = profileData,
            vm = vm,
            onDismiss = { selectedProfileData = null },
            onQuarantineSuccess = { msg -> snackbarMessage = msg }
        )
    }
}
