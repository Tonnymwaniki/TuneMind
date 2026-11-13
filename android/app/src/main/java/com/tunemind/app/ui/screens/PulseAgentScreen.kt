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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.voice.AgentState
import com.tunemind.app.domain.voice.PendingProposedTrack
import com.tunemind.app.ui.DopamineCapsuleState
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.ui.theme.TuneMindPalettes
import com.tunemind.app.ui.theme.TuneMindTypography
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Ultra-Dark Abyssal Obsidian Palette (Exclusive to the Agent Screen)
private val AgentVoidTop = Color(0xFF010103)
private val AgentVoidMid = Color(0xFF04030A)
private val AgentVoidBottom = Color(0xFF010104)
private val AgentObsidianCard = Color(0xFF07060F)
private val AgentObsidianDeep = Color(0xFF040308)

/**
 * Screen 2 (Center Default): THE PULSE AGENT — Abyssal Obsidian Edition
 * Features an exclusive pitch-dark obsidian void theme and an expressive Cyber-Acoustic Mascot
 * with floating headphone ear-pods, neural visor, reactive cyber-brows, and a live Song Approval bar.
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
    onCycleAtmospherePalette: () -> Unit = {}
) {
    val colors = LocalAdaptiveTheme.current
    val scrollState = rememberScrollState()
    val hasPendingApproval = pendingProposedTrack != null

    val activeAccent by animateColorAsState(
        targetValue = when {
            hasPendingApproval -> Color(0xFF00FFA3)
            agentState == AgentState.LISTENING -> Color(0xFF00E5FF)
            agentState == AgentState.THINKING -> Color(0xFFA855F7)
            agentState == AgentState.SPEAKING -> Color(0xFF10B981)
            agentState == AgentState.ERROR -> Color(0xFFF59E0B)
            else -> colors.primaryAccent
        },
        animationSpec = tween(450),
        label = "agent_active_accent"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        AgentVoidTop,
                        AgentVoidMid,
                        AgentVoidBottom
                    )
                )
            )
    ) {
        // Subtle pitch-dark abyssal nebula aura behind the mascot
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.36f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeAccent.copy(alpha = 0.11f),
                        colors.secondaryAccent.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = size.minDimension * 0.68f
                ),
                radius = size.minDimension * 0.68f,
                center = Offset(cx, cy)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 124.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Minimal Obsidian Top Studio Status Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AgentObsidianCard.copy(alpha = 0.94f))
                        .border(1.dp, activeAccent.copy(alpha = 0.25f), RoundedCornerShape(50))
                        .clickable { onCycleAtmospherePalette() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(activeAccent)
                    )
                    Text(
                        text = "OBSIDIAN NEURAL CORE • ${colors.activePalette.name.uppercase()}",
                        style = TuneMindTypography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp
                    )
                }

                // Live Satiety Badge
                val satietyColor = when {
                    satietyPercentage >= 75 -> Color(0xFFFF2A55)
                    satietyPercentage >= 50 -> Color(0xFFFFB020)
                    else -> Color(0xFF00FFA3)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AgentObsidianCard.copy(alpha = 0.94f))
                        .border(1.dp, satietyColor.copy(alpha = 0.32f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(satietyColor)
                    )
                    Text(
                        text = "$satietyPercentage% SATIETY",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = satietyColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Compact Obsidian Dopamine Capsule Banner
            if (dopamineCapsule != null && dopamineCapsule.tracks.isNotEmpty()) {
                DopamineCapsuleBanner(
                    capsule = dopamineCapsule,
                    accentColor = activeAccent,
                    onClick = onLaunchDopamineCapsule
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 3. Centerpiece: Creative Cyber-Acoustic Studio Mascot
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
                StudioNeuralMascot(
                    agentState = agentState,
                    isMusicPlaying = isMusicPlaying,
                    hasPendingApproval = hasPendingApproval,
                    energy = energy,
                    primaryAccent = activeAccent,
                    secondaryAccent = colors.secondaryAccent,
                    satietyPercentage = satietyPercentage,
                    modifier = Modifier.size(280.dp)
                )
            }

            // 4. Interactive Song Proposal Approval Card (Visible whenever the agent suggests a song)
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

            // 5. Mascot State Pill & Voice Dialogue Console
            MascotDialogueConsole(
                agentState = agentState,
                hasPendingApproval = hasPendingApproval,
                liveTranscript = liveTranscript,
                flowAnalysis = flowAnalysis,
                accentColor = activeAccent,
                onTapSpeak = onOrbTapped
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Active Track Telemetry Strip
            NowPlayingMiniBar(
                trackTitle = currentTrackName,
                artistName = artistName,
                isMusicPlaying = isMusicPlaying,
                satietyPercentage = satietyPercentage,
                accentColor = activeAccent
            )
        }
    }
}

/**
 * Interactive Approval Card shown whenever TuneMind proposes a song and waits for user approval.
 */
