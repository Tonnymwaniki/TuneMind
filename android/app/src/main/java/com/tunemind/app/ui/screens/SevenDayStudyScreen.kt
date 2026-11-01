package com.tunemind.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.model.TuneMindDemoData
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SevenDayStudyScreen(vm: TuneMindViewModel, onBack: () -> Unit) {
    val historyState by vm.historyState.collectAsState()
    val colors = TuneMindTheme.colors
    val scope = rememberCoroutineScope()

    var selectedDayIndex by remember { mutableIntStateOf(2) } // Wednesday default
    var isRecalibrating by remember { mutableStateOf(false) }
    var recalibrationSuccessMessage by remember { mutableStateOf<String?>(null) }

    val days = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    val listeningHours = listOf("2.4h", "4.1h", "3.2h", "5.0h", "4.8h", "6.2h", "3.5h")
    val fillPercentages = listOf(0.48f, 0.82f, 0.64f, 0.95f, 0.90f, 1.0f, 0.70f)
    val dayDescriptors = listOf(
        "Harmonic Awakening • Balanced mid-tempo groove",
        "Deep Acoustic Flow • High linguistic tolerance",
        "Midweek Peak • Dynamic structural lock",
        "Satiety Equilibrium • Safe vocal repetition",
        "Evening Novelty • High receptivity window",
        "Weekend Immersion • Extended soundscape exploration",
        "Restorative Cooldown • Low treble ambient recovery"
    )

    val realTrackCount = historyState.tracks.size
    val observedHoursLabel = if (realTrackCount > 0) "${maxOf(12, realTrackCount * 4)}m tracked" else "87.4h observed"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepObsidianAmber.AppBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. TOP HARDWARE TELEMETRY HEADER
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DeepObsidianAmber.SecondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(DeepObsidianAmber.Success)
                    )
                    Text(
                        text = "CONTINUOUS INTELLIGENCE // REAL-TIME",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.TextSecondary,
                        letterSpacing = 1.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DeepObsidianAmber.RecessedBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "EVOLVING PERMANENTLY",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.PrimaryAccent,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // Section Title & Mission
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "CONTINUOUS ACOUSTIC INTELLIGENCE",
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = DeepObsidianAmber.TextPrimary,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "Unlocked and actively learning your auditory fingerprint. Intelligence refines continuously across all playback cycles.",
                fontSize = 12.sp,
                color = DeepObsidianAmber.TextSecondary,
                lineHeight = 17.sp
            )
        }

        // 2. INTELLIGENCE KEY METRICS CARD (Creative layered surface with copper & gold details)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DeepObsidianAmber.CardElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "NEURAL LISTENING INDEX",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.SecondaryAccent,
                        letterSpacing = 1.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DeepObsidianAmber.RecessedBackground
                    ) {
                        Text(
                            text = "+4.8% EVOLUTION / WK",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.Success,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "94.2",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = DeepObsidianAmber.TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Acoustic Plasticity Score",
                            fontSize = 11.sp,
                            color = DeepObsidianAmber.TextMuted
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = observedHoursLabel,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.PrimaryAccent,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Continuous Ingestion Active",
                            fontSize = 10.sp,
                            color = DeepObsidianAmber.TextSecondary
                        )
                    }
                }

                // Progress Bar with rich copper-gold gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(DeepObsidianAmber.RecessedBackground)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.942f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        DeepObsidianAmber.CopperAccent,
                                        DeepObsidianAmber.PrimaryAccent,
                                        DeepObsidianAmber.SecondaryAccent
                                    )
                                )
                            )
                    )
                }
            }
        }

        // 3. INTERACTIVE EVOLVING INTELLIGENCE MATRIX (7 Vacuum Pill Tubes)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DeepObsidianAmber.CardBackground)
                .border(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = DeepObsidianAmber.PrimaryAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "ROLLING AUDITORY CYCLE MATRIX",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.TextPrimary,
                        letterSpacing = 0.8.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = DeepObsidianAmber.RecessedBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "ALL ACTIVE // UNLOCKED",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.Success,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // The 7 Vacuum Tubes in Recessed Obsidian Amber Well
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DeepObsidianAmber.RecessedBackground)
                    .border(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(vertical = 12.dp, horizontal = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    days.forEachIndexed { index, day ->
                        val isSelected = index == selectedDayIndex
                        val fill = fillPercentages[index]
                        val hours = listeningHours[index]

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedDayIndex = index }
                        ) {
                            Text(
                                text = hours,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) DeepObsidianAmber.AccentLight else DeepObsidianAmber.TextMuted
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            // Vacuum Tube Glass Capsule
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DeepObsidianAmber.SecondaryBackground)
                                    .border(
                                        1.dp,
                                        if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(2.dp),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                // Illuminated Liquid Amber Column with gradient
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(fill)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    if (isSelected) DeepObsidianAmber.AccentSoft else DeepObsidianAmber.PrimaryAccent,
                                                    if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.CopperAccent
                                                )
                                            )
                                        )
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = day,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextSecondary
                            )
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 4.dp else 2.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextDisabled)
                            )
                        }
                    }
                }
            }
        }

        // 4. DAY DETAIL TELEMETRY CARD (Highlights active selected day)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = DeepObsidianAmber.SecondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${days[selectedDayIndex]} ADAPTIVE PROFILE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.PrimaryAccent,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${listeningHours[selectedDayIndex]} ACTIVE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.TextPrimary
                    )
                }

                Text(
                    text = dayDescriptors[selectedDayIndex],
                    fontSize = 12.sp,
                    color = DeepObsidianAmber.TextPrimary,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "Acoustic equilibrium maintained. Neural auditory models continually calibrate against your skip dynamics and harmonic preference.",
                    fontSize = 11.sp,
                    color = DeepObsidianAmber.TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }

        // 5. CIRCADIAN RECEPTIVITY CURVE PREVIEW
        Text(
            text = "Adaptive Circadian Calibration",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = DeepObsidianAmber.TextPrimary
        )

        TuneMindDemoData.circadianWindows.forEach { window ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DeepObsidianAmber.CardBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = window.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.TextPrimary
                        )
                        Text(
                            text = "${window.timeRange} • ${window.descriptor}",
                            fontSize = 10.sp,
                            color = DeepObsidianAmber.TextSecondary
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (window.isFatigueProne) DeepObsidianAmber.SurfaceHighlight else DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (window.isFatigueProne) DeepObsidianAmber.Warning.copy(alpha = 0.5f) else DeepObsidianAmber.CopperAccent.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            text = "${window.receptivityPercent}% Receptive",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (window.isFatigueProne) DeepObsidianAmber.Warning else DeepObsidianAmber.SecondaryAccent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // 6. REAL-TIME RECALIBRATION ACTION
        Button(
            onClick = {
                scope.launch {
                    isRecalibrating = true
                    delay(800)
                    isRecalibrating = false
                    recalibrationSuccessMessage = "Intelligence baseline refreshed with latest telemetry!"
                    delay(3000)
                    recalibrationSuccessMessage = null
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DeepObsidianAmber.PrimaryAccent,
                contentColor = DeepObsidianAmber.AppBackground
            ),
            enabled = !isRecalibrating
        ) {
            if (isRecalibrating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = DeepObsidianAmber.AppBackground,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Recalibrating Neural Matrix...", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("RECALIBRATE INTELLIGENCE // RUN PULSE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        AnimatedVisibility(
            visible = recalibrationSuccessMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DeepObsidianAmber.RecessedBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.Success.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = recalibrationSuccessMessage ?: "",
                    fontSize = 11.sp,
                    color = DeepObsidianAmber.Success,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
