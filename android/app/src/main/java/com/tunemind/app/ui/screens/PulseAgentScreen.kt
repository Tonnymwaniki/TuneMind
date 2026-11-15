package com.tunemind.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.ui.theme.TuneMindTypography
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Center Screen: Quiet dark-canvas voice companion anchored by the living fluid orb,
 * unboxed live song strip, and hairline daily mix row.
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
            agentState == AgentState.LISTENING -> Color(0xFF00E5FF)
            agentState == AgentState.THINKING -> Color(0xFFA855F7)
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
                .padding(top = 10.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Quiet Top Header (only Stats & Settings icon buttons — zero decorative pills)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TuneMind",
                    color = Color(0xFFF8FAFC),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenStats) {
                        Icon(
                            imageVector = Icons.Rounded.BarChart,
                            contentDescription = "Listening Stats",
                            tint = Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // 2. Unboxed Suggested Mix Strip
            if (dopamineCapsule != null && dopamineCapsule.tracks.isNotEmpty()) {
                SuggestedMixHairlineRow(
                    capsule = dopamineCapsule,
                    accentColor = activeAccent,
                    onClick = onLaunchDopamineCapsule
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Living Fluid Sphere Centerpiece
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
                TuneMindLivingOrb(
                    agentState = agentState,
                    isMusicPlaying = isMusicPlaying,
                    hasPendingApproval = hasPendingApproval,
                    energy = energy,
                    primaryAccent = activeAccent,
                    secondaryAccent = colors.secondaryAccent,
                    modifier = Modifier.size(264.dp)
                )
            }

            // 4. Up Next Approval Card (only prominent card when waiting for user action)
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
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // 5. Open-Canvas Voice Prompt & Live Response
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
                    .clip(RoundedCornerShape(14.dp))
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
                    color = if (liveTranscript.isNotBlank()) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.62f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 21.sp
                )
                if (flowAnalysis.isNotBlank()) {
                    Text(
                        text = flowAnalysis,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.48f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            StudioHairlineDivider()

            // 6. Unboxed Now-Playing Strip
            NowPlayingStrip(
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
private fun SuggestedMixHairlineRow(
    capsule: DopamineCapsuleState,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${capsule.slotLabel} mix",
                    color = accentColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = capsule.tracks.take(3).joinToString(" · ") { it.title },
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Play",
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.56f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        StudioHairlineDivider()
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

    SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
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
                        contentColor = Color(0xFF07090E)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .height(44.dp)
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
private fun NowPlayingStrip(
    trackTitle: String?,
    artistName: String?,
    isMusicPlaying: Boolean,
    playCount: Int,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (isMusicPlaying) Color(0xFF10B981).copy(alpha = 0.14f)
                    else accentColor.copy(alpha = 0.12f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isMusicPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = if (isMusicPlaying) Color(0xFF10B981) else accentColor,
                modifier = Modifier.size(18.dp)
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
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
