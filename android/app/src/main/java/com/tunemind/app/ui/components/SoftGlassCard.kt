package com.tunemind.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Dimensional frosted tonal card that adapts seamlessly to the active palette
 * while maintaining crisp edge definition and clean readability.
 */
@Composable
fun SoftGlassCard(
    modifier: Modifier = Modifier,
    accentTint: Color? = null,
    cornerRadius: Dp = 20.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val shape = RoundedCornerShape(cornerRadius)
    val rimAccent = accentTint ?: theme.primaryAccent

    val topSurface = lerp(theme.elevatedSurface, rimAccent, 0.08f).copy(alpha = 0.78f)
    val bottomSurface = lerp(theme.cardSurface, theme.primaryBackground, 0.25f).copy(alpha = 0.88f)

    val borderBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.16f),
            rimAccent.copy(alpha = 0.22f),
            theme.border.copy(alpha = 0.42f)
        )
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(topSurface, bottomSurface)
                )
            )
            .border(
                width = 1.dp,
                brush = borderBrush,
                shape = shape
            )
            .padding(contentPadding)
    ) {
        content()
    }
}

/**
 * Spring-animated sliding capsule tab switcher with generous spacing,
 * adaptive glow indicator, and smooth per-tab accent tinting.
 */
data class MagicalTabItem(
    val label: String,
    val accent: Color? = null,
    val isAiHighlighted: Boolean = false
)

@Composable
fun MagicalSegmentedTabs(
    tabs: List<MagicalTabItem>,
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp
) {
    if (tabs.isEmpty()) return
    val theme = LocalAdaptiveTheme.current
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    val activeAccent = tabs[safeIndex].accent ?: theme.primaryAccent
    val animatedAccent by animateColorAsState(
        targetValue = activeAccent,
        animationSpec = tween(240),
        label = "tab_accent_color"
    )

    val outerShape = RoundedCornerShape(16.dp)
    val trackColor = lerp(theme.cardSurface, Color(0xFF06080E), 0.38f).copy(alpha = 0.84f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(outerShape)
            .background(trackColor)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        theme.border.copy(alpha = 0.45f)
                    )
                ),
                shape = outerShape
            )
            .padding(4.dp)
    ) {
        val tabGap = 6.dp
        val totalGaps = tabGap * (tabs.size - 1).coerceAtLeast(0)
        val slotWidth = ((maxWidth - totalGaps) / tabs.size).coerceAtLeast(1.dp)
        val targetOffset = (slotWidth + tabGap) * safeIndex

        val indicatorOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = spring(
                dampingRatio = 0.74f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "magical_tab_offset"
        )

        val pillShape = RoundedCornerShape(12.dp)

        // Sliding glowing capsule behind selected tab
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(slotWidth)
                .fillMaxHeight()
                .clip(pillShape)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            animatedAccent.copy(alpha = 0.26f),
                            animatedAccent.copy(alpha = 0.12f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            animatedAccent.copy(alpha = 0.58f),
                            animatedAccent.copy(alpha = 0.22f)
                        )
                    ),
                    shape = pillShape
                )
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(tabGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { idx, tab ->
                val isSelected = idx == safeIndex
                val aiCyan = Color(0xFF38BDF8)
                val aiViolet = Color(0xFFA855F7)
                val tabAccent = tab.accent ?: theme.primaryAccent
                val textColor by animateColorAsState(
                    targetValue = when {
                        isSelected -> Color(0xFFF8FAFC)
                        tab.isAiHighlighted -> aiCyan.copy(alpha = 0.92f)
                        else -> Color.White.copy(alpha = 0.56f)
                    },
                    animationSpec = tween(180),
                    label = "tab_label_color_$idx"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pillShape)
                        .then(
                            if (tab.isAiHighlighted && !isSelected) {
                                Modifier
                                    .background(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                aiCyan.copy(alpha = 0.14f),
                                                aiViolet.copy(alpha = 0.14f)
                                            )
                                        )
                                    )
                                    .border(
                                        width = 0.8.dp,
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                aiCyan.copy(alpha = 0.48f),
                                                aiViolet.copy(alpha = 0.42f)
                                            )
                                        ),
                                        shape = pillShape
                                    )
                            } else {
                                Modifier
                            }
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelectTab(idx) }
                        )
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (tab.isAiHighlighted) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = "AI Powered",
                                tint = if (isSelected) Color(0xFF67E8F9) else tabAccent,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = tab.label,
                            color = textColor,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected || tab.isAiHighlighted) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StudioHairlineDivider(
    modifier: Modifier = Modifier
) {
    val theme = LocalAdaptiveTheme.current
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = lerp(Color.White.copy(alpha = 0.08f), theme.border.copy(alpha = 0.35f), 0.35f)
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
            color = theme.primaryText,
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
