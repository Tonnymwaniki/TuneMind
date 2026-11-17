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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tunemind.app.domain.voice.PendingProposedTrack
import com.tunemind.app.provider.SpotifyAppRemoteManager
import com.tunemind.app.provider.TuneMindInAppPlayer
import com.tunemind.app.ui.DopamineCapsuleState
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.AnimatedEqualizerBars
import com.tunemind.app.ui.components.LayeredSongArtistBadge
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.SpotifyAvatarCircle
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.components.rememberResolvedTrackMedia
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
    val context = LocalContext.current
    val homeState by vm.homeState.collectAsState()
    val liveSpotify by vm.spotifyLiveState.collectAsState()
    val spotifyDevices by vm.spotifyDevices.collectAsState()
    val upNextQueueState by vm.upNextQueueState.collectAsState()
    val autoOverrideEnabled by vm.upNextAutoOverrideEnabled.collectAsState()
    val vmActionStatus by vm.nowPlayingActionStatus.collectAsState()
    val inAppHasTrack by TuneMindInAppPlayer.hasActiveTrackFlow.collectAsState()
    val inAppPlaying by TuneMindInAppPlayer.isPlayingFlow.collectAsState()
    val inAppTitle by TuneMindInAppPlayer.currentTitleFlow.collectAsState()
    val inAppArtist by TuneMindInAppPlayer.currentArtistFlow.collectAsState()
    val inAppPosMs by TuneMindInAppPlayer.positionMsFlow.collectAsState()
    val inAppDurMs by TuneMindInAppPlayer.durationMsFlow.collectAsState()

    val effectiveUpNext = remember(upNextQueueState, dopamineCapsule) {
        if (upNextQueueState.tracks.isNotEmpty()) upNextQueueState else dopamineCapsule
    }

    LaunchedEffect(Unit) {
        vm.refreshSpotifyDevices()
        vm.compileDopamineCapsule()
    }

    val nowPlaying = homeState.nowPlaying
    val isInAppCurrent = remember(inAppHasTrack, inAppPlaying, nowPlaying?.sourcePackage, context.packageName) {
        inAppPlaying || (inAppHasTrack && (nowPlaying?.sourcePackage == null || nowPlaying.sourcePackage == context.packageName))
    }
    val effectiveTitle = (if (isInAppCurrent) inAppTitle else nowPlaying?.title)
        ?.takeIf { it.isNotBlank() } ?: "Nothing Playing"
    val effectiveArtist = (if (isInAppCurrent) inAppArtist else nowPlaying?.artist)
        ?.takeIf { it.isNotBlank() } ?: "Start a song or ask TuneMind"
    val effectivePlaying = if (isInAppCurrent) inAppPlaying else (nowPlaying?.isPlaying == true)
    val effectiveDurationMs = if (isInAppCurrent && inAppDurMs > 0L) inAppDurMs else (nowPlaying?.durationMs ?: 0L)

    var externalTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isInAppCurrent, effectivePlaying, nowPlaying?.positionMs, nowPlaying?.updatedAtMs) {
        externalTickMs = System.currentTimeMillis()
        while (!isInAppCurrent && effectivePlaying) {
            kotlinx.coroutines.delay(1000L)
            externalTickMs = System.currentTimeMillis()
        }
    }
    val effectivePosMs = if (isInAppCurrent) {
        inAppPosMs
    } else {
        val base = nowPlaying?.positionMs ?: 0L
        val updatedAt = nowPlaying?.updatedAtMs ?: 0L
        if (effectivePlaying && updatedAt > 0L) {
            val delta = (externalTickMs - updatedAt).coerceIn(0L, 12 * 60 * 1000L)
            val extrapolated = base + delta
            if (effectiveDurationMs > 0L) extrapolated.coerceIn(0L, effectiveDurationMs) else extrapolated
        } else {
            base
        }
    }

    val sourceLabel = when {
        isInAppCurrent -> "TuneMind Built-In Player"
        nowPlaying?.sourcePackage?.contains("spotify", ignoreCase = true) == true -> "Spotify"
        nowPlaying?.sourcePackage?.contains("youtube", ignoreCase = true) == true -> "YouTube Music"
        nowPlaying?.sourcePackage?.contains("boomplay", ignoreCase = true) == true -> "Boomplay"
        nowPlaying?.sourcePackage?.contains("apple", ignoreCase = true) == true -> "Apple Music"
        nowPlaying?.sourcePackage?.contains("audiomack", ignoreCase = true) == true -> "Audiomack"
        nowPlaying?.sourcePackage?.isNotBlank() == true -> nowPlaying.sourcePackage.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        else -> "Playback Queue"
    }

    var sliderOverride by remember { mutableStateOf<Float?>(null) }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    val displayActionStatus = vmActionStatus ?: actionStatus

    val neonCyan = theme.primaryAccent
    val electricPurple = theme.secondaryAccent
    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFF59E0B)

    val hasTrack = effectiveTitle != "Nothing Playing"
    val matchedLiveSpotify = remember(liveSpotify, effectiveTitle, effectiveArtist, hasTrack) {
        if (!hasTrack) null
        else liveSpotify?.takeIf { sp ->
            com.tunemind.app.domain.memory.ListeningPlayDeduplicator.isSameTrack(
                effectiveTitle, effectiveArtist, null,
                sp.title, sp.artistOrShow, sp.spotifyUri
            )
        }
    }
    val activeSpotifyUri = matchedLiveSpotify?.spotifyUri ?: nowPlaying?.spotifyUri
    val activeMedia = if (hasTrack) {
        rememberResolvedTrackMedia(
            title = effectiveTitle,
            artist = effectiveArtist,
            spotifyUri = activeSpotifyUri,
            fallbackAlbumArtUrl = matchedLiveSpotify?.albumArtUrl
        )
    } else null
    val durationSafe = effectiveDurationMs.coerceAtLeast(1L)
    val liveFraction = (effectivePosMs.toFloat() / durationSafe.toFloat()).coerceIn(0f, 1f)
    val displayFraction = sliderOverride ?: liveFraction

    val canSeek = hasTrack && effectiveDurationMs > 0L

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
                        text = "$sourceLabel · ${if (effectivePlaying) "Playing" else if (hasTrack) "Paused" else "Standby"}",
                        fontSize = 12.sp,
                        color = if (effectivePlaying) safeGreen else Color.White.copy(alpha = 0.62f)
                    )
                }
                IconButton(
                    onClick = {
                        if (hasTrack) {
                            vm.syncAtmosphereToTrack(effectiveTitle, effectiveArtist, activeMedia?.albumArtUrl)
                            actionStatus = "Chromatic sync applied to \"$effectiveTitle\""
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

            // Studio Artwork Frame (Rounded-square cover + Artist badge)
            Box(
                modifier = Modifier
                    .size(228.dp)
                    .graphicsLayer {
                        scaleX = if (effectivePlaying) 1.0f else 0.98f
                        scaleY = if (effectivePlaying) 1.0f else 0.98f
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    theme.elevatedSurface,
                                    theme.cardSurface
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(24.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val artUrl = activeMedia?.albumArtUrl?.takeIf { it.startsWith("http") }
                    if (artUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(artUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "$effectiveTitle album art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = if (effectivePlaying) 0.18f else 0.08f))
                        )
                    }
                    if (effectivePlaying && artUrl == null) {
                        AnimatedEqualizerBars(
                            color = neonCyan,
                            modifier = Modifier.size(56.dp, 46.dp)
                        )
                    } else if (artUrl == null) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = neonCyan.copy(alpha = 0.85f),
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }

                if (hasTrack) {
                    SpotifyAvatarCircle(
                        imageUrl = activeMedia?.artistImageUrl,
                        fallbackInitial = effectiveArtist,
                        size = 46.dp,
                        accentColor = neonCyan,
                        borderWidth = 2.dp,
                        borderColor = theme.primaryBackground,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-2).dp, y = (-2).dp)
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
                        vm.seekTo(targetMs)
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
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFAFAFA))
                        .clickable { vm.togglePlayback() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (effectivePlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (effectivePlaying) "Pause" else "Play",
                        tint = Color(0xFF09090B),
                        modifier = Modifier.size(34.dp)
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

            if (autoOverrideEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Auto Queue Takeover ON · Next spins TuneMind queue & stops Spotify autoplay",
                    color = safeGreen.copy(alpha = 0.9f),
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (!displayActionStatus.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = safeGreen.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, safeGreen.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = displayActionStatus,
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
                                endMs = end,
                                spotifyUri = activeSpotifyUri
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
                            vm.quarantineTrack(
                                title = effectiveTitle,
                                artist = effectiveArtist,
                                sourcePackage = nowPlaying?.sourcePackage,
                                spotifyUri = activeSpotifyUri,
                                trackId = nowPlaying?.trackId
                            )
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
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NowPlayingQuickAction(
                        icon = Icons.Rounded.PlaylistAdd,
                        label = "Spotify Queue",
                        accent = Color(0xFF1DB954),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            vm.addTrackToSpotifyQueue(effectiveTitle, effectiveArtist, activeSpotifyUri)
                            actionStatus = "Adding \"$effectiveTitle\" to Spotify queue…"
                        }
                    )
                    NowPlayingQuickAction(
                        icon = Icons.Rounded.FavoriteBorder,
                        label = "Save Liked",
                        accent = Color(0xFF1DB954),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            vm.saveTrackToSpotifyLiked(effectiveTitle, effectiveArtist, activeSpotifyUri)
                            actionStatus = "Saving \"$effectiveTitle\" to Liked Songs…"
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (spotifyDevices.isNotEmpty()) {
                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StudioSectionHeader(title = "Spotify Devices")
                            IconButton(
                                onClick = { vm.refreshSpotifyDevices() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Refresh devices",
                                    tint = neonCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        spotifyDevices.forEach { dev ->
                            val isActive = dev.isActive
                            val devAccent = if (isActive) Color(0xFF1DB954) else Color.White.copy(alpha = 0.6f)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isActive) Color(0xFF1DB954).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
                                    .border(
                                        1.dp,
                                        if (isActive) Color(0xFF1DB954).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        vm.transferPlaybackToDevice(dev.id)
                                        actionStatus = "Switching playback to ${dev.name}…"
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        dev.type.contains("Speaker", true) -> Icons.Rounded.Speaker
                                        dev.type.contains("Computer", true) -> Icons.Rounded.Computer
                                        else -> Icons.Rounded.Headphones
                                    },
                                    contentDescription = null,
                                    tint = devAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dev.name,
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 13.5.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isActive) "Active · ${dev.type}" else dev.type,
                                        color = devAccent,
                                        fontSize = 11.5.sp
                                    )
                                }
                                if (isActive) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Active",
                                        tint = Color(0xFF1DB954),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Up Next Queue (Manual / Auto Spotify-Override + Pending AI Proposal + Non-Repeating Queue)
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StudioSectionHeader(title = "Up Next Queue")
                        IconButton(
                            onClick = { vm.refreshUpNextQueue() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = "Refresh Up Next Queue",
                                tint = neonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Manual vs Auto Queue Takeover Switch
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(
                            1.dp,
                            if (autoOverrideEnabled) safeGreen.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.10f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = if (!autoOverrideEnabled) neonCyan.copy(alpha = 0.18f) else Color.Transparent,
                                    border = BorderStroke(
                                        1.dp,
                                        if (!autoOverrideEnabled) neonCyan else Color.White.copy(alpha = 0.14f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(22.dp))
                                        .clickable { vm.setUpNextAutoOverrideEnabled(false) }
                                ) {
                                    Text(
                                        text = "Manual",
                                        color = if (!autoOverrideEnabled) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.68f),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = if (autoOverrideEnabled) safeGreen.copy(alpha = 0.22f) else Color.Transparent,
                                    border = BorderStroke(
                                        1.dp,
                                        if (autoOverrideEnabled) safeGreen else Color.White.copy(alpha = 0.14f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(22.dp))
                                        .clickable { vm.setUpNextAutoOverrideEnabled(true) }
                                ) {
                                    Text(
                                        text = "Auto · Override Spotify",
                                        color = if (autoOverrideEnabled) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.68f),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (autoOverrideEnabled) {
                                    "Auto: stops Spotify from playing random next songs and auto-spins TuneMind's Up Next track."
                                } else {
                                    "Manual: tap any queued track or Play Next below when you want TuneMind to play next."
                                },
                                color = if (autoOverrideEnabled) safeGreen.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.58f),
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }

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

                    if (effectiveUpNext.tracks.isEmpty()) {
                        Text(
                            text = "Play a few tracks or tap Refresh above to populate your non-repeating Up Next queue.",
                            color = Color.White.copy(alpha = 0.56f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    } else {
                        effectiveUpNext.tracks.forEachIndexed { idx, capTrack ->
                            val capMedia = rememberResolvedTrackMedia(
                                title = capTrack.title,
                                artist = capTrack.artist,
                                spotifyUri = capTrack.spotifyUri
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        vm.playQueuedTrack(capTrack, selectedTargetPlayer)
                                        actionStatus = null
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(neonCyan.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Play",
                                        tint = neonCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${idx + 1}. ${capTrack.title}",
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${capTrack.artist} · ${capTrack.freshnessPercent}% fresh",
                                        color = Color.White.copy(alpha = 0.62f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = capTrack.reason,
                                        color = Color.White.copy(alpha = 0.44f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (idx > 0) {
                                    IconButton(
                                        onClick = { vm.moveUpNextTrackToTop(capTrack) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.VerticalAlignTop,
                                            contentDescription = "Play Next",
                                            tint = neonCyan.copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { vm.removeFromUpNextQueue(capTrack) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Skip from queue",
                                        tint = Color.White.copy(alpha = 0.52f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                LayeredSongArtistBadge(
                                    albumArtUrl = capMedia.albumArtUrl,
                                    artistImageUrl = capMedia.artistImageUrl,
                                    title = capTrack.title,
                                    artist = capTrack.artist,
                                    accentColor = neonCyan,
                                    size = 44.dp
                                )
                            }
                            if (idx < effectiveUpNext.tracks.lastIndex) {
                                StudioHairlineDivider()
                            }
                        }

                        val headTrack = effectiveUpNext.tracks.first()
                        Spacer(modifier = Modifier.height(2.dp))
                        Button(
                            onClick = { vm.playNextFromQueue(selectedTargetPlayer) },
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = neonCyan.copy(alpha = 0.16f),
                                contentColor = Color(0xFFF8FAFC)
                            ),
                            border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.52f)),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = null,
                                tint = neonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Play Next: ${headTrack.title}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
