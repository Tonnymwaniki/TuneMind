package com.tunemind.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single crisp launch reveal displaying TuneMind's woven-wave T monogram
 * on an open #07090E dark studio canvas.
 */
@Composable
fun TuneMindSplashOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logoScale = remember { Animatable(0.88f) }
    val contentAlpha = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch {
            logoScale.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
        launch {
            contentAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            )
        }

        delay(680L)
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
            .background(Color(0xFF07090E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .alpha(contentAlpha.value)
                .scale(logoScale.value),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            TuneMindLogoMark(
                size = 96.dp,
                showContainer = true,
                animatedWave = true
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "TuneMind",
                color = Color(0xFFF8FAFC),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp
            )
        }
    }
}
