package com.tunemind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun SoftGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val squircleShape = RoundedCornerShape(24.dp)

    // Deep obsidian translucent glass lightly tinted by the active theme's card & accent hues
    val topGlass = lerp(Color(0xFF10131C), theme.cardSurface, 0.24f).copy(alpha = 0.86f)
    val bottomGlass = lerp(Color(0xFF080A10), theme.secondaryBackground, 0.20f).copy(alpha = 0.92f)

    Box(
        modifier = modifier
            .clip(squircleShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(topGlass, bottomGlass)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        theme.primaryAccent.copy(alpha = 0.24f),
                        Color.White.copy(alpha = 0.08f),
                        theme.secondaryAccent.copy(alpha = 0.12f)
                    )
                ),
                shape = squircleShape
            )
            .padding(18.dp)
    ) {
        content()
    }
}
