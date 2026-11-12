package com.tunemind.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import com.tunemind.app.domain.voice.AgentState
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.bouncyClickable

@Composable
fun PulseAgentScreen(
    agentState: AgentState,
    satietyPercentage: Int,
    currentTrackName: String,
    artistName: String,
    energy: Float = 0.8f, // Spotify Telemetry (0.0 to 1.0)
    liveTranscript: String = "",
    onOrbTapped: () -> Unit
) {
    // LAYER 1: Chrono-Canvas (Time of Day)
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val canvasBaseColor = when (hour) {
        in 6..11 -> Color(0xFF0F172A) // Morning Mist
        in 12..16 -> Color(0xFF000000) // Midday Obsidian
        in 17..21 -> Color(0xFF1E1014) // Evening Ember
        else -> Color(0xFF06050A)      // Late Night Abyss
    }
    
    val animatedBgColor by animateColorAsState(
        targetValue = canvasBaseColor, 
        animationSpec = tween(3000), 
        label = "BgColor"
    )

    // LAYER 2: Acoustic Core & State Colors
    val targetOrbColor = when (agentState) {
        AgentState.LISTENING -> Color(0xFF00E5FF) // Acoustic Cyan
        AgentState.THINKING -> Color(0xFF7000FF)  // Neural Violet
        AgentState.SPEAKING -> Color(0xFFFFB000)  // Liquid Amber
        AgentState.IDLE -> {
            if (energy > 0.7f) Color(0xFFFF4500) // High Energy
            else Color(0xFF10B981) // Low Energy / Chill
        }
        else -> Color.DarkGray
    }

    val animatedOrbColor by animateColorAsState(
        targetValue = targetOrbColor, 
        animationSpec = tween(800), 
        label = "OrbColor"
    )

    // LAYER 3: Satiety Override
    val satietyColor = if (satietyPercentage > 75) Color(0xFFFF2A55) // Fatigue Crimson
                       else Color(0xFF00F0FF) // Glacier Cyan

    // Breathing Physics
    val infiniteTransition = rememberInfiniteTransition(label = "InfiniteBreathing")
    val orbBreathe by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = if (agentState != AgentState.IDLE) 1.05f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (energy > 0.7f) 800 else 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OrbBreathe"
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    var showServerDialog by remember { mutableStateOf(false) }
    var serverUrlInput by remember {
        mutableStateOf(com.tunemind.app.domain.network.BackendEndpointManager.getCustomBaseUrl(context))
    }
    var connectionLabel by remember {
        mutableStateOf(com.tunemind.app.domain.network.BackendEndpointManager.getDisplayConnectionLabel(context))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedBgColor)
            .padding(24.dp)
    ) {
        // TOP: Soft Telemetry Card (Tap to configure Wireless / Cloud Server URL)
        SoftGlassCard(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .bouncyClickable {
                    serverUrlInput = com.tunemind.app.domain.network.BackendEndpointManager.getCustomBaseUrl(context)
                    showServerDialog = true
                }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("NOW SYNCING", color = animatedOrbColor.copy(alpha = 0.7f), fontSize = 10.sp, letterSpacing = 2.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(currentTrackName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(artistName, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = connectionLabel,
                    color = Color(0xFF00E5FF).copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
        }

        if (showServerDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showServerDialog = false },
                containerColor = Color(0xFF12101B),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.85f),
                title = {
                    Text("Wireless / Cloud Brain URL", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Run without a USB cable! Enter either:\n" +
                            "• Your 24/7 Cloud or Tunnel URL (e.g. https://xxxx.loca.lt or https://tunemind.onrender.com)\n" +
                            "• Your PC's Wi-Fi IP printed in the backend terminal (e.g. 192.168.1.25:3000)",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            placeholder = {
                                Text(
                                    "e.g. https://tunemind.loca.lt or 192.168.1.25:3000",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.4f)
                                )
                            },
                            singleLine = true,
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            com.tunemind.app.domain.network.BackendEndpointManager.setCustomBaseUrl(context, serverUrlInput)
                            connectionLabel = com.tunemind.app.domain.network.BackendEndpointManager.getDisplayConnectionLabel(context)
                            showServerDialog = false
                        }
                    ) {
                        Text("Save Wireless URL", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showServerDialog = false }
                    ) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                }
            )
        }

        // CENTER: The Agent Core (Orb & Satiety Gauge)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.align(Alignment.Center).size(300.dp)
        ) {
            // Ambient Satiety Glow (Heats up when tired)
            if (satietyPercentage > 75) {
                Box(modifier = Modifier.matchParentSize().blur(60.dp).background(satietyColor.copy(alpha = 0.2f), CircleShape))
            }

            // Satiety Ring
            Canvas(modifier = Modifier.matchParentSize()) {
                drawArc(
                    color = Color.White.copy(alpha = 0.05f),
                    startAngle = 135f, sweepAngle = 270f, useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = satietyColor,
                    startAngle = 135f, sweepAngle = 270f * (satietyPercentage / 100f), useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Living Orb
            Box(
                modifier = Modifier
                    .size(140.dp * orbBreathe)
                    .clip(CircleShape)
                    .background(animatedOrbColor)
                    .bouncyClickable { onOrbTapped() }
            )
            // Orb Inner Core (Depth)
            Box(
                modifier = Modifier
                    .size(80.dp * orbBreathe)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            )
        }

        // BOTTOM: Neural Feed Transcript (Animated Visibility with Spring)
        AnimatedVisibility(
            visible = agentState != AgentState.IDLE,
            enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) { it } + fadeIn(),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        ) {
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(agentState.name, color = animatedOrbColor, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = liveTranscript.ifEmpty { "Listening..." },
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
