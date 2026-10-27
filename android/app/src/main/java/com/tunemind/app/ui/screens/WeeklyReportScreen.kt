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
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyReportScreen(onBack: () -> Unit) {
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
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.4f)),
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
                                .background(Color(0xFF8B5CF6).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Insights,
                                contentDescription = null,
                                tint = PrimaryVioletBright,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "WEEKLY REPORT",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryVioletBright,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Receptive Horizon",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            text = "Week 43",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryVioletBright,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = "Derived from 87.4 monitored hours. Identifies cyclical pattern shifts, recovery velocity, and acoustic freshness recommendations for your next rotation.",
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
                    Text("Satiety Index", fontSize = 10.sp, color = Color(0xFF64748B), fontFamily = FontFamily.Monospace)
                    Text("42%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                    Text("-8% (Optimal)", fontSize = 10.sp, color = AccentGreen)
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Peak Window", fontSize = 10.sp, color = Color(0xFF64748B), fontFamily = FontFamily.Monospace)
                    Text("10:30 AM", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PrimaryVioletBright)
                    Text("Harmonic tolerance", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Loop Velocity", fontSize = 10.sp, color = Color(0xFF64748B), fontFamily = FontFamily.Monospace)
                    Text("0.18 /d", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AccentAmber)
                    Text("Stable decay", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }
        }

        // Auditory Anchors Breakdown
        Text(
            text = "Auditory Anchors Distribution",
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
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(anchor.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${anchor.percentage}% (${anchor.driftDelta})", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AccentCyan)
                    }
                    LinearProgressIndicator(
                        progress = { anchor.percentage / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentCyan,
                        trackColor = Color(0xFF1E293B)
                    )
                    Text(anchor.description, fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
            }
        }

        // Recommendations Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ACTIONABLE RECOMMENDATIONS",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen
                )
                Text(
                    text = "Optimal Rotation for Coming Week",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "• Maintain the 3 tracks in Cooldown Vault for at least 2 more days to ensure full receptor recovery.\n• Shift high-tempo electronic listening toward the 10:30 AM - 1:00 PM circadian window.\n• Introduce acoustic palate cleansers during late-evening listening to prevent loop fixation.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
