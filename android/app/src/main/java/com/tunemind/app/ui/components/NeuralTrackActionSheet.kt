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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunemind.app.ui.theme.AdaptiveThemeEngine
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Compact animated sonic waveform button on track cards.
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
            .defaultMinSize(minWidth = 74.dp, minHeight = 42.dp)
            .clip(pillShape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.14f),
                        secondaryColor.copy(alpha = 0.10f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.55f),
                        secondaryColor.copy(alpha = 0.40f)
                    )
                ),
                shape = pillShape
            )
            .bouncyClickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
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
            text = "Sync",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Interactive Track Actions bottom sheet triggered when tapping Sync on any track card.
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
                .background(Color.Black.copy(alpha = 0.72f))
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
                    .clip(RoundedCornerShape(30.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF121622),
                                Color(0xFF080A10)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                theme.primaryAccent.copy(alpha = 0.45f),
                                theme.secondaryAccent.copy(alpha = 0.30f)
                            )
                        ),
                        shape = RoundedCornerShape(30.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(62.dp)
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
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Track Insights",
                            color = theme.primaryAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryMiniPill(
                        label = "Mood",
                        value = detectedMood.label,
                        accent = theme.primaryAccent,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryMiniPill(
                        label = "Freshness",
                        value = if (isCoolingDown) "Cooling" else "Fresh",
                        accent = ringColor,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryMiniPill(
                        label = "Plays",
                        value = "${playCount}x",
                        accent = theme.secondaryAccent,
                        modifier = Modifier.weight(1f)
                    )
                }

                ActionSheetButton(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Ask TuneMind about this track",
                    subtitle = "Hear why it fits your flow or ask for a similar pick",
                    accentColor = theme.primaryAccent,
                    isPrimary = true,
                    onClick = {
                        onDismiss()
                        onAgentNeuralBreakdown()
                    }
                )

                ActionSheetButton(
                    icon = Icons.Rounded.PlayArrow,
                    title = "Play now",
                    subtitle = "Start playback in your selected player",
                    accentColor = Color(0xFF10B981),
                    isPrimary = false,
                    onClick = {
                        onDismiss()
                        onPlayNow()
                    }
                )

                ActionSheetButton(
                    icon = Icons.Rounded.Palette,
                    title = "Match theme to ${detectedMood.label}",
                    subtitle = "Shift studio lighting to match this track's mood",
                    accentColor = theme.secondaryAccent,
                    isPrimary = false,
                    onClick = {
                        onDismiss()
                        onSyncAtmosphereToTrack()
                    }
                )

                ActionSheetButton(
                    icon = if (isCoolingDown) Icons.Rounded.Bolt else Icons.Rounded.AcUnit,
                    title = if (isCoolingDown) "Return to Fresh" else "Rest in Vault",
                    subtitle = if (isCoolingDown) "Move this track back into active rotation"
                               else "Give this song a break so it feels new again",
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
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.50f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActionSheetButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isPrimary) accentColor.copy(alpha = 0.16f)
                else Color.White.copy(alpha = 0.04f)
            )
            .border(
                width = 1.dp,
                color = if (isPrimary) accentColor.copy(alpha = 0.45f)
                        else Color.White.copy(alpha = 0.08f),
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
                .background(accentColor.copy(alpha = 0.18f))
                .border(1.dp, accentColor.copy(alpha = 0.42f), CircleShape)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(19.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 11.sp
            )
        }
    }
}
