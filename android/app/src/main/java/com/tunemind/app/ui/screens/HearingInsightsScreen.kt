package com.tunemind.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.audio.AudioDeviceObserver
import com.tunemind.app.domain.audio.AudioDeviceSnapshot
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@Composable
fun HearingInsightsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val hearingState by vm.hearingInsightsState.collectAsState()
    val colors = TuneMindTheme.colors
    val context = LocalContext.current

    // Query active hardware snapshot immediately on launch and whenever device state changes
    LaunchedEffect(Unit) {
        vm.refreshHearingInsights()
    }

    val snapshot: AudioDeviceSnapshot = hearingState.activeSnapshot
        ?: remember(hearingState) { AudioDeviceObserver.getActiveAudioDeviceSnapshot(context) }

    val avgDb = snapshot.estimatedDb
    val headroomDb = snapshot.headroomDb
    val isSafe = avgDb < 85f

    // Ballistic Dual Channel VU Meters (Spring Damping 0.52ζ)
    val needleAngleL by animateFloatAsState(
        targetValue = -50f + (((avgDb - 1.4f) - 40f) / 75f).coerceIn(0f, 1f) * 100f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessLow),
        label = "vuLeft"
    )
    val needleAngleR by animateFloatAsState(
        targetValue = -50f + (((avgDb + 1.2f) - 40f) / 75f).coerceIn(0f, 1f) * 100f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessLow),
        label = "vuRight"
    )

    // Circadian Sound Dose Percentage (0.0 to 1.0)
    val soundDoseFraction = hearingState.soundDoseFraction.coerceIn(0.10f, 1.0f)
    val soundDoseColor = when {
        soundDoseFraction < 0.60f -> DeepObsidianAmber.DecibelSafe
        soundDoseFraction < 0.82f -> DeepObsidianAmber.PrimaryAccent
        else -> DeepObsidianAmber.Error
    }

    val safeHours = hearingState.safeHoursRemaining
    val safeHoursText = when {
        safeHours >= 16f -> "> 16.0 HOURS"
        safeHours >= 1f -> "%.1f HOURS".format(safeHours)
        else -> "%d MINS".format((safeHours * 60).toInt())
    }

    val (headroomBadgeText, headroomBadgeColor) = when {
        headroomDb >= 12f -> "[PRISTINE HEADROOM]" to DeepObsidianAmber.DecibelSafe
        headroomDb >= 4f -> "[SAFE MARGIN]" to DeepObsidianAmber.PrimaryAccent
        headroomDb >= 0f -> "[THRESHOLD REACHED]" to DeepObsidianAmber.Warning
        else -> "[CAUTION EXPOSURE]" to DeepObsidianAmber.Error
    }

    // Transducer category display info
    val (categoryTitle, categoryTag, categoryColor) = when (snapshot.transducerCategory) {
        "BLUETOOTH_EARBUDS" -> Triple("Bluetooth Earbuds / TWS", "IN-EAR SEAL", DeepObsidianAmber.DecibelSafe)
        "BLUETOOTH_HEADPHONES" -> Triple("Bluetooth Headphones", "OVER-EAR CIRCUMAURAL", DeepObsidianAmber.PrimaryAccent)
        "WIRED_EARPHONES" -> Triple("Wired Earphones (3.5mm)", "IN-EAR CANAL", DeepObsidianAmber.DecibelSafe)
        "WIRED_HEADPHONES" -> Triple("Wired Headphones (3.5mm)", "OVER-EAR MONITOR", DeepObsidianAmber.SecondaryAccent)
        "USB_EARPHONES", "USB_DAC" -> Triple("USB-C Digital Audio / DAC", "BITSTREAM DIRECT", DeepObsidianAmber.NeuralViolet)
        "BUILTIN_SPEAKER" -> Triple("Built-in Phone Loudspeaker", "OPEN AMBIENT", DeepObsidianAmber.CopperAccent)
        else -> Triple(snapshot.deviceType, "STANDARD", DeepObsidianAmber.SecondaryAccent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepObsidianAmber.DecibelSafe.copy(alpha = 0.08f),
                        DeepObsidianAmber.AppBackground,
                        DeepObsidianAmber.RecessedBackground
                    )
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. TOP HARDWARE TELEMETRY STRIP & RE-PROBE BRIDGE
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
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
                            .background(DeepObsidianAmber.DecibelSafe)
                    )
                    Text(
                        text = "TRANSDUCER INSPECTION // EXPOSURE ENGINE",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 0.8.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${snapshot.sampleRate / 1000}kHz • BUS",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.SecondaryAccent
                    )
                    IconButton(
                        onClick = { vm.refreshHearingInsights() },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Refresh Device Probe",
                            tint = colors.primaryAccent,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // 2. ACTIVE HARDWARE TRANSDUCER & ACOUSTIC COUPLING CARD
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(
                1.2.dp,
                Brush.horizontalGradient(
                    listOf(
                        categoryColor.copy(alpha = 0.6f),
                        DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.3f)
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Row: Transducer Badge and Connection Bus
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = when (snapshot.transducerCategory) {
                                "BUILTIN_SPEAKER" -> Icons.Outlined.VolumeUp
                                "USB_EARPHONES", "USB_DAC" -> Icons.Outlined.GraphicEq
                                else -> Icons.Outlined.Headphones
                            },
                            contentDescription = null,
                            tint = categoryColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = categoryTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryText
                            )
                            Text(
                                text = "CONNECTION: ${snapshot.connectionType}",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.secondaryText
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = BorderStroke(1.dp, categoryColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = categoryTag,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = categoryColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                // Device Product Identification (e.g. "AirPods Pro", "Sony WH-1000XM4", "Built-in Speaker")
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DeepObsidianAmber.RecessedBackground,
                    border = BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "HARDWARE SINK PRODUCT",
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.secondaryText
                            )
                            Text(
                                text = snapshot.productName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.PrimaryAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(DeepObsidianAmber.DecibelSafe))
                            Text(
                                text = "MONITORED",
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.DecibelSafe
                            )
                        }
                    }
                }

                // Android Gain Step Meter (e.g. Step 8 of 15)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ANDROID MEDIA GAIN: STEP ${snapshot.volumeIndex} / ${snapshot.maxVolume}",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondaryText
                        )
                        Text(
                            text = "${((snapshot.volumeIndex.toFloat() / maxOf(1, snapshot.maxVolume)) * 100).toInt()}% GAIN",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryAccent
                        )
                    }

                    // 15-cell Segmented Gain Bar
                    val totalSteps = snapshot.maxVolume.coerceIn(10, 25)
                    val activeSteps = snapshot.volumeIndex.coerceIn(0, totalSteps)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(DeepObsidianAmber.RecessedBackground)
                            .padding(1.5.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        for (i in 0 until totalSteps) {
                            val isActive = i < activeSteps
                            val cellColor = when {
                                !isActive -> DeepObsidianAmber.CardElevated.copy(alpha = 0.5f)
                                i < (totalSteps * 0.65f) -> DeepObsidianAmber.DecibelSafe
                                i < (totalSteps * 0.85f) -> DeepObsidianAmber.PrimaryAccent
                                else -> DeepObsidianAmber.Error
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(cellColor)
                            )
                        }
                    }
                }

                // Acoustic Coupling Physics Description
                Text(
                    text = snapshot.acousticCouplingDescription,
                    fontSize = 11.sp,
                    color = colors.secondaryText,
                    lineHeight = 15.sp
                )
            }
        }

        // 3. MAIN CENTERPIECE: DUAL ANALOG LEFT/RIGHT VU METERS (Warm Amber Glow)
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
                            text = "${(avgDb - 1.4f).toInt()} dBA",
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
                            text = "${(avgDb + 1.2f).toInt()} dBA",
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

            // Digital Exposure Readout & Dynamic Headroom Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CALIBRATED SPL: ${avgDb.toInt()} dBA",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isSafe) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning
                )
                Text(
                    text = headroomBadgeText,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = headroomBadgeColor
                )
            }
        }

        // 4. CIRCADIAN SOUND DOSE PROGRESS RING (Emerald -> Amber -> Crimson)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)),
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
                        text = hearingState.safetyGuidance,
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
                            text = if (soundDoseFraction < 0.6f) "DOSE: OPTIMAL [WHO 85dBA]" else "DOSE: ELEVATED",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = soundDoseColor
                        )
                    }
                }
            }
        }

        // 5. TRUE SIGNED ACOUSTIC HEADROOM & SAFE ALLOWANCE
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        val formattedHeadroom = if (headroomDb >= 0f) "+%.1f dB".format(headroomDb) else "%.1f dB".format(headroomDb)
                        Text(
                            text = formattedHeadroom,
                            fontSize = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (headroomDb >= 4f) DeepObsidianAmber.DecibelSafe else if (headroomDb >= 0f) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.Error
                        )
                        Text("Headroom to 85 dBA Threshold", fontSize = 11.sp, color = colors.secondaryText)
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = safeHoursText,
                            fontSize = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (safeHours >= 4f) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning
                        )
                        Text("Safe Listening Allowance", fontSize = 11.sp, color = colors.secondaryText)
                    }
                }
            }
        }

        // 6. ANDROID TRANSDUCER ACOUSTIC COUPLING SPECTRUM
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
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
                        text = "TRANSDUCER COUPLING SPECTRUM",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "ACOUSTIC CALIBRATION",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DeepObsidianAmber.PrimaryAccent
                    )
                }

                // 1. In-Ear Canal Earbuds / TWS
                TransducerCouplingRow(
                    title = "In-Ear Canal (Earbuds / TWS)",
                    rangeText = "58 – 104 dBA",
                    desc = "Acoustic seal in canal. Direct tympanic transfer; highest stereocilia fatigue rate.",
                    isActive = snapshot.transducerCategory in listOf("BLUETOOTH_EARBUDS", "WIRED_EARPHONES", "USB_EARPHONES"),
                    accentColor = DeepObsidianAmber.DecibelSafe
                )

                // 2. Over-Ear Circumaural Headphones
                TransducerCouplingRow(
                    title = "Over-Ear Circumaural (Headphones)",
                    rangeText = "54 – 96 dBA",
                    desc = "Ear cushions & pinna chamber dispersion. Balanced acoustic air cushion absorption.",
                    isActive = snapshot.transducerCategory in listOf("BLUETOOTH_HEADPHONES", "WIRED_HEADPHONES"),
                    accentColor = DeepObsidianAmber.PrimaryAccent
                )

                // 3. Open Phone Loudspeaker
                TransducerCouplingRow(
                    title = "Ambient Phone Loudspeaker",
                    rangeText = "44 – 82 dBA",
                    desc = "Open air inverse-square distance decay. Minimal auditory fatigue under normal distance.",
                    isActive = snapshot.transducerCategory == "BUILTIN_SPEAKER",
                    accentColor = DeepObsidianAmber.CopperAccent
                )
            }
        }
    }
}

@Composable
private fun TransducerCouplingRow(
    title: String,
    rangeText: String,
    desc: String,
    isActive: Boolean,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isActive) accentColor.copy(alpha = 0.12f) else DeepObsidianAmber.RecessedBackground,
        border = BorderStroke(
            1.dp,
            if (isActive) accentColor.copy(alpha = 0.6f) else DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isActive) accentColor else DeepObsidianAmber.TextMuted)
                    )
                    Text(
                        text = title,
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) DeepObsidianAmber.TextPrimary else DeepObsidianAmber.TextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = rangeText,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isActive) accentColor else DeepObsidianAmber.TextMuted
                    )
                    if (isActive) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = accentColor.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, accentColor)
                        ) {
                            Text(
                                text = "ACTIVE",
                                fontSize = 7.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = desc,
                fontSize = 10.sp,
                color = DeepObsidianAmber.TextSecondary,
                lineHeight = 14.sp
            )
        }
    }
}
