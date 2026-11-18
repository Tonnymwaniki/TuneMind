package com.tunemind.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.R
import kotlinx.coroutines.delay

/**
 * Clean, high-contrast TuneMind obsidian launch splash reveal.
 * Zero cloudy light glare: crisp Concept 2B emblem + expanding hairline acoustic pulse
 * rings + minimalist live micro-equalizer lockup on deep obsidian (#07020F).
 */
@Composable
fun TuneMindSplashOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var startReveal by remember { mutableStateOf(false) }
    val overlayAlpha = remember { Animatable(1f) }
    val ringWave = remember { Animatable(0f) }

    val logoAlpha by animateFloatAsState(
        targetValue = if (startReveal) 1f else 0f,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "SplashLogoAlpha"
    )
    val logoScale by animateFloatAsState(
        targetValue = if (startReveal) 1.0f else 0.88f,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        label = "SplashLogoScale"
    )
    val wordmarkAlpha by animateFloatAsState(
        targetValue = if (startReveal) 1f else 0f,
        animationSpec = tween(durationMillis = 560, delayMillis = 160, easing = FastOutSlowInEasing),
        label = "SplashWordmarkAlpha"
    )

    val eqTransition = rememberInfiniteTransition(label = "splash_eq")
    val eqPhase by eqTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splash_eq_phase"
    )

    LaunchedEffect(Unit) {
        startReveal = true
        ringWave.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1100, easing = FastOutSlowInEasing)
        )
        delay(180L)
        overlayAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 240, easing = FastOutLinearInEasing)
        )
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(overlayAlpha.value)
            .background(Color(0xFF07020F)),
        contentAlignment = Alignment.Center
    ) {
        // Crisp 1.2dp hairline acoustic pulse rings expanding outward into obsidian (zero blurry fog)
        Canvas(modifier = Modifier.size(280.dp)) {
            val t1 = ringWave.value.coerceIn(0f, 1f)
            val t2 = ((ringWave.value - 0.18f) / 0.82f).coerceIn(0f, 1f)
            if (t1 > 0f) {
                drawCircle(
                    color = Color(0xFF22D3EE).copy(alpha = (1f - t1) * 0.36f),
                    radius = (size.minDimension * 0.26f) + (size.minDimension * 0.24f * t1),
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
            if (t2 > 0f) {
                drawCircle(
                    color = Color(0xFF6366F1).copy(alpha = (1f - t2) * 0.28f),
                    radius = (size.minDimension * 0.22f) + (size.minDimension * 0.24f * t2),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_tunemind_logo),
                contentDescription = "TuneMind Logo",
                modifier = Modifier
                    .size(134.dp)
                    .alpha(logoAlpha)
                    .scale(logoScale),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(26.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(wordmarkAlpha)
            ) {
                Text(
                    text = "TUNEMIND",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC),
                    letterSpacing = 7.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Minimalist 5-bar live equalizer accent matching Concept 2B's Cyan-Indigo-Teal spectrum
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(16.dp)
                ) {
                    val heights = listOf(
                        6.dp + (6.dp * eqPhase),
                        10.dp + (5.dp * (1f - eqPhase)),
                        14.dp,
                        10.dp + (5.dp * (1f - eqPhase)),
                        6.dp + (6.dp * eqPhase)
                    )
                    val barColors = listOf(
                        Color(0xFF22D3EE),
                        Color(0xFF6366F1),
                        Color(0xFFF8FAFC),
                        Color(0xFF6366F1),
                        Color(0xFF2DD4BF)
                    )
                    heights.forEachIndexed { idx, h ->
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(h)
                                .clip(RoundedCornerShape(2.dp))
                                .background(barColors[idx])
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "PERSONAL AI MUSIC COMPANION",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF38BDF8).copy(alpha = 0.85f),
                    letterSpacing = 3.2.sp
                )
            }
        }
    }
}
