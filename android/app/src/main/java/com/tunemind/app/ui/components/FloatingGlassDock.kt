package com.tunemind.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.voice.AgentState
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Clean fixed bottom tab bar (3 destinations) with minimal icon + 1-word label.
 * Replaces multi-border floating pill docks and decorative colored dots.
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF090C13).copy(alpha = 0.96f))
            .navigationBarsPadding()
    ) {
        HorizontalDivider(thickness = 1.dp, color = Color.White.copy(alpha = 0.07f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomDockTab(
                label = "Fresh",
                icon = Icons.Rounded.Bolt,
                isSelected = currentPage == 0,
                activeColor = theme.primaryAccent,
                onClick = { onSelectPage(0) },
                modifier = Modifier.weight(1f)
            )
            BottomDockTab(
                label = centerLabel,
                icon = Icons.Rounded.GraphicEq,
                isSelected = currentPage == 1,
                activeColor = agentAccentColor,
                onClick = { onSelectPage(1) },
                modifier = Modifier.weight(1f)
            )
            BottomDockTab(
                label = "Flow",
                icon = Icons.Rounded.LibraryMusic,
                isSelected = currentPage == 2,
                activeColor = theme.secondaryAccent,
                onClick = { onSelectPage(2) },
                modifier = Modifier.weight(1f)
            )
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
        targetValue = if (isSelected) activeColor else Color.White.copy(alpha = 0.48f),
        animationSpec = tween(180),
        label = "tabColor"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(21.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
