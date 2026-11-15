package com.tunemind.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.AdaptiveThemeEngine
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Clean 44dp overflow trigger replacing noisy Sync gradient pills on track rows.
 */
@Composable
fun NeuralSonicActionButton(
    accentColor: Color,
    secondaryColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        Icon(
            imageVector = Icons.Rounded.MoreHoriz,
            contentDescription = "Track options",
            tint = Color.White.copy(alpha = 0.62f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Full-width native Material 3 ModalBottomSheet for track actions (Play, Ask, Theme, Rest).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuralTrackActionDialog(
    title: String,
    artist: String,
    freshnessPercent: Int,
    playCount: Int,
    isCoolingDown: Boolean,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit,
    onAgentNeuralBreakdown: () -> Unit,
    onSyncAtmosphereToTrack: () -> Unit,
    onToggleCooldownVault: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val detectedMood = remember(title, artist) {
        AdaptiveThemeEngine.resolveTrackMood(title, artist)
    }
    val ringColor = if (isCoolingDown) Color(0xFFFFB000) else theme.primaryAccent

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0E131D),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(56.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawArc(
                            color = Color.White.copy(alpha = 0.08f),
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = ringColor,
                            startAngle = 135f,
                            sweepAngle = 270f * (freshnessPercent / 100f),
                            useCenter = false,
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = "$freshnessPercent%",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color(0xFFF8FAFC),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${artist.ifBlank { "Unknown Artist" }} · $playCount ${if (playCount == 1) "play" else "plays"} · ${detectedMood.label}",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

            TrackActionRow(
                icon = Icons.Rounded.PlayArrow,
                title = "Play now",
                subtitle = "Start playback right away",
                accent = theme.primaryAccent,
                onClick = {
                    onPlayNow()
                    onDismiss()
                }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

            TrackActionRow(
                icon = Icons.Rounded.GraphicEq,
                title = "Ask TuneMind about this song",
                subtitle = "Get listening insight and mix ideas",
                accent = theme.secondaryAccent,
                onClick = {
                    onAgentNeuralBreakdown()
                    onDismiss()
                }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

            TrackActionRow(
                icon = Icons.Rounded.Palette,
                title = "Match colors to this song",
                subtitle = "Set app tint to ${detectedMood.label.lowercase()} tone",
                accent = Color(0xFFA855F7),
                onClick = {
                    onSyncAtmosphereToTrack()
                    onDismiss()
                }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

            TrackActionRow(
                icon = if (isCoolingDown) Icons.Rounded.Bolt else Icons.Rounded.Schedule,
                title = if (isCoolingDown) "Move back to Fresh" else "Rest this song for a few days",
                subtitle = if (isCoolingDown) {
                    "Bring back into regular rotation"
                } else {
                    "Keep it from getting overplayed"
                },
                accent = if (isCoolingDown) Color(0xFF10B981) else Color(0xFFFFB000),
                onClick = {
                    onToggleCooldownVault()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun TrackActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color(0xFFF8FAFC),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.54f),
                fontSize = 12.sp
            )
        }
    }
}
