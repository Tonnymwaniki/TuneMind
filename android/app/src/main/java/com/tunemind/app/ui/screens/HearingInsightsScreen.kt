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
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@Composable
fun HearingInsightsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val hearingState by vm.hearingInsightsState.collectAsState()
    val colors = TuneMindTheme.colors
    val avgDb = hearingState.estimatedAverageDb.takeIf { it > 0f } ?: 72f
    val activeDevice = hearingState.activeAudioDevice ?: "Bluetooth Headset (A2DP • LDAC)"

    val isSafe = avgDb < 85f
    val headroomDb = (85f - avgDb).coerceAtLeast(0f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.primaryBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. TOP HARDWARE TELEMETRY STRIP & DEVICE BRIDGE
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.secondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981))
                    )
                    Text(
                        text = "HEARING HEALTH // EXPOSURE ENGINE",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = "DSP BUS • 48kHz",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF83B9BD)
                )
            }
        }

        // Active Transducer Recessed Pill
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.secondaryBackground.copy(alpha = 0.8f),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Outlined.Headphones,
                        contentDescription = null,
                        tint = colors.primaryAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = activeDevice,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981))
                    )
                    Text(
                        text = "SYNCED",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                }
            }
        }

        // 2. MAIN CENTERPIECE: PRECISION ARC VU FACEPLATE (Machined Screws Detailing)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.secondaryBackground)
                .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header within faceplate
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RANGE: 40-120 dBA",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText
                )
                Text(
                    text = "MONITOR: CONTINUOUS",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF83B9BD)
                )
            }

            // Visual Dial Plate
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer Track
                Box(
                    modifier = Modifier
                        .size(220.dp, 110.dp)
                        .clip(RoundedCornerShape(topStart = 110.dp, topEnd = 110.dp))
                        .background(
                            Brush.sweepGradient(
                                listOf(
                                    Color(0xFF83B9BD),
                                    Color(0xFF83B9BD),
                                    Color(0xFFF59E0B),
                                    Color(0xFFCF7778),
                                    Color(0xFF83B9BD)
                                )
                            )
                        )
                        .padding(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 102.dp, topEnd = 102.dp))
                            .background(colors.cardSurface)
                    )
                }

                // Center Decibel Digital Readout & Needle Pivot
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Text(
                        text = "${avgDb.toInt()} dBA",
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isSafe) Color(0xFF83B9BD) else Color(0xFFF59E0B),
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = if (isSafe) "SAFE ACOUSTIC HEADROOM" else "ATTENTION: NEAR CRITICAL THRESHOLD",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isSafe) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                }
            }

            // Scale Markings Legend Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("40", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = colors.secondaryText)
                Text("60", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF83B9BD))
                Text("80", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = colors.secondaryText)
                Text("85 CRIT", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                Text("100", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFF59E0B))
                Text("120 dB", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFFCF7778))
            }
        }

        // 3. HEADROOM STATS
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.secondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ACOUSTIC HEADROOM TELEMETRY",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText,
                    letterSpacing = 1.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "+%.1f dB".format(headroomDb),
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryAccent
                        )
                        Text("Headroom to 85 dBA Threshold", fontSize = 11.sp, color = colors.secondaryText)
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "8.0 HOURS",
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                        Text("Daily Safe Listening Allowance", fontSize = 11.sp, color = colors.secondaryText)
                    }
                }
            }
        }
    }
}
