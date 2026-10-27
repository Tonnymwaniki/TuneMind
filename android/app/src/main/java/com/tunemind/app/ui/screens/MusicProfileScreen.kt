package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.model.TuneMindDemoData
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicProfileScreen(vm: TuneMindViewModel, onBack: () -> Unit) {
    val state by vm.profileState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Profile Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
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
                                    listOf(AccentCyan, PrimaryViolet)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "T",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF020617)
                        )
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Tonny",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "tonny@tunemind.neuro",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF94A3B8)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentCyan.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "Acoustic Intelligence Tier 1 • Active",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyanBright,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // 3 Stats
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
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Monitored", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            Text("87.4h", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Synced", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            Text("3 Apps", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryVioletBright)
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Confidence", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            Text("91%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                        }
                    }
                }
            }
        }

        // Sonic Genome Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "CROSS-PLATFORM SONIC GENOME",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )

                val genres = listOf(
                    Triple("Synthwave / Lo-Fi", 46, AccentCyan),
                    Triple("Cyber Electro", 32, PrimaryVioletBright),
                    Triple("Neo-Soul & Indie", 22, AccentAmber)
                )

                genres.forEach { (genre, pct, color) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(genre, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                            Text("$pct%", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color)
                        }
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = color,
                            trackColor = Color(0xFF1E293B)
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
            color = Color.White
        )

        TuneMindDemoData.anchorWeights.forEach { anchor ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(anchor.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${anchor.percentage}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AccentCyan)
                    }
                    Text(anchor.description, fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
