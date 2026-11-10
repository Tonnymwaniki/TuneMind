package com.tunemind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SoftGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // 32.dp rounding creates a "Squircle" effect
    val squircleShape = RoundedCornerShape(32.dp)
    
    Box(
        modifier = modifier
            .clip(squircleShape)
            .background(Color.White.copy(alpha = 0.04f)) // Frosted translucent feel
            .border(1.dp, Color.White.copy(alpha = 0.08f), squircleShape) // Microscopic light catch
            .padding(20.dp)
    ) {
        content()
    }
}
