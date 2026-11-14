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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.ui.theme.TuneMindPalettes
import com.tunemind.app.ui.theme.TuneMindTypography
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val StudioGlassCard = Color(0xFF0C0F18)
private val StudioGlassInner = Color(0xFF07090F)

/**
 * Screen 2 (Center Default): Minimalist dark-studio voice companion with a fluid living sphere
 * inspired by modern conversational voice interfaces.
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
                .padding(top = 12.dp, bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Minimal Top Studio Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(StudioGlassCard.copy(alpha = 0.88f))
                        .border(1.dp, activeAccent.copy(alpha = 0.25f), RoundedCornerShape(50))
                        .clickable { onCycleAtmospherePalette() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = activeAccent,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = colors.activePalette.name,
                        style = TuneMindTypography.labelMedium,
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val fatigueColor = when {
                        satietyPercentage >= 75 -> Color(0xFFFF4D6D)
                        satietyPercentage >= 50 -> Color(0xFFFFB000)
                        else -> Color(0xFF10B981)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(StudioGlassCard.copy(alpha = 0.88f))
                            .border(1.dp, fatigueColor.copy(alpha = 0.30f), RoundedCornerShape(50))
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(fatigueColor)
                        )
                        Text(
                            text = if (autonomousDjEnabled) "DJ • $satietyPercentage%" else "$satietyPercentage% Fatigue",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            color = fatigueColor
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(StudioGlassCard.copy(alpha = 0.88f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                            .clickable { onOpenSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = Color.White.copy(alpha = 0.88f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Compact Mix Suggestion Banner
            if (dopamineCapsule != null && dopamineCapsule.tracks.isNotEmpty()) {
                DopamineCapsuleBanner(
                    capsule = dopamineCapsule,
                    accentColor = activeAccent,
                    onClick = onLaunchDopamineCapsule
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 3. Centerpiece: Minimal ChatGPT-Voice-Mode Inspired Living Sphere
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(292.dp)
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
                    modifier = Modifier.size(276.dp)
                )
            }

            // 4. Suggested Song Approval Bar
            AnimatedVisibility(
                visible = pendingProposedTrack != null,
                enter = fadeIn(tween(250)),
                exit = fadeOut(tween(200))
            ) {
                pendingProposedTrack?.let { proposed ->
                    ProposedTrackApprovalCard(
                        proposedTrack = proposed,
                        isListeningForVoiceApproval = agentState == AgentState.LISTENING,
                        onApprove = onApproveProposedTrack,
                        onReject = onRejectProposedTrack
                    )
                }
            }

            if (pendingProposedTrack != null) {
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 5. Voice Dialogue Card
            VoiceDialogueConsole(
                agentState = agentState,
                hasPendingApproval = hasPendingApproval,
                liveTranscript = liveTranscript,
                flowAnalysis = flowAnalysis,
                accentColor = activeAccent,
                onTapSpeak = onOrbTapped
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Now-Playing Mini Card
            NowPlayingMiniBar(
                trackTitle = currentTrackName,
                artistName = artistName,
                isMusicPlaying = isMusicPlaying,
                accentColor = activeAccent
            )
        }
    }
}

/**
 * Minimal ChatGPT-Voice-Mode inspired living sphere:
 * Distinctly animates Idle, Music Playing, Listening, Thinking, and Speaking states
 * with fluid radial wave geometry and clean studio lighting.
 */
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

    // Smooth continuous wave phase used across morphing, talking, and music pulse
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

    // Secondary organic breath
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

    // Outward ripple expansion progress (for Listening & Music Playing)
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

    // Orbital rotation angle (mainly visible in Thinking swirl & subtle sheen)
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

        // 1. Soft atmospheric studio halo around the orb
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

        // 2. Listening or Music-Playing expanding ripple rings
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

        // 3. Organic Fluid Morphing Orb Silhouette (Speaking talks with rich harmonic waves;
        //    Listening ripples gently; Music Playing grooves smoothly; Idle breathes cleanly)
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
                // Expressive multi-frequency voice modulation
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

        // Main luminous sphere body
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

        // Secondary tilted inner cloud layer for ChatGPT-like liquid cloud depth
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

        // 4. State-specific minimalist inner light geometry
        when (agentState) {
            AgentState.THINKING -> {
                // Smooth intersecting luminous orbital light ribbons inside the sphere
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
                // Radiant inner vocal horizon wave inside the sphere
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
                // Crisp subtle inner focus ring
                drawCircle(
                    color = luminousWhite.copy(alpha = 0.52f),
                    radius = baseRadius * (0.48f + 0.08f * sin(wavePhase)),
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            else -> {
                if (isMusicPlaying) {
                    // Subtle musical orbital ring around the sphere
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

        // 5. Delicate glassy outer rim highlight
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

/**
 * Interactive Approval Card shown whenever TuneMind suggests a song and waits for user approval.
 */
@Composable
private fun ProposedTrackApprovalCard(
    proposedTrack: PendingProposedTrack,
    isListeningForVoiceApproval: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val approvalAccent = Color(0xFF10B981)
    val infiniteTransition = rememberInfiniteTransition(label = "approval_pulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.80f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "approval_border_alpha"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioGlassCard.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            approvalAccent.copy(alpha = borderAlpha)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(approvalAccent)
                )
                Text(
                    text = if (isListeningForVoiceApproval) {
                        "Up Next • Say \"Yes\" to play"
                    } else {
                        "Suggested Track"
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = approvalAccent
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = proposedTrack.title,
                        style = TuneMindTypography.headlineSmall,
                        fontSize = 16.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val cleanArtist = proposedTrack.artist.takeIf {
                        it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true)
                    } ?: "Curated pick"
                    Text(
                        text = cleanArtist,
                        style = TuneMindTypography.bodySmall,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                            .clickable { onReject() }
                            .padding(horizontal = 11.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Skip",
                            tint = Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Skip",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF10B981), Color(0xFF00E5FF))
                                )
                            )
                            .clickable { onApprove() }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play",
                            tint = Color(0xFF030408),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Play",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF030408)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact Mix Banner showing 3 fresh tracks curated for the user's current time of day.
 */
@Composable
private fun DopamineCapsuleBanner(
    capsule: DopamineCapsuleState,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioGlassCard.copy(alpha = 0.88f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.35f),
                    Color.White.copy(alpha = 0.08f),
                    accentColor.copy(alpha = 0.18f)
                )
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Text(
                        text = "${capsule.slotLabel} Mix",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = accentColor
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Listen",
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                capsule.tracks.take(3).forEachIndexed { index, track ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(StudioGlassInner)
                            .border(
                                1.dp,
                                if (index == 0) accentColor.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.07f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 9.dp, vertical = 7.dp)
                    ) {
                        Column {
                            Text(
                                text = track.title,
                                style = TuneMindTypography.labelLarge,
                                fontSize = 11.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${track.freshnessPercent}% Fresh",
                                fontWeight = FontWeight.Medium,
                                fontSize = 10.sp,
                                color = accentColor.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean Voice Dialogue Console showing current companion status and live spoken response.
 */
@Composable
private fun VoiceDialogueConsole(
    agentState: AgentState,
    hasPendingApproval: Boolean,
    liveTranscript: String,
    flowAnalysis: String,
    accentColor: Color,
    onTapSpeak: () -> Unit
) {
    val stateLabel = when {
        hasPendingApproval && agentState == AgentState.LISTENING -> "Say \"Yes\" to play or \"Skip\" for another"
        hasPendingApproval -> "Waiting for your go-ahead"
        agentState == AgentState.IDLE -> "Tap to talk, or say \"Hey TuneMind\""
        agentState == AgentState.LISTENING -> "Listening…"
        agentState == AgentState.THINKING -> "Thinking…"
        agentState == AgentState.SPEAKING -> "Speaking"
        agentState == AgentState.ERROR -> "Tap to try again"
        else -> "Ready"
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = StudioGlassCard.copy(alpha = 0.90f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accentColor.copy(alpha = if (agentState == AgentState.IDLE && !hasPendingApproval) 0.16f else 0.42f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTapSpeak() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accentColor.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Text(
                    text = stateLabel,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = accentColor
                )
            }

            val displayLine = liveTranscript.ifBlank {
                "Ask for a track, match your mood, or launch your daily mix."
            }
            Text(
                text = displayLine,
                style = TuneMindTypography.bodyMedium,
                fontSize = 13.sp,
                color = if (liveTranscript.isNotBlank()) Color.White else Color.White.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 19.sp
            )

            if (flowAnalysis.isNotBlank()) {
                Text(
                    text = flowAnalysis,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.48f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Compact Now-Playing Mini Card at the bottom of the center screen.
 */
@Composable
private fun NowPlayingMiniBar(
    trackTitle: String?,
    artistName: String?,
    isMusicPlaying: Boolean,
    accentColor: Color
) {
    val mood = remember(trackTitle, artistName) {
        com.tunemind.app.ui.theme.AdaptiveThemeEngine.resolveTrackMood(trackTitle, artistName)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioGlassCard.copy(alpha = 0.88f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.14f))
                        .border(1.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isMusicPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trackTitle ?: "Nothing playing",
                        style = TuneMindTypography.labelLarge,
                        fontSize = 13.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artistName ?: "Tap the sphere or ask for a track",
                        style = TuneMindTypography.bodySmall,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.56f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            val moodAccent = TuneMindPalettes.MoodProfiles[mood]?.moodAccent ?: accentColor
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(moodAccent.copy(alpha = 0.14f))
                    .border(1.dp, moodAccent.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = mood.label,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = moodAccent
                )
            }
        }
    }
}
