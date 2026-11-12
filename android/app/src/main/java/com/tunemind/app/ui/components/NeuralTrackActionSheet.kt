package com.tunemind.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunemind.app.ui.theme.AdaptiveThemeEngine
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Upgraded sculpted Neural Sonic Action Button at the right end of every song card.
 * Replaces the casual "Ask" text button with a custom animated 4-bar sonic waveform
 * and gradient-bordered neural capsule.
 */
@Composable
fun NeuralSonicActionButton(
    accentColor: Color,
    secondaryColor: Color = LocalAdaptiveTheme.current.secondaryAccent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SonicWave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "WavePhase"
    )

    val pillShape = RoundedCornerShape(16.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .defaultMinSize(minWidth = 76.dp, minHeight = 44.dp)
            .clip(pillShape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.16f),
                        secondaryColor.copy(alpha = 0.12f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.65f),
                        secondaryColor.copy(alpha = 0.45f)
                    )
                ),
                shape = pillShape
            )
            .bouncyClickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        // Custom 4-bar Neural Sonic Waveform Icon
        Canvas(modifier = Modifier.size(width = 16.dp, height = 14.dp)) {
            val barWidth = 2.4.dp.toPx()
            val gap = 2.0.dp.toPx()
            val maxH = size.height
            val heights = listOf(
                maxH * (0.45f + 0.35f * wavePhase),
                maxH * (0.95f - 0.30f * wavePhase),
                maxH * (0.60f + 0.35f * wavePhase),
                maxH * (0.80f - 0.25f * wavePhase)
            )
            heights.forEachIndexed { index, h ->
                val x = index * (barWidth + gap)
                val y = (maxH - h) / 2f
                drawRoundRect(
                    color = if (index % 2 == 0) accentColor else secondaryColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }

        Text(
            text = "SYNC",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp
        )
    }
}

/**
 * Interactive Neural Track Intelligence Sheet triggered when tapping the SYNC button on any song card.
 */
@Composable
fun NeuralTrackActionDialog(
    title: String,
    artist: String,
    freshnessPercent: Int,
    playCount: Int,
    isCoolingDown: Boolean,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit,
    onAgentNeuralBreakdown: () -> Unit,
    onSyncAtmosphereToTrack: () -> Unit,
    onToggleCooldownVault: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val detectedMood = remember(title, artist) {
        AdaptiveThemeEngine.resolveTrackMood(title, artist)
    }
    val ringColor = if (isCoolingDown) Color(0xFFFFB000) else theme.primaryAccent

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(horizontal = 20.dp, vertical = 32.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                theme.elevatedSurface,
                                theme.primaryBackground
                            )
                        )
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                theme.primaryAccent.copy(alpha = 0.65f),
                                theme.secondaryAccent.copy(alpha = 0.45f)
                            )
                        ),
                        shape = RoundedCornerShape(32.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Drag Handle Pill
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(42.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f))
                )

                // Track Telemetry Header with Radial Dopamine Ring
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawArc(
                                color = Color.White.copy(alpha = 0.08f),
                                startAngle = 135f,
                                sweepAngle = 270f,
                                useCenter = false,
                                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = ringColor,
                                startAngle = 135f,
                                sweepAngle = 270f * (freshnessPercent / 100f),
                                useCenter = false,
                                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        Text(
                            text = "$freshnessPercent%",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "NEURAL TRACK INTELLIGENCE",
                            color = theme.primaryAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = artist,
                            color = theme.secondaryText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 3 Sonic DNA Badges (Mood, Dopamine State, Rotation Count)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryMiniPill(
                        label = "MOOD DNA",
                        value = detectedMood.label,
                        accent = theme.primaryAccent,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryMiniPill(
                        label = "DOPAMINE",
                        value = if (isCoolingDown) "Cooling" else "Peak Hit",
                        accent = ringColor,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryMiniPill(
                        label = "ROTATION",
                        value = "${playCount}x Played",
                        accent = theme.secondaryAccent,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Upgraded Action 1: Live Voice Agent Synthesis & Dialogue
                ActionSheetButton(
                    badge = "AI",
                    title = "Neural Voice Breakdown & Dialogue",
                    subtitle = "Agent analyzes this track's vibe & asks before spinning",
                    accentColor = theme.primaryAccent,
                    isPrimary = true,
                    onClick = {
                        onDismiss()
                        onAgentNeuralBreakdown()
                    }
                )

                // Upgraded Action 2: Direct Spin Now
                ActionSheetButton(
                    badge = "▶",
                    title = "Spin Track Immediately",
                    subtitle = "Launch straight into playback with voice intro",
                    accentColor = Color(0xFF10B981),
                    isPrimary = false,
                    onClick = {
                        onDismiss()
                        onPlayNow()
                    }
                )

                // Upgraded Action 3: Sync Adaptive Atmosphere Theme to Song Mood
                ActionSheetButton(
                    badge = "✦",
                    title = "Shift App Atmosphere to ${detectedMood.label}",
                    subtitle = "Adapt TuneMind's colors & glow to this track's mood",
                    accentColor = theme.secondaryAccent,
                    isPrimary = false,
                    onClick = {
                        onDismiss()
                        onSyncAtmosphereToTrack()
                    }
                )

                // Upgraded Action 4: Cooldown Vault / Restore
                ActionSheetButton(
                    badge = if (isCoolingDown) "⚡" else "❄️",
                    title = if (isCoolingDown) "Restore to Peak Dopamine" else "Rest in Cooldown Vault",
                    subtitle = if (isCoolingDown) "Mark track as fresh and ready for rotation"
                               else "Prevent ear fatigue by resting this track for 5 days",
                    accentColor = Color(0xFFFFB000),
                    isPrimary = false,
                    onClick = {
                        onDismiss()
                        onToggleCooldownVault()
                    }
                )
            }
        }
    }
}

@Composable
private fun TelemetryMiniPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActionSheetButton(
    badge: String,
    title: String,
    subtitle: String,
    accentColor: Color,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isPrimary) accentColor.copy(alpha = 0.18f)
                else Color.White.copy(alpha = 0.04f)
            )
            .border(
                width = 1.dp,
                color = if (isPrimary) accentColor.copy(alpha = 0.55f)
                        else Color.White.copy(alpha = 0.09f),
                shape = shape
            )
            .bouncyClickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.20f))
                .border(1.dp, accentColor.copy(alpha = 0.5f), CircleShape)
        ) {
            Text(
                text = badge,
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 11.sp
            )
        }
    }
}
