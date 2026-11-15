package com.tunemind.app.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.voice.PendingProposedTrack
import com.tunemind.app.provider.SpotifyAppRemoteManager
import com.tunemind.app.provider.TuneMindInAppPlayer
import com.tunemind.app.ui.DopamineCapsuleState
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.AnimatedEqualizerBars
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun NowPlayingSheet(
    vm: TuneMindViewModel,
    selectedTargetPlayer: String,
    dopamineCapsule: DopamineCapsuleState,
    pendingProposedTrack: PendingProposedTrack?,
    onBack: () -> Unit,
    onApproveProposedTrack: () -> Unit,
    onRejectProposedTrack: () -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePkg: String) -> Unit,
    onAskAgentAboutCurrent: (title: String, artist: String) -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val homeState by vm.homeState.collectAsState()
    val inAppHasTrack by TuneMindInAppPlayer.hasActiveTrackFlow.collectAsState()
    val inAppPlaying by TuneMindInAppPlayer.isPlayingFlow.collectAsState()
    val inAppTitle by TuneMindInAppPlayer.currentTitleFlow.collectAsState()
    val inAppArtist by TuneMindInAppPlayer.currentArtistFlow.collectAsState()
    val inAppPosMs by TuneMindInAppPlayer.positionMsFlow.collectAsState()
    val inAppDurMs by TuneMindInAppPlayer.durationMsFlow.collectAsState()

    val nowPlaying = homeState.nowPlaying
    val effectiveTitle = (if (inAppHasTrack) inAppTitle else nowPlaying?.title)
        ?.takeIf { it.isNotBlank() } ?: "Nothing Playing"
    val effectiveArtist = (if (inAppHasTrack) inAppArtist else nowPlaying?.artist)
        ?.takeIf { it.isNotBlank() } ?: "Start a song or ask TuneMind"
    val effectivePlaying = if (inAppHasTrack) inAppPlaying else (nowPlaying?.isPlaying == true)
    val effectiveDurationMs = if (inAppHasTrack && inAppDurMs > 0L) inAppDurMs else (nowPlaying?.durationMs ?: 0L)
    val effectivePosMs = if (inAppHasTrack) inAppPosMs else (nowPlaying?.positionMs ?: 0L)

    val sourceLabel = when {
        inAppHasTrack -> "TuneMind Built-In Player"
        nowPlaying?.sourcePackage?.contains("spotify") == true -> "Spotify"
        nowPlaying?.sourcePackage?.contains("youtube") == true -> "YouTube Music"
        nowPlaying?.sourcePackage?.isNotBlank() == true -> nowPlaying.sourcePackage.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        else -> "Playback Queue"
    }

    var sliderOverride by remember { mutableStateOf<Float?>(null) }
    var actionStatus by remember { mutableStateOf<String?>(null) }

    val neonCyan = Color(0xFF00F0FF)
    val electricPurple = Color(0xFF8A2BE2)
    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFFFB000)

    val hasTrack = effectiveTitle != "Nothing Playing"
    val durationSafe = effectiveDurationMs.coerceAtLeast(1L)
    val liveFraction = (effectivePosMs.toFloat() / durationSafe.toFloat()).coerceIn(0f, 1f)
    val displayFraction = sliderOverride ?: liveFraction

    val canSeek = inAppHasTrack || SpotifyAppRemoteManager.isConnected()

    val infiniteTransition = rememberInfiniteTransition(label = "np_pulse")
    val discScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = if (effectivePlaying) 1.04f else 0.98f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "np_disc"
    )

    StudioAmbientBackground(
        accentOverride = if (effectivePlaying) safeGreen else theme.primaryAccent,
        isActivePulse = effectivePlaying
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Minimize",
                        tint = Color(0xFFF8FAFC),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "NOW PLAYING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = neonCyan,
                        letterSpacing = 1.6.sp
                    )
                    Text(
                        text = sourceLabel,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.62f)
                    )
                }
                IconButton(
                    onClick = {
                        if (hasTrack) {
                            vm.syncAtmosphereToTrack(effectiveTitle, effectiveArtist)
                            actionStatus = "Synced theme mood to \"$effectiveTitle\""
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = "Sync Theme",
                        tint = theme.primaryAccent
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Glowing artwork disc
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .graphicsLayer {
                        scaleX = discScale
                        scaleY = discScale
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                electricPurple.copy(alpha = 0.45f),
                                neonCyan.copy(alpha = 0.16f),
                                Color(0xFF0D0D0D)
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(listOf(neonCyan.copy(alpha = 0.7f), electricPurple.copy(alpha = 0.6f))),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (effectivePlaying) {
                    AnimatedEqualizerBars(
                        color = neonCyan,
                        modifier = Modifier.size(64.dp, 52.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = neonCyan,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = effectiveTitle,
                color = Color(0xFFF8FAFC),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = effectiveArtist,
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Progress Slider
            if (hasTrack && effectiveDurationMs > 0L) {
                Slider(
                    value = displayFraction,
                    enabled = canSeek,
                    onValueChange = { sliderOverride = it },
                    onValueChangeFinished = {
                        val targetMs = ((sliderOverride ?: liveFraction) * durationSafe).toLong()
                        sliderOverride = null
                        if (inAppHasTrack) {
                            TuneMindInAppPlayer.seekTo(targetMs)
                        } else if (SpotifyAppRemoteManager.isConnected()) {
                            SpotifyAppRemoteManager.seekTo(targetMs)
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = neonCyan,
                        activeTrackColor = neonCyan,
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val shownMs = (displayFraction * durationSafe).toLong()
                    Text(formatTrackTime(shownMs), color = Color.White.copy(alpha = 0.56f), fontSize = 12.sp)
                    Text(formatTrackTime(effectiveDurationMs), color = Color.White.copy(alpha = 0.56f), fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { vm.skipPrev() },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color(0xFFF8FAFC),
                        modifier = Modifier.size(30.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(neonCyan, electricPurple)))
                        .clickable { vm.togglePlayback() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (effectivePlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (effectivePlaying) "Pause" else "Play",
                        tint = Color(0xFF0D0D0D),
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = { vm.skipNext() },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = Color(0xFFF8FAFC),
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            if (!actionStatus.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = safeGreen.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, safeGreen.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = actionStatus!!,
                        color = Color(0xFFF8FAFC),
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Quick Actions for Current Track
            if (hasTrack) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NowPlayingQuickAction(
                        icon = Icons.Rounded.BookmarkAdd,
                        label = "Moment",
                        accent = neonCyan,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val start = maxOf(0L, effectivePosMs - 15_000L)
                            val end = minOf(maxOf(start + 15_000L, effectivePosMs + 15_000L), maxOf(30_000L, effectiveDurationMs))
                            vm.saveMusicalMoment(
                                title = effectiveTitle,
                                artist = effectiveArtist,
                                album = nowPlaying?.album,
                                sourcePkg = nowPlaying?.sourcePackage,
                                startMs = start,
                                endMs = end
                            )
                            actionStatus = "Saved moment from \"$effectiveTitle\""
                        }
                    )
                    NowPlayingQuickAction(
                        icon = Icons.Rounded.Shield,
                        label = "Vault",
                        accent = warnAmber,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            vm.quarantineTrack(effectiveTitle, effectiveArtist)
                            actionStatus = "Moved \"$effectiveTitle\" to Cooldown Vault"
                        }
                    )
                    NowPlayingQuickAction(
                        icon = Icons.Rounded.AutoAwesome,
                        label = "Ask Agent",
                        accent = electricPurple,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onAskAgentAboutCurrent(effectiveTitle, effectiveArtist)
                            onBack()
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Up Next Queue (Pending AI Proposal + Dopamine Capsule)
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioSectionHeader(title = "Up Next Queue")

                    if (pendingProposedTrack != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = safeGreen.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, safeGreen.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = safeGreen, modifier = Modifier.size(18.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pendingProposedTrack.title,
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Suggested · ${pendingProposedTrack.artist}",
                                        color = safeGreen,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(onClick = onRejectProposedTrack) {
                                    Text("Skip", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                                }
                                Button(
                                    onClick = onApproveProposedTrack,
                                    shape = RoundedCornerShape(28.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = safeGreen, contentColor = Color(0xFF0D0D0D)),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                                ) {
                                    Text("Play", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    if (dopamineCapsule.tracks.isEmpty()) {
                        Text(
                            text = "Play a few tracks or tap your morning mix to populate your upcoming fresh queue.",
                            color = Color.White.copy(alpha = 0.56f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    } else {
                        dopamineCapsule.tracks.forEachIndexed { idx, capTrack ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onPlayTrack(
                                            capTrack.title,
                                            capTrack.artist,
                                            capTrack.spotifyUri,
                                            selectedTargetPlayer
                                        )
                                        actionStatus = "Playing \"${capTrack.title}\"…"
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    color = neonCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = capTrack.title,
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${capTrack.artist} · ${capTrack.freshnessPercent}% fresh",
                                        color = Color.White.copy(alpha = 0.58f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Play",
                                    tint = neonCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (idx < dopamineCapsule.tracks.lastIndex) {
                                StudioHairlineDivider()
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun NowPlayingQuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.36f)),
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = Color(0xFFF8FAFC), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatTrackTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
