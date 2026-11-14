package com.tunemind.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.data.ingestion.SpotifyLikedTrackEntity
import com.tunemind.app.data.ingestion.SpotifyLiveTrackState
import com.tunemind.app.data.ingestion.SpotifyPlaylistEntity
import com.tunemind.app.data.ingestion.SpotifyPodcastEntity
import com.tunemind.app.ui.components.AnimatedEqualizerBars
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Embedded Spotify Library tab inside Rhythm:
 * Shows Liked Songs, Playlists (with drill-down), Podcasts (Shows + Episodes), and Live Spotify State.
 */
@Composable
fun SpotifyLibrarySection(
    isSpotifyConnected: Boolean,
    needsScopeUpgrade: Boolean,
    isSyncing: Boolean,
    liveState: SpotifyLiveTrackState?,
    likedTracks: List<SpotifyLikedTrackEntity>,
    playlists: List<SpotifyPlaylistEntity>,
    podcasts: List<SpotifyPodcastEntity>,
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onRefreshLibrary: () -> Unit,
    onRequestSpotifyAuth: () -> Unit,
    onLoadPlaylistTracks: (playlistId: String) -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit
) {
    val spotifyGreen = Color(0xFF1DB954)
    val theme = LocalAdaptiveTheme.current
    var subTab by remember { mutableStateOf(0) } // 0 = Liked Songs, 1 = Playlists, 2 = Podcasts
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }

    val selectedPlaylist = remember(playlists, selectedPlaylistId) {
        selectedPlaylistId?.let { id -> playlists.firstOrNull { it.playlistId == id } }
    }

    LaunchedEffect(selectedPlaylistId) {
        selectedPlaylistId?.let { onLoadPlaylistTracks(it) }
    }

    val podcastShows = remember(podcasts) { podcasts.filter { it.isShowSummary } }
    val podcastEpisodes = remember(podcasts) { podcasts.filter { !it.isShowSummary } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        // 1. Live Spotify Playback Banner (when track or podcast episode is playing)
        if (liveState != null) {
            item(key = "sp_live_card") {
                val progressFrac = if (liveState.durationMs > 0L) {
                    (liveState.progressMs.toFloat() / liveState.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val livePlayCount = resolvePlayCount(liveState.title, liveState.artistOrShow, liveState.spotifyUri)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = spotifyGreen.copy(alpha = 0.12f),
                    border = BorderStroke(1.2.dp, spotifyGreen.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (liveState.isPlaying) {
                                    AnimatedEqualizerBars(
                                        color = spotifyGreen,
                                        modifier = Modifier.size(16.dp, 14.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Pause,
                                        contentDescription = null,
                                        tint = spotifyGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = when {
                                        liveState.isPlaying && liveState.isPodcast -> "Playing Podcast on Spotify"
                                        liveState.isPlaying -> "Playing on Spotify"
                                        else -> "Paused on Spotify"
                                    },
                                    color = spotifyGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (livePlayCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(spotifyGreen.copy(alpha = 0.20f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "$livePlayCount ${if (livePlayCount == 1) "play" else "plays"}",
                                        color = spotifyGreen,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Column {
                            Text(
                                text = liveState.title,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    append(liveState.artistOrShow)
                                    if (liveState.albumOrSubtitle.isNotBlank()) {
                                        append(" • ${liveState.albumOrSubtitle}")
                                    }
                                },
                                color = Color.White.copy(alpha = 0.68f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (liveState.durationMs > 0L) {
                            LinearProgressIndicator(
                                progress = { progressFrac },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = spotifyGreen,
                                trackColor = Color.White.copy(alpha = 0.12f)
                            )
                        }
                    }
                }
            }
        }

        // 2. Sync Header + Permission Upgrade Prompt if needed
        item(key = "sp_library_header") {
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Spotify Library",
                                color = spotifyGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${likedTracks.size} Liked • ${playlists.size} Playlists • ${podcastShows.size} Shows",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(spotifyGreen)
                                .bouncyClickable {
                                    if (isSpotifyConnected) onRefreshLibrary()
                                    else onRequestSpotifyAuth()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.Black
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = when {
                                    isSyncing -> "Syncing…"
                                    isSpotifyConnected -> "Sync"
                                    else -> "Connect"
                                },
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (!isSpotifyConnected || needsScopeUpgrade) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFFFB000).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFFFFB000).copy(alpha = 0.42f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (!isSpotifyConnected) {
                                        "Connect Spotify to sync your Liked Songs, Playlists, and Podcasts into TuneMind."
                                    } else {
                                        "Update Spotify permissions once so TuneMind can load your Liked Songs, Playlists, and Podcasts."
                                    },
                                    color = Color.White.copy(alpha = 0.90f),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                                Button(
                                    onClick = onRequestSpotifyAuth,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB000)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (!isSpotifyConnected) "Connect Spotify" else "Update Spotify Permissions",
                                        color = Color.Black,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // 3 Sub-Tabs: Liked Songs | Playlists | Podcasts
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val tabs = listOf(
                            "Liked (${likedTracks.size})",
                            "Playlists (${playlists.size})",
                            "Podcasts (${podcastShows.size + podcastEpisodes.size})"
                        )
                        tabs.forEachIndexed { idx, label ->
                            val selected = subTab == idx
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) spotifyGreen.copy(alpha = 0.20f) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (selected) spotifyGreen.copy(alpha = 0.48f) else Color.Transparent,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .bouncyClickable {
                                        subTab = idx
                                        if (idx != 1) selectedPlaylistId = null
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) spotifyGreen else Color.White.copy(alpha = 0.62f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Content per Sub-Tab
        when (subTab) {
            0 -> {
                if (likedTracks.isEmpty()) {
                    item(key = "sp_liked_empty") {
                        LibraryEmptyCard(
                            title = "No Liked Songs synced yet",
                            subtitle = if (isSpotifyConnected) "Tap Sync above to pull your saved tracks from Spotify."
                            else "Connect Spotify to view your Liked Songs and live play counts."
                        )
                    }
                } else {
                    itemsIndexed(
                        items = likedTracks,
                        key = { _, item -> "liked_${item.trackId}" }
                    ) { idx, item ->
                        val plays = resolvePlayCount(item.trackTitle, item.artistName, item.spotifyUri)
                        val isPlaying = isTrackCurrentlyPlaying(item.trackTitle, item.artistName, item.spotifyUri)
                        val durLabel = formatTrackMinutesSeconds(item.durationMs)
                        TuneMindTrackCard(
                            title = item.trackTitle,
                            artist = item.artistName,
                            subtitle = listOfNotNull(
                                item.albumName.takeIf { it.isNotBlank() },
                                durLabel.takeIf { it.isNotBlank() }
                            ).joinToString(" • ").ifBlank { null },
                            playCount = plays,
                            showUnplayedLabel = true,
                            isCurrentlyPlaying = isPlaying,
                            accentColor = spotifyGreen,
                            leadingIndex = idx + 1,
                            onPlayClick = {
                                onPlayTrack(item.trackTitle, item.artistName, item.spotifyUri, "com.spotify.music")
                            }
                        )
                    }
                }
            }

            1 -> {
                if (selectedPlaylist != null) {
                    val playlistTracks = selectedPlaylist.decodeTracks()
                    item(key = "pl_back_header") {
                        SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .bouncyClickable { selectedPlaylistId = null }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowBack,
                                        contentDescription = "Back to Playlists",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedPlaylist.name,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${selectedPlaylist.trackCount} songs${if (selectedPlaylist.ownerName.isNotBlank()) " • ${selectedPlaylist.ownerName}" else ""}",
                                        color = spotifyGreen,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    if (playlistTracks.isEmpty()) {
                        item(key = "pl_tracks_loading") {
                            LibraryEmptyCard(
                                title = "Loading playlist tracks…",
                                subtitle = "If tracks don't appear, tap Sync above or ensure playlist permissions are granted."
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = playlistTracks,
                            key = { idx, tr -> "pl_${selectedPlaylist.playlistId}_${tr.trackId}_$idx" }
                        ) { idx, tr ->
                            val plays = resolvePlayCount(tr.title, tr.artist, tr.spotifyUri)
                            val isPlaying = isTrackCurrentlyPlaying(tr.title, tr.artist, tr.spotifyUri)
                            val durLabel = formatTrackMinutesSeconds(tr.durationMs)
                            TuneMindTrackCard(
                                title = tr.title,
                                artist = tr.artist,
                                subtitle = listOfNotNull(
                                    tr.album.takeIf { it.isNotBlank() },
                                    durLabel.takeIf { it.isNotBlank() }
                                ).joinToString(" • ").ifBlank { null },
                                playCount = plays,
                                showUnplayedLabel = true,
                                isCurrentlyPlaying = isPlaying,
                                accentColor = spotifyGreen,
                                leadingIndex = idx + 1,
                                onPlayClick = {
                                    onPlayTrack(tr.title, tr.artist, tr.spotifyUri.ifBlank { null }, "com.spotify.music")
                                }
                            )
                        }
                    }
                } else if (playlists.isEmpty()) {
                    item(key = "sp_playlists_empty") {
                        LibraryEmptyCard(
                            title = "No Playlists synced yet",
                            subtitle = "Tap Sync above to fetch your personal Spotify playlists."
                        )
                    }
                } else {
                    items(
                        items = playlists,
                        key = { "playlist_${it.playlistId}" }
                    ) { pl ->
                        val previewTracks = pl.decodeTracks()
                        val hasPlayingTrack = previewTracks.any {
                            isTrackCurrentlyPlaying(it.title, it.artist, it.spotifyUri)
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (hasPlayingTrack) spotifyGreen.copy(alpha = 0.12f) else Color(0xFF0C1018).copy(alpha = 0.92f),
                            border = BorderStroke(
                                1.dp,
                                if (hasPlayingTrack) spotifyGreen.copy(alpha = 0.58f) else Color.White.copy(alpha = 0.08f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .bouncyClickable { selectedPlaylistId = pl.playlistId }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(spotifyGreen.copy(alpha = 0.16f))
                                        .border(1.dp, spotifyGreen.copy(alpha = 0.36f), RoundedCornerShape(14.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.QueueMusic,
                                        contentDescription = null,
                                        tint = spotifyGreen,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pl.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            append("${pl.trackCount} songs")
                                            if (pl.ownerName.isNotBlank()) append(" • ${pl.ownerName}")
                                        },
                                        color = Color.White.copy(alpha = 0.62f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (hasPlayingTrack) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Playing now",
                                            color = spotifyGreen,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = "Open Playlist",
                                    tint = Color.White.copy(alpha = 0.65f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                if (podcasts.isEmpty()) {
                    item(key = "sp_podcasts_empty") {
                        LibraryEmptyCard(
                            title = "No Podcasts synced yet",
                            subtitle = "Follow shows or save podcast episodes on Spotify, then tap Sync."
                        )
                    }
                } else {
                    if (podcastShows.isNotEmpty()) {
                        item(key = "pod_shows_header") {
                            Text(
                                text = "Following Shows (${podcastShows.size})",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(
                            items = podcastShows,
                            key = { "show_${it.itemId}" }
                        ) { show ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color(0xFF0C1018).copy(alpha = 0.92f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(theme.secondaryAccent.copy(alpha = 0.16f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Podcasts,
                                            contentDescription = null,
                                            tint = theme.secondaryAccent,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = show.showName,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = buildString {
                                                if (show.publisher.isNotBlank()) append(show.publisher)
                                                if (show.totalEpisodes > 0) {
                                                    if (isNotEmpty()) append(" • ")
                                                    append("${show.totalEpisodes} episodes")
                                                }
                                            }.ifBlank { "Podcast Show" },
                                            color = Color.White.copy(alpha = 0.62f),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (podcastEpisodes.isNotEmpty()) {
                        item(key = "pod_episodes_header") {
                            Text(
                                text = "Recent & Saved Episodes",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        items(
                            items = podcastEpisodes,
                            key = { "ep_${it.itemId}" }
                        ) { ep ->
                            val isPlaying = isTrackCurrentlyPlaying(ep.episodeTitle, ep.showName, ep.uri)
                            val durMins = (ep.durationMs / 60_000L).coerceAtLeast(1L)
                            val resumeFrac = if (ep.durationMs > 0L) {
                                (ep.resumePositionMs.toFloat() / ep.durationMs.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            val remainingMins = ((ep.durationMs - ep.resumePositionMs).coerceAtLeast(0L) / 60_000L)

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (isPlaying) spotifyGreen.copy(alpha = 0.12f) else Color(0xFF0C1018).copy(alpha = 0.92f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isPlaying) spotifyGreen.copy(alpha = 0.60f) else Color.White.copy(alpha = 0.08f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bouncyClickable {
                                        onPlayTrack(ep.episodeTitle, ep.showName, ep.uri.ifBlank { null }, "com.spotify.music")
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background((if (isPlaying) spotifyGreen else theme.primaryAccent).copy(alpha = 0.16f))
                                        ) {
                                            if (isPlaying) {
                                                AnimatedEqualizerBars(
                                                    color = spotifyGreen,
                                                    modifier = Modifier.size(16.dp, 14.dp)
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Rounded.PlayArrow,
                                                    contentDescription = "Play Episode",
                                                    tint = theme.primaryAccent,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = ep.episodeTitle,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${ep.showName} • ${durMins}m${if (ep.releaseDate.isNotBlank()) " • ${ep.releaseDate}" else ""}",
                                                color = Color.White.copy(alpha = 0.62f),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    if (ep.resumePositionMs > 0L || ep.fullyPlayed || isPlaying) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            LinearProgressIndicator(
                                                progress = { if (ep.fullyPlayed) 1f else resumeFrac },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(2.dp)),
                                                color = if (isPlaying) spotifyGreen else theme.primaryAccent,
                                                trackColor = Color.White.copy(alpha = 0.08f)
                                            )
                                            Text(
                                                text = when {
                                                    isPlaying -> "Playing"
                                                    ep.fullyPlayed -> "Played"
                                                    else -> "${remainingMins}m left"
                                                },
                                                color = if (isPlaying) spotifyGreen else Color.White.copy(alpha = 0.64f),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyCard(
    title: String,
    subtitle: String
) {
    SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

private fun formatTrackMinutesSeconds(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSec = ms / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format("%d:%02d", mins, secs)
}
