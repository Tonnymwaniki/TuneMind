package com.tunemind.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun AnimatedEqualizerBars(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "live_eq_bars")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(620, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq_phase"
    )

    Canvas(modifier = modifier) {
        val barWidth = (size.width / 6.5f).coerceAtLeast(2.dp.toPx())
        val gap = (size.width - barWidth * 4f) / 3f
        val maxH = size.height
        val heights = listOf(
            maxH * (0.40f + 0.50f * phase),
            maxH * (0.95f - 0.38f * phase),
            maxH * (0.52f + 0.44f * phase),
            maxH * (0.82f - 0.34f * phase)
        )
        heights.forEachIndexed { idx, h ->
            val x = idx * (barWidth + gap)
            val y = maxH - h
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/**
 * Redesigned unified dark-studio track card used consistently across Dopamine Vault,
 * Rhythm History, Cross-App Stats, Spotify Library, and Weekly Recap.
 *
 * Enforces:
 * - Accurate verified play count badge everywhere the track appears
 * - Live "Playing" indicator & equalizer whenever [isCurrentlyPlaying] is true
 */
@Composable
fun TuneMindTrackCard(
    title: String,
    artist: String,
    playCount: Int,
    isCurrentlyPlaying: Boolean,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    appLabel: String? = null,
    freshnessPercent: Int? = null,
    isCoolingDown: Boolean = false,
    accentColor: Color = LocalAdaptiveTheme.current.primaryAccent,
    secondaryAccent: Color = LocalAdaptiveTheme.current.secondaryAccent,
    leadingIndex: Int? = null,
    showUnplayedLabel: Boolean = false,
    onSyncClick: (() -> Unit)? = null,
    onCardClick: (() -> Unit)? = null
) {
    val liveGreen = Color(0xFF10B981)
    val coolingAmber = Color(0xFFFFB000)

    val effectiveAccent = when {
        isCurrentlyPlaying -> liveGreen
        isCoolingDown -> coolingAmber
        else -> accentColor
    }

    val cardShape = RoundedCornerShape(22.dp)
    val baseModifier = modifier
        .fillMaxWidth()
        .clip(cardShape)
        .background(
            if (isCurrentlyPlaying) liveGreen.copy(alpha = 0.13f)
            else Color(0xFF0C1018).copy(alpha = 0.92f)
        )
        .border(
            width = if (isCurrentlyPlaying) 1.3.dp else 1.dp,
            color = if (isCurrentlyPlaying) liveGreen.copy(alpha = 0.62f)
            else Color.White.copy(alpha = 0.08f),
            shape = cardShape
        )
        .let { mod ->
            if (onCardClick != null) mod.bouncyClickable { onCardClick() } else mod
        }
        .padding(horizontal = 14.dp, vertical = 12.dp)

    Row(
        modifier = baseModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (leadingIndex != null) {
            Text(
                text = "$leadingIndex",
                color = if (isCurrentlyPlaying) liveGreen else Color.White.copy(alpha = 0.44f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.widthIn(min = 18.dp)
            )
        }

        // Play / Live Equalizer Button (with optional freshness arc)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .bouncyClickable { onPlayClick() }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = effectiveAccent.copy(alpha = if (isCurrentlyPlaying) 0.22f else 0.14f))
                if (freshnessPercent != null) {
                    drawArc(
                        color = effectiveAccent.copy(alpha = 0.22f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = effectiveAccent,
                        startAngle = -90f,
                        sweepAngle = 360f * (freshnessPercent.coerceIn(5, 100) / 100f),
                        useCenter = false,
                        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                    )
                } else {
                    drawCircle(
                        color = effectiveAccent.copy(alpha = if (isCurrentlyPlaying) 0.72f else 0.34f),
                        style = Stroke(width = 1.4.dp.toPx())
                    )
                }
            }

            if (isCurrentlyPlaying) {
                AnimatedEqualizerBars(
                    color = liveGreen,
                    modifier = Modifier.size(18.dp, 15.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play $title",
                    tint = effectiveAccent,
                    modifier = Modifier.size(21.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(artist.ifBlank { "Unknown Artist" })
                    if (!subtitle.isNullOrBlank()) {
                        append(" • ")
                        append(subtitle)
                    }
                },
                color = Color.White.copy(alpha = 0.64f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Strict Play Count + Live Playing + App Source / Freshness Badges
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isCurrentlyPlaying) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(liveGreen.copy(alpha = 0.22f))
                            .border(1.dp, liveGreen.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = liveGreen,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "Playing",
                            color = liveGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Authoritative Play Count Badge across all screens
                val playText = when {
                    playCount > 0 -> "$playCount ${if (playCount == 1) "play" else "plays"}"
                    showUnplayedLabel -> "Unplayed"
                    else -> null
                }
                if (playText != null) {
                    val countColor = when {
                        playCount >= 5 -> coolingAmber
                        playCount > 0 -> effectiveAccent
                        else -> Color.White.copy(alpha = 0.54f)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (playCount > 0) countColor.copy(alpha = 0.14f)
                                else Color.White.copy(alpha = 0.06f)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = playText,
                            color = countColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                if (freshnessPercent != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isCoolingDown) "Cooling" else "${freshnessPercent}% Fresh",
                            color = if (isCoolingDown) coolingAmber else Color.White.copy(alpha = 0.78f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }

                if (!appLabel.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = appLabel,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        if (onSyncClick != null) {
            NeuralSonicActionButton(
                accentColor = effectiveAccent,
                secondaryColor = secondaryAccent,
                onClick = onSyncClick
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(effectiveAccent.copy(alpha = 0.16f))
                    .border(1.dp, effectiveAccent.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
                    .bouncyClickable { onPlayClick() }
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) {
                Text(
                    text = if (isCurrentlyPlaying) "Active" else "Play",
                    color = effectiveAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
