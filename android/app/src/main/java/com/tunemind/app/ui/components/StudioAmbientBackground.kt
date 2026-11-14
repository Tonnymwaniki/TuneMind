package com.tunemind.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Cohesive Dark Studio Ambient backdrop layered on top of the active Adaptive Theme.
 * Keeps every screen anchored in deep carbon obsidian (#05060A -> #090C14) while letting
 * the user's adaptive theme or chosen mood shift the ambient studio lighting softly.
 */
@Composable
fun StudioAmbientBackground(
    modifier: Modifier = Modifier,
    accentOverride: Color? = null,
    isActivePulse: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val primaryGlow = accentOverride ?: theme.primaryAccent
    val secondaryGlow = theme.secondaryAccent

    // Blend a very subtle hint of the adaptive palette into deep pitch-obsidian studio base
    val studioTop = lerp(Color(0xFF06070B), theme.primaryBackground, 0.14f)
    val studioMid = lerp(Color(0xFF090B12), theme.secondaryBackground, 0.16f)
    val studioBottom = Color(0xFF040508)

    val infiniteTransition = rememberInfiniteTransition(label = "studio_ambient")
    val driftPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActivePulse) 4200 else 7600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambient_drift"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(studioTop, studioMid, studioBottom)
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Soft upper overhead studio key-light tinted with active theme primaryAccent
            val topAlpha = if (isActivePulse) 0.14f + 0.04f * driftPhase else 0.10f + 0.03f * driftPhase
            val topCenter = Offset(w * (0.45f + 0.12f * driftPhase), h * 0.14f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = topAlpha),
                        primaryGlow.copy(alpha = topAlpha * 0.35f),
                        Color.Transparent
                    ),
                    center = topCenter,
                    radius = w * 0.92f
                ),
                center = topCenter,
                radius = w * 0.92f
            )

            // Secondary lower fill light using secondaryAccent
            val lowAlpha = if (isActivePulse) 0.10f + 0.03f * (1f - driftPhase) else 0.07f + 0.02f * (1f - driftPhase)
            val lowCenter = Offset(w * (0.78f - 0.14f * driftPhase), h * 0.78f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        secondaryGlow.copy(alpha = lowAlpha),
                        secondaryGlow.copy(alpha = lowAlpha * 0.3f),
                        Color.Transparent
                    ),
                    center = lowCenter,
                    radius = w * 0.85f
                ),
                center = lowCenter,
                radius = w * 0.85f
            )

            // Subtle dark edge vignette so foreground glass cards stay crisp
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.22f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.36f)
                    )
                )
            )
        }

        content()
    }
}
