package com.tunemind.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.RenderMode
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
    @Suppress("UNUSED_PARAMETER") animatedWave: Boolean = false
) {
    val shape = remember(size) { RoundedCornerShape(size * 0.24f) }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        val frameModifier = if (showContainer) {
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Color(0xFF07020F))
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
            renderMode = RenderMode.HARDWARE,
            modifier = modifier.alpha(alpha)
        )
    }
}
