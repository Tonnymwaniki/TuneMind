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
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Clean obsidian canvas (#07090E) with a subtle ambient key light.
 * Avoids noisy gradient blobs so foreground typography and hairline lists breathe.
 */
@Composable
fun StudioAmbientBackground(
    modifier: Modifier = Modifier,
    accentOverride: Color? = null,
    isActivePulse: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val topGlow = accentOverride ?: theme.primaryAccent
    val glowAlpha = if (isActivePulse) 0.07f else 0.045f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w * 0.5f, h * 0.08f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        topGlow.copy(alpha = glowAlpha),
                        Color.Transparent
                    ),
                    center = center,
                    radius = w * 0.95f
                ),
                center = center,
                radius = w * 0.95f
            )
        }

        content()
    }
}
