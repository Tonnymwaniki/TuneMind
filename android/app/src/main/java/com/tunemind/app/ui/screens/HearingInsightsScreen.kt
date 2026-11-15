package com.tunemind.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
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

    val (headroomText, headroomColor) = when {
        headroomDb >= 12f -> "Comfortable level" to safeGreen
        headroomDb >= 4f -> "Balanced level" to theme.primaryAccent
        headroomDb >= 0f -> "Near daily limit" to warnAmber
        else -> "Too loud" to alertRed
    }

    val (categoryTitle, categoryTag, categoryColor) = when (snapshot.transducerCategory) {
        "BLUETOOTH_EARBUDS" -> Triple("Bluetooth Earbuds", "In-Ear", safeGreen)
        "BLUETOOTH_HEADPHONES" -> Triple("Bluetooth Headphones", "Over-Ear", theme.primaryAccent)
        "WIRED_EARPHONES" -> Triple("Wired Earphones", "In-Ear", safeGreen)
        "WIRED_HEADPHONES" -> Triple("Wired Headphones", "Over-Ear", theme.secondaryAccent)
        "USB_EARPHONES", "USB_DAC" -> Triple("USB Digital Audio", "Direct", Color(0xFFA855F7))
        "BUILTIN_SPEAKER" -> Triple("Speaker", "Open Air", warnAmber)
        else -> Triple(AudioDeviceObserver.sanitizeOutputDeviceLabel(snapshot.deviceType, snapshot.transducerCategory), "Audio Output", theme.secondaryAccent)
    }

    val sanitizedProduct = AudioDeviceObserver.sanitizeOutputDeviceLabel(
        raw = snapshot.productName,
        transducerCategory = snapshot.transducerCategory
    )

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFF8FAFC)
                        )
                    }
                    Text(
                        text = "Safe Listening",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                }

                IconButton(onClick = { vm.refreshHearingInsights() }) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh Audio Output",
                        tint = Color.White.copy(alpha = 0.80f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Primary Hero Card (only SoftGlassCard on this screen): Sound Level + VU Meters
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${avgDb.toInt()} dBA",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSafe) safeGreen else warnAmber
                        )
                        Text(
                            text = "$headroomText · ${snapshot.sampleRate / 1000} kHz",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = headroomColor
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StereoMeterPanel(
                            channelLabel = "Left",
                            dbLabel = "${(avgDb - 1.4f).toInt()} dBA",
                            needleAngle = needleAngleL,
                            accent = if (isSafe) safeGreen else warnAmber,
                            needleTint = theme.primaryAccent,
                            modifier = Modifier.weight(1f)
                        )
                        StereoMeterPanel(
                            channelLabel = "Right",
                            dbLabel = "${(avgDb + 1.2f).toInt()} dBA",
                            needleAngle = needleAngleR,
                            accent = if (isSafe) safeGreen else warnAmber,
                            needleTint = theme.primaryAccent,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    StudioHairlineDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val formattedHeadroom = if (headroomDb >= 0f) "+%.1f dB margin".format(headroomDb) else "%.1f dB over".format(headroomDb)
                        Column {
                            Text(formattedHeadroom, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
                            Text("Below 85 dBA guide", fontSize = 12.sp, color = Color.White.copy(alpha = 0.54f))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(safeHoursText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = safeGreen)
                            Text("Safe time left today", fontSize = 12.sp, color = Color.White.copy(alpha = 0.54f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Active Output Card
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = categoryColor
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StudioSectionHeader(title = "Active Output")
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = when (snapshot.transducerCategory) {
                                "BUILTIN_SPEAKER" -> Icons.Rounded.VolumeUp
                                "USB_EARPHONES", "USB_DAC" -> Icons.Rounded.GraphicEq
                                else -> Icons.Rounded.Headphones
                            },
                            contentDescription = null,
                            tint = categoryColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = categoryTitle,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = "$sanitizedProduct · $categoryTag",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.60f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Volume (${snapshot.volumeIndex} / ${snapshot.maxVolume})",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.64f)
                        )
                        Text(
                            text = "${((snapshot.volumeIndex.toFloat() / maxOf(1, snapshot.maxVolume)) * 100).toInt()}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.primaryAccent
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val totalSteps = snapshot.maxVolume.coerceIn(10, 25)
                    val activeSteps = snapshot.volumeIndex.coerceIn(0, totalSteps)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        for (i in 0 until totalSteps) {
                            val isActive = i < activeSteps
                            val cellColor = when {
                                !isActive -> Color.White.copy(alpha = 0.10f)
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

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = snapshot.acousticCouplingDescription,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.62f),
                        lineHeight = 19.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3. Daily Sound Dose Card
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = soundDoseColor
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StudioSectionHeader(title = "Daily Sound Dose")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(68.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(64.dp)) {
                                val strokeW = 6.dp.toPx()
                                val arcSize = size.minDimension - strokeW
                                val topLeftOffset = Offset(strokeW / 2f, strokeW / 2f)
                                drawArc(
                                    color = Color.White.copy(alpha = 0.10f),
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
                            Text(
                                text = "${(soundDoseFraction * 100).toInt()}%",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = soundDoseColor
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (soundDoseFraction < 0.6f) "Healthy range" else "Approaching daily limit",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = soundDoseColor
                            )
                            Text(
                                text = hearingState.safetyGuidance,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.66f),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 4. Output Profiles
            StudioSectionHeader(title = "Output Profiles")
            Spacer(modifier = Modifier.height(8.dp))
            StudioHairlineDivider()

            OutputProfileRow(
                title = "In-Ear Earbuds",
                rangeText = "58 – 104 dBA",
                desc = "Sealed ear canal transfers sound directly. Keep volume moderate.",
                isActive = snapshot.transducerCategory in listOf("BLUETOOTH_EARBUDS", "WIRED_EARPHONES", "USB_EARPHONES"),
                accentColor = safeGreen
            )
            StudioHairlineDivider()
            OutputProfileRow(
                title = "Over-Ear Headphones",
                rangeText = "54 – 96 dBA",
                desc = "Cushioned ear cups diffuse pressure naturally for longer comfort.",
                isActive = snapshot.transducerCategory in listOf("BLUETOOTH_HEADPHONES", "WIRED_HEADPHONES"),
                accentColor = theme.primaryAccent
            )
            StudioHairlineDivider()
            OutputProfileRow(
                title = "Speaker",
                rangeText = "44 – 82 dBA",
                desc = "Open air listening reduces ear fatigue during casual playback.",
                isActive = snapshot.transducerCategory == "BUILTIN_SPEAKER",
                accentColor = warnAmber
            )
            StudioHairlineDivider()

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun StereoMeterPanel(
    channelLabel: String,
    dbLabel: String,
    needleAngle: Float,
    accent: Color,
    needleTint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF07090E))
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
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.68f)
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
                .height(72.dp),
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
                    .height(50.dp)
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
                    .size(10.dp)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isActive) accentColor else Color(0xFFF8FAFC)
            )
            Text(
                text = if (isActive) "Active · $rangeText" else rangeText,
                fontSize = 12.sp,
                color = if (isActive) accentColor else Color.White.copy(alpha = 0.50f)
            )
        }
        Text(
            text = desc,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.58f),
            lineHeight = 17.sp
        )
    }
}
