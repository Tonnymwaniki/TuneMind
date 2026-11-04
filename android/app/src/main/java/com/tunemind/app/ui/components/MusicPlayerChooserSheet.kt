package com.tunemind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.provider.MusicProvider
import com.tunemind.app.ui.theme.DeepObsidianAmber

/**
 * Interactive audio-grade bottom sheet allowing the user to select which installed music player
 * to route playback to, with an option to remember the choice as default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerChooserSheet(
    trackTitle: String,
    trackArtist: String,
    installedProviders: List<MusicProvider>,
    activeSessionPackage: String? = null,
    onSelectPlayer: (providerId: String, rememberAsDefault: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var rememberAsDefault by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DeepObsidianAmber.CardBackground,
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = DeepObsidianAmber.SurfaceBorder
            ) {}
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PLAY WITH MUSIC PLAYER",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.PrimaryAccent,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = trackTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = trackArtist,
                        fontSize = 12.sp,
                        color = DeepObsidianAmber.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close",
                        tint = DeepObsidianAmber.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f))

            // Player List
            Text(
                text = "Detected Music Players on Device:",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = DeepObsidianAmber.TextMuted
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(installedProviders, key = { it.providerId }) { provider ->
                    val isBackgroundActive = activeSessionPackage != null &&
                            activeSessionPackage.contains(provider.packageName, ignoreCase = true)

                    val brandColor = when (provider.providerId) {
                        "boomplay" -> Color(0xFFFF6B00)
                        "youtube_music" -> Color(0xFFFF0033)
                        "spotify" -> Color(0xFF1DB954)
                        "apple_music" -> Color(0xFFFA243C)
                        "audiomack" -> Color(0xFFFFA200)
                        else -> DeepObsidianAmber.SecondaryAccent
                    }

                    val playerSubtitle = when (provider.providerId) {
                        "boomplay" -> "Afrobeats, Bongo Flava & Amapiano"
                        "youtube_music" -> "YouTube Music Ecosystem"
                        "spotify" -> "Spotify Global Audio"
                        "apple_music" -> "Apple Music Library"
                        "audiomack" -> "Audiomack Stream"
                        else -> "System Default Player"
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isBackgroundActive) brandColor.copy(alpha = 0.6f)
                            else DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectPlayer(provider.providerId, rememberAsDefault)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Player Icon Badge
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(brandColor.copy(alpha = 0.16f))
                                    .border(1.dp, brandColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isBackgroundActive) Icons.Filled.GraphicEq else Icons.Filled.Headphones,
                                    contentDescription = provider.displayName,
                                    tint = brandColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Details
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = provider.displayName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepObsidianAmber.TextPrimary
                                    )
                                    if (isBackgroundActive) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = DeepObsidianAmber.Success.copy(alpha = 0.16f),
                                            border = androidx.compose.foundation.BorderStroke(
                                                0.8.dp,
                                                DeepObsidianAmber.Success.copy(alpha = 0.6f)
                                            )
                                        ) {
                                            Text(
                                                text = "ACTIVE BG",
                                                fontSize = 7.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = DeepObsidianAmber.Success,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = if (isBackgroundActive) "Ready for direct background stream without opening" else playerSubtitle,
                                    fontSize = 10.sp,
                                    color = if (isBackgroundActive) DeepObsidianAmber.Success.copy(alpha = 0.85f) else DeepObsidianAmber.TextSecondary
                                )
                            }

                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Play",
                                tint = DeepObsidianAmber.TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Remember As Default Toggle
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DeepObsidianAmber.SecondaryBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { rememberAsDefault = !rememberAsDefault }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (rememberAsDefault) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.RecessedBackground)
                            .border(
                                1.dp,
                                if (rememberAsDefault) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.SurfaceBorder,
                                RoundedCornerShape(5.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (rememberAsDefault) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = DeepObsidianAmber.AppBackground,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Always use this player as my default",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DeepObsidianAmber.TextPrimary
                        )
                        Text(
                            text = "You can change or reset your default anytime in Settings",
                            fontSize = 10.sp,
                            color = DeepObsidianAmber.TextSecondary
                        )
                    }
                }
            }
        }
    }
}
