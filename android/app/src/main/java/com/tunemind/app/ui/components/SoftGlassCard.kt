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
import androidx.compose.ui.unit.dp
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun SoftGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val squircleShape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier
            .clip(squircleShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        theme.cardSurface.copy(alpha = 0.72f),
                        theme.secondaryBackground.copy(alpha = 0.82f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        theme.primaryAccent.copy(alpha = 0.28f),
                        theme.border.copy(alpha = 0.55f),
                        theme.secondaryAccent.copy(alpha = 0.22f)
                    )
                ),
                shape = squircleShape
            )
            .padding(18.dp)
    ) {
        content()
    }
}
