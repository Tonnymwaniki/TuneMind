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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.audio.AudioDeviceObserver
import com.tunemind.app.domain.audio.AudioDeviceSnapshot
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun HearingInsightsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val hearingState by vm.hearingInsightsState.collectAsState()
    val theme = LocalAdaptiveTheme.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.refreshHearingInsights()
    }

    val snapshot: AudioDeviceSnapshot = hearingState.activeSnapshot
        ?: remember(hearingState) { AudioDeviceObserver.getActiveAudioDeviceSnapshot(context) }

    val avgDb = snapshot.estimatedDb
    val headroomDb = snapshot.headroomDb
    val isSafe = avgDb < 85f
    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFFFB000)
    val alertRed = Color(0xFFFF4D6D)

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

    val soundDoseFraction = hearingState.soundDoseFraction.coerceIn(0.10f, 1.0f)
    val soundDoseColor = when {
        soundDoseFraction < 0.60f -> safeGreen
        soundDoseFraction < 0.82f -> warnAmber
        else -> alertRed
    }

    val safeHours = hearingState.safeHoursRemaining
    val safeHoursText = when {
        safeHours >= 16f -> "16+ hrs"
        safeHours >= 1f -> "%.1f hrs".format(safeHours)
        else -> "%d mins".format((safeHours * 60).toInt())
    }

    val (headroomBadgeText, headroomBadgeColor) = when {
        headroomDb >= 12f -> "Comfortable Margin" to safeGreen
        headroomDb >= 4f -> "Balanced Level" to theme.primaryAccent
        headroomDb >= 0f -> "Near Daily Limit" to warnAmber
        else -> "Too Loud" to alertRed
    }

    val (categoryTitle, categoryTag, categoryColor) = when (snapshot.transducerCategory) {
        "BLUETOOTH_EARBUDS" -> Triple("Bluetooth Earbuds", "In-Ear", safeGreen)
        "BLUETOOTH_HEADPHONES" -> Triple("Bluetooth Headphones", "Over-Ear", theme.primaryAccent)
        "WIRED_EARPHONES" -> Triple("Wired Earphones", "In-Ear", safeGreen)
        "WIRED_HEADPHONES" -> Triple("Wired Headphones", "Over-Ear", theme.secondaryAccent)
        "USB_EARPHONES", "USB_DAC" -> Triple("USB Digital Audio", "Direct", Color(0xFFA855F7))
        "BUILTIN_SPEAKER" -> Triple("Phone Speaker", "Open Air", warnAmber)
        else -> Triple(snapshot.deviceType, "Audio Output", theme.secondaryAccent)
    }

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header with Back + Refresh
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF0C1018).copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier
                            .size(38.dp)
                            .clickable { onBack() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "HEARING HEALTH",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = safeGreen,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            text = "Safe Listening",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = Color(0xFF0C1018).copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, theme.primaryAccent.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { vm.refreshHearingInsights() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh Audio Device",
                            tint = theme.primaryAccent,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            // Active Output Device Card
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(categoryColor.copy(alpha = 0.16f))
                            ) {
                                Icon(
                                    imageVector = when (snapshot.transducerCategory) {
                                        "BUILTIN_SPEAKER" -> Icons.Rounded.VolumeUp
                                        "USB_EARPHONES", "USB_DAC" -> Icons.Rounded.GraphicEq
                                        else -> Icons.Rounded.Headphones
                                    },
                                    contentDescription = null,
                                    tint = categoryColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = categoryTitle,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${snapshot.productName} • ${snapshot.connectionType}",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.62f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(categoryColor.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = categoryTag,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = categoryColor
                            )
                        }
                    }

                    // Device Media Gain Bar
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Device Volume (${snapshot.volumeIndex} / ${snapshot.maxVolume})",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.68f)
                            )
                            Text(
                                text = "${((snapshot.volumeIndex.toFloat() / maxOf(1, snapshot.maxVolume)) * 100).toInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.primaryAccent
                            )
                        }

                        val totalSteps = snapshot.maxVolume.coerceIn(10, 25)
                        val activeSteps = snapshot.volumeIndex.coerceIn(0, totalSteps)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(1.5.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            for (i in 0 until totalSteps) {
                                val isActive = i < activeSteps
                                val cellColor = when {
                                    !isActive -> Color.White.copy(alpha = 0.08f)
                                    i < (totalSteps * 0.65f) -> safeGreen
                                    i < (totalSteps * 0.85f) -> warnAmber
                                    else -> alertRed
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(cellColor)
                                )
                            }
                        }
                    }

                    Text(
                        text = snapshot.acousticCouplingDescription,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.64f),
                        lineHeight = 16.sp
                    )
                }
            }

            // Dual Stereo Volume Meters
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Estimated Sound Level",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${snapshot.sampleRate / 1000} kHz output",
                            fontSize = 11.sp,
                            color = theme.primaryAccent,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StereoMeterCard(
                            channelLabel = "Left",
                            dbLabel = "${(avgDb - 1.4f).toInt()} dBA",
                            needleAngle = needleAngleL,
                            accent = if (isSafe) safeGreen else warnAmber,
                            needleTint = theme.primaryAccent,
                            modifier = Modifier.weight(1f)
                        )
                        StereoMeterCard(
                            channelLabel = "Right",
                            dbLabel = "${(avgDb + 1.2f).toInt()} dBA",
                            needleAngle = needleAngleR,
                            accent = if (isSafe) safeGreen else warnAmber,
                            needleTint = theme.primaryAccent,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${avgDb.toInt()} dBA estimated",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSafe) safeGreen else warnAmber
                        )
                        Text(
                            text = headroomBadgeText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = headroomBadgeColor
                        )
                    }
                }
            }

            // Daily Sound Dose Ring
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier.size(78.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(74.dp)) {
                            val strokeW = 8.dp.toPx()
                            val arcSize = size.minDimension - strokeW
                            val topLeftOffset = Offset(strokeW / 2f, strokeW / 2f)

                            drawArc(
                                color = Color.White.copy(alpha = 0.08f),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeftOffset,
                                size = Size(arcSize, arcSize),
                                style = Stroke(width = strokeW, cap = StrokeCap.Round)
                            )

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
                                fontWeight = FontWeight.Bold,
                                color = soundDoseColor
                            )
                            Text(
                                text = "Dose",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.60f)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Daily Sound Dose",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = hearingState.safetyGuidance,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.68f),
                            lineHeight = 16.sp
                        )
                        Text(
                            text = if (soundDoseFraction < 0.6f) "Healthy range (WHO 85 dBA guide)" else "Approaching daily limit",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = soundDoseColor
                        )
                    }
                }
            }

            // Safe Headroom & Daily Allowance
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
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
                            fontWeight = FontWeight.Bold,
                            color = if (headroomDb >= 4f) safeGreen else if (headroomDb >= 0f) warnAmber else alertRed
                        )
                        Text(
                            text = "Safety margin below 85 dBA",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.60f)
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = safeHoursText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (safeHours >= 4f) safeGreen else warnAmber
                        )
                        Text(
                            text = "Safe listening left today",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.60f)
                        )
                    }
                }
            }

            // Output Type Guide
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Output Profiles",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    OutputProfileRow(
                        title = "In-Ear Earbuds",
                        rangeText = "58 – 104 dBA",
                        desc = "Sealed ear canal transfers sound directly. Keep volume moderate for long sessions.",
                        isActive = snapshot.transducerCategory in listOf("BLUETOOTH_EARBUDS", "WIRED_EARPHONES", "USB_EARPHONES"),
                        accentColor = safeGreen
                    )
                    OutputProfileRow(
                        title = "Over-Ear Headphones",
                        rangeText = "54 – 96 dBA",
                        desc = "Cushioned ear cups diffuse pressure naturally for balanced comfort.",
                        isActive = snapshot.transducerCategory in listOf("BLUETOOTH_HEADPHONES", "WIRED_HEADPHONES"),
                        accentColor = theme.primaryAccent
                    )
                    OutputProfileRow(
                        title = "Phone Speaker",
                        rangeText = "44 – 82 dBA",
                        desc = "Open air listening reduces direct ear fatigue during casual playback.",
                        isActive = snapshot.transducerCategory == "BUILTIN_SPEAKER",
                        accentColor = warnAmber
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StereoMeterCard(
    channelLabel: String,
    dbLabel: String,
    needleAngle: Float,
    accent: Color,
    needleTint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = channelLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.72f)
            )
            Text(
                text = dbLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("-20", fontSize = 9.sp, color = Color.White.copy(alpha = 0.42f))
                Text("0", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = needleTint)
                Text("+3", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF4D6D))
            }

            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .width(2.5.dp)
                    .height(54.dp)
                    .graphicsLayer {
                        rotationZ = needleAngle
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(
                        Brush.verticalGradient(
                            listOf(needleTint, accent, Color.Transparent)
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}

@Composable
private fun OutputProfileRow(
    title: String,
    rangeText: String,
    desc: String,
    isActive: Boolean,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) accentColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f),
        border = BorderStroke(
            1.dp,
            if (isActive) accentColor.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.72f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = rangeText,
                        fontSize = 10.sp,
                        color = if (isActive) accentColor else Color.White.copy(alpha = 0.50f)
                    )
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accentColor.copy(alpha = 0.22f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Active",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        }
                    }
                }
            }
            Text(
                text = desc,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.60f),
                lineHeight = 15.sp
            )
        }
    }
}
