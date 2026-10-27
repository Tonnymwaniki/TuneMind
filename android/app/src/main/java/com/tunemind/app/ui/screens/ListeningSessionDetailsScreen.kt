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
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningSessionDetailsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
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
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF38BDF8).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "SESSION BREAKDOWN",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Reconstructed Episode",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "CLOSED",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = "Session observed via Spotify background daemon. Reconstructed from continuous Android playback callbacks and notification state machine.",
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
                    Text("Duration", fontSize = 10.sp, color = Color(0xFF64748B), fontFamily = FontFamily.Monospace)
                    Text("34m 12s", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                    Text("8 tracks total", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Satiety Shift", fontSize = 10.sp, color = Color(0xFF64748B), fontFamily = FontFamily.Monospace)
                    Text("+14%", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentAmber)
                    Text("Velocity: 0.42/h", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Confidence", fontSize = 10.sp, color = Color(0xFF64748B), fontFamily = FontFamily.Monospace)
                    Text("96%", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                    Text("Zero jitter", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }
        }

        // Chronological Track Sequence
        Text(
            text = "Reconstructed Track Timeline",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        val tracksInSession = listOf(
            Triple("1. Espresso", "Sabrina Carpenter • 2:55", "+0.45 Satiety"),
            Triple("2. Midnight Thoughts", "Luna Rivers • 3:36", "+0.18 Satiety"),
            Triple("3. Stay With Me", "The Neon Hearts • 3:24", "+0.72 High Satiety"),
            Triple("4. Hyperion Drive", "Solar Pulse • 3:50", "+0.55 Satiety"),
            Triple("5. Electric Dreams", "The Wanderers • 3:14", "-0.10 Palate Cleanse")
        )

        tracksInSession.forEach { (title, meta, tag) ->
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
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = meta, fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (tag.contains("High")) Color(0xFFEF4444).copy(alpha = 0.15f)
                        else if (tag.contains("Palate")) Color(0xFF10B981).copy(alpha = 0.15f)
                        else Color(0xFF0284C7).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = tag,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (tag.contains("High")) Color(0xFFF87171)
                            else if (tag.contains("Palate")) Color(0xFF34D399)
                            else AccentCyanBright,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
