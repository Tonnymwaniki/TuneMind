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
import com.tunemind.app.model.TrackItem
import com.tunemind.app.model.TuneMindDemoData
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CooldownManagementScreen(
    onBack: () -> Unit = {},
    onAddTrack: () -> Unit = {}
) {
    var restingTracks by remember { mutableStateOf(TuneMindDemoData.highSatietyTracks) }
    var candidateTracks by remember { mutableStateOf(TuneMindDemoData.candidateCooldownTracks) }
    var notificationText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0891B2).copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                .background(Color(0xFF0891B2).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.AcUnit,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "RECOVERY VAULT",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Cooldown Management",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Button(
                        onClick = onAddTrack,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF020617), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Track", color = Color(0xFF020617), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Text(
                    text = "Songs in cooldown are suppressed from high-rotation recommendation loops to prevent listener burnout. TuneMind uses an exponential decay curve to return tracks to full acoustic freshness.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
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
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Total Resting", fontSize = 10.sp, color = Color(0xFF64748B), fontFamily = FontFamily.Monospace)
                    Text("${restingTracks.size} Tracks", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                    Text("3 apps synced", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Avg Reset", fontSize = 10.sp, color = Color(0xFF64748B), fontFamily = FontFamily.Monospace)
                    Text("4.2 Days", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PrimaryVioletBright)
                    Text("Decay rate: 0.18/d", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Shield", fontSize = 10.sp, color = Color(0xFF64748B), fontFamily = FontFamily.Monospace)
                    Text("Active", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                    Text("Dopamine guard", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }
        }

        // Active Resting List
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.HourglassEmpty, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Text(
                    text = "Tracks in Active Cooldown",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                text = "${restingTracks.size} Resting",
                fontSize = 11.sp,
                color = Color(0xFF64748B)
            )
        }

        restingTracks.forEach { track ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(PrimaryViolet, AccentCyan)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = track.title.take(1),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "${track.artist} • ${track.genre}",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF0284C7).copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "${track.cooldownDaysRemaining}d Left",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyanBright,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Progress bar
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Resting progress",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = "${(track.recoveryProgress * 100).toInt()}% Restored",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyan
                            )
                        }
                        LinearProgressIndicator(
                            progress = { track.recoveryProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AccentCyan,
                            trackColor = Color(0xFF1E293B)
                        )
                    }

                    // Adjustment Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    restingTracks = restingTracks.map {
                                        if (it.id == track.id) it.copy(cooldownDaysRemaining = it.cooldownDaysRemaining + 1f) else it
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                            ) {
                                Text("+1 Day", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }

                            OutlinedButton(
                                onClick = {
                                    restingTracks = restingTracks.map {
                                        if (it.id == track.id) it.copy(
                                            cooldownDaysRemaining = maxOf(0.5f, it.cooldownDaysRemaining - 1f)
                                        ) else it
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                            ) {
                                Text("-1 Day", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }

                        TextButton(
                            onClick = {
                                restingTracks = restingTracks.filter { it.id != track.id }
                                candidateTracks = candidateTracks + track
                                notificationText = "Reactivated ${track.title}"
                            }
                        ) {
                            Text("Reactivate", fontSize = 11.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Candidate Cooldown Tracks (Quick add)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = PrimaryVioletBright, modifier = Modifier.size(18.dp))
                Text(
                    text = "Quick Quarantine Candidates",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                text = "Tap to Rest",
                fontSize = 11.sp,
                color = Color(0xFF64748B)
            )
        }

        candidateTracks.forEach { cand ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = cand.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${cand.artist} • Satiety: ${cand.satietyScore}%", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }

                    Button(
                        onClick = {
                            val newCooldown = cand.copy(
                                cooldownActive = true,
                                recoveryProgress = 0.05f,
                                cooldownDaysRemaining = 4.0f
                            )
                            candidateTracks = candidateTracks.filter { it.id != cand.id }
                            restingTracks = restingTracks + newCooldown
                            notificationText = "Added ${cand.title} to Cooldown"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Cooldown", fontSize = 11.sp, color = Color(0xFF020617), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
