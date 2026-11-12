package com.tunemind.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.voice.AgentState
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.ui.theme.MusicalMood
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun PulseAgentScreen(
    agentState: AgentState,
    satietyPercentage: Int,
    currentTrackName: String?,
    artistName: String?,
    isMusicPlaying: Boolean = false,
    energy: Float = 0.78f,
    liveTranscript: String = "",
    flowAnalysis: String = "",
    recentTracks: List<TrackMemoryEntry> = emptyList(),
    dopamineCapsule: com.tunemind.app.ui.DopamineCapsuleState? = null,
    onOrbTapped: () -> Unit,
    onLaunchDopamineCapsule: () -> Unit = {},
    onCycleAtmospherePalette: () -> Unit = {}
) {
    val theme = LocalAdaptiveTheme.current

    // Rotate through fresh tracks in the top Studio Pill when music is paused/standby so it is NEVER stuck on a single hardcoded track
    var tickerIndex by remember { mutableStateOf(0) }
    LaunchedEffect(recentTracks.size, isMusicPlaying) {
        if (!isMusicPlaying && recentTracks.size > 1) {
            while (true) {
                delay(5500L)
                tickerIndex = (tickerIndex + 1) % recentTracks.size
            }
        }
    }

    val rotatingCandidate = remember(recentTracks, tickerIndex) {
        recentTracks.getOrNull(tickerIndex % maxOf(1, recentTracks.size))
    }

    // Dynamic Core & Aura Colors driven by Agent State + Song Mood + Adaptive Theme
    val targetOrbColor = when (agentState) {
        AgentState.LISTENING -> Color(0xFF00E5FF) // Acoustic Sonar Cyan
        AgentState.THINKING -> Color(0xFFA855F7)  // Neural Synapse Violet
        AgentState.SPEAKING -> Color(0xFFFFB000)  // Vocal Gold-Amber
        AgentState.ERROR -> Color(0xFFFF2A55)     // Crimson Alert
        AgentState.IDLE -> when {
            isMusicPlaying -> when (theme.musicMood) {
                MusicalMood.UPLIFTING -> Color(0xFFFFB000)
                MusicalMood.ENERGETIC -> Color(0xFF10B981)
                MusicalMood.CALM -> Color(0xFF00E5FF)
                MusicalMood.REFLECTIVE -> Color(0xFFA855F7)
                MusicalMood.INTENSE -> Color(0xFFFF2A55)
                MusicalMood.UNKNOWN -> theme.primaryAccent
            }
            else -> theme.primaryAccent
        }
    }

    val animatedOrbColor by animateColorAsState(
        targetValue = targetOrbColor,
        animationSpec = tween(550),
        label = "StudioOrbColor"
    )

    val secondaryGlowColor by animateColorAsState(
        targetValue = when (agentState) {
            AgentState.SPEAKING -> Color(0xFF00E5FF)
            AgentState.LISTENING -> Color(0xFF10B981)
            AgentState.THINKING -> Color(0xFF00E5FF)
            else -> theme.secondaryAccent
        },
        animationSpec = tween(550),
        label = "SecondaryGlowColor"
    )

    // Continuous Physics & Harmonic Phase Animations
    val infiniteTransition = rememberInfiniteTransition(label = "StudioMascotPhysics")

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when {
                    agentState == AgentState.SPEAKING -> 520
                    agentState == AgentState.LISTENING -> 720
                    isMusicPlaying -> 900
                    else -> 2200
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavePhase"
    )

    val orbitalRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (agentState) {
                    AgentState.THINKING -> 2400
                    AgentState.SPEAKING -> 4200
                    AgentState.LISTENING -> 5000
                    else -> if (isMusicPlaying) 6500 else 12000
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "OrbitalRotation"
    )

    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = when {
            agentState == AgentState.SPEAKING -> 1.10f
            agentState == AgentState.LISTENING -> 1.07f
            agentState == AgentState.THINKING -> 1.06f
            isMusicPlaying -> 1.05f
            else -> 1.01f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when {
                    agentState == AgentState.SPEAKING -> 420
                    agentState == AgentState.LISTENING -> 650
                    isMusicPlaying -> 760
                    else -> 1800
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BreatheScale"
    )

    // Natural Mascot Eye Blink Timer
    var isBlinking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3800L)
            isBlinking = true
            delay(140L)
            isBlinking = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        theme.primaryBackground,
                        theme.secondaryBackground,
                        theme.recessedBackground
                    )
                )
            )
    ) {
        // Studio Atmosphere Dual Radial Spotlights (Adapts to Song Mood & Agent State)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-50).dp)
                .size(280.dp)
                .blur(85.dp)
                .background(animatedOrbColor.copy(alpha = 0.22f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-90).dp)
                .size(240.dp)
                .blur(90.dp)
                .background(secondaryGlowColor.copy(alpha = 0.16f), CircleShape)
        )

        // Non-Overlapping Full-Screen Studio Column (94dp bottom padding reserves space for FloatingGlassDock)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 94.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. TOP: LIVE STUDIO TELEMETRY & MOOD BAR (Never hardcoded; reacts to Agent State, Live Playback, or Rotating Fresh Crate)
            LiveStudioTelemetryHeader(
                agentState = agentState,
                isMusicPlaying = isMusicPlaying,
                currentTrackName = currentTrackName,
                artistName = artistName,
                rotatingCandidate = rotatingCandidate,
                moodLabel = theme.musicMood.label,
                paletteName = theme.activePalette.name,
                primaryColor = animatedOrbColor,
                secondaryColor = secondaryGlowColor,
                cardSurface = theme.cardSurface,
                wavePhase = wavePhase,
                onCycleAtmospherePalette = onCycleAtmospherePalette
            )

            // 2. CENTER: FULL-SCREEN LIVING STUDIO AI MASCOT & REACTIVE SONIC ENTITY
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(274.dp)
                            .bouncyClickable { onOrbTapped() }
                    ) {
                        // Ambient Studio Glow Behind Mascot
                        Box(
                            modifier = Modifier
                                .size(210.dp * breatheScale)
                                .blur(58.dp)
                                .background(animatedOrbColor.copy(alpha = 0.32f), CircleShape)
                        )

                        // Custom Multi-Layer Studio Mascot & Frequency Spectrum Canvas
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val maxRadius = size.minDimension / 2f

                            // LAYER A: Outer Orbital Studio Ring & Satiety/Dopamine Arc
                            drawArc(
                                color = Color.White.copy(alpha = 0.07f),
                                startAngle = 135f,
                                sweepAngle = 270f,
                                useCenter = false,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                                topLeft = Offset(center.x - maxRadius * 0.92f, center.y - maxRadius * 0.92f),
                                size = Size(maxRadius * 1.84f, maxRadius * 1.84f)
                            )
                            val freshnessSweep = 270f * ((100 - satietyPercentage).coerceIn(12, 98) / 100f)
                            drawArc(
                                brush = Brush.sweepGradient(
                                    colors = listOf(animatedOrbColor, secondaryGlowColor, animatedOrbColor)
                                ),
                                startAngle = 135f,
                                sweepAngle = freshnessSweep,
                                useCenter = false,
                                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                                topLeft = Offset(center.x - maxRadius * 0.92f, center.y - maxRadius * 0.92f),
                                size = Size(maxRadius * 1.84f, maxRadius * 1.84f)
                            )

                            // LAYER B: Rotating Gyroscope Studio Halo when Thinking or Playing Music
                            rotate(degrees = orbitalRotation, pivot = center) {
                                val gyroRadius = maxRadius * 0.78f
                                for (i in 0 until 3) {
                                    drawArc(
                                        color = if (i % 2 == 0) animatedOrbColor.copy(alpha = 0.45f)
                                                else secondaryGlowColor.copy(alpha = 0.35f),
                                        startAngle = i * 120f,
                                        sweepAngle = if (agentState == AgentState.THINKING) 75f else 48f,
                                        useCenter = false,
                                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                                        topLeft = Offset(center.x - gyroRadius, center.y - gyroRadius),
                                        size = Size(gyroRadius * 2f, gyroRadius * 2f)
                                    )
                                }
                            }

                            // LAYER C: 36-Petal Radial Vocal & Musical Spectrum Equalizer
                            val petalCount = 36
                            val baseRingRadius = maxRadius * 0.54f * breatheScale
                            for (i in 0 until petalCount) {
                                val angleRad = (i * (360f / petalCount)) * (PI / 180f)
                                val dynamicAmp = when (agentState) {
                                    AgentState.SPEAKING -> {
                                        val v1 = sin(wavePhase * 2.2f + i * 0.55f).toFloat()
                                        val v2 = cos(wavePhase * 1.4f - i * 0.35f).toFloat()
                                        0.35f + 0.65f * ((v1 * v2 + 1f) / 2f)
                                    }
                                    AgentState.LISTENING -> {
                                        0.25f + 0.45f * ((sin(wavePhase * 1.8f + i * 0.4f).toFloat() + 1f) / 2f)
                                    }
                                    AgentState.THINKING -> {
                                        0.20f + 0.55f * ((sin(wavePhase * 3.0f + i * 0.9f).toFloat() + 1f) / 2f)
                                    }
                                    else -> {
                                        if (isMusicPlaying) {
                                            val speedMul = when (theme.musicMood) {
                                                MusicalMood.ENERGETIC, MusicalMood.INTENSE -> 2.4f
                                                MusicalMood.UPLIFTING -> 1.8f
                                                else -> 1.2f
                                            }
                                            0.22f + 0.58f * ((sin(wavePhase * speedMul + i * 0.65f).toFloat() + 1f) / 2f)
                                        } else {
                                            0.12f + 0.14f * ((sin(wavePhase + i * 0.3f).toFloat() + 1f) / 2f)
                                        }
                                    }
                                }

                                val barLen = maxRadius * 0.22f * dynamicAmp
                                val startR = baseRingRadius + 4.dp.toPx()
                                val endR = startR + barLen
                                val sx = center.x + (startR * cos(angleRad)).toFloat()
                                val sy = center.y + (startR * sin(angleRad)).toFloat()
                                val ex = center.x + (endR * cos(angleRad)).toFloat()
                                val ey = center.y + (endR * sin(angleRad)).toFloat()

                                drawLine(
                                    color = if (i % 2 == 0) animatedOrbColor.copy(alpha = 0.85f)
                                            else secondaryGlowColor.copy(alpha = 0.75f),
                                    start = Offset(sx, sy),
                                    end = Offset(ex, ey),
                                    strokeWidth = if (agentState == AgentState.SPEAKING) 4.dp.toPx() else 3.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }

                            // LAYER D: 3D Studio Mascot Core Sphere
                            val coreRadius = baseRingRadius
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        animatedOrbColor.copy(alpha = 0.92f),
                                        secondaryGlowColor.copy(alpha = 0.78f),
                                        Color(0xFF090A14)
                                    ),
                                    center = Offset(center.x - coreRadius * 0.22f, center.y - coreRadius * 0.22f),
                                    radius = coreRadius * 1.15f
                                ),
                                radius = coreRadius,
                                center = center
                            )

                            // Inner Obsidian Studio Visor for the Mascot Face
                            val visorWidth = coreRadius * 1.32f
                            val visorHeight = coreRadius * 0.86f
                            drawRoundRect(
                                color = Color(0xFF070811).copy(alpha = 0.86f),
                                topLeft = Offset(center.x - visorWidth / 2f, center.y - visorHeight / 2f),
                                size = Size(visorWidth, visorHeight),
                                cornerRadius = CornerRadius(visorHeight / 2f, visorHeight / 2f)
                            )
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.18f),
                                topLeft = Offset(center.x - visorWidth / 2f, center.y - visorHeight / 2f),
                                size = Size(visorWidth, visorHeight),
                                cornerRadius = CornerRadius(visorHeight / 2f, visorHeight / 2f),
                                style = Stroke(width = 1.5.dp.toPx())
                            )

                            // LAYER E: Expressive Studio Mascot Eyes
                            val eyeOffsetX = visorWidth * 0.23f
                            val eyeY = center.y - visorHeight * 0.12f
                            val eyeWidth = 14.dp.toPx()
                            val eyeHeight = when {
                                isBlinking -> 2.5.dp.toPx()
                                agentState == AgentState.SPEAKING -> 13.dp.toPx()
                                agentState == AgentState.LISTENING -> 16.dp.toPx()
                                isMusicPlaying -> 12.dp.toPx()
                                else -> 11.dp.toPx()
                            }

                            listOf(-1f, 1f).forEach { dir ->
                                val ex = center.x + dir * eyeOffsetX
                                drawRoundRect(
                                    color = Color.White,
                                    topLeft = Offset(ex - eyeWidth / 2f, eyeY - eyeHeight / 2f),
                                    size = Size(eyeWidth, eyeHeight),
                                    cornerRadius = CornerRadius(eyeWidth / 2f, eyeWidth / 2f)
                                )
                                drawCircle(
                                    color = animatedOrbColor.copy(alpha = 0.55f),
                                    radius = eyeWidth * 0.75f,
                                    center = Offset(ex, eyeY)
                                )
                            }

                            // LAYER F: Expressive Vocal Mouth / Studio Waveform inside Visor
                            val mouthY = center.y + visorHeight * 0.22f
                            val mouthWidth = visorWidth * 0.52f
                            when (agentState) {
                                AgentState.SPEAKING -> {
                                    val path = Path()
                                    val segments = 20
                                    val startX = center.x - mouthWidth / 2f
                                    path.moveTo(startX, mouthY)
                                    for (s in 0..segments) {
                                        val frac = s / segments.toFloat()
                                        val x = startX + frac * mouthWidth
                                        val envelope = sin(frac * PI).toFloat()
                                        val wave = sin(wavePhase * 3f + frac * 10f).toFloat() * 8.dp.toPx() * envelope
                                        path.lineTo(x, mouthY + wave)
                                    }
                                    drawPath(
                                        path = path,
                                        color = Color(0xFFFFB000),
                                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                    val path2 = Path()
                                    path2.moveTo(startX, mouthY)
                                    for (s in 0..segments) {
                                        val frac = s / segments.toFloat()
                                        val x = startX + frac * mouthWidth
                                        val envelope = sin(frac * PI).toFloat()
                                        val wave = -sin(wavePhase * 3f + frac * 10f).toFloat() * 5.dp.toPx() * envelope
                                        path2.lineTo(x, mouthY + wave)
                                    }
                                    drawPath(
                                        path = path2,
                                        color = Color.White.copy(alpha = 0.85f),
                                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                }
                                AgentState.LISTENING -> {
                                    val openR = (4.dp.toPx() + 4.dp.toPx() * ((sin(wavePhase * 2f).toFloat() + 1f) / 2f))
                                    drawRoundRect(
                                        color = Color(0xFF00E5FF),
                                        topLeft = Offset(center.x - openR * 1.6f, mouthY - openR),
                                        size = Size(openR * 3.2f, openR * 2f),
                                        cornerRadius = CornerRadius(openR, openR),
                                        style = Stroke(width = 2.5.dp.toPx())
                                    )
                                }
                                else -> {
                                    if (isMusicPlaying) {
                                        val bars = 5
                                        val bWidth = 3.dp.toPx()
                                        val bGap = 4.dp.toPx()
                                        val totalW = bars * bWidth + (bars - 1) * bGap
                                        val bStart = center.x - totalW / 2f
                                        for (b in 0 until bars) {
                                            val bh = (4.dp.toPx() + 8.dp.toPx() * ((sin(wavePhase * 2.2f + b * 1.1f).toFloat() + 1f) / 2f))
                                            drawRoundRect(
                                                color = animatedOrbColor,
                                                topLeft = Offset(bStart + b * (bWidth + bGap), mouthY - bh / 2f),
                                                size = Size(bWidth, bh),
                                                cornerRadius = CornerRadius(bWidth / 2f, bWidth / 2f)
                                            )
                                        }
                                    } else {
                                        drawArc(
                                            color = animatedOrbColor.copy(alpha = 0.9f),
                                            startAngle = 20f,
                                            sweepAngle = 140f,
                                            useCenter = false,
                                            topLeft = Offset(center.x - 14.dp.toPx(), mouthY - 8.dp.toPx()),
                                            size = Size(28.dp.toPx(), 14.dp.toPx()),
                                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Dynamic Studio Mode Pill Caption underneath Mascot
                    val modeBadgeText = when (agentState) {
                        AgentState.LISTENING -> "🎙 LISTENING TO YOUR VOICE • SPEAK FREELY"
                        AgentState.THINKING -> "🧠 CLAUDE SYNTHESIZING DOPAMINE & MOOD..."
                        AgentState.SPEAKING -> "🔊 TALKING TO YOU • TAP MASCOT TO INTERRUPT"
                        AgentState.ERROR -> "⚠️ TAP MASCOT TO RECONNECT VOICE"
                        AgentState.IDLE -> if (isMusicPlaying) {
                            "🎵 STUDIO VISUALIZER LIVE • TAP MASCOT TO TALK"
                        } else {
                            "✦ STUDIO AGENT READY • TAP MASCOT TO TALK"
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(animatedOrbColor.copy(alpha = 0.14f))
                            .border(1.dp, animatedOrbColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = modeBadgeText,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp
                        )
                    }
                }
            }

            // 3. BOTTOM STACK (ABOVE FLOATING DOCK — GUARANTEED ZERO COLLISION):
            //    PROACTIVE DOPAMINE CAPSULE PILL + STUDIO DIALOGUE & AI REASONING CONSOLE
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (dopamineCapsule != null && dopamineCapsule.tracks.isNotEmpty()) {
                    DopamineCapsuleBriefingPill(
                        capsule = dopamineCapsule,
                        primaryColor = animatedOrbColor,
                        secondaryColor = secondaryGlowColor,
                        cardSurface = theme.cardSurface,
                        onLaunchCapsule = onLaunchDopamineCapsule
                    )
                }

                StudioDialogueAndReasoningConsole(
                    agentState = agentState,
                    liveTranscript = liveTranscript,
                    flowAnalysis = flowAnalysis,
                    moodLabel = theme.musicMood.label,
                    freshnessPercent = (100 - satietyPercentage).coerceIn(12, 98),
                    primaryColor = animatedOrbColor,
                    secondaryColor = secondaryGlowColor,
                    cardSurface = theme.cardSurface
                )
            }
        }
    }
}

@Composable
private fun DopamineCapsuleBriefingPill(
    capsule: com.tunemind.app.ui.DopamineCapsuleState,
    primaryColor: Color,
    secondaryColor: Color,
    cardSurface: Color,
    onLaunchCapsule: () -> Unit
) {
    val lead = capsule.tracks.firstOrNull() ?: return
    val pillShape = RoundedCornerShape(18.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(pillShape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.18f),
                        cardSurface.copy(alpha = 0.88f),
                        secondaryColor.copy(alpha = 0.16f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.65f),
                        secondaryColor.copy(alpha = 0.45f)
                    )
                ),
                shape = pillShape
            )
            .bouncyClickable { onLaunchCapsule() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = 0.22f))
                .border(1.dp, primaryColor.copy(alpha = 0.6f), CircleShape)
        ) {
            Text(
                text = "⚡",
                fontSize = 14.sp
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "✦ ${capsule.slotLabel} CAPSULE STORED (${capsule.tracks.size} TRACKS)",
                color = primaryColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.1.sp,
                maxLines = 1
            )
            Text(
                text = "Lead: ${lead.title} • ${lead.freshnessPercent}% Freshness",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(primaryColor.copy(alpha = 0.22f))
                .border(1.dp, primaryColor.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = "BRIEF ME",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.9.sp
            )
        }
    }
}

