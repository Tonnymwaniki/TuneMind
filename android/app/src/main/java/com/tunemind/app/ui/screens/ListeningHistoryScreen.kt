package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    Column(modifier = Modifier.fillMaxSize().background(Surface).padding(16.dp)) {
        // Filter Chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "All", "identified" to "Identified", "recurring" to "Recurring").forEach { (mode, label) ->
                FilterChip(
                    selected = state.filterMode == mode,
                    onClick = { vm.setHistoryFilter(mode) },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryViolet.copy(alpha = 0.2f),
                        selectedLabelColor = PrimaryViolet,
                        containerColor = SurfaceContainer,
                        labelColor = TextSecondary,
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tracks found.", color = TextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.entryId }) { track ->
                    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceContainerHigh),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(track.title ?: "Unknown", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                                Text(track.artist ?: "Unknown artist", fontSize = 12.sp, color = TextSecondary)
                                Text("${track.distinctAppearanceCount} appearance(s) · ${track.identityBasis.name.lowercase().replace('_', ' ')}", fontSize = 11.sp, color = TextMuted)
                                track.lastObservedAppearanceAtMs?.let { Text("Last: ${fmt.format(Date(it))}", fontSize = 11.sp, color = TextMuted) }
                            }
                        }
                    }
                }
            }
        }
    }
}
