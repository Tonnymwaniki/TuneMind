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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.voice.AgentState

@Composable
fun FloatingGlassDock(
    currentPage: Int,
    agentState: AgentState,
    freshTrackCount: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dockShape = RoundedCornerShape(32.dp)
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
        AgentState.THINKING -> Color(0xFF7000FF)
        AgentState.SPEAKING -> Color(0xFFFFB000)
        AgentState.ERROR -> Color(0xFFFF2A55)
        AgentState.IDLE -> Color(0xFF00E5FF)
    }

    Box(
        modifier = modifier
            .height(60.dp)
            .widthIn(min = 288.dp, max = 336.dp)
            .clip(dockShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF141622).copy(alpha = 0.90f),
                        Color(0xFF0A0B12).copy(alpha = 0.95f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        agentAccentColor.copy(alpha = if (isAgentActive) 0.55f else 0.22f),
                        Color.White.copy(alpha = 0.12f)
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
            // TAB 0: DOPAMINE VAULT
            DockPillItem(
                label = "DOPAMINE",
                isSelected = currentPage == 0,
                activeColor = Color(0xFF00E5FF),
                badgeDotColor = if (freshTrackCount > 0) Color(0xFF10B981) else null,
                isCenterOrb = false,
                orbPulseAlpha = 1f,
                onClick = { onSelectPage(0) },
                modifier = Modifier.weight(1f)
            )

            // TAB 1: CENTER LIVING ORB (HOME)
            DockPillItem(
                label = if (isAgentActive) agentState.name else "AGENT",
                isSelected = currentPage == 1,
                activeColor = agentAccentColor,
                badgeDotColor = agentAccentColor,
                isCenterOrb = true,
                orbPulseAlpha = if (isAgentActive) pulseAlpha else 0.85f,
                onClick = { onSelectPage(1) },
                modifier = Modifier.weight(1.1f)
            )

            // TAB 2: RHYTHM DNA
            DockPillItem(
                label = "RHYTHM",
                isSelected = currentPage == 2,
                activeColor = Color(0xFF9D4EDD),
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
    val pillShape = RoundedCornerShape(26.dp)
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) activeColor.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(250),
        label = "DockTabBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.52f),
        animationSpec = tween(250),
        label = "DockTabText"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
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
            .padding(horizontal = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isCenterOrb) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(activeColor.copy(alpha = orbPulseAlpha))
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else if (badgeDotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(badgeDotColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }

            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = if (isSelected || isCenterOrb) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 1.1.sp,
                maxLines = 1
            )
        }
    }
}
