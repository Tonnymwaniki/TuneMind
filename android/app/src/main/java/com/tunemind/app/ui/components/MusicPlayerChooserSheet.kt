package com.tunemind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.provider.MusicProvider
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Interactive bottom sheet allowing the user to select which music player
 * to route playback to, with an option to save as default.
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
    val theme = LocalAdaptiveTheme.current
    var rememberAsDefault by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E111A),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = Color.White.copy(alpha = 0.22f)
            ) {}
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                        text = "Choose Player",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.primaryAccent
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = trackTitle,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = trackArtist,
                        fontSize = 12.sp,
                        color = theme.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Text(
                text = "Available Players",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.60f)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(installedProviders, key = { it.providerId }) { provider ->
                    val isBackgroundActive = activeSessionPackage != null &&
                        activeSessionPackage.contains(provider.packageName, ignoreCase = true)

                    val brandColor = when {
                        provider.providerId == "boomplay" -> Color(0xFFFF6B00)
                        provider.providerId == "youtube_music" -> Color(0xFFFF0033)
                        provider.providerId == "spotify" -> Color(0xFF1DB954)
                        provider.providerId == "apple_music" -> Color(0xFFFA243C)
                        provider.providerId == "audiomack" -> Color(0xFFFFA200)
                        provider.providerId == "local_files" -> theme.primaryAccent
                        provider.providerId.startsWith("pkg_") -> Color(0xFFA855F7)
                        else -> theme.secondaryAccent
                    }

                    val playerSubtitle = when {
                        provider.providerId == "boomplay" -> "Afrobeats, Bongo Flava & Amapiano"
                        provider.providerId == "youtube_music" -> "YouTube Music"
                        provider.providerId == "spotify" -> "Spotify Streaming"
                        provider.providerId == "apple_music" -> "Apple Music"
                        provider.providerId == "audiomack" -> "Audiomack"
                        provider.providerId == "local_files" -> "Local audio library & offline songs"
                        provider.providerId.startsWith("pkg_") -> "Installed audio player"
                        else -> "Default media player"
                    }

                    val playerIcon = when {
                        isBackgroundActive -> Icons.Rounded.GraphicEq
                        provider.providerId == "local_files" -> Icons.Rounded.FolderOpen
                        provider.providerId.startsWith("pkg_") -> Icons.Rounded.MusicNote
                        else -> Icons.Rounded.Headphones
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF141824),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isBackgroundActive) brandColor.copy(alpha = 0.55f)
                            else Color.White.copy(alpha = 0.08f)
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
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(brandColor.copy(alpha = 0.16f))
                                    .border(1.dp, brandColor.copy(alpha = 0.38f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = playerIcon,
                                    contentDescription = provider.displayName,
                                    tint = brandColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = provider.displayName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    if (isBackgroundActive) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFF10B981).copy(alpha = 0.16f)
                                        ) {
                                            Text(
                                                text = "Active",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF10B981),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = if (isBackgroundActive) "Ready to play in background" else playerSubtitle,
                                    fontSize = 11.sp,
                                    color = if (isBackgroundActive) Color(0xFF10B981).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.62f)
                                )
                            }

                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = "Select",
                                tint = Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF141824),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
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
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (rememberAsDefault) theme.primaryAccent else Color(0xFF0A0D14))
                            .border(
                                1.dp,
                                if (rememberAsDefault) theme.primaryAccent else Color.White.copy(alpha = 0.25f),
                                RoundedCornerShape(6.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (rememberAsDefault) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color(0xFF05060A),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Always use this player",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "You can change this anytime in Settings",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.60f)
                        )
                    }
                }
            }
        }
    }
}
