package com.tunemind.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.voice.AgentState
import com.tunemind.app.domain.voice.PendingProposedTrack
import com.tunemind.app.ui.DopamineCapsuleState
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.TuneMindAiIntelligenceLoop
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.ui.theme.TuneMindTypography
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Center Screen: Living fluid orb voice companion anchored on the adaptive atmospheric canvas
 * with tactile dimensional mix and now-playing cards plus an interactive palette switcher.
 */
@Composable
fun PulseAgentScreen(
    agentState: AgentState,
    satietyPercentage: Int,
    currentTrackName: String?,
    artistName: String?,
    isMusicPlaying: Boolean = false,
    energy: Float,
    liveTranscript: String,
    flowAnalysis: String = "",
    recentTracks: List<TrackMemoryEntry> = emptyList(),
    dopamineCapsule: DopamineCapsuleState? = null,
    pendingProposedTrack: PendingProposedTrack? = null,
    onApproveProposedTrack: () -> Unit = {},
    onRejectProposedTrack: () -> Unit = {},
    onOrbTapped: () -> Unit,
    onLaunchDopamineCapsule: () -> Unit = {},
    onCycleAtmospherePalette: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    currentTrackPlayCount: Int = 0,
    autonomousDjEnabled: Boolean = false
) {
    val colors = LocalAdaptiveTheme.current
    val scrollState = rememberScrollState()
    val hasPendingApproval = pendingProposedTrack != null

    val activeAccent by animateColorAsState(
        targetValue = when {
            hasPendingApproval -> Color(0xFF10B981)
            agentState == AgentState.LISTENING -> Color(0xFF00F0FF)
            agentState == AgentState.THINKING -> Color(0xFF8A2BE2)
            agentState == AgentState.SPEAKING -> Color(0xFFFFB000)
            agentState == AgentState.ERROR -> Color(0xFFF59E0B)
            else -> colors.primaryAccent
        },
        animationSpec = tween(450),
        label = "agent_active_accent"
    )

    StudioAmbientBackground(
        accentOverride = activeAccent,
        isActivePulse = isMusicPlaying || agentState != AgentState.IDLE
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 112.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Clean Top Header with Interactive Theme Cycle + Stats & Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TuneMind",
                        color = Color(0xFFF8FAFC),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp
                    )
                    Text(
                        text = colors.activePalette.name,
                        color = activeAccent.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(onClick = onCycleAtmospherePalette) {
                        Icon(
                            imageVector = Icons.Rounded.Palette,
                            contentDescription = "Cycle Theme Atmosphere",
                            tint = activeAccent,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    IconButton(onClick = onOpenStats) {
                        Icon(
                            imageVector = Icons.Rounded.BarChart,
                            contentDescription = "Listening Stats",
                            tint = Color.White.copy(alpha = 0.82f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = Color.White.copy(alpha = 0.82f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Suggested Mix Dimensional Card
            if (dopamineCapsule != null && dopamineCapsule.tracks.isNotEmpty()) {
                SuggestedMixCard(
                    capsule = dopamineCapsule,
                    accentColor = activeAccent,
                    onClick = onLaunchDopamineCapsule
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Living Fluid Sphere + Lottie Neural Intelligence Loop Centerpiece
            val lottieSpeed = when {
                agentState == AgentState.SPEAKING -> 1.85f
                agentState == AgentState.THINKING -> 1.65f
                agentState == AgentState.LISTENING -> 1.45f
                isMusicPlaying -> 1.25f
                else -> 0.90f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(276.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOrbTapped
                    ),
                contentAlignment = Alignment.Center
            ) {
                TuneMindAiIntelligenceLoop(
                    modifier = Modifier.size(276.dp),
                    speed = lottieSpeed,
                    alpha = when {
                        agentState != AgentState.IDLE -> 0.92f
                        isMusicPlaying -> 0.78f
                        else -> 0.58f
                    }
                )
                TuneMindLivingOrb(
                    agentState = agentState,
                    isMusicPlaying = isMusicPlaying,
                    hasPendingApproval = hasPendingApproval,
                    energy = energy,
                    primaryAccent = activeAccent,
                    secondaryAccent = colors.secondaryAccent,
                    modifier = Modifier.size(252.dp)
                )
            }

            // 4. Up Next Approval Card
            AnimatedVisibility(
                visible = pendingProposedTrack != null,
                enter = fadeIn(tween(240)),
                exit = fadeOut(tween(180))
            ) {
                pendingProposedTrack?.let { proposed ->
                    Column {
                        ProposedTrackApprovalCard(
                            proposedTrack = proposed,
                            isListeningForVoiceApproval = agentState == AgentState.LISTENING,
                            onApprove = onApproveProposedTrack,
                            onReject = onRejectProposedTrack
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            }

            // 5. Voice Prompt & Live Response
            val stateHint = when {
                hasPendingApproval && agentState == AgentState.LISTENING -> "Say \"Yes\" to play or \"Skip\""
                hasPendingApproval -> "Waiting for your go-ahead"
                agentState == AgentState.IDLE -> "Tap orb or say \"Hey TuneMind\""
                agentState == AgentState.LISTENING -> "Listening…"
                agentState == AgentState.THINKING -> "Thinking…"
                agentState == AgentState.SPEAKING -> "Speaking"
                agentState == AgentState.ERROR -> "Tap to try again"
                else -> "Ready"
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOrbTapped() }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stateHint,
                    color = activeAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = liveTranscript.ifBlank { "Ask for a song, switch the mood, or start your daily mix." },
                    style = TuneMindTypography.bodyMedium,
                    fontSize = 15.sp,
                    color = if (liveTranscript.isNotBlank()) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 21.sp
                )
                if (flowAnalysis.isNotBlank()) {
                    Text(
                        text = flowAnalysis,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.52f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 6. Tactile Now-Playing Card
            NowPlayingCard(
                trackTitle = currentTrackName,
                artistName = artistName,
                isMusicPlaying = isMusicPlaying,
                playCount = currentTrackPlayCount,
                accentColor = activeAccent
            )
        }
    }
}

@Composable
private fun SuggestedMixCard(
    capsule: DopamineCapsuleState,
    accentColor: Color,
    onClick: () -> Unit
) {
    SoftGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        accentTint = accentColor,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${capsule.slotLabel} mix",
                    color = accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = capsule.tracks.take(3).joinToString(" · ") { it.title },
                    color = Color(0xFFF8FAFC).copy(alpha = 0.88f),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.14f))
                    .border(1.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Play",
                    color = Color(0xFFF8FAFC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun TuneMindLivingOrb(
    agentState: AgentState,
    isMusicPlaying: Boolean,
    hasPendingApproval: Boolean,
    energy: Float,
    primaryAccent: Color,
    secondaryAccent: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "living_orb")

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (agentState) {
                    AgentState.SPEAKING -> 1100
                    AgentState.LISTENING -> 1400
                    AgentState.THINKING -> 1700
                    else -> if (isMusicPlaying) 1900 else 3600
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_wave_phase"
    )

    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = when (agentState) {
            AgentState.SPEAKING -> 1.09f
            AgentState.LISTENING -> 1.07f
            AgentState.THINKING -> 1.03f
            else -> if (isMusicPlaying) 1.05f else 1.02f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (agentState) {
                    AgentState.SPEAKING -> 520
                    AgentState.LISTENING -> 780
                    AgentState.THINKING -> 1150
                    else -> if (isMusicPlaying) 880 else 2400
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_breath"
    )

    val rippleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (agentState == AgentState.LISTENING) 1500 else 2200,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_ripple"
    )

    val swirlAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (agentState == AgentState.THINKING) 2800 else 12000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_swirl"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension * 0.285f * breathScale
        val luminousWhite = Color.White

        val haloAlpha = when {
            agentState == AgentState.SPEAKING -> 0.34f
            agentState == AgentState.LISTENING -> 0.30f
            agentState == AgentState.THINKING -> 0.26f
            isMusicPlaying -> 0.24f
            else -> 0.16f
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryAccent.copy(alpha = haloAlpha),
                    secondaryAccent.copy(alpha = haloAlpha * 0.45f),
                    Color.Transparent
                ),
                center = center,
                radius = baseRadius * 2.15f
            ),
            radius = baseRadius * 2.15f,
            center = center
        )

        if (agentState == AgentState.LISTENING || isMusicPlaying || hasPendingApproval) {
            for (ringIdx in 0..1) {
                val p = (rippleProgress + ringIdx * 0.5f) % 1f
                val r = baseRadius * (1.06f + p * 0.62f)
                val alpha = (1f - p) * if (agentState == AgentState.LISTENING) 0.48f else 0.26f
                drawCircle(
                    color = primaryAccent.copy(alpha = alpha),
                    radius = r,
                    center = center,
                    style = Stroke(width = (2.2f * (1f - p * 0.5f)).dp.toPx())
                )
            }
        }

        val wobbleAmp = when (agentState) {
            AgentState.SPEAKING -> baseRadius * 0.085f
            AgentState.LISTENING -> baseRadius * 0.048f
            AgentState.THINKING -> baseRadius * 0.032f
            else -> if (isMusicPlaying) baseRadius * (0.034f * energy.coerceIn(0.6f, 1.1f)) else baseRadius * 0.012f
        }

        val sampleCount = 72
        val orbPath = Path()
        for (i in 0..sampleCount) {
            val theta = (i.toFloat() / sampleCount) * (2f * PI).toFloat()
            val harmonic = if (agentState == AgentState.SPEAKING) {
                0.55f * sin(theta * 3f + wavePhase * 2f) +
                    0.35f * sin(theta * 5f - wavePhase * 3f) +
                    0.20f * cos(theta * 2f + wavePhase)
            } else if (agentState == AgentState.THINKING) {
                0.65f * sin(theta * 2f + wavePhase) +
                    0.35f * cos(theta * 4f - wavePhase * 2f)
            } else {
                0.70f * sin(theta * 3f + wavePhase) +
                    0.30f * cos(theta * 2f - wavePhase)
            }
            val r = baseRadius + wobbleAmp * harmonic
            val x = center.x + r * cos(theta)
            val y = center.y + r * sin(theta)
            if (i == 0) orbPath.moveTo(x, y) else orbPath.lineTo(x, y)
        }
        orbPath.close()

        val coreTop = lerp(luminousWhite, primaryAccent, 0.42f)
        val coreMid = primaryAccent
        val coreDeep = lerp(secondaryAccent, Color(0xFF0A0D18), 0.55f)

        val focalOffset = Offset(
            center.x - baseRadius * 0.24f + cos(wavePhase) * baseRadius * 0.08f,
            center.y - baseRadius * 0.28f + sin(wavePhase) * baseRadius * 0.08f
        )

        drawPath(
            path = orbPath,
            brush = Brush.radialGradient(
                colors = listOf(
                    coreTop.copy(alpha = 0.96f),
                    coreMid.copy(alpha = 0.88f),
                    coreDeep.copy(alpha = 0.94f)
                ),
                center = focalOffset,
                radius = baseRadius * 1.45f
            )
        )

        val cloudCenter = Offset(
            center.x + sin(wavePhase) * baseRadius * 0.16f,
            center.y + cos(wavePhase * 0.8f) * baseRadius * 0.14f
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    luminousWhite.copy(alpha = if (agentState == AgentState.SPEAKING) 0.48f else 0.30f),
                    secondaryAccent.copy(alpha = 0.24f),
                    Color.Transparent
                ),
                center = cloudCenter,
                radius = baseRadius * 0.78f
            ),
            radius = baseRadius * 0.78f,
            center = cloudCenter
        )

        when (agentState) {
            AgentState.THINKING -> {
                rotate(degrees = swirlAngle, pivot = center) {
                    drawOval(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color.Transparent,
                                luminousWhite.copy(alpha = 0.88f),
                                primaryAccent.copy(alpha = 0.55f),
                                Color.Transparent
                            ),
                            center = center
                        ),
                        topLeft = Offset(center.x - baseRadius * 0.78f, center.y - baseRadius * 0.36f),
                        size = Size(baseRadius * 1.56f, baseRadius * 0.72f),
                        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                rotate(degrees = -swirlAngle * 1.3f + 60f, pivot = center) {
                    drawOval(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color.Transparent,
                                secondaryAccent.copy(alpha = 0.82f),
                                luminousWhite.copy(alpha = 0.45f),
                                Color.Transparent
                            ),
                            center = center
                        ),
                        topLeft = Offset(center.x - baseRadius * 0.72f, center.y - baseRadius * 0.32f),
                        size = Size(baseRadius * 1.44f, baseRadius * 0.64f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            AgentState.SPEAKING -> {
                val innerVoicePath = Path()
                val widthSpan = baseRadius * 1.05f
                val segments = 32
                for (i in 0..segments) {
                    val t = i.toFloat() / segments
                    val x = center.x - widthSpan / 2f + widthSpan * t
                    val envelope = sin(t * PI).toFloat()
                    val y = center.y + sin(t * 4f * PI.toFloat() + wavePhase * 2f) * (baseRadius * 0.22f) * envelope
                    if (i == 0) innerVoicePath.moveTo(x, y) else innerVoicePath.lineTo(x, y)
                }
                drawPath(
                    path = innerVoicePath,
                    color = luminousWhite.copy(alpha = 0.90f),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            AgentState.LISTENING -> {
                drawCircle(
                    color = luminousWhite.copy(alpha = 0.52f),
                    radius = baseRadius * (0.48f + 0.08f * sin(wavePhase)),
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            else -> {
                if (isMusicPlaying) {
                    rotate(degrees = swirlAngle * 0.6f, pivot = center) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    luminousWhite.copy(alpha = 0.72f),
                                    primaryAccent.copy(alpha = 0.65f),
                                    Color.Transparent
                                ),
                                center = center
                            ),
                            startAngle = 0f,
                            sweepAngle = 220f,
                            useCenter = false,
                            topLeft = Offset(center.x - baseRadius * 1.18f, center.y - baseRadius * 1.18f),
                            size = Size(baseRadius * 2.36f, baseRadius * 2.36f),
                            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }

        drawPath(
            path = orbPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    luminousWhite.copy(alpha = 0.55f),
                    primaryAccent.copy(alpha = 0.35f),
                    Color.Transparent
                )
            ),
            style = Stroke(width = 1.4.dp.toPx())
        )
    }
}

@Composable
private fun ProposedTrackApprovalCard(
    proposedTrack: PendingProposedTrack,
    isListeningForVoiceApproval: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val approvalAccent = Color(0xFF10B981)

    SoftGlassCard(
        modifier = Modifier.fillMaxWidth(),
        accentTint = approvalAccent
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (isListeningForVoiceApproval) "Up next · Say \"Yes\" to play" else "Up next",
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = approvalAccent
            )

            Column {
                Text(
                    text = proposedTrack.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF8FAFC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val cleanArtist = proposedTrack.artist.takeIf {
                    it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true)
                } ?: "Picked for right now"
                Text(
                    text = cleanArtist,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onReject,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(
                        text = "Skip",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF8FAFC),
                        contentColor = Color(0xFF0D0D0D)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                ) {
                    Text(
                        text = "Play",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    trackTitle: String?,
    artistName: String?,
    isMusicPlaying: Boolean,
    playCount: Int,
    accentColor: Color
) {
    SoftGlassCard(
        modifier = Modifier.fillMaxWidth(),
        accentTint = if (isMusicPlaying) Color(0xFF10B981) else accentColor,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (isMusicPlaying) Color(0xFF10B981).copy(alpha = 0.18f)
                        else accentColor.copy(alpha = 0.14f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMusicPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (isMusicPlaying) Color(0xFF10B981) else accentColor,
                    modifier = Modifier.size(19.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trackTitle ?: "Nothing playing",
                    color = Color(0xFFF8FAFC),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildString {
                    append(artistName ?: "Start a song or ask TuneMind")
                    if (!trackTitle.isNullOrBlank() && playCount > 0) {
                        append(" · $playCount ${if (playCount == 1) "play" else "plays"}")
                    }
                    if (isMusicPlaying) append(" · Playing")
                }
                Text(
                    text = meta,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
