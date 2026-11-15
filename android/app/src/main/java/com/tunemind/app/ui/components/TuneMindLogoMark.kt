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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Original TuneMind emblem: a clean geometric "T" monogram seamlessly woven
 * with a continuous flowing soundwave ribbon.
 */
@Composable
fun TuneMindLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    showContainer: Boolean = true,
    animatedWave: Boolean = false
) {
    val waveShift = if (animatedWave) {
        val infinite = rememberInfiniteTransition(label = "tm_logo_wave")
        val anim by infinite.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "tm_wave_shift"
        )
        anim
    } else {
        0f
    }

    val containerModifier = if (showContainer) {
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Color(0xFF0E131D))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(size * 0.28f))
    } else {
        modifier.size(size)
    }

    Box(
        modifier = containerModifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.72f)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val dy = waveShift * (h * 0.035f)

            val tStroke = (w * 0.115f).coerceAtLeast(2.6.dp.toPx())
            val waveStroke = (w * 0.082f).coerceAtLeast(2.dp.toPx())

            val amber = Color(0xFFFFB000)
            val mint = Color(0xFF10B981)
            val brightMint = Color(0xFF34D399)
            val pureWhite = Color(0xFFF8FAFC)

            // 1. Back half of the woven soundwave passing behind the T stem
            val leftWave = Path().apply {
                moveTo(w * 0.04f, cy + h * 0.12f - dy)
                cubicTo(
                    w * 0.16f, cy + h * 0.12f - dy,
                    w * 0.22f, cy - h * 0.13f + dy,
                    w * 0.34f, cy - h * 0.13f + dy
                )
                cubicTo(
                    w * 0.42f, cy - h * 0.13f + dy,
                    w * 0.45f, cy,
                    cx, cy
                )
            }
            drawPath(
                path = leftWave,
                brush = Brush.horizontalGradient(
                    colors = listOf(amber, brightMint),
                    startX = 0f,
                    endX = cx
                ),
                style = Stroke(width = waveStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // 2. Crisp architectural "T" monogram
            val topY = h * 0.16f
            val bottomY = h * 0.86f
            val barHalf = w * 0.34f
            drawLine(
                color = pureWhite,
                start = Offset(cx - barHalf, topY),
                end = Offset(cx + barHalf, topY),
                strokeWidth = tStroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = pureWhite,
                start = Offset(cx, topY),
                end = Offset(cx, bottomY),
                strokeWidth = tStroke,
                cap = StrokeCap.Round
            )

            // 3. Front half of the woven soundwave gliding across the T stem
            val rightWave = Path().apply {
                moveTo(cx, cy)
                cubicTo(
                    w * 0.55f, cy,
                    w * 0.58f, cy + h * 0.14f - dy,
                    w * 0.67f, cy + h * 0.14f - dy
                )
                cubicTo(
                    w * 0.78f, cy + h * 0.14f - dy,
                    w * 0.84f, cy - h * 0.10f + dy,
                    w * 0.96f, cy - h * 0.10f + dy
                )
            }
            drawPath(
                path = rightWave,
                brush = Brush.horizontalGradient(
                    colors = listOf(brightMint, mint),
                    startX = cx,
                    endX = w
                ),
                style = Stroke(width = waveStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
