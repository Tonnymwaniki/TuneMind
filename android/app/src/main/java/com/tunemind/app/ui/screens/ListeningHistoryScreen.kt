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
import androidx.compose.ui.text.style.TextOverflow
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
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepObsidianAmber.NeuralViolet.copy(alpha = 0.08f),
                        DeepObsidianAmber.AppBackground,
                        DeepObsidianAmber.RecessedBackground
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. TOP: Recessed Analog Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search songs, artists, or albums…", fontSize = 13.sp, color = DeepObsidianAmber.TextSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = DeepObsidianAmber.PrimaryAccent, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = DeepObsidianAmber.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (searchQuery.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.2f),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = "${displayedTracks.size}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = DeepObsidianAmber.PrimaryAccent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DeepObsidianAmber.RecessedBackground,
                unfocusedContainerColor = DeepObsidianAmber.RecessedBackground,
                focusedBorderColor = DeepObsidianAmber.PrimaryAccent,
                unfocusedBorderColor = DeepObsidianAmber.CopperAccent.copy(alpha = 0.4f),
                focusedTextColor = DeepObsidianAmber.TextPrimary,
                unfocusedTextColor = DeepObsidianAmber.TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // 2. Filter Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "all" to "All (${rawTracks.size})",
                "satiated" to "Fatigue ($satiatedCount)",
                "recurring" to "Loops",
                "fresh" to "Fresh"
            ).forEach { (mode, label) ->
                val isSelected = filterChip == mode
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) DeepObsidianAmber.CardElevated else DeepObsidianAmber.RecessedBackground,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.clickable { filterChip = mode }
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) DeepObsidianAmber.AccentSoft else DeepObsidianAmber.TextSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // 3. Audiophile Telemetry Bar (Recessed Obsidian Well with Brushed Copper Accent)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DeepObsidianAmber.RecessedBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (totalCaptured > 0) DeepObsidianAmber.Success else DeepObsidianAmber.SecondaryAccent)
                    )
                    Text(
                        text = "$totalCaptured CAPTURED",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.AccentSoft,
                        letterSpacing = 0.5.sp
                    )
                }

                Text(
                    text = "$distinctArtists ARTISTS",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = DeepObsidianAmber.TextSecondary,
                    letterSpacing = 0.5.sp
                )

                Text(
                    text = "SATIETY: $avgSatiety%",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (avgSatiety >= 60) DeepObsidianAmber.Warning else DeepObsidianAmber.PrimaryAccent,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Notification Snackbar
        snackbarMessage?.let { msg ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DeepObsidianAmber.CardElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.Success),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(msg, color = DeepObsidianAmber.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { snackbarMessage = null }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = DeepObsidianAmber.TextPrimary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // 4. Sleek Spotify-Style Timeline List (Clean rows with dividers)
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DeepObsidianAmber.PrimaryAccent)
            }
        } else if (displayedTracks.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DeepObsidianAmber.CardBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = DeepObsidianAmber.PrimaryAccent,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No tracks matched '$searchQuery'" else "Listening Timeline Standby",
                        color = DeepObsidianAmber.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Play songs on Spotify or YouTube Music to view your live acoustic trajectory.",
                        color = DeepObsidianAmber.TextSecondary,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DeepObsidianAmber.CardBackground)
                    .border(1.dp, DeepObsidianAmber.SurfaceBorder, RoundedCornerShape(16.dp))
            ) {
                items(displayedTracks, key = { it.entryId }) { track ->
                    val appName = track.sourcePackage?.substringAfterLast(".")?.replaceFirstChar { it.uppercase() } ?: "Spotify"
                    val appColor = when {
                        track.sourcePackage?.contains("spotify") == true -> Color(0xFF1DB954)
                        track.sourcePackage?.contains("youtube") == true -> Color(0xFFEF4444)
                        track.sourcePackage?.contains("apple") == true -> Color(0xFFFA243C)
                        else -> DeepObsidianAmber.PrimaryAccent
                    }
                    val isHighFatigue = track.distinctAppearanceCount >= 3
                    val satietyScore = minOf(95, maxOf(15, track.distinctAppearanceCount * 22))
                    val isTrackInVault = vaultTracks.any { it.title.equals(track.title, ignoreCase = true) }

                    val trackEmoji = remember(track.title, track.artist) {
                        SongProfileData.pickEmoji(track.title ?: "", track.artist ?: "")
                    }
                    val trackVibe = remember(track.title, track.artist, satietyScore) {
                        SongProfileData.pickVibeTag(track.title ?: "", track.artist ?: "", satietyScore)
                    }

                    // Sleek Modern List Item (Spotify-Style)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedProfileData = SongProfileData.fromTrackMemory(track, isInVault = isTrackInVault)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Artwork Tile
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.radialGradient(
                                        if (isTrackInVault) listOf(DeepObsidianAmber.PrimaryAccent, DeepObsidianAmber.CardElevated)
                                        else if (isHighFatigue) listOf(DeepObsidianAmber.Error, DeepObsidianAmber.CardElevated)
                                        else listOf(DeepObsidianAmber.CopperAccent, DeepObsidianAmber.CardBackground)
                                    )
                                )
                                .border(
                                    1.dp,
                                    if (isTrackInVault) DeepObsidianAmber.PrimaryAccent
                                    else if (isHighFatigue) DeepObsidianAmber.Error
                                    else DeepObsidianAmber.SurfaceBorder,
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = trackEmoji, fontSize = 20.sp)
                        }

                        // Middle: Title, Artist, Source badge & Time
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title ?: "Unknown Title",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist ?: "Unknown Artist",
                                fontSize = 11.sp,
                                color = DeepObsidianAmber.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(3.dp),
                                    color = appColor.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = appName,
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = appColor,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                                track.lastObservedAppearanceAtMs?.let { lastMs ->
                                    Text(
                                        text = timeOnlyFmt.format(Date(lastMs)),
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = DeepObsidianAmber.TextSecondary
                                    )
                                }
                                Text(
                                    text = "• ${track.distinctAppearanceCount}x plays",
                                    fontSize = 9.sp,
                                    color = DeepObsidianAmber.TextMuted
                                )
                            }
                        }

                        // Right: Satiety badge + quick quarantine if high fatigue
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isTrackInVault) DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.25f)
                                else if (isHighFatigue) DeepObsidianAmber.Error.copy(alpha = 0.2f)
                                else DeepObsidianAmber.CardElevated,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isTrackInVault) DeepObsidianAmber.PrimaryAccent
                                    else if (isHighFatigue) DeepObsidianAmber.Error
                                    else DeepObsidianAmber.SurfaceBorder
                                )
                            ) {
                                Text(
                                    text = if (isTrackInVault) "In Vault" else "$satietyScore% Sat.",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isTrackInVault) DeepObsidianAmber.PrimaryAccent else if (isHighFatigue) DeepObsidianAmber.Error else DeepObsidianAmber.AccentSoft,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            if (!isTrackInVault && isHighFatigue) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = DeepObsidianAmber.CardElevated,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CopperAccent),
                                    modifier = Modifier.clickable {
                                        vm.quarantineTrack(track.title ?: "Unknown", track.artist ?: "Unknown", 5f)
                                        snackbarMessage = "Quarantined '${track.title}' to Cooldown Vault."
                                    }
                                ) {
                                    Text(
                                        text = "Cooldown",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepObsidianAmber.PrimaryAccent,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f), thickness = 0.6.dp)
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
