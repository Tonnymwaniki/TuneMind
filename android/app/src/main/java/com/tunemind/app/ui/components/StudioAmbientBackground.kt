package com.tunemind.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Adaptive atmospheric canvas driven live by [LocalAdaptiveTheme].
 * Blends circadian time-of-day palettes (Glacier, Emerald, Ember, Violet)
 * with live song mood and energy intensity while preserving dark contrast.
 */
@Composable
fun StudioAmbientBackground(
    modifier: Modifier = Modifier,
    accentOverride: Color? = null,
    isActivePulse: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val topAccent = accentOverride ?: theme.primaryAccent
    val bottomAccent = theme.secondaryAccent
    val intensity = theme.gradientIntensity.coerceIn(0.25f, 0.85f)

    val topBg = theme.primaryBackground
    val midBg = theme.secondaryBackground
    val deepBg = lerp(theme.secondaryBackground, theme.recessedBackground, 0.42f)

    val topGlowAlpha = if (isActivePulse) (0.20f * intensity).coerceIn(0.08f, 0.24f)
    else (0.14f * intensity).coerceIn(0.06f, 0.17f)
    val bottomGlowAlpha = if (isActivePulse) (0.12f * intensity).coerceIn(0.05f, 0.15f)
    else (0.08f * intensity).coerceIn(0.04f, 0.11f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(topBg, midBg, deepBg)
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val topCenter = Offset(w * 0.50f, h * 0.08f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        topAccent.copy(alpha = topGlowAlpha),
                        topAccent.copy(alpha = topGlowAlpha * 0.32f),
                        Color.Transparent
                    ),
                    center = topCenter,
                    radius = w * 1.05f
                ),
                center = topCenter,
                radius = w * 1.05f
            )

            val lowerCenter = Offset(w * 0.82f, h * 0.84f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        bottomAccent.copy(alpha = bottomGlowAlpha),
                        Color.Transparent
                    ),
                    center = lowerCenter,
                    radius = w * 0.90f
                ),
                center = lowerCenter,
                radius = w * 0.90f
            )
        }

        content()
    }
}
