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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.voice.AgentState
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun PulseAgentScreen(
    agentState: AgentState,
    satietyPercentage: Int,
    currentTrackName: String,
    artistName: String,
    energy: Float = 0.8f,
    liveTranscript: String = "",
    onOrbTapped: () -> Unit,
    onCycleAtmospherePalette: () -> Unit = {}
) {
    val theme = LocalAdaptiveTheme.current

    // Acoustic Core & State Colors blended with Active Adaptive Theme
    val targetOrbColor = when (agentState) {
        AgentState.LISTENING -> Color(0xFF00E5FF) // Acoustic Cyan
        AgentState.THINKING -> Color(0xFFA855F7)  // Neural Violet
        AgentState.SPEAKING -> Color(0xFFFFB000)  // Liquid Amber
        AgentState.ERROR -> Color(0xFFFF2A55)     // Alert Crimson
        AgentState.IDLE -> theme.primaryAccent    // Dynamic Adaptive Theme Accent (Dawn Cyan / Midday Emerald / Dusk Amber / Night Violet)
    }

    val animatedOrbColor by animateColorAsState(
        targetValue = targetOrbColor,
        animationSpec = tween(600),
        label = "OrbColor"
    )

    val satietyColor = when {
        satietyPercentage > 75 -> Color(0xFFFF2A55)
        satietyPercentage > 50 -> Color(0xFFFFB000)
        else -> theme.secondaryAccent
    }

    // Breathing Physics
    val infiniteTransition = rememberInfiniteTransition(label = "InfiniteBreathing")
    val orbBreathe by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = if (agentState != AgentState.IDLE) 1.08f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (agentState != AgentState.IDLE) 650 else 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OrbBreathe"
    )

    val outerRipple by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.20f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OuterRipple"
    )

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
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        // Ambient Adaptive Atmosphere Glow Halo
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-40).dp)
                .size(260.dp)
                .blur(80.dp)
                .background(theme.primaryAccent.copy(alpha = 0.18f), CircleShape)
        )

        // TOP: Now Syncing & Adaptive Atmosphere Header Pill
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(theme.cardSurface.copy(alpha = 0.72f))
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            theme.primaryAccent.copy(alpha = 0.45f),
                            theme.secondaryAccent.copy(alpha = 0.35f)
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                )
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
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
                                .background(animatedOrbColor)
                        )
                        Text(
                            text = "NOW SYNCING",
                            color = animatedOrbColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.8.sp
                        )
                    }

                    // Tap to cycle Adaptive Theme Atmosphere right from the header
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.primaryAccent.copy(alpha = 0.16f))
                            .border(1.dp, theme.primaryAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .bouncyClickable { onCycleAtmospherePalette() }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "✦ ${theme.activePalette.name.uppercase()}",
                            color = theme.primaryText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = currentTrackName,
                    color = theme.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$artistName • ${theme.musicMood.label}",
                    color = theme.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // CENTER: 100% Dedicated Living Voice Orb & Satiety Arc
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(300.dp)
            ) {
                // Multi-layered Adaptive Halo
                Box(
                    modifier = Modifier
                        .size(230.dp * orbBreathe)
                        .blur(60.dp)
                        .background(animatedOrbColor.copy(alpha = 0.28f), CircleShape)
                )

                // Active Voice Ripple when Listening or Speaking
                if (agentState == AgentState.LISTENING || agentState == AgentState.SPEAKING) {
                    Box(
                        modifier = Modifier
                            .size(192.dp * outerRipple)
                            .clip(CircleShape)
                            .border(
                                width = 2.dp,
                                color = animatedOrbColor.copy(alpha = (1.22f - outerRipple).coerceIn(0f, 0.65f)),
                                shape = CircleShape
                            )
                    )
                }

                // 270-Degree Satiety & Dopamine Arc
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawArc(
                        color = Color.White.copy(alpha = 0.07f),
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = satietyColor,
                        startAngle = 135f,
                        sweepAngle = 270f * ((100 - satietyPercentage).coerceIn(10, 95) / 100f),
                        useCenter = false,
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Outer Living Orb Sphere (Tap to talk / interrupt)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(154.dp * orbBreathe)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.42f),
                                    animatedOrbColor,
                                    theme.secondaryAccent.copy(alpha = 0.85f)
                                )
                            )
                        )
                        .bouncyClickable { onOrbTapped() }
                ) {
                    // Inner Acoustic Core
                    Box(
                        modifier = Modifier
                            .size(76.dp * orbBreathe)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.26f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val statusCaption = when (agentState) {
                AgentState.LISTENING -> "LISTENING TO YOU..."
                AgentState.THINKING -> "SYNTHESIZING FLOW..."
                AgentState.SPEAKING -> "AGENT SPEAKING • TAP TO INTERRUPT"
                AgentState.ERROR -> "TAP ORB TO RETRY"
                AgentState.IDLE -> "TAP ORB TO TALK"
            }

            Text(
                text = statusCaption,
                color = if (agentState == AgentState.IDLE) theme.secondaryText else animatedOrbColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        // BOTTOM (Above Floating Dock): Live Dialogue Transcript Card
        AnimatedVisibility(
            visible = agentState != AgentState.IDLE || liveTranscript.isNotBlank(),
            enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) { it } + fadeIn(),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 98.dp)
        ) {
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (agentState == AgentState.IDLE) "LAST DIALOGUE" else agentState.name,
                        color = animatedOrbColor,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = liveTranscript.ifEmpty { "Listening..." },
                        color = Color.White.copy(alpha = 0.94f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}