@Composable
private fun ProposedTrackApprovalCard(
    proposedTrack: PendingProposedTrack,
    isListeningForVoiceApproval: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val approvalAccent = Color(0xFF00FFA3)
    val infiniteTransition = rememberInfiniteTransition(label = "approval_pulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "approval_border_alpha"
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = AgentObsidianCard.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(
            1.2.dp,
            approvalAccent.copy(alpha = borderAlpha)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(approvalAccent)
                    )
                    Text(
                        text = if (isListeningForVoiceApproval) {
                            "AWAITING APPROVAL • SAY \"YES\" TO PLAY"
                        } else {
                            "PROPOSED TRACK • AWAITING YOUR APPROVAL"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        color = approvalAccent,
                        letterSpacing = 0.7.sp
                    )
                }
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
                    } ?: "Spotify Studio Selection"
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
                    // Reject / Another Track button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                            .clickable { onReject() }
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕ Next",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }

                    // Approve & Play Now button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF00FFA3), Color(0xFF00E5FF))
                                )
                            )
                            .clickable { onApprove() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✓ Play Now",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            color = Color(0xFF020205)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Creative Cyber-Acoustic Studio Mascot:
 * Features an obsidian dark-matter head, floating cyber-acoustic headphone ear-pods on both sides,
 * a glowing neural headband arc, a dark-glass cyber visor with expressive eyes + eyebrows,
 * an animated harmonic vocalizer mouth, and multi-layered gyroscopic plasma rings.
 */
@Composable
private fun StudioNeuralMascot(
    agentState: AgentState,
    isMusicPlaying: Boolean,
    hasPendingApproval: Boolean,
    energy: Float,
    primaryAccent: Color,
    secondaryAccent: Color,
    satietyPercentage: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mascot_anim")

    // Hover & breathing physics
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -7f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (agentState) {
                    AgentState.LISTENING -> 950
                    AgentState.THINKING -> 650
                    AgentState.SPEAKING -> 800
                    else -> if (isMusicPlaying) 1400 else 2400
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mascot_hover"
    )

    val coreBreath by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = when (agentState) {
            AgentState.LISTENING -> 1.12f
            AgentState.SPEAKING -> 1.09f
            AgentState.THINKING -> 1.06f
            else -> if (hasPendingApproval) 1.07f else if (isMusicPlaying) 1.04f else 1.0f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(1150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_breath"
    )

    val haloAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (agentState == AgentState.THINKING) 2600 else 10500,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "halo_angle"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (agentState) {
                    AgentState.SPEAKING -> 400
                    AgentState.LISTENING -> 520
                    else -> 1250
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    // Natural eye blink every ~3.6 seconds
    val blinkCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "blink_cycle"
    )
    val eyeOpenScale = if (blinkCycle in 0.94f..0.985f && agentState != AgentState.LISTENING) 0.12f else 1.0f

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f + floatOffset)
        val baseRadius = size.minDimension * 0.255f * coreBreath

        // 1. Deep Obsidian Void Contrast Backdrop + Neon Halo Bloom
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryAccent.copy(alpha = if (agentState == AgentState.IDLE && !hasPendingApproval) 0.20f else 0.36f),
                    secondaryAccent.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = center,
                radius = baseRadius * 2.35f
            ),
            radius = baseRadius * 2.35f,
            center = center
        )

        // 2. Outer 36-Tick Sonar Precision Bezel
        val tickCount = 36
        val tickInner = baseRadius * 1.62f
        val tickOuter = baseRadius * 1.74f
        for (i in 0 until tickCount) {
            val angleDeg = i * (360f / tickCount) + haloAngle * 0.25f
            val rad = Math.toRadians(angleDeg.toDouble())
            val isActiveTick = (i % 3 == 0) || agentState == AgentState.LISTENING || hasPendingApproval
            val tickWave = if (agentState == AgentState.SPEAKING || agentState == AgentState.LISTENING) {
                (sin(wavePhase + i * 0.5f) * 6f).coerceAtLeast(0f)
            } else 0f
            val start = Offset(
                center.x + (tickInner * cos(rad)).toFloat(),
                center.y + (tickInner * sin(rad)).toFloat()
            )
            val end = Offset(
                center.x + ((tickOuter + tickWave) * cos(rad)).toFloat(),
                center.y + ((tickOuter + tickWave) * sin(rad)).toFloat()
            )
            drawLine(
                color = if (isActiveTick) primaryAccent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f),
                start = start,
                end = end,
                strokeWidth = if (i % 3 == 0) 2.5f else 1.3f,
                cap = StrokeCap.Round
            )
        }

        // 3. Counter-Rotating Gyroscopic Orbital Rings + Satellites
        rotate(degrees = haloAngle, pivot = center) {
            drawOval(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        primaryAccent.copy(alpha = 0.65f),
                        secondaryAccent.copy(alpha = 0.45f),
                        Color.Transparent
                    ),
                    center = center
                ),
                topLeft = Offset(center.x - baseRadius * 1.48f, center.y - baseRadius * 1.16f),
                size = Size(baseRadius * 2.96f, baseRadius * 2.32f),
                style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        rotate(degrees = -haloAngle * 0.75f, pivot = center) {
            drawOval(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        secondaryAccent.copy(alpha = 0.50f),
                        primaryAccent.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    center = center
                ),
                topLeft = Offset(center.x - baseRadius * 1.18f, center.y - baseRadius * 1.45f),
                size = Size(baseRadius * 2.36f, baseRadius * 2.90f),
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // 3 Orbiting Sonic Satellites
        for (satIdx in 0..2) {
            val satAngleRad = Math.toRadians((haloAngle * (1.15f + satIdx * 0.25f) + satIdx * 120f).toDouble())
            val orbitR = baseRadius * (1.36f + satIdx * 0.08f)
            val satPos = Offset(
                x = center.x + (orbitR * cos(satAngleRad)).toFloat(),
                y = center.y + (orbitR * sin(satAngleRad) * 0.80).toFloat()
            )
            drawCircle(
                color = primaryAccent.copy(alpha = 0.30f),
                radius = 7.dp.toPx(),
                center = satPos
            )
            drawCircle(
                color = Color.White,
                radius = 2.8.dp.toPx(),
                center = satPos
            )
        }

        // 4. Cyber-Acoustic Headband Arc + Floating Ear-Pods on Left & Right Flanks
        val headbandRectSize = Size(baseRadius * 2.28f, baseRadius * 2.22f)
        drawArc(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    primaryAccent.copy(alpha = 0.75f),
                    Color.White.copy(alpha = 0.55f),
                    primaryAccent.copy(alpha = 0.75f)
                )
            ),
            startAngle = 198f,
            sweepAngle = 144f,
            useCenter = false,
            topLeft = Offset(center.x - headbandRectSize.width / 2f, center.y - headbandRectSize.height / 2f - baseRadius * 0.06f),
            size = headbandRectSize,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        )

        // Left & Right Floating Cyber Ear-Pods (pulse outward with beat/speech)
        val earPulse = if (agentState == AgentState.SPEAKING || agentState == AgentState.LISTENING || isMusicPlaying) {
            (sin(wavePhase * 1.5f) * 3.5f).dp.toPx()
        } else 0f
        val earPodWidth = baseRadius * 0.28f
        val earPodHeight = baseRadius * 0.68f
        val leftPodTopLeft = Offset(
            x = center.x - baseRadius * 1.24f - earPulse,
            y = center.y - earPodHeight * 0.48f
        )
        val rightPodTopLeft = Offset(
            x = center.x + baseRadius * 0.96f + earPulse,
            y = center.y - earPodHeight * 0.48f
        )

        listOf(leftPodTopLeft, rightPodTopLeft).forEach { podTopLeft ->
            // Outer glow
            drawRoundRect(
                color = primaryAccent.copy(alpha = 0.28f),
                topLeft = Offset(podTopLeft.x - 2.dp.toPx(), podTopLeft.y - 2.dp.toPx()),
                size = Size(earPodWidth + 4.dp.toPx(), earPodHeight + 4.dp.toPx()),
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
            )
            // Obsidian Ear-Pod Shell
            drawRoundRect(
                color = Color(0xFF05040C),
                topLeft = podTopLeft,
                size = Size(earPodWidth, earPodHeight),
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
            )
            drawRoundRect(
                color = primaryAccent.copy(alpha = 0.85f),
                topLeft = podTopLeft,
                size = Size(earPodWidth, earPodHeight),
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                style = Stroke(width = 1.6.dp.toPx())
            )
            // Glowing LED slit inside each ear-pod
            drawLine(
                color = primaryAccent,
                start = Offset(podTopLeft.x + earPodWidth * 0.5f, podTopLeft.y + earPodHeight * 0.25f),
                end = Offset(podTopLeft.x + earPodWidth * 0.5f, podTopLeft.y + earPodHeight * 0.75f),
                strokeWidth = 2.4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 5. Mascot Pitch-Obsidian Head Sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF100E20),
                    Color(0xFF06050D),
                    Color(0xFF020205)
                ),
                center = Offset(center.x - baseRadius * 0.22f, center.y - baseRadius * 0.24f),
                radius = baseRadius * 1.15f
            ),
            radius = baseRadius,
            center = center
        )

        // Subtle Crown Specular Rim
        drawArc(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.48f),
                    primaryAccent.copy(alpha = 0.85f),
                    secondaryAccent.copy(alpha = 0.35f)
                )
            ),
            startAngle = -140f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
            size = Size(baseRadius * 2f, baseRadius * 2f),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // 6. Recessed Dark-Glass Cyber Visor Plate across the Mascot's Face
        val visorWidth = baseRadius * 1.54f
        val visorHeight = baseRadius * 0.86f
        val visorTopLeft = Offset(center.x - visorWidth / 2f, center.y - visorHeight * 0.56f)
        drawRoundRect(
            color = Color(0xFF020206),
            topLeft = visorTopLeft,
            size = Size(visorWidth, visorHeight),
            cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx())
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    primaryAccent.copy(alpha = 0.55f),
                    Color.White.copy(alpha = 0.20f),
                    primaryAccent.copy(alpha = 0.55f)
                )
            ),
            topLeft = visorTopLeft,
            size = Size(visorWidth, visorHeight),
            cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
            style = Stroke(width = 1.4.dp.toPx())
        )

        // 7. Expressive Neural Eyes + Dynamic Cyber-Brows inside the Visor
        val eyeSpacing = baseRadius * 0.36f
        val eyeY = center.y - baseRadius * 0.12f
        val eyeWidth = baseRadius * 0.22f
        val eyeHeight = (baseRadius * 0.28f * eyeOpenScale).coerceAtLeast(3.dp.toPx())

        val leftEyeCenter = Offset(center.x - eyeSpacing, eyeY)
        val rightEyeCenter = Offset(center.x + eyeSpacing, eyeY)

        // Expressive Cyber-Brows above each eye
        val browY = eyeY - baseRadius * 0.22f
        val browTilt = when {
            hasPendingApproval -> -5.dp.toPx() // Curious raised inner brows asking for approval
            agentState == AgentState.THINKING -> 4.dp.toPx() // Focused analytical tilt
            agentState == AgentState.LISTENING -> -3.dp.toPx() // Alert wide-open focus
            else -> 0f
        }
        // Left brow
        drawLine(
            color = primaryAccent.copy(alpha = 0.90f),
            start = Offset(leftEyeCenter.x - eyeWidth * 0.7f, browY - browTilt * 0.4f),
            end = Offset(leftEyeCenter.x + eyeWidth * 0.7f, browY + browTilt),
            strokeWidth = 2.6.dp.toPx(),
            cap = StrokeCap.Round
        )
        // Right brow
        drawLine(
            color = primaryAccent.copy(alpha = 0.90f),
            start = Offset(rightEyeCenter.x - eyeWidth * 0.7f, browY + browTilt),
            end = Offset(rightEyeCenter.x + eyeWidth * 0.7f, browY - browTilt * 0.4f),
            strokeWidth = 2.6.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Left & Right Glowing Neural Eyes
        listOf(leftEyeCenter, rightEyeCenter).forEach { eyePos ->
            // Outer iris aura
            drawRoundRect(
                color = primaryAccent.copy(alpha = 0.38f),
                topLeft = Offset(eyePos.x - eyeWidth * 0.68f, eyePos.y - eyeHeight * 0.68f),
                size = Size(eyeWidth * 1.36f, eyeHeight * 1.36f),
                cornerRadius = CornerRadius(eyeWidth, eyeWidth)
            )
            // Bright cyber pupil capsule
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(eyePos.x - eyeWidth * 0.5f, eyePos.y - eyeHeight * 0.5f),
                size = Size(eyeWidth, eyeHeight),
                cornerRadius = CornerRadius(eyeWidth, eyeWidth)
            )
            // Inner cyan/emerald core glint
            if (eyeOpenScale > 0.4f) {
                drawCircle(
                    color = primaryAccent,
                    radius = eyeWidth * 0.24f,
                    center = Offset(eyePos.x + eyeWidth * 0.12f, eyePos.y - eyeHeight * 0.12f)
                )
            }
        }

        // 8. Mascot Vocalizer Mouth / Expression Curve below Visor
        val mouthCenterY = center.y + baseRadius * 0.44f
        when {
            agentState == AgentState.SPEAKING || agentState == AgentState.LISTENING -> {
                val barCount = 7
                val totalMouthWidth = baseRadius * 0.86f
                val spacing = totalMouthWidth / (barCount - 1)
                val startX = center.x - totalMouthWidth / 2f
                for (i in 0 until barCount) {
                    val centerWeight = 1f - kotlin.math.abs(i - 3) * 0.2f
                    val wave = kotlin.math.abs(sin(wavePhase + i * 0.75f))
                    val barH = (baseRadius * 0.08f + baseRadius * 0.28f * wave * centerWeight)
                        .coerceAtLeast(4.dp.toPx())
                    val x = startX + i * spacing
                    drawLine(
                        color = primaryAccent,
                        start = Offset(x, mouthCenterY - barH / 2f),
                        end = Offset(x, mouthCenterY + barH / 2f),
                        strokeWidth = 3.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            agentState == AgentState.THINKING -> {
                for (i in -1..1) {
                    val dotOffset = sin(wavePhase + i * 1.1f) * 4.dp.toPx()
                    drawCircle(
                        color = primaryAccent,
                        radius = 3.5.dp.toPx(),
                        center = Offset(center.x + i * 14.dp.toPx(), mouthCenterY + dotOffset)
                    )
                }
            }
            else -> {
                // Expressive confident cyber-smile arc
                val smileWidth = baseRadius * 0.46f
                val smileHeight = if (hasPendingApproval || isMusicPlaying) baseRadius * 0.22f else baseRadius * 0.14f
                val path = Path().apply {
                    moveTo(center.x - smileWidth / 2f, mouthCenterY - smileHeight * 0.2f)
                    quadraticTo(
                        center.x,
                        mouthCenterY + smileHeight,
                        center.x + smileWidth / 2f,
                        mouthCenterY - smileHeight * 0.2f
                    )
                }
                drawPath(
                    path = path,
                    color = primaryAccent.copy(alpha = 0.92f),
                    style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

/**
 * Compact Dopamine Capsule Banner:
 * Displays the 3 tracks TuneMind compacted for the user's current circadian slot.
 */
@Composable
private fun DopamineCapsuleBanner(
    capsule: DopamineCapsuleState,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = AgentObsidianCard.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.42f),
                    Color.White.copy(alpha = 0.08f),
                    accentColor.copy(alpha = 0.22f)
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
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
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
                            .background(accentColor)
                    )
                    Text(
                        text = "${capsule.slotLabel} DOPAMINE CAPSULE",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        color = accentColor,
                        letterSpacing = 0.8.sp
                    )
                }

                Text(
                    text = "TAP FOR VOICE BRIEFING →",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.65f)
                )
            }

            // Compact horizontal pills for the 3 capsule tracks
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                capsule.tracks.take(3).forEachIndexed { index, track ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AgentObsidianDeep)
                            .border(
                                1.dp,
                                if (index == 0) accentColor.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.07f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp)
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
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = accentColor.copy(alpha = 0.90f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mascot Voice Dialogue Console:
 * Displays the current mascot state, live Claude psychoacoustic insight, and spoken transcript.
 */
@Composable
private fun MascotDialogueConsole(
    agentState: AgentState,
    hasPendingApproval: Boolean,
    liveTranscript: String,
    flowAnalysis: String,
    accentColor: Color,
    onTapSpeak: () -> Unit
) {
    val stateLabel = when {
        hasPendingApproval && agentState == AgentState.LISTENING -> "LISTENING FOR YOUR APPROVAL — SAY \"YES\" OR \"NEXT\""
        hasPendingApproval -> "SONG PROPOSED — AWAITING YOUR APPROVAL TO EXECUTE"
        agentState == AgentState.IDLE -> "TAP CYBER MASCOT OR SAY \"HI TUNEMIND\""
        agentState == AgentState.LISTENING -> "LISTENING TO YOU — SPEAK NATURALLY"
        agentState == AgentState.THINKING -> "CLAUDE SONNET REASONING OVER YOUR TELEMETRY..."
        agentState == AgentState.SPEAKING -> "TUNEMIND STUDIO ADVISOR SPEAKING"
        agentState == AgentState.ERROR -> "TAP MASCOT TO SPEAK AGAIN"
        else -> "STUDIO READY"
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AgentObsidianCard.copy(alpha = 0.95f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accentColor.copy(alpha = if (agentState == AgentState.IDLE && !hasPendingApproval) 0.18f else 0.45f)
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
            // Status Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accentColor.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Text(
                    text = stateLabel,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = accentColor,
                    letterSpacing = 0.7.sp
                )
            }

            // Spoken Transcript or Natural Prompt Hint
            val displayLine = liveTranscript.ifBlank {
                "\"Ask me to suggest a song, check your decibel headroom, or launch your Dopamine Capsule.\""
            }
            Text(
                text = displayLine,
                style = TuneMindTypography.bodyMedium,
                fontSize = 13.sp,
                color = if (liveTranscript.isNotBlank()) Color.White else Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 19.sp
            )

            // Subtle Telemetry Analysis Footer
            if (flowAnalysis.isNotBlank()) {
                Text(
                    text = flowAnalysis,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.50f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Compact Now-Playing Mini Bar at the bottom of the Studio Mascot screen.
 */
@Composable
private fun NowPlayingMiniBar(
    trackTitle: String?,
    artistName: String?,
    isMusicPlaying: Boolean,
    satietyPercentage: Int,
    accentColor: Color
) {
    val mood = remember(trackTitle, artistName) {
        com.tunemind.app.ui.theme.AdaptiveThemeEngine.resolveTrackMood(trackTitle, artistName)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AgentObsidianCard.copy(alpha = 0.90f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
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
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.14f))
                        .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isMusicPlaying) "♫" else "⏸",
                        fontSize = 14.sp,
                        color = accentColor
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trackTitle ?: "Studio Standing By",
                        style = TuneMindTypography.labelLarge,
                        fontSize = 13.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artistName ?: "Tap the mascot or say 'Suggest a song'",
                        style = TuneMindTypography.bodySmall,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Mood DNA Badge
            val moodAccent = TuneMindPalettes.MoodProfiles[mood]?.moodAccent ?: accentColor
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(moodAccent.copy(alpha = 0.14f))
                    .border(1.dp, moodAccent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = mood.label.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = moodAccent
                )
            }
        }
    }
}
