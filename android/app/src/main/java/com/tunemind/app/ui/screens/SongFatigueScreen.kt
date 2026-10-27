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
fun SongFatigueScreen(
    onOpenCalibration: () -> Unit = {},
    onOpenCooldown: () -> Unit = {}
) {
    var restingTracks by remember { mutableStateOf(TuneMindDemoData.highSatietyTracks) }
    var watchlistTracks by remember { mutableStateOf(TuneMindDemoData.candidateCooldownTracks) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

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
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF78350F).copy(alpha = 0.5f)),
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
                                .background(Color(0xFF78350F).copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = AccentAmber,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "SONG FATIGUE LAB",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentAmber,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Acoustic Satiety Monitor",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Button(
                        onClick = onOpenCooldown,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF020617), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Cooldown", color = Color(0xFF020617), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Text(
                    text = "Deterministic satiety detection spots tracks entering acoustic exhaustion before you burn out on them. Tracks with high repeat velocity are quarantined to restore receptivity.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
            }
        }

        // Satiety Gauge Hero Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "OVERALL LISTENER SATIETY",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B)
                    )
                    Text(
                        text = "82% • High Velocity",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentAmber
                    )
                    Text(
                        text = "${restingTracks.size} songs currently in receptor recovery vault",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { 0.82f },
                        modifier = Modifier.size(46.dp),
                        color = AccentAmber,
                        strokeWidth = 4.dp,
                        trackColor = Color(0xFF334155)
                    )
                    Text(
                        text = "82%",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Active Cooldowns Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.AcUnit, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Text(
                    text = "Active Cooldown Vault (${restingTracks.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                text = "Receptor Recovery",
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
                        // Track Initial Avatar
                        Box(
                            modifier = Modifier
                                .size(46.dp)
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

                        // Satiety Pill
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "${track.satietyScore}% Satiety",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF87171),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Progress Bar
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Resting: ${track.cooldownDaysRemaining} days remaining",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = "${(track.recoveryProgress * 100).toInt()}% Recovered",
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

                    // Interactive Controls
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
                                Text("+1d", fontSize = 11.sp, color = Color(0xFF94A3B8))
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
                                Text("-1d", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }

                        TextButton(
                            onClick = {
                                restingTracks = restingTracks.filter { it.id != track.id }
                                snackbarMessage = "Reactivated ${track.title} early"
                            }
                        ) {
                            Text("Reactivate Early", fontSize = 11.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Impending Fatigue Watchlist
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.TrendingUp, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                Text(
                    text = "Impending Burnout Watchlist",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                text = "Rapid Repeat Velocity",
                fontSize = 11.sp,
                color = Color(0xFF64748B)
            )
        }

        watchlistTracks.forEach { track ->
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
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = track.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${track.artist} • ${track.playCount} plays", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Text(
                            text = "${track.fatigueCategory} • Satiety: ${track.satietyScore}%",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AccentAmber
                        )
                    }

                    Button(
                        onClick = {
                            val moved = track.copy(
                                cooldownActive = true,
                                recoveryProgress = 0.1f,
                                cooldownDaysRemaining = 5.0f
                            )
                            watchlistTracks = watchlistTracks.filter { it.id != track.id }
                            restingTracks = restingTracks + moved
                            snackbarMessage = "Moved ${track.title} to Cooldown Vault"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Text("Rest Track", fontSize = 11.sp, color = AccentCyanBright, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Acoustic Palate Cleansers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Spa, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                Text(
                    text = "Acoustic Palate Cleansers",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                text = "Low Satiety Contrast",
                fontSize = 11.sp,
                color = Color(0xFF64748B)
            )
        }

        TuneMindDemoData.palateCleansers.forEach { track ->
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
                            .background(Color(0xFF064E3B).copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Headphones, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = track.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${track.artist} • ${track.genre}", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "${track.satietyScore}% Satiety",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Scientific Explanation Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "NEURO-ACOUSTIC SATIATION DYNAMICS",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )
                Text(
                    text = "Why songs become stale",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Musical satiety is driven by predictable pattern repetition exhausting mesolimbic dopamine release. Rest periods of 4-7 days reset auditory receptor tolerance, restoring emotional resonance.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
