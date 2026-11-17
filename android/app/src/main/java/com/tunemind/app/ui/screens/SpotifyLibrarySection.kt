package com.tunemind.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tunemind.app.data.ingestion.SpotifyLikedTrackEntity
import com.tunemind.app.data.ingestion.SpotifyLiveTrackState
import com.tunemind.app.data.ingestion.SpotifyPlaylistEntity
import com.tunemind.app.data.ingestion.SpotifyPodcastEntity
import com.tunemind.app.ui.components.AnimatedEqualizerBars
import com.tunemind.app.ui.TrackVaultUiStatus
import com.tunemind.app.ui.components.LayeredSongArtistBadge
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.components.rememberResolvedTrackMedia
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

/**
 * Embedded Spotify Library tab inside Flow:
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
    loadingPlaylistIds: Set<String> = emptySet(),
    loadedPlaylistIds: Set<String> = emptySet(),
    playlistTrackErrors: Map<String, String> = emptyMap(),
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onRefreshLibrary: () -> Unit,
    onRequestSpotifyAuth: () -> Unit,
    onLoadPlaylistTracks: (playlistId: String) -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    resolveTrackVaultStatus: (title: String?, artist: String?, uri: String?, trackId: String?) -> TrackVaultUiStatus = { _, _, _, _ -> TrackVaultUiStatus() },
    onToggleTrackVault: ((title: String, artist: String, sourcePkg: String?, uri: String?, trackId: String?) -> Unit)? = null,
    onToggleTrackFresh: ((title: String, artist: String, sourcePkg: String?, uri: String?, trackId: String?) -> Unit)? = null,
    onAskAgentAboutTrack: ((title: String, artist: String, freshness: Int, uri: String?) -> Unit)? = null,
    onSyncAtmosphereToTrack: ((title: String, artist: String) -> Unit)? = null
) {
    val spotifyGreen = Color(0xFF1DB954)
    val theme = LocalAdaptiveTheme.current
    var subTab by remember { mutableStateOf(0) } // 0 = Liked Songs, 1 = Playlists, 2 = Podcasts
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var inspectedLibTrack by remember { mutableStateOf<Triple<String, String, Triple<String?, String, Int>>?>(null) }

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
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 124.dp)
    ) {
        // 1. Live Spotify Playback Hero (only when active)
        if (liveState != null) {
            item(key = "sp_live_card") {
                val progressFrac = if (liveState.durationMs > 0L) {
                    (liveState.progressMs.toFloat() / liveState.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val livePlayCount = resolvePlayCount(liveState.title, liveState.artistOrShow, liveState.spotifyUri)
                val liveActuallyPlaying = isTrackCurrentlyPlaying(liveState.title, liveState.artistOrShow, liveState.spotifyUri)
                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (liveActuallyPlaying) {
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
                                        liveActuallyPlaying && liveState.isPodcast -> "Playing Podcast on Spotify"
                                        liveActuallyPlaying -> "Playing on Spotify"
                                        else -> "Paused on Spotify"
                                    },
                                    color = spotifyGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (livePlayCount > 0) {
                                Text(
                                    text = "$livePlayCount ${if (livePlayCount == 1) "play" else "plays"}",
                                    color = spotifyGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        val liveMedia = rememberResolvedTrackMedia(
                            title = liveState.title,
                            artist = liveState.artistOrShow,
                            spotifyUri = liveState.spotifyUri,
                            fallbackAlbumArtUrl = liveState.albumArtUrl
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = liveState.title,
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        append(liveState.artistOrShow)
                                        val livePlays = resolvePlayCount(liveState.title, liveState.artistOrShow, liveState.spotifyUri)
                                        append(" · $livePlays ${if (livePlays == 1) "play" else "plays"}")
                                        if (liveState.albumOrSubtitle.isNotBlank()) {
                                            append(" · ${liveState.albumOrSubtitle}")
                                        }
                                    },
                                    color = Color.White.copy(alpha = 0.60f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            LayeredSongArtistBadge(
                                albumArtUrl = liveState.albumArtUrl?.takeIf { it.isNotBlank() } ?: liveMedia.albumArtUrl,
                                artistImageUrl = liveMedia.artistImageUrl,
                                title = liveState.title,
                                artist = liveState.artistOrShow,
                                accentColor = spotifyGreen,
                                size = 48.dp
                            )
                        }

                        if (liveState.durationMs > 0L) {
                            LinearProgressIndicator(
                                progress = { progressFrac },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = spotifyGreen,
                                trackColor = Color.White.copy(alpha = 0.10f)
                            )
                        }
                    }
                }
            }
        }

        // 2. Open Library Header + Sub-tabs
        item(key = "sp_library_header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Spotify Library",
                            color = Color(0xFFF8FAFC),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${likedTracks.size} Liked · ${playlists.size} Playlists · ${podcastShows.size} Shows",
                            color = Color.White.copy(alpha = 0.56f),
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
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
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (!isSpotifyConnected || needsScopeUpgrade) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (!isSpotifyConnected) {
                                "Connect Spotify to sync your Liked Songs, Playlists, and Podcasts into TuneMind."
                            } else {
                                "Update Spotify permissions once so TuneMind can load your Liked Songs, Playlists, and Podcasts."
                            },
                            color = Color(0xFFFFB000),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Button(
                            onClick = onRequestSpotifyAuth,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB000)),
                            shape = RoundedCornerShape(28.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (!isSpotifyConnected) "Connect Spotify" else "Update Spotify Permissions",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Magical Sliding Capsule Sub-Tabs: Liked | Playlists | Podcasts
                MagicalSegmentedTabs(
                    tabs = listOf(
                        MagicalTabItem("Liked (${likedTracks.size})", spotifyGreen),
                        MagicalTabItem("Playlists (${playlists.size})", spotifyGreen),
                        MagicalTabItem("Podcasts (${podcastShows.size + podcastEpisodes.size})", theme.secondaryAccent)
                    ),
                    selectedIndex = subTab,
                    onSelectTab = { idx ->
                        subTab = idx
                        if (idx != 1) selectedPlaylistId = null
                    },
                    height = 42.dp
                )
            }
        }

        // 3. Content per Sub-Tab
        when (subTab) {
            0 -> {
                if (likedTracks.isEmpty()) {
                    item(key = "sp_liked_empty") {
                        LibraryEmptyBlock(
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
                        val canonicalId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalTrackId(
                            trackId = item.trackId,
                            spotifyUri = item.spotifyUri,
                            title = item.trackTitle,
                            artist = item.artistName
                        )
                        val plays = resolvePlayCount(item.trackTitle, item.artistName, item.spotifyUri)
                        val isPlaying = isTrackCurrentlyPlaying(item.trackTitle, item.artistName, item.spotifyUri)
                        val durLabel = formatTrackMinutesSeconds(item.durationMs)
                        val vStatus = resolveTrackVaultStatus(item.trackTitle, item.artistName, item.spotifyUri, canonicalId)
                        TuneMindTrackCard(
                            title = item.trackTitle,
                            artist = item.artistName,
                            subtitle = listOfNotNull(
                                item.albumName.takeIf { it.isNotBlank() },
                                durLabel.takeIf { it.isNotBlank() }
                            ).joinToString(" · ").ifBlank { null },
                            playCount = plays,
                            showUnplayedLabel = true,
                            isCurrentlyPlaying = isPlaying,
                            freshnessPercent = if (vStatus.isInVault || vStatus.satietyScore > 0) vStatus.freshnessPercent else null,
                            isCoolingDown = vStatus.isInVault || vStatus.isCoolingDown,
                            cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                            recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                            accentColor = spotifyGreen,
                            leadingIndex = idx + 1,
                            spotifyUri = item.spotifyUri,
                            albumArtUrl = item.albumArtUrl,
                            trackId = canonicalId,
                            onPlayClick = {
                                onPlayTrack(item.trackTitle, item.artistName, item.spotifyUri, "com.spotify.music")
                            },
                            onSyncClick = if (onToggleTrackVault != null) {
                                {
                                    inspectedLibTrack = Triple(
                                        item.trackTitle,
                                        item.artistName,
                                        Triple(item.spotifyUri, canonicalId, plays)
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }

            1 -> {
                if (selectedPlaylist != null) {
                    val playlistTracks = selectedPlaylist.decodeTracks()
                    val isLoadingThisPlaylist = selectedPlaylist.playlistId in loadingPlaylistIds
                    val isLoadedThisPlaylist = selectedPlaylist.playlistId in loadedPlaylistIds
                    val playlistError = playlistTrackErrors[selectedPlaylist.playlistId]
                    val displayedSongCount = maxOf(selectedPlaylist.trackCount, playlistTracks.size)

                    item(key = "pl_back_header") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF0E131D),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clickable { selectedPlaylistId = null }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.ArrowBack,
                                            contentDescription = "Back to Playlists",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedPlaylist.name,
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            when {
                                                displayedSongCount > 0 -> append("$displayedSongCount songs")
                                                isLoadingThisPlaylist -> append("Fetching songs…")
                                                else -> append("Playlist")
                                            }
                                            if (selectedPlaylist.ownerName.isNotBlank()) {
                                                append(" · ${selectedPlaylist.ownerName}")
                                            }
                                        },
                                        color = spotifyGreen,
                                        fontSize = 12.sp
                                    )
                                }
                                if (isLoadingThisPlaylist) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = spotifyGreen
                                    )
                                }
                            }
                            StudioHairlineDivider()
                        }
                    }

                    if (playlistTracks.isNotEmpty()) {
                        itemsIndexed(
                            items = playlistTracks,
                            key = { idx, tr -> "pl_${selectedPlaylist.playlistId}_${tr.trackId}_$idx" }
                        ) { idx, tr ->
                            val canonicalId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalTrackId(
                                trackId = tr.trackId,
                                spotifyUri = tr.spotifyUri,
                                title = tr.title,
                                artist = tr.artist
                            )
                            val plays = resolvePlayCount(tr.title, tr.artist, tr.spotifyUri)
                            val isPlaying = isTrackCurrentlyPlaying(tr.title, tr.artist, tr.spotifyUri)
                            val durLabel = formatTrackMinutesSeconds(tr.durationMs)
                            val vStatus = resolveTrackVaultStatus(tr.title, tr.artist, tr.spotifyUri, canonicalId)
                            TuneMindTrackCard(
                                title = tr.title,
                                artist = tr.artist,
                                subtitle = listOfNotNull(
                                    tr.album.takeIf { it.isNotBlank() },
                                    durLabel.takeIf { it.isNotBlank() }
                                ).joinToString(" · ").ifBlank { null },
                                playCount = plays,
                                showUnplayedLabel = true,
                                isCurrentlyPlaying = isPlaying,
                                freshnessPercent = if (vStatus.isInVault || vStatus.satietyScore > 0) vStatus.freshnessPercent else null,
                                isCoolingDown = vStatus.isInVault || vStatus.isCoolingDown,
                                cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                                recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                                accentColor = spotifyGreen,
                                leadingIndex = idx + 1,
                                spotifyUri = tr.spotifyUri.ifBlank { null },
                                albumArtUrl = tr.albumArtUrl,
                                trackId = canonicalId,
                                onPlayClick = {
                                    onPlayTrack(tr.title, tr.artist, tr.spotifyUri.ifBlank { null }, "com.spotify.music")
                                },
                                onSyncClick = if (onToggleTrackVault != null) {
                                    {
                                        inspectedLibTrack = Triple(
                                            tr.title,
                                            tr.artist,
                                            Triple(tr.spotifyUri.ifBlank { null }, canonicalId, plays)
                                        )
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                    } else if (isLoadingThisPlaylist || !isLoadedThisPlaylist) {
                        item(key = "pl_tracks_loading") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.2.dp,
                                    color = spotifyGreen
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Loading songs from Spotify…",
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Fetching playlist items for ${selectedPlaylist.name}",
                                        color = Color.White.copy(alpha = 0.58f),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    } else {
                        item(key = "pl_tracks_empty_or_error") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = if (!playlistError.isNullOrBlank()) {
                                        "Could not load playlist songs"
                                    } else {
                                        "No songs returned for this playlist"
                                    },
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = playlistError
                                        ?: "This playlist appears empty or Spotify omitted its tracks for this account.",
                                    color = Color.White.copy(alpha = 0.64f),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onLoadPlaylistTracks(selectedPlaylist.playlistId) },
                                        shape = RoundedCornerShape(28.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Retry",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            onPlayTrack(
                                                selectedPlaylist.name,
                                                selectedPlaylist.ownerName.ifBlank { "Spotify Playlist" },
                                                selectedPlaylist.uri,
                                                "com.spotify.music"
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                                        shape = RoundedCornerShape(28.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Play Playlist",
                                            color = Color.Black,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (playlists.isEmpty()) {
                    item(key = "sp_playlists_empty") {
                        LibraryEmptyBlock(
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
                        val effectiveCount = maxOf(pl.trackCount, previewTracks.size)
                        val isLoaded = pl.playlistId in loadedPlaylistIds
                        val countLabel = when {
                            effectiveCount > 0 -> "$effectiveCount songs"
                            isLoaded -> "0 songs"
                            else -> "Tap to view songs"
                        }
                        val hasPlayingTrack = previewTracks.any {
                            isTrackCurrentlyPlaying(it.title, it.artist, it.spotifyUri)
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (hasPlayingTrack) spotifyGreen.copy(alpha = 0.08f) else Color.Transparent)
                                    .bouncyClickable { selectedPlaylistId = pl.playlistId }
                                    .padding(horizontal = 4.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(spotifyGreen.copy(alpha = 0.14f))
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
                                        color = if (hasPlayingTrack) spotifyGreen else Color(0xFFF8FAFC),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            append(countLabel)
                                            if (pl.ownerName.isNotBlank()) append(" · ${pl.ownerName}")
                                            if (hasPlayingTrack) append(" · Playing")
                                        },
                                        color = Color.White.copy(alpha = 0.56f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = "Open Playlist",
                                    tint = Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier.size(20.dp)
                                )

                                SpotifyCoverArtBox(
                                    imageUrl = pl.imageUrl,
                                    fallbackIcon = Icons.Rounded.QueueMusic,
                                    accentColor = spotifyGreen,
                                    size = 44.dp
                                )
                            }
                            StudioHairlineDivider(modifier = Modifier.padding(start = 60.dp))
                        }
                    }
                }
            }

            else -> {
                if (podcasts.isEmpty()) {
                    item(key = "sp_podcasts_empty") {
                        LibraryEmptyBlock(
                            title = "No Podcasts synced yet",
                            subtitle = "Follow shows or save podcast episodes on Spotify, then tap Sync."
                        )
                    }
                } else {
                    if (podcastShows.isNotEmpty()) {
                        item(key = "pod_shows_header") {
                            StudioSectionHeader(title = "Following Shows (${podcastShows.size})")
                        }
                        items(
                            items = podcastShows,
                            key = { "show_${it.itemId}" }
                        ) { show ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(theme.secondaryAccent.copy(alpha = 0.14f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Podcasts,
                                            contentDescription = null,
                                            tint = theme.secondaryAccent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = show.showName,
                                            color = Color(0xFFF8FAFC),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = buildString {
                                                if (show.publisher.isNotBlank()) append(show.publisher)
                                                if (show.totalEpisodes > 0) {
                                                    if (isNotEmpty()) append(" · ")
                                                    append("${show.totalEpisodes} episodes")
                                                }
                                            }.ifBlank { "Podcast Show" },
                                            color = Color.White.copy(alpha = 0.56f),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    SpotifyCoverArtBox(
                                        imageUrl = show.imageUrl,
                                        fallbackIcon = Icons.Rounded.Podcasts,
                                        accentColor = theme.secondaryAccent,
                                        size = 44.dp
                                    )
                                }
                                StudioHairlineDivider(modifier = Modifier.padding(start = 54.dp))
                            }
                        }
                    }

                    if (podcastEpisodes.isNotEmpty()) {
                        item(key = "pod_episodes_header") {
                            StudioSectionHeader(
                                title = "Recent & Saved Episodes",
                                modifier = Modifier.padding(top = 6.dp)
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

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isPlaying) spotifyGreen.copy(alpha = 0.08f) else Color.Transparent)
                                        .bouncyClickable {
                                            onPlayTrack(ep.episodeTitle, ep.showName, ep.uri.ifBlank { null }, "com.spotify.music")
                                        }
                                        .padding(horizontal = 4.dp, vertical = 12.dp),
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
                                                .background((if (isPlaying) spotifyGreen else theme.primaryAccent).copy(alpha = 0.14f))
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
                                                color = if (isPlaying) spotifyGreen else Color(0xFFF8FAFC),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${ep.showName} · ${durMins}m${if (ep.releaseDate.isNotBlank()) " · ${ep.releaseDate}" else ""}",
                                                color = Color.White.copy(alpha = 0.58f),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        SpotifyCoverArtBox(
                                            imageUrl = ep.imageUrl,
                                            fallbackIcon = Icons.Rounded.Podcasts,
                                            accentColor = if (isPlaying) spotifyGreen else theme.primaryAccent,
                                            size = 44.dp
                                        )
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
                                                    .height(3.dp)
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
                                                color = if (isPlaying) spotifyGreen else Color.White.copy(alpha = 0.60f),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                                StudioHairlineDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    inspectedLibTrack?.let { (libTitle, libArtist, meta) ->
        val (libUri, libCanonicalId, libPlays) = meta
        val vStatus = resolveTrackVaultStatus(libTitle, libArtist, libUri, libCanonicalId)
        NeuralTrackActionDialog(
            title = libTitle,
            artist = libArtist,
            freshnessPercent = vStatus.freshnessPercent,
            playCount = libPlays,
            isCoolingDown = vStatus.isCoolingDown,
            isInFresh = vStatus.isInFresh && !vStatus.isCoolingDown,
            spotifyUri = libUri,
            trackId = libCanonicalId,
            cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
            recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
            vaultReason = vStatus.entryReason,
            onDismiss = { inspectedLibTrack = null },
            onPlayNow = { onPlayTrack(libTitle, libArtist, libUri, "com.spotify.music") },
            onAgentNeuralBreakdown = {
                onAskAgentAboutTrack?.invoke(libTitle, libArtist, vStatus.freshnessPercent, libUri)
            },
            onSyncAtmosphereToTrack = {
                onSyncAtmosphereToTrack?.invoke(libTitle, libArtist)
            },
            onToggleCooldownVault = {
                onToggleTrackVault?.invoke(libTitle, libArtist, "com.spotify.music", libUri, libCanonicalId)
            },
            onToggleFreshList = if (onToggleTrackFresh != null) {
                { onToggleTrackFresh(libTitle, libArtist, "com.spotify.music", libUri, libCanonicalId) }
            } else null
        )
    }
}

@Composable
private fun LibraryEmptyBlock(
    title: String,
    subtitle: String
) {
    SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                color = Color(0xFFF8FAFC),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun SpotifyCoverArtBox(
    imageUrl: String?,
    fallbackIcon: ImageVector,
    accentColor: Color,
    size: Dp = 44.dp
) {
    val context = LocalContext.current
    val cleanUrl = imageUrl?.trim()?.takeIf { it.startsWith("http") }
    var imageFailed by remember(cleanUrl) { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(accentColor.copy(alpha = 0.14f))
    ) {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size((size * 0.45f).coerceIn(16.dp, 24.dp))
        )
        if (cleanUrl != null && !imageFailed) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(cleanUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
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
