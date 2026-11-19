package com.tunemind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val curatedProviders = remember(installedProviders) {
        MusicPlayerUiHelper.filterMusicPlayers(context, installedProviders)
    }
    var rememberAsDefault by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.cardSurface,
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = Color.White.copy(alpha = 0.20f)
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
                        text = "Play With",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$trackTitle · $trackArtist",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.60f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            StudioHairlineDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
            ) {
                items(curatedProviders, key = { it.providerId }) { provider ->
                    val isBackgroundActive = activeSessionPackage != null &&
                        activeSessionPackage.contains(provider.packageName, ignoreCase = true)

                    val brandColor = when {
                        provider.providerId == "boomplay" -> Color(0xFFFF6B00)
                        provider.providerId == "youtube_music" -> Color(0xFFFF0033)
                        provider.providerId == "spotify" -> Color(0xFF1DB954)
                        provider.providerId == "audiomack" -> Color(0xFFFFA200)
                        provider.providerId == "local_files" -> theme.primaryAccent
                        provider.providerId.startsWith("pkg_") -> theme.secondaryAccent
                        else -> theme.secondaryAccent
                    }

                    val playerSubtitle = when {
                        provider.providerId == "boomplay" -> "Afrobeats, Bongo Flava & Amapiano"
                        provider.providerId == "youtube_music" -> "YouTube Music"
                        provider.providerId == "spotify" -> "Spotify (OS Playback Control)"
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

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectPlayer(provider.providerId, rememberAsDefault)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(brandColor.copy(alpha = 0.14f)),
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
                                Text(
                                    text = if (isBackgroundActive) "${provider.displayName} · Active" else provider.displayName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isBackgroundActive) Color(0xFF10B981) else Color(0xFFF8FAFC)
                                )
                                Text(
                                    text = if (isBackgroundActive) "Ready to play in background" else playerSubtitle,
                                    fontSize = 12.sp,
                                    color = if (isBackgroundActive) Color(0xFF10B981).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.56f)
                                )
                            }

                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = "Select",
                                tint = Color.White.copy(alpha = 0.42f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        StudioHairlineDivider()
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { rememberAsDefault = !rememberAsDefault }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Always use this player",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "You can change this anytime in Settings",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.56f)
                    )
                }

                Switch(
                    checked = rememberAsDefault,
                    onCheckedChange = { rememberAsDefault = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = theme.primaryAccent
                    )
                )
            }
        }
    }
}
