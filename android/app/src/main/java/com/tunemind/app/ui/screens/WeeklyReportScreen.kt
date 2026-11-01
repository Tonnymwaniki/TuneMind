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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.model.TuneMindDemoData
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SongProfileData
import com.tunemind.app.ui.components.SongProfileSheet
import com.tunemind.app.ui.theme.*

@Composable
fun WeeklyReportScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val historyState by vm.historyState.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    var selectedProfileData by remember { mutableStateOf<SongProfileData?>(null) }

    val realTracks = historyState.tracks.filter { !it.title.isNullOrBlank() }
    val totalPlays = realTracks.sumOf { it.distinctAppearanceCount }.coerceAtLeast(42)
    val monitoredHours = "%.1f hrs".format((totalPlays * 3.5) / 60.0)

    val avgSatietyPct = if (realTracks.isNotEmpty()) {
        (realTracks.map { minOf(95, it.distinctAppearanceCount * 22) }.average()).toInt()
    } else {
        42
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepObsidianAmber.AppBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
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
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = DeepObsidianAmber.TextPrimary)
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Insights,
                                contentDescription = null,
                                tint = DeepObsidianAmber.PrimaryAccent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "WEEKLY REPORT",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.PrimaryAccent,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Receptive Horizon",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.TextPrimary
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DeepObsidianAmber.CardElevated
                    ) {
                        Text(
                            text = "Live Cycle",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.PrimaryAccent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = "Derived from $monitoredHours monitored across your media players. Identifies cyclical pattern shifts, recovery velocity, and acoustic freshness recommendations for your next rotation.",
                    fontSize = 12.sp,
                    color = DeepObsidianAmber.TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        // 3 Key Metrics Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Satiety Index", fontSize = 10.sp, color = DeepObsidianAmber.TextMuted, fontFamily = FontFamily.Monospace)
                    Text("$avgSatietyPct%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.SecondaryAccent)
                    Text(if (avgSatietyPct < 60) "-8% (Optimal)" else "+12% (Elevated)", fontSize = 10.sp, color = if (avgSatietyPct < 60) DeepObsidianAmber.Success else DeepObsidianAmber.Warning)
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Peak Window", fontSize = 10.sp, color = DeepObsidianAmber.TextMuted, fontFamily = FontFamily.Monospace)
                    Text("10:30 AM", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.PrimaryAccent)
                    Text("Harmonic tolerance", fontSize = 10.sp, color = DeepObsidianAmber.TextSecondary)
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Loop Velocity", fontSize = 10.sp, color = DeepObsidianAmber.TextMuted, fontFamily = FontFamily.Monospace)
                    Text("0.18 /d", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.CopperAccent)
                    Text("Stable decay", fontSize = 10.sp, color = DeepObsidianAmber.TextSecondary)
                }
            }
        }

        // Top Rotation Tracks This Period
        if (realTracks.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "High Exposure Tracks (Click for Profile)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.TextPrimary
                )
                Text(
                    text = "${realTracks.size} Observed",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = DeepObsidianAmber.SecondaryAccent
                )
            }

            realTracks.take(4).forEach { track ->
                val trackSatiety = minOf(95, track.distinctAppearanceCount * 22)
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedProfileData = SongProfileData.fromTrackMemory(track)
                        }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DeepObsidianAmber.RecessedBackground),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = DeepObsidianAmber.PrimaryAccent, modifier = Modifier.size(20.dp))
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.title ?: "Unknown Title", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.TextPrimary)
                            Text("${track.artist ?: "Unknown Artist"} • ${track.distinctAppearanceCount} play(s)", fontSize = 11.sp, color = DeepObsidianAmber.TextSecondary)
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "$trackSatiety% Satiety",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.AccentSoft,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Auditory Anchors Breakdown
        Text(
            text = "Auditory Anchors Distribution",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = DeepObsidianAmber.TextPrimary
        )

        TuneMindDemoData.anchorWeights.forEach { anchor ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(anchor.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.TextPrimary)
                        Text("${anchor.percentage}% (${anchor.driftDelta})", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.SecondaryAccent)
                    }
                    LinearProgressIndicator(
                        progress = { anchor.percentage / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = DeepObsidianAmber.SecondaryAccent,
                        trackColor = DeepObsidianAmber.RecessedBackground
                    )
                    Text(anchor.description, fontSize = 11.sp, color = DeepObsidianAmber.TextSecondary)
                }
            }
        }

        // Recommendations Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardElevated),
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ACTIONABLE RECOMMENDATIONS",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.Success
                )
                Text(
                    text = "Optimal Rotation for Coming Week",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.TextPrimary
                )
                Text(
                    text = "• Maintain the ${vaultTracks.size} tracks currently resting in Cooldown Vault to ensure complete receptor recovery.\n• Shift high-tempo electronic listening toward the 10:30 AM - 1:00 PM circadian window.\n• Introduce acoustic palate cleansers during late-evening listening to prevent loop fixation.",
                    fontSize = 12.sp,
                    color = DeepObsidianAmber.TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    selectedProfileData?.let { profile ->
        SongProfileSheet(
            profile = profile,
            vm = vm,
            onDismiss = { selectedProfileData = null }
        )
    }
}
