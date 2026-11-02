package com.tunemind.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
    val activeDevice = hearingState.activeAudioDevice?.deviceName ?: "Bluetooth Headset (A2DP • LDAC)"

    val isSafe = avgDb < 85f
    val headroomDb = (85f - avgDb).coerceAtLeast(0f)

    // Ballistic Dual Channel VU Meters (Spring Damping 0.52ζ)
    val needleAngleL by animateFloatAsState(
        targetValue = -50f + (((avgDb - 1.6f) - 40f) / 80f).coerceIn(0f, 1f) * 100f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessLow),
        label = "vuLeft"
    )
    val needleAngleR by animateFloatAsState(
        targetValue = -50f + (((avgDb + 1.4f) - 40f) / 80f).coerceIn(0f, 1f) * 100f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessLow),
        label = "vuRight"
    )

    // Circadian Sound Dose Percentage (0.0 to 1.0)
    val soundDoseFraction = ((avgDb / 85f) * 0.44f).coerceIn(0.12f, 1.0f)
    val soundDoseColor = when {
        soundDoseFraction < 0.60f -> DeepObsidianAmber.DecibelSafe
        soundDoseFraction < 0.82f -> DeepObsidianAmber.PrimaryAccent
        else -> DeepObsidianAmber.Error
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepObsidianAmber.DecibelSafe.copy(alpha = 0.07f),
                        DeepObsidianAmber.AppBackground,
                        DeepObsidianAmber.RecessedBackground
                    )
                )
            )
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
                    color = DeepObsidianAmber.SecondaryAccent
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

        // 2. MAIN CENTERPIECE: DUAL ANALOG LEFT/RIGHT VU METERS (Warm Amber Backlight)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.secondaryBackground)
                .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header within rack
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(DeepObsidianAmber.PrimaryAccent))
                    Text(
                        text = "ANALOG VU RACK // DUAL STEREO BUS",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.SecondaryAccent,
                        letterSpacing = 0.8.sp
                    )
                }
                Text(
                    text = "BALLISTIC 0.52ζ",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = DeepObsidianAmber.PrimaryAccent
                )
            }

            // Dual Meters Row (Left & Right Channels)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // LEFT CHANNEL VU METER
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.16f),
                                    DeepObsidianAmber.BrandSoulAmber.copy(alpha = 0.08f),
                                    DeepObsidianAmber.CardElevated
                                )
                            )
                        )
                        .border(1.2.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "CH-L",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.SecondaryAccent
                        )
                        Text(
                            text = "${(avgDb - 1.6f).toInt()} dB",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isSafe) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning
                        )
                    }

                    // Meter Arc & Needle Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(82.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        // Curved Scale Markings
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("-20", fontSize = 7.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.TextMuted)
                            Text("-7", fontSize = 7.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.TextSecondary)
                            Text("0", fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.PrimaryAccent)
                            Text("+3", fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.Error)
                        }

                        // Ballistic Pointer Needle
                        Box(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .width(2.dp)
                                .height(58.dp)
                                .graphicsLayer {
                                    rotationZ = needleAngleL
                                    transformOrigin = TransformOrigin(0.5f, 1f)
                                }
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            DeepObsidianAmber.PrimaryAccent,
                                            DeepObsidianAmber.CopperAccent,
                                            DeepObsidianAmber.AppBackground
                                        )
                                    )
                                )
                        )

                        // Pivot Bearing
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(DeepObsidianAmber.CopperAccent)
                                .border(1.5.dp, DeepObsidianAmber.AppBackground, CircleShape)
                        )
                    }
                }

                // RIGHT CHANNEL VU METER
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.16f),
                                    DeepObsidianAmber.BrandSoulAmber.copy(alpha = 0.08f),
                                    DeepObsidianAmber.CardElevated
                                )
                            )
                        )
                        .border(1.2.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "CH-R",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.SecondaryAccent
                        )
                        Text(
                            text = "${(avgDb + 1.4f).toInt()} dB",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isSafe) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning
                        )
                    }

                    // Meter Arc & Needle Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(82.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        // Curved Scale Markings
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("-20", fontSize = 7.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.TextMuted)
                            Text("-7", fontSize = 7.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.TextSecondary)
                            Text("0", fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.PrimaryAccent)
                            Text("+3", fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.Error)
                        }

                        // Ballistic Pointer Needle
                        Box(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .width(2.dp)
                                .height(58.dp)
                                .graphicsLayer {
                                    rotationZ = needleAngleR
                                    transformOrigin = TransformOrigin(0.5f, 1f)
                                }
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            DeepObsidianAmber.PrimaryAccent,
                                            DeepObsidianAmber.CopperAccent,
                                            DeepObsidianAmber.AppBackground
                                        )
                                    )
                                )
                        )

                        // Pivot Bearing
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(DeepObsidianAmber.CopperAccent)
                                .border(1.5.dp, DeepObsidianAmber.AppBackground, CircleShape)
                        )
                    }
                }
            }

            // Digital Exposure Readout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PEAK INTEGRATION: ${avgDb.toInt()} dBA",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isSafe) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning
                )
                Text(
                    text = if (isSafe) "[SAFE HEADROOM]" else "[CAUTION EXPOSURE]",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isSafe) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning
                )
            }
        }

        // 2B. CIRCADIAN SOUND DOSE PROGRESS RING (Emerald -> Amber -> Crimson)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Circular Gauge Ring
                Box(
                    modifier = Modifier.size(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(76.dp)) {
                        val strokeW = 8.dp.toPx()
                        val arcSize = size.minDimension - strokeW
                        val topLeftOffset = Offset(strokeW / 2f, strokeW / 2f)

                        // Background track
                        drawArc(
                            color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.35f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeftOffset,
                            size = Size(arcSize, arcSize),
                            style = Stroke(width = strokeW, cap = StrokeCap.Round)
                        )

                        // Dynamic Dose Arc
                        drawArc(
                            color = soundDoseColor,
                            startAngle = -90f,
                            sweepAngle = 360f * soundDoseFraction,
                            useCenter = false,
                            topLeft = topLeftOffset,
                            size = Size(arcSize, arcSize),
                            style = Stroke(width = strokeW, cap = StrokeCap.Round)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${(soundDoseFraction * 100).toInt()}%",
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = soundDoseColor
                        )
                        Text(
                            text = "DOSE",
                            fontSize = 7.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.secondaryText
                        )
                    }
                }

                // Dose Context Telemetry
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "CIRCADIAN SOUND DOSE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText
                    )
                    Text(
                        text = if (soundDoseFraction < 0.6f) "Auditory stereocilia fatigue is within pristine recovery bounds. +%.1f dB margin.".format(headroomDb)
                               else "Auditory dose elevated. Consider acoustic recovery interval before bedtime.",
                        fontSize = 11.sp,
                        color = colors.secondaryText,
                        lineHeight = 15.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(soundDoseColor))
                        Text(
                            text = if (soundDoseFraction < 0.6f) "DOSE: SAFE [EMERALD]" else "DOSE: ELEVATED",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = soundDoseColor
                        )
                    }
                }
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
