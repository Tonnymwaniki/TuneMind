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
import java.util.Calendar
import com.tunemind.app.domain.voice.AgentState
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.bouncyClickable

@Composable
fun PulseAgentScreen(
    agentState: AgentState,
    satietyPercentage: Int,
    currentTrackName: String,
    artistName: String,
    energy: Float = 0.8f,
    liveTranscript: String = "",
    onOrbTapped: () -> Unit
) {
    // LAYER 1: Chrono-Canvas (Time of Day)
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val canvasBaseColor = when (hour) {
        in 6..11 -> Color(0xFF070B14) // Morning Mist Obsidian
        in 12..16 -> Color(0xFF050508) // Midday Obsidian
        in 17..21 -> Color(0xFF12090D) // Evening Ember
        else -> Color(0xFF05040A)      // Late Night Abyss
    }

    val animatedBgColor by animateColorAsState(
        targetValue = canvasBaseColor,
        animationSpec = tween(2500),
        label = "BgColor"
    )

    // LAYER 2: Acoustic Core & State Colors
    val targetOrbColor = when (agentState) {
        AgentState.LISTENING -> Color(0xFF00E5FF) // Acoustic Cyan
        AgentState.THINKING -> Color(0xFF7000FF)  // Neural Violet
        AgentState.SPEAKING -> Color(0xFFFFB000)  // Liquid Amber
        AgentState.ERROR -> Color(0xFFFF2A55)     // Alert Crimson
        AgentState.IDLE -> {
            if (energy > 0.7f) Color(0xFF00E5FF) // Ready Dopamine Cyan
            else Color(0xFF10B981)               // Deep Flow Emerald
        }
    }

    val animatedOrbColor by animateColorAsState(
        targetValue = targetOrbColor,
        animationSpec = tween(600),
        label = "OrbColor"
    )

    // LAYER 3: Satiety Arc Color
    val satietyColor = when {
        satietyPercentage > 75 -> Color(0xFFFF2A55) // Fatigue Crimson
        satietyPercentage > 50 -> Color(0xFFFFB000) // Warm Amber
        else -> Color(0xFF00F0FF)                   // Glacier Cyan
    }

    // Breathing Physics
    val infiniteTransition = rememberInfiniteTransition(label = "InfiniteBreathing")
    val orbBreathe by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = if (agentState != AgentState.IDLE) 1.08f else 1.01f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (agentState != AgentState.IDLE) 650 else 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OrbBreathe"
    )

    val outerRipple by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OuterRipple"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedBgColor)
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        // TOP: Minimal Now Syncing Pill
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
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
                        color = animatedOrbColor.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentTrackName,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artistName,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
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
                // Ambient Halo
                Box(
                    modifier = Modifier
                        .size(220.dp * orbBreathe)
                        .blur(55.dp)
                        .background(animatedOrbColor.copy(alpha = 0.24f), CircleShape)
                )

                // Active Voice Ripple when Listening or Speaking
                if (agentState == AgentState.LISTENING || agentState == AgentState.SPEAKING) {
                    Box(
                        modifier = Modifier
                            .size(190.dp * outerRipple)
                            .clip(CircleShape)
                            .border(
                                width = 2.dp,
                                color = animatedOrbColor.copy(alpha = (1.2f - outerRipple).coerceIn(0f, 0.6f)),
                                shape = CircleShape
                            )
                    )
                }

                // 270-Degree Satiety & Dopamine Arc
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawArc(
                        color = Color.White.copy(alpha = 0.06f),
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
                        .size(152.dp * orbBreathe)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.35f),
                                    animatedOrbColor,
                                    animatedOrbColor.copy(alpha = 0.75f)
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
                            .background(Color.White.copy(alpha = 0.24f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Minimal 2-Word Status Caption beneath the Orb
            val statusCaption = when (agentState) {
                AgentState.LISTENING -> "LISTENING TO YOU..."
                AgentState.THINKING -> "SYNTHESIZING FLOW..."
                AgentState.SPEAKING -> "AGENT SPEAKING • TAP TO INTERRUPT"
                AgentState.ERROR -> "TAP ORB TO RETRY"
                AgentState.IDLE -> "TAP ORB TO TALK"
            }

            Text(
                text = statusCaption,
                color = if (agentState == AgentState.IDLE) Color.White.copy(alpha = 0.5f) else animatedOrbColor,
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
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}