@Composable
private fun LiveStudioTelemetryHeader(
    agentState: AgentState,
    isMusicPlaying: Boolean,
    currentTrackName: String?,
    artistName: String?,
    rotatingCandidate: TrackMemoryEntry?,
    moodLabel: String,
    paletteName: String,
    primaryColor: Color,
    secondaryColor: Color,
    cardSurface: Color,
    wavePhase: Float,
    onCycleAtmospherePalette: () -> Unit
) {
    val headerShape = RoundedCornerShape(26.dp)

    // Determine live header status so it is NEVER stuck on a stale hardcoded track
    val statusBadge: String
    val headlineText: String
    val subtitleText: String

    when (agentState) {
        AgentState.LISTENING -> {
            statusBadge = "🎙 VOICE CAPTURE LIVE"
            headlineText = "Listening to your request..."
            subtitleText = "Studio microphone active • High-clarity 16kHz stream"
        }
        AgentState.THINKING -> {
            statusBadge = "🧠 NEURAL REASONING"
            headlineText = "Matching your Taste Genome..."
            subtitleText = "Evaluating dopamine freshness & $moodLabel flow"
        }
        AgentState.SPEAKING -> {
            statusBadge = "🔊 AGENT SPEAKING"
            headlineText = currentTrackName?.takeIf { it.isNotBlank() && it != "Deep Flow State" }
                ?: "Proposing Next Dopamine Hit"
            subtitleText = "Voice dialogue active • Tap mascot anytime to interrupt"
        }
        else -> {
            if (isMusicPlaying && !currentTrackName.isNullOrBlank() && currentTrackName != "Deep Flow State") {
                statusBadge = "● LIVE IN STUDIO"
                headlineText = currentTrackName
                subtitleText = "${artistName ?: "Active Player"} • $moodLabel"
            } else if (rotatingCandidate != null && !rotatingCandidate.title.isNullOrBlank()) {
                val plays = maxOf(1, rotatingCandidate.distinctAppearanceCount)
                val fresh = (100 - minOf(92, plays * 18)).coerceIn(20, 98)
                statusBadge = "✦ PEAK DOPAMINE QUEUE"
                headlineText = rotatingCandidate.title ?: "Ready for Flow"
                subtitleText = "${rotatingCandidate.artist ?: "Your Rotation"} • $fresh% Fresh in Vault"
            } else {
                statusBadge = "✦ STUDIO STANDBY"
                headlineText = "Autonomous Sonic Intelligence"
                subtitleText = "$paletteName Atmosphere • $moodLabel"
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(headerShape)
            .background(cardSurface.copy(alpha = 0.78f))
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.55f),
                        secondaryColor.copy(alpha = 0.35f)
                    )
                ),
                shape = headerShape
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left: Live Animated 4-Bar Studio Equalizer Badge
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = 0.16f))
                .border(1.dp, primaryColor.copy(alpha = 0.45f), CircleShape)
        ) {
            Canvas(modifier = Modifier.size(18.dp, 16.dp)) {
                val barW = 3.dp.toPx()
                val gap = 2.dp.toPx()
                for (i in 0 until 4) {
                    val active = agentState != AgentState.IDLE || isMusicPlaying
                    val frac = if (active) {
                        0.35f + 0.65f * ((sin(wavePhase * 2f + i * 1.1f).toFloat() + 1f) / 2f)
                    } else {
                        0.35f + (i % 2) * 0.25f
                    }
                    val h = size.height * frac
                    drawRoundRect(
                        color = if (i % 2 == 0) primaryColor else secondaryColor,
                        topLeft = Offset(i * (barW + gap), (size.height - h) / 2f),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                    )
                }
            }
        }

        // Center: Live Changing Track / Agent Telemetry
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = statusBadge,
                color = primaryColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.4.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = headlineText,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitleText,
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Right: 1-Tap Studio Atmosphere Switcher Pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(primaryColor.copy(alpha = 0.16f))
                .border(1.dp, primaryColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .bouncyClickable { onCycleAtmospherePalette() }
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "✦ MOOD",
                    color = primaryColor,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = paletteName.split(" ").firstOrNull()?.uppercase() ?: "STUDIO",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StudioDialogueAndReasoningConsole(
    agentState: AgentState,
    liveTranscript: String,
    flowAnalysis: String,
    moodLabel: String,
    freshnessPercent: Int,
    primaryColor: Color,
    secondaryColor: Color,
    cardSurface: Color
) {
    val consoleShape = RoundedCornerShape(24.dp)
    val displayDialogue = liveTranscript.ifBlank {
        "Tap the Studio Mascot and say \"Play me a song\" or \"Change the vibe\"."
    }
    val displayReasoning = flowAnalysis.ifBlank {
        "Mood DNA: $moodLabel • $freshnessPercent% Dopamine Readiness"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(consoleShape)
            .background(cardSurface.copy(alpha = 0.82f))
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.50f),
                        secondaryColor.copy(alpha = 0.35f)
                    )
                ),
                shape = consoleShape
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Top Console Strip: Live Dialogue Status + Mood Tag
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                )
                Text(
                    text = if (agentState == AgentState.IDLE) "STUDIO VOICE CONSOLE" else "LIVE • ${agentState.name}",
                    color = primaryColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.4.sp
                )
            }

            Text(
                text = "$moodLabel • $freshnessPercent% Fresh",
                color = secondaryColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Spoken Dialogue / User Transcript (Clean 2-3 line cap so it never overflows)
        Text(
            text = "\"$displayDialogue\"",
            color = Color.White.copy(alpha = 0.95f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start
        )

        // AI Reasoning Sub-Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "🧠 AI:",
                color = primaryColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = displayReasoning,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
