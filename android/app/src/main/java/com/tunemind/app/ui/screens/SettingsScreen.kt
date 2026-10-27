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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.*

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCrossApp: () -> Unit,
    onOpenWeeklyReport: () -> Unit
) {
    var persistentListener by remember { mutableStateOf(true) }
    var fatigueSensitivity by remember { mutableStateOf(0.75f) }
    var chronoReceptivity by remember { mutableStateOf(true) }
    var cloakingSession by remember { mutableStateOf(false) }
    var deduplicateLogs by remember { mutableStateOf(true) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // User Identity Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(PrimaryVioletDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("T", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tonny", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(PrimaryViolet)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("PRO", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Text("tonny@tunemind.neuro", fontSize = 12.sp, color = TextMuted)
                        Text("• Acoustic Intelligence Tier 1 • Active", fontSize = 10.sp, color = AccentCyan)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("87.4h", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                        Text("Monitored", fontSize = 10.sp, color = TextMuted)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("3 Apps", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                        Text("Synced Live", fontSize = 10.sp, color = TextMuted)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("91%", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AccentCyan)
                        Text("Confidence", fontSize = 10.sp, color = TextMuted)
                    }
                }
            }
        }

        // Audio Sources & Ingestion
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Audio Sources & Ingestion", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            TextButton(onClick = onOpenCrossApp) {
                Text("Manage Daemons →", fontSize = 11.sp, color = AccentCyan)
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Persistent Background Daemon", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Switch(checked = persistentListener, onCheckedChange = { persistentListener = it })
                }
            }
        }

        // Intelligence & Calibration (TM-Engine 4.2)
        Text("Intelligence & Calibration (TM-Engine 4.2)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Song Fatigue Sensitivity", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text("Balanced (75%)", fontSize = 11.sp, color = AccentCyan, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = fatigueSensitivity,
                    onValueChange = { fatigueSensitivity = it },
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryViolet,
                        activeTrackColor = PrimaryViolet,
                        inactiveTrackColor = SurfaceContainerHighest
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tolerant (50%)", fontSize = 9.sp, color = TextMuted)
                    Text("Adaptive", fontSize = 9.sp, color = TextMuted)
                    Text("Aggressive (90%)", fontSize = 9.sp, color = TextMuted)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Chrono-Receptivity Profiling", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("Dampens treble and vocal fatigue after 22:00", fontSize = 10.sp, color = TextMuted)
                    }
                    Switch(checked = chronoReceptivity, onCheckedChange = { chronoReceptivity = it })
                }
            }
        }

        // Privacy & Local Vault
        Text("Privacy & Zero-Cloud Local Vault", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Incognito / Cloak Session", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("Pause telemetry during guest or DJ sets", fontSize = 10.sp, color = TextMuted)
                    }
                    Switch(checked = cloakingSession, onCheckedChange = { cloakingSession = it })
                }

                Divider(color = BorderSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Deduplicate Cross-Platform Logs", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("Unify matching ISRC tags across Spotify & YT Music", fontSize = 10.sp, color = TextMuted)
                    }
                    Switch(checked = deduplicateLogs, onCheckedChange = { deduplicateLogs = it })
                }
            }
        }

        // System & Diagnostics
        OutlinedButton(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Text("Export Listening Ledger (.JSON / .CSV)", fontSize = 12.sp, color = TextSecondary)
        }
    }
}
