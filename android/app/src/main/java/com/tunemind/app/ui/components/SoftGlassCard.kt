package com.tunemind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Calm single-elevation surface reserved for primary hero blocks (max 1 per screen).
 * Uses a restrained #0E131D surface with a clean 1dp hairline border.
 */
@Composable
fun SoftGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF0E131D))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.07f),
                shape = shape
            )
            .padding(18.dp)
    ) {
        content()
    }
}

@Composable
fun StudioHairlineDivider(
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = Color.White.copy(alpha = 0.06f)
    )
}

@Composable
fun StudioSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    val theme = LocalAdaptiveTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color(0xFFF8FAFC),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (!actionLabel.isNullOrBlank() && onActionClick != null) {
            Text(
                text = actionLabel,
                color = theme.primaryAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onActionClick() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}
