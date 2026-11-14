package com.tunemind.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TikTok-inspired dark studio splash reveal combining an animated circular sound-ring
 * and TuneMind's geometric "T" monogram with warm amber & cool cyan beat-split shimmer.
 */
@Composable
fun TuneMindSplashOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logoScale = remember { Animatable(0.76f) }
    val chromaticSplit = remember { Animatable(1f) }
    val ringSweep = remember { Animatable(0f) }
    val pulseRadius = remember { Animatable(0.7f) }
    val pulseAlpha = remember { Animatable(0.75f) }
    val textAlpha = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch {
            logoScale.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
        launch {
            ringSweep.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing)
            )
        }
        launch {
            delay(140L)
            chromaticSplit.animateTo(
                targetValue = 0.28f,
                animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
            )
        }
        launch {
            delay(180L)
            launch {
                pulseRadius.animateTo(1.55f, animationSpec = tween(680, easing = FastOutSlowInEasing))
            }
            launch {
                pulseAlpha.animateTo(0f, animationSpec = tween(680, easing = FastOutSlowInEasing))
            }
        }
        launch {
            delay(220L)
            textAlpha.animateTo(1f, animationSpec = tween(360, easing = FastOutSlowInEasing))
        }

        delay(980L)
        overlayAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 280, easing = FastOutLinearInEasing)
        )
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(overlayAlpha.value)
            .background(Color(0xFF05060A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(164.dp)
                    .scale(logoScale.value),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val ringRadius = size.minDimension * 0.34f
                    val cyanColor = Color(0xFF00E5FF)
                    val amberColor = Color(0xFFFFB000)

                    // Expanding shockwave ring
                    if (pulseAlpha.value > 0.01f) {
                        drawCircle(
                            color = cyanColor.copy(alpha = pulseAlpha.value * 0.35f),
                            radius = ringRadius * pulseRadius.value,
                            center = Offset(cx, cy),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // Subtle studio aura behind emblem
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                cyanColor.copy(alpha = 0.16f),
                                amberColor.copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            center = Offset(cx, cy),
                            radius = ringRadius * 1.85f
                        ),
                        radius = ringRadius * 1.85f,
                        center = Offset(cx, cy)
                    )

                    val arcTopLeft = Offset(cx - ringRadius, cy - ringRadius)
                    val arcSize = Size(ringRadius * 2f, ringRadius * 2f)
                    val splitOffset = 5.5.dp.toPx() * chromaticSplit.value

                    // Cyan circular sound-ring arc (upper-left beat)
                    drawArc(
                        color = cyanColor,
                        startAngle = 155f,
                        sweepAngle = 155f * ringSweep.value,
                        useCenter = false,
                        topLeft = Offset(arcTopLeft.x - splitOffset * 0.45f, arcTopLeft.y - splitOffset * 0.45f),
                        size = arcSize,
                        style = Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Amber circular sound-ring arc (lower-right beat)
                    drawArc(
                        color = amberColor,
                        startAngle = -25f,
                        sweepAngle = 155f * ringSweep.value,
                        useCenter = false,
                        topLeft = Offset(arcTopLeft.x + splitOffset * 0.45f, arcTopLeft.y + splitOffset * 0.45f),
                        size = arcSize,
                        style = Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Thin crisp outer hairline circle
                    drawCircle(
                        color = Color.White.copy(alpha = 0.18f * ringSweep.value),
                        radius = ringRadius * 1.15f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.dp.toPx())
                    )

                    // Helper to build the geometric "T" monogram path at a given offset
                    fun buildTPath(dx: Float, dy: Float): Path {
                        val topBarHalf = ringRadius * 0.46f
                        val topY = cy - ringRadius * 0.34f + dy
                        val bottomY = cy + ringRadius * 0.44f + dy
                        val x = cx + dx
                        return Path().apply {
                            moveTo(x - topBarHalf, topY)
                            lineTo(x + topBarHalf, topY)
                            moveTo(x, topY)
                            lineTo(x, bottomY)
                        }
                    }

                    val strokeWidth = 7.dp.toPx()

                    // Cyan left beat layer
                    drawPath(
                        path = buildTPath(-splitOffset, -splitOffset * 0.35f),
                        color = cyanColor.copy(alpha = 0.88f),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Amber right beat layer
                    drawPath(
                        path = buildTPath(splitOffset, splitOffset * 0.35f),
                        color = amberColor.copy(alpha = 0.88f),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Luminous core white "T" monogram
                    drawPath(
                        path = buildTPath(0f, 0f),
                        color = Color.White,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "TuneMind",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.alpha(textAlpha.value)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "LISTEN IN FLOW",
                color = Color.White.copy(alpha = 0.46f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.2.sp,
                modifier = Modifier.alpha(textAlpha.value)
            )
        }
    }
}
