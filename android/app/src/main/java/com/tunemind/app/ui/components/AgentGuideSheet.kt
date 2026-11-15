package com.tunemind.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

private data class GuideCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color,
    val prompts: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentGuideSheet(
    onDismiss: () -> Unit,
    onRunPrompt: (String) -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val neonCyan = Color(0xFF00F0FF)
    val electricPurple = Color(0xFF8A2BE2)
    val safeGreen = Color(0xFF10B981)
    val warmGold = Color(0xFFFFB000)

    val categories = listOf(
        GuideCategory(
            title = "Play & Discover",
            subtitle = "Queue songs from Spotify, local audio, or your fresh pool",
            icon = Icons.Rounded.PlayArrow,
            accent = neonCyan,
            prompts = listOf(
                "Play something fresh I haven't overplayed",
                "Play my morning dopamine mix",
                "Play something calm for late night focus",
                "Suggest three songs matching what is playing now"
            )
        ),
        GuideCategory(
            title = "Dopamine Protection",
            subtitle = "Protect favorite tracks from fatigue & manage your Vault",
            icon = Icons.Rounded.Shield,
            accent = warmGold,
            prompts = listOf(
                "Quarantine the current track to the Vault",
                "Which songs are getting overplayed this week?",
                "Are any resting Vault tracks ready to come back?",
                "How fresh is my listening queue right now?"
            )
        ),
        GuideCategory(
            title = "Atmosphere & Visuals",
            subtitle = "Shape TuneMind's ambient mood & player controls",
            icon = Icons.Rounded.Palette,
            accent = electricPurple,
            prompts = listOf(
                "Shift the atmosphere to deep violet night",
                "Match the theme color to the current song",
                "Turn on Hands-Free DJ mode",
                "Switch to Built-in Audio Player preference"
            )
        ),
        GuideCategory(
            title = "Hearing & Analytics",
            subtitle = "Acoustic safety headroom, listening stats & moments",
            icon = Icons.Rounded.Hearing,
            accent = safeGreen,
            prompts = listOf(
                "Check my safe listening headroom right now",
                "Give me my weekly listening recap",
                "Open my listening stats",
                "Open my notifications and alerts"
            )
        )
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF130B22),
        contentColor = Color(0xFFF8FAFC),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.24f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "What TuneMind Can Do",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "Say \"Hey TuneMind\" anytime or tap any command below to run it immediately.",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.64f),
                        lineHeight = 18.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close Guide",
                        tint = Color.White.copy(alpha = 0.72f)
                    )
                }
            }

            categories.forEach { cat ->
                SoftGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    accentTint = cat.accent,
                    cornerRadius = 18.dp,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(cat.accent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = cat.icon,
                                    contentDescription = null,
                                    tint = cat.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = cat.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF8FAFC)
                                )
                                Text(
                                    text = cat.subtitle,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.56f)
                                )
                            }
                        }

                        cat.prompts.forEach { prompt ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, cat.accent.copy(alpha = 0.24f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        onRunPrompt(prompt)
                                        onDismiss()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 13.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "\"$prompt\"",
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.Mic,
                                        contentDescription = "Run command",
                                        tint = cat.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = theme.primaryAccent,
                    contentColor = Color(0xFF0D0D0D)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Got It", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
