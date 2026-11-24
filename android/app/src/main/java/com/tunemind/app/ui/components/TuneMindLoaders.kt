package com.tunemind.app.ui.components

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Lightweight device capability detector cached once per process so TuneMind never allocates
 * during Compose frames while keeping budget / <4GB phones fast and responsive.
 */
object TuneMindDevicePerf {
    @Volatile
    private var cachedIsLowTier: Boolean? = null

    fun isLowTierDevice(context: Context): Boolean {
        cachedIsLowTier?.let { return it }
        val result = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching false
            if (am.isLowRamDevice) return@runCatching true
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            memInfo.totalMem in 1L..3_800_000_000L
        }.getOrDefault(false)
        cachedIsLowTier = result
        return result
    }
}

@Composable
fun rememberIsLowTierDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) { TuneMindDevicePerf.isLowTierDevice(context) }
}

/**
 * Branded TuneMind orbital acoustic loader replacing stock Material spinners across sheets,
 * cards, and buttons with minimal GPU overhead.
 */
@Composable
fun TuneMindStudioLoader(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = LocalAdaptiveTheme.current.primaryAccent,
    secondaryColor: Color = LocalAdaptiveTheme.current.secondaryAccent,
    strokeWidth: Dp = 2.2.dp
) {
    val transition = rememberInfiniteTransition(label = "tm_studio_loader")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tm_loader_angle"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tm_loader_pulse"
    )

    val sweepBrush = remember(color, secondaryColor) {
        Brush.sweepGradient(
            colors = listOf(
                Color.Transparent,
                secondaryColor.copy(alpha = 0.55f),
                color
            )
        )
    }

    Canvas(modifier = modifier.size(size)) {
        val strokePx = strokeWidth.toPx()
        drawCircle(
            color = color.copy(alpha = 0.16f),
            style = Stroke(width = strokePx)
        )
        rotate(degrees = angle) {
            drawArc(
                brush = sweepBrush,
                startAngle = 0f,
                sweepAngle = 250f,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
        drawCircle(
            color = color.copy(alpha = 0.72f * pulse),
            radius = (this.size.minDimension * 0.14f).coerceAtLeast(1.5f)
        )
    }
}

/**
 * Smooth dark-glass shimmer skeleton list matching TuneMindTrackCard layout.
 * Shares a single animation driver across all skeleton rows so list loading is zero-jank.
 */
@Composable
fun TuneMindShimmerTrackSkeletonList(
    count: Int = 5,
    accentColor: Color = LocalAdaptiveTheme.current.primaryAccent,
    modifier: Modifier = Modifier
) {
    val isLowTier = rememberIsLowTierDevice()
    val shimmerOffset = if (isLowTier) {
        0.5f
    } else {
        val inf = rememberInfiniteTransition(label = "tm_track_shimmer")
        inf.animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1250, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "tm_shimmer_sweep"
        ).value
    }

    val barBrush = remember(shimmerOffset, accentColor) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.05f),
                accentColor.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.05f)
            ),
            start = Offset(shimmerOffset * 600f - 220f, 0f),
            end = Offset(shimmerOffset * 600f + 220f, 120f)
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(count.coerceIn(1, 10)) { idx ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(barBrush)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (idx % 2 == 0) 0.62f else 0.74f)
                            .height(13.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(barBrush)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (idx % 2 == 0) 0.44f else 0.52f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(barBrush)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(barBrush)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color.White.copy(alpha = 0.06f))
            )
        }
    }
}
