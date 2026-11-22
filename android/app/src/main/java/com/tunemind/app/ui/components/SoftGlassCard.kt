package com.tunemind.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val rimAccent = accentTint ?: theme.primaryAccent

    val bgBrush = remember(theme.elevatedSurface, theme.cardSurface, theme.primaryBackground, rimAccent) {
        val topSurface = lerp(theme.elevatedSurface, rimAccent, 0.03f).copy(alpha = 0.88f)
        val bottomSurface = lerp(theme.cardSurface, theme.primaryBackground, 0.18f).copy(alpha = 0.94f)
        Brush.verticalGradient(colors = listOf(topSurface, bottomSurface))
    }

    val borderBrush = remember(rimAccent) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.10f),
                rimAccent.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.05f)
            )
        )
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(brush = bgBrush)
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
 * Miniature animated AI neural orbit emblem rendered directly inside AI-powered tabs (e.g. For You).
 */
@Composable
private fun CompactAiTabLogo(
    isSelected: Boolean,
    hasFreshDrop: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "compact_ai_tab_logo")
    val orbitAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(5600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tab_orbit_angle"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tab_star_pulse"
    )

    val aiCyan = Color(0xFF38BDF8)
    val aiViolet = Color(0xFFA855F7)
    val aiGold = Color(0xFFFBBF24)
    val orbitBrush = remember {
        Brush.sweepGradient(
            colors = listOf(
                aiCyan,
                aiViolet,
                aiGold,
                aiCyan.copy(alpha = 0.2f)
            )
        )
    }
    val starBrush = remember(hasFreshDrop) {
        Brush.linearGradient(
            colors = if (hasFreshDrop) {
                listOf(Color(0xFFF8FAFC), aiCyan)
            } else {
                listOf(Color(0xFFE2E8F0), aiViolet)
            }
        )
    }
    val starPath = remember { Path() }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f

        rotate(degrees = orbitAngle, pivot = Offset(cx, cy)) {
            drawArc(
                brush = orbitBrush,
                startAngle = 0f,
                sweepAngle = 275f,
                useCenter = false,
                topLeft = Offset(cx - r * 0.84f, cy - r * 0.84f),
                size = Size(r * 1.68f, r * 1.68f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        val starR = r * 0.52f * (if (isSelected) pulse else 0.92f)
        val innerR = starR * 0.32f
        starPath.rewind()
        starPath.moveTo(cx, cy - starR)
        starPath.lineTo(cx + innerR, cy - innerR)
        starPath.lineTo(cx + starR, cy)
        starPath.lineTo(cx + innerR, cy + innerR)
        starPath.lineTo(cx, cy + starR)
        starPath.lineTo(cx - innerR, cy + innerR)
        starPath.lineTo(cx - starR, cy)
        starPath.lineTo(cx - innerR, cy - innerR)
        starPath.close()
        drawPath(
            path = starPath,
            brush = starBrush
        )
    }
}

/**
 * Spring-animated sliding capsule tab switcher with generous spacing,
 * adaptive glow indicator, and smooth per-tab accent tinting.
 */
data class MagicalTabItem(
    val label: String,
    val accent: Color? = null,
    val isAiHighlighted: Boolean = false,
    val hasFreshDrop: Boolean = false
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

    val outerShape = RoundedCornerShape(14.dp)
    val trackColor = theme.recessedBackground.copy(alpha = 0.76f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(outerShape)
            .background(trackColor)
            .border(
                width = 0.8.dp,
                color = Color.White.copy(alpha = 0.08f),
                shape = outerShape
            )
            .padding(4.dp)
    ) {
        val tabGap = 4.dp
        val totalGaps = tabGap * (tabs.size - 1).coerceAtLeast(0)
        val slotWidth = ((maxWidth - totalGaps) / tabs.size).coerceAtLeast(1.dp)
        val targetOffset = (slotWidth + tabGap) * safeIndex

        val indicatorOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "magical_tab_offset"
        )

        val pillShape = RoundedCornerShape(10.dp)

        // Sliding tonal capsule behind selected tab
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(slotWidth)
                .fillMaxHeight()
                .clip(pillShape)
                .background(animatedAccent.copy(alpha = 0.16f))
                .border(
                    width = 0.8.dp,
                    color = animatedAccent.copy(alpha = 0.36f),
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
                val textColor by animateColorAsState(
                    targetValue = when {
                        isSelected -> Color(0xFFFAFAFA)
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
                            CompactAiTabLogo(
                                isSelected = isSelected,
                                hasFreshDrop = tab.hasFreshDrop,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        Text(
                            text = tab.label,
                            color = textColor,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected || tab.isAiHighlighted) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (tab.isAiHighlighted && tab.hasFreshDrop && !isSelected) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF34D399))
                            )
                        }
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
