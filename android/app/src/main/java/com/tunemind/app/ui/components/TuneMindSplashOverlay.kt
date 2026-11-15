package com.tunemind.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import com.tunemind.app.R
import kotlinx.coroutines.delay

/**
 * Animated TuneMind splash reveal with glowing neural-purple aura,
 * custom half-cerebrum soundwave logo, and PERSONAL AI INTELLIGENCE lockup.
 */
@Composable
fun TuneMindSplashOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var startAnimation by remember { mutableStateOf(false) }
    val overlayAlpha = remember { Animatable(1f) }

    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        label = "SplashAlpha"
    )
    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1.0f else 0.8f,
        animationSpec = tween(durationMillis = 900, easing = EaseOutBack),
        label = "SplashScale"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(1350L)
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
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF07020F), Color(0xFF0E0E15))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Glowing background ambient aura
        Box(
            modifier = Modifier
                .size(250.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF8A2BE2).copy(alpha = 0.22f),
                            Color(0xFF00F0FF).copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
                .blur(40.dp)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .alpha(alphaAnim)
                .scale(scaleAnim)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_tunemind_logo),
                contentDescription = "TuneMind App Logo",
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(32.dp)),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "TUNEMIND",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 6.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "PERSONAL AI INTELLIGENCE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF00F0FF),
                letterSpacing = 3.sp
            )
        }
    }
}
