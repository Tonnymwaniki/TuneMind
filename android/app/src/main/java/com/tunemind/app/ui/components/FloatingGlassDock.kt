package com.tunemind.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.voice.AgentState
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun FloatingGlassDock(
    currentPage: Int,
    agentState: AgentState,
    freshTrackCount: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalAdaptiveTheme.current
    val dockShape = RoundedCornerShape(30.dp)
    val isAgentActive = agentState != AgentState.IDLE

    val infiniteTransition = rememberInfiniteTransition(label = "DockPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "DockPulseAlpha"
    )

    val agentAccentColor = when (agentState) {
        AgentState.LISTENING -> Color(0xFF00E5FF)
        AgentState.THINKING -> Color(0xFFA855F7)
        AgentState.SPEAKING -> Color(0xFFFFB000)
        AgentState.ERROR -> Color(0xFFFF2A55)
        AgentState.IDLE -> theme.primaryAccent
    }

    val centerLabel = when (agentState) {
        AgentState.LISTENING -> "Listening"
        AgentState.THINKING -> "Thinking"
        AgentState.SPEAKING -> "Speaking"
        AgentState.ERROR -> "Retry"
        AgentState.IDLE -> "TuneMind"
    }

    val dockTop = lerp(Color(0xFF121622), theme.elevatedSurface, 0.25f).copy(alpha = 0.94f)
    val dockBottom = Color(0xFF07090F).copy(alpha = 0.97f)

    Box(
        modifier = modifier
            .height(58.dp)
            .widthIn(min = 296.dp, max = 344.dp)
            .clip(dockShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(dockTop, dockBottom)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        theme.primaryAccent.copy(alpha = 0.28f),
                        agentAccentColor.copy(alpha = if (isAgentActive) 0.68f else 0.36f),
                        theme.secondaryAccent.copy(alpha = 0.26f)
                    )
                ),
                shape = dockShape
            )
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // TAB 0: DOPAMINE
            DockPillItem(
                label = "Dopamine",
                isSelected = currentPage == 0,
                activeColor = theme.primaryAccent,
                badgeDotColor = if (freshTrackCount > 0) Color(0xFF10B981) else null,
                isCenterOrb = false,
                orbPulseAlpha = 1f,
                onClick = { onSelectPage(0) },
                modifier = Modifier.weight(1f)
            )

            // TAB 1: CENTER TUNEMIND LIVING SPHERE
            DockPillItem(
                label = centerLabel,
                isSelected = currentPage == 1,
                activeColor = agentAccentColor,
                badgeDotColor = agentAccentColor,
                isCenterOrb = true,
                orbPulseAlpha = if (isAgentActive) pulseAlpha else 0.9f,
                onClick = { onSelectPage(1) },
                modifier = Modifier.weight(1.08f)
            )

            // TAB 2: RHYTHM
            DockPillItem(
                label = "Rhythm",
                isSelected = currentPage == 2,
                activeColor = theme.secondaryAccent,
                badgeDotColor = null,
                isCenterOrb = false,
                orbPulseAlpha = 1f,
                onClick = { onSelectPage(2) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DockPillItem(
    label: String,
    isSelected: Boolean,
    activeColor: Color,
    badgeDotColor: Color?,
    isCenterOrb: Boolean,
    orbPulseAlpha: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(24.dp)
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) activeColor.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(250),
        label = "DockTabBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.60f),
        animationSpec = tween(250),
        label = "DockTabText"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .defaultMinSize(minWidth = 48.dp, minHeight = 46.dp)
            .clip(pillShape)
            .background(bgColor)
            .then(
                if (isSelected) {
                    Modifier.border(1.dp, activeColor.copy(alpha = 0.45f), pillShape)
                } else {
                    Modifier
                }
            )
            .bouncyClickable { onClick() }
            .padding(horizontal = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isCenterOrb) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(activeColor.copy(alpha = orbPulseAlpha))
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else if (badgeDotColor != null) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(badgeDotColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }

            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = if (isSelected || isCenterOrb) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
