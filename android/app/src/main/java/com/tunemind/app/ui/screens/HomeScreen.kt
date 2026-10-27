package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    vm: TuneMindViewModel,
    onOpenFatigue: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenStudy: () -> Unit = {}
) {
    val state by vm.homeState.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().background(Surface).verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
            return@Column
        }

        // Overview Cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp)) {
                    Text(state.identifiableTrackCount.toString(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PrimaryViolet)
                    Text("Tracks", fontSize = 12.sp, color = TextSecondary)
                }
            }
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp)) {
                    Text(state.distinctAppearanceCount.toString(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                    Text("Appearances", fontSize = 12.sp, color = TextSecondary)
                }
            }
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp)) {
                    Text(state.recurringTrackCount.toString(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                    Text("Recurring", fontSize = 12.sp, color = TextSecondary)
                }
            }
        }

        // Coverage Card
        state.coverage?.let { cov ->
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth().clickable { onOpenHistory() }) {
                Column(Modifier.padding(14.dp)) {
                    Text("Coverage", fontSize = 11.sp, color = TextMuted, letterSpacing = 0.06.sp)
                    Spacer(Modifier.height(4.dp))
                    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                    Text("${cov.distinctObserverSessionCount} session(s)", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    cov.earliestObservedMs?.let { Text(fmt.format(Date(it)), fontSize = 12.sp, color = TextSecondary) }
                    if (cov.retentionMayHavePruned) Text("History may be incomplete", fontSize = 11.sp, color = AccentAmber)
                }
            }
        }

        // Recent Tracks
        if (state.recentTracks.isNotEmpty()) {
            Text("Recent Activity", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            state.recentTracks.forEach { track ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceContainer)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceContainerHighest),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title ?: "Unknown", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                        Text(track.artist ?: "Unknown artist", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }
        } else {
            Text("No listening data yet.", fontSize = 14.sp, color = TextMuted)
        }

        // Quick Portals
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.weight(1f).clickable { onOpenFatigue() }) {
                Column(Modifier.padding(14.dp)) {
                    Icon(Icons.Outlined.Speed, contentDescription = null, tint = AccentCrimson)
                    Spacer(Modifier.height(8.dp))
                    Text("Song Fatigue", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                    Text("Research", fontSize = 11.sp, color = TextSecondary)
                }
            }
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.weight(1f).clickable { onOpenStudy() }) {
                Column(Modifier.padding(14.dp)) {
                    Icon(Icons.Outlined.DateRange, contentDescription = null, tint = AccentCyan)
                    Spacer(Modifier.height(8.dp))
                    Text("7-Day Study", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                    Text("Track your patterns", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }

    }
}
