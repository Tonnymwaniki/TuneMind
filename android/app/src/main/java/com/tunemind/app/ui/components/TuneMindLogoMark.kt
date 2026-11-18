package com.tunemind.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.tunemind.app.R

/**
 * Custom TuneMind brand emblem: half-cerebrum neural circuits merging cleanly
 * into luminous cyan soundwaves and musical note artwork on dark obsidian indigo.
 */
@Composable
fun TuneMindLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    showContainer: Boolean = true,
    animatedWave: Boolean = false
) {
    val glowPulse = if (animatedWave) {
        val infinite = rememberInfiniteTransition(label = "tm_logo_glow")
        val anim by infinite.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "tm_glow_scale"
        )
        anim
    } else {
        1.0f
    }

    val shape = RoundedCornerShape(size * 0.24f)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (animatedWave) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(glowPulse)
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF22D3EE).copy(alpha = 0.14f),
                            Color(0xFF6366F1).copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    ),
                    radius = this.size.minDimension * 0.62f
                )
            }
        }

        val frameModifier = if (showContainer) {
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Color(0xFF07020F))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF22D3EE).copy(alpha = 0.36f),
                            Color(0xFF6366F1).copy(alpha = 0.34f),
                            Color(0xFF2DD4BF).copy(alpha = 0.30f)
                        )
                    ),
                    shape = shape
                )
        } else {
            Modifier
                .fillMaxSize()
                .clip(shape)
        }

        Image(
            painter = painterResource(id = R.drawable.tunemind_logo_badge),
            contentDescription = "TuneMind Logo",
            contentScale = ContentScale.Crop,
            modifier = frameModifier
        )
    }
}

/**
 * Glowing & pulsing Lottie AI neural circuit + soundwave loop (res/raw/ai_intelligence_loop.json).
 */
@Composable
fun TuneMindAiIntelligenceLoop(
    modifier: Modifier = Modifier,
    speed: Float = 1.0f,
    alpha: Float = 0.92f
) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.ai_intelligence_loop)
    )
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        speed = speed.coerceIn(0.4f, 2.5f),
        restartOnPlay = false
    )

    if (composition != null) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = modifier.alpha(alpha)
        )
    }
}
