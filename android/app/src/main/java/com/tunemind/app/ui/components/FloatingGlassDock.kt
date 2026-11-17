package com.tunemind.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.voice.AgentState
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Floating translucent glass capsule dock with fluid spring-gliding active pill,
 * subtle adaptive border sheen, and responsive icon bounce.
 */
@Composable
fun FloatingGlassDock(
    currentPage: Int,
    agentState: AgentState,
    freshTrackCount: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalAdaptiveTheme.current
    val safePage = currentPage.coerceIn(0, 2)

    val agentAccentColor = when (agentState) {
        AgentState.LISTENING -> Color(0xFF00E5FF)
        AgentState.THINKING -> Color(0xFFA855F7)
        AgentState.SPEAKING -> Color(0xFFFFB000)
        AgentState.ERROR -> Color(0xFFFF4D6D)
        AgentState.IDLE -> theme.primaryAccent
    }

    val centerLabel = when (agentState) {
        AgentState.LISTENING -> "Listening"
        AgentState.THINKING -> "Thinking"
        AgentState.SPEAKING -> "Speaking"
        AgentState.ERROR -> "Retry"
        AgentState.IDLE -> "TuneMind"
    }

    val targetAccent = when (safePage) {
        0 -> theme.primaryAccent
        2 -> theme.secondaryAccent
        else -> agentAccentColor
    }
    val activeAccent by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = tween(260),
        label = "dock_active_accent"
    )

    val dockShape = RoundedCornerShape(30.dp)
    val dockSurfaceTop = Color(0xFF141419).copy(alpha = 0.97f)
    val dockSurfaceBottom = Color(0xFF0C0C10).copy(alpha = 0.98f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = dockShape,
                    ambientColor = Color.Black.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.55f)
                )
                .clip(dockShape)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(dockSurfaceTop, dockSurfaceBottom)
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.06f)
                        )
                    ),
                    shape = dockShape
                )
                .padding(5.dp)
        ) {
            val slotGap = 6.dp
            val slotWidth = ((maxWidth - (slotGap * 2)) / 3f).coerceAtLeast(1.dp)
            val targetOffset = (slotWidth + slotGap) * safePage

            val sliderOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "dock_slider_offset"
            )

            val pillShape = RoundedCornerShape(24.dp)

            // Fluid sliding glowing capsule behind active tab
            Box(
                modifier = Modifier
                    .offset(x = sliderOffset)
                    .width(slotWidth)
                    .fillMaxHeight()
                    .clip(pillShape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                activeAccent.copy(alpha = 0.25f),
                                activeAccent.copy(alpha = 0.10f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                activeAccent.copy(alpha = 0.56f),
                                activeAccent.copy(alpha = 0.18f)
                            )
                        ),
                        shape = pillShape
                    )
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(slotGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomDockTab(
                    label = if (freshTrackCount > 0 && safePage == 0) "Fresh · $freshTrackCount" else "Fresh",
                    icon = Icons.Rounded.Bolt,
                    isSelected = safePage == 0,
                    activeColor = theme.primaryAccent,
                    onClick = { onSelectPage(0) },
                    modifier = Modifier.weight(1f)
                )
                BottomDockTab(
                    label = centerLabel,
                    icon = Icons.Rounded.GraphicEq,
                    isSelected = safePage == 1,
                    activeColor = agentAccentColor,
                    onClick = { onSelectPage(1) },
                    modifier = Modifier.weight(1f)
                )
                BottomDockTab(
                    label = "Flow",
                    icon = Icons.Rounded.LibraryMusic,
                    isSelected = safePage == 2,
                    activeColor = theme.secondaryAccent,
                    onClick = { onSelectPage(2) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BottomDockTab(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.54f),
        animationSpec = tween(200),
        label = "tabColor"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) activeColor else Color.White.copy(alpha = 0.54f),
        animationSpec = tween(200),
        label = "iconColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.12f else 0.96f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "iconScale"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
