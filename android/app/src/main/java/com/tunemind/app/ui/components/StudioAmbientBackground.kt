package com.tunemind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
    val deepBg = remember(midBg, theme.recessedBackground) {
        lerp(midBg, theme.recessedBackground, 0.42f)
    }

    val topGlowAlpha = if (isActivePulse) (0.20f * intensity).coerceIn(0.10f, 0.24f)
    else (0.14f * intensity).coerceIn(0.07f, 0.17f)
    val bottomGlowAlpha = if (isActivePulse) (0.11f * intensity).coerceIn(0.05f, 0.14f)
    else (0.07f * intensity).coerceIn(0.03f, 0.10f)

    val isLowTier = rememberIsLowTierDevice()
    val bgBrush = remember(topBg, midBg, deepBg) {
        Brush.verticalGradient(colors = listOf(topBg, midBg, deepBg))
    }

    val glowModifier = remember(topAccent, bottomAccent, topGlowAlpha, bottomGlowAlpha, isLowTier) {
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val topCenter = Offset(w * 0.50f, h * 0.08f)
                val topRadius = (w * 1.05f).coerceAtLeast(1f)
                val topBrush = Brush.radialGradient(
                    colors = listOf(
                        topAccent.copy(alpha = topGlowAlpha),
                        topAccent.copy(alpha = topGlowAlpha * 0.32f),
                        Color.Transparent
                    ),
                    center = topCenter,
                    radius = topRadius
                )
                val lowerCenter = Offset(w * 0.82f, h * 0.84f)
                val lowerRadius = (w * 0.90f).coerceAtLeast(1f)
                val lowerBrush = if (isLowTier) null else Brush.radialGradient(
                    colors = listOf(
                        bottomAccent.copy(alpha = bottomGlowAlpha),
                        Color.Transparent
                    ),
                    center = lowerCenter,
                    radius = lowerRadius
                )
                onDrawBehind {
                    drawCircle(brush = topBrush, center = topCenter, radius = topRadius)
                    if (lowerBrush != null) {
                        drawCircle(brush = lowerBrush, center = lowerCenter, radius = lowerRadius)
                    }
                }
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush = bgBrush)
    ) {
        Spacer(modifier = glowModifier)
        content()
    }
}
