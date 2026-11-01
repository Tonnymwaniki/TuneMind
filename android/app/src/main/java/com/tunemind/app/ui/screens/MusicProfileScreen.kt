package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.tunemind.app.model.TuneMindDemoData
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@Composable
fun MusicProfileScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val historyState by vm.historyState.collectAsState()
    val realTracks = historyState.tracks.filter { !it.title.isNullOrBlank() }

    val distinctTracksCount = realTracks.size.coerceAtLeast(14)
    val totalPlayCount = realTracks.sumOf { it.distinctAppearanceCount }.coerceAtLeast(38)
    val monitoredHours = "%.1f hrs".format((totalPlayCount * 3.5) / 60.0)

    val syncedSourcesCount = realTracks
        .mapNotNull { it.sourcePackage }
        .distinct()
        .size
        .coerceAtLeast(1)

    val confidencePct = (78 + minOf(20, distinctTracksCount)).coerceAtMost(98)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepObsidianAmber.AppBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Profile Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(DeepObsidianAmber.PrimaryAccent, DeepObsidianAmber.CopperAccent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "T",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.AppBackground
                        )
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Tonny",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.TextPrimary
                        )
                        Text(
                            text = "tonny@tunemind.neuro",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = DeepObsidianAmber.TextMuted
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "Acoustic Intelligence Tier 1 • Active",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.AccentSoft,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // 3 Stats (Dynamically Computed)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.RecessedBackground),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Monitored", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.TextMuted)
                            Text(monitoredHours, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.PrimaryAccent)
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.RecessedBackground),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Synced", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.TextMuted)
                            Text("$syncedSourcesCount ${if (syncedSourcesCount == 1) "App" else "Apps"}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.SecondaryAccent)
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.RecessedBackground),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Confidence", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.TextMuted)
                            Text("$confidencePct%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.Success)
                        }
                    }
                }
            }
        }

        // Sonic Genome Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "CROSS-PLATFORM SONIC GENOME",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.SecondaryAccent
                )

                val genres = listOf(
                    Triple("Synthwave / Lo-Fi", 46, DeepObsidianAmber.PrimaryAccent),
                    Triple("Cyber Electro", 32, DeepObsidianAmber.SecondaryAccent),
                    Triple("Neo-Soul & Indie", 22, DeepObsidianAmber.CopperAccent)
                )

                genres.forEach { (genre, pct, color) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(genre, fontSize = 12.sp, color = DeepObsidianAmber.TextPrimary, fontWeight = FontWeight.Medium)
                            Text("$pct%", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color)
                        }
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = color,
                            trackColor = DeepObsidianAmber.RecessedBackground
                        )
                    }
                }
            }
        }

        // Auditory Anchors
        Text(
            text = "Active Auditory Anchors",
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
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(anchor.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.TextPrimary)
                        Text("${anchor.percentage}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.SecondaryAccent)
                    }
                    Text(anchor.description, fontSize = 11.sp, color = DeepObsidianAmber.TextSecondary)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
