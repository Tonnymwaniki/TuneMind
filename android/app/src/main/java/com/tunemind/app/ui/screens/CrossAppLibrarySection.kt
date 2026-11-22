package com.tunemind.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine
import com.tunemind.app.data.local.CrossAppPlayRecorder
import com.tunemind.app.data.local.CrossAppTrackEntity
import com.tunemind.app.data.local.DetectedPlayerTab
import com.tunemind.app.data.local.TuneMindPlaylistWithTracks
import com.tunemind.app.provider.SpotifyMusicProvider
import com.tunemind.app.provider.SpotifyPremiumConnectManager
import com.tunemind.app.ui.components.LayeredSongArtistBadge
import com.tunemind.app.ui.components.rememberResolvedTrackMedia
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import kotlinx.coroutines.launch
import java.util.Locale

private fun playerAccentColor(packageName: String): Color {
    val pkg = packageName.lowercase(Locale.ROOT)
    return when {
        pkg.contains("youtube.music") -> Color(0xFFFF4B4B)
        pkg.contains("boomplay") -> Color(0xFF00E5FF)
        pkg.contains("spotify") -> Color(0xFF1DB954)
        pkg.contains("audiomack") -> Color(0xFFFFA200)
        pkg.contains("deezer") -> Color(0xFFA855F7)
        pkg.contains("soundcloud") -> Color(0xFFFF6B2B)
        pkg.contains("tunemind") -> Color(0xFF38BDF8)
        else -> Color(0xFF10B981)
    }
}

private fun formatDurationLabel(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return when {
        hours > 0 -> "${hours}h ${mins}m"
        totalMinutes > 0 -> "${totalMinutes}m"
        ms >= 1000 -> "${ms / 1000}s"
        else -> "0m"
    }
}

private fun formatTruePlayCount(plays: Int): String = when {
    plays <= 0 -> "Not played yet"
    plays == 1 -> "1 play"
    else -> "$plays plays"
}

/**
 * Clean, Player-Scoped Cross-App Music Library inside Flow -> Library.
 *
 * Keeps every player cleanly organized with zero confusion:
 * - "All Music": unified permanent Track Cards across every player
 * - "Spotify": dedicated Spotify hub with clean sub-tabs (Songs | Liked | Playlists)
 * - Other detected players (YouTube Music, Boomplay, Audiomack, Phone Audio, etc.)
 * - "Liked": all favorited Track Cards across apps
 * - "TuneMind Playlists": ONLY custom cross-app TuneMind playlists (manual + AI voice)
 *
 * Every song card displays its exact verified play count resolved from TuneMind's
 * permanent relational play ledger.
 */
@Composable
fun CrossAppLibrarySection(
    libraryTracks: List<CrossAppTrackEntity>,
    detectedPlayerTabs: List<DetectedPlayerTab>,
    playlists: List<TuneMindPlaylistWithTracks>,
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int = { _, _, _ -> 0 },
    isTrackCurrentlyPlaying: (title: String, artist: String) -> Boolean,
    resolveTrackVaultStatus: (title: String, artist: String) -> Pair<Boolean, Int>,
    onPlayTrack: (title: String, artist: String, preferredPackage: String?) -> Unit,
    onToggleLike: (canonicalTrackKey: String, liked: Boolean) -> Unit,
    onToggleTrackVault: (title: String, artist: String, sourcePackage: String) -> Unit,
    onAskAgentAboutTrack: (title: String, artist: String) -> Unit,
    onOpenPlaylistsManager: () -> Unit,
    onOpenPlaylistByName: ((String) -> Unit)? = null
) {
    val theme = LocalAdaptiveTheme.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val spotifyStatus by SpotifyPremiumConnectManager.statusFlow.collectAsState()
    val spotifySyncState by SpotifyLibrarySyncEngine.syncState.collectAsState()

    LaunchedEffect(Unit) {
        SpotifyPremiumConnectManager.ensureLoaded(context)
        SpotifyLibrarySyncEngine.ensureLoaded(context)
    }

    val spotifyPkg = SpotifyMusicProvider.SPOTIFY_PACKAGE

    // Key: "ALL", "com.spotify.music", other player packageName, "LIKED", or "TUNEMIND_PLAYLISTS"
    var selectedTabKey by rememberSaveable { mutableStateOf("ALL") }
    // Sub-tab inside Spotify Hub: "SONGS", "LIKED", "PLAYLISTS"
    var spotifySubTab by rememberSaveable { mutableStateOf("SONGS") }

    val spotifyPlaylists = remember(playlists) {
        playlists.filter { SpotifyLibrarySyncEngine.isSpotifySyncedPlaylistId(it.playlist.playlistId) }
    }
    val customTuneMindPlaylists = remember(playlists) {
        playlists.filter { !SpotifyLibrarySyncEngine.isSpotifySyncedPlaylistId(it.playlist.playlistId) }
    }

    val likedTracks = remember(libraryTracks) { libraryTracks.filter { it.isLiked } }
    val spotifyTracks = remember(libraryTracks) {
        libraryTracks.filter { track ->
            track.primarySourcePackage.equals(spotifyPkg, ignoreCase = true) ||
                track.sourcePackagesCsv.split(",").any { it.trim().equals(spotifyPkg, ignoreCase = true) }
        }
    }
    val spotifyLikedTracks = remember(spotifyTracks) { spotifyTracks.filter { it.isLiked } }

    val showSpotifyTab = spotifyStatus.isConnected || spotifyTracks.isNotEmpty() || spotifyPlaylists.isNotEmpty()
    val nonSpotifyPlayerTabs = remember(detectedPlayerTabs) {
        detectedPlayerTabs.filter { !it.packageName.equals(spotifyPkg, ignoreCase = true) }
    }

    val visibleTracks = remember(libraryTracks, likedTracks, spotifyTracks, spotifyLikedTracks, selectedTabKey, spotifySubTab) {
        val raw = when (selectedTabKey) {
            "ALL" -> libraryTracks
            "LIKED" -> likedTracks
            "TUNEMIND_PLAYLISTS" -> emptyList()
            spotifyPkg -> when (spotifySubTab) {
                "LIKED" -> spotifyLikedTracks
                "PLAYLISTS" -> emptyList()
                else -> spotifyTracks
            }
            else -> libraryTracks.filter { track ->
                track.primarySourcePackage.equals(selectedTabKey, ignoreCase = true) ||
                    track.sourcePackagesCsv.split(",").any { it.trim().equals(selectedTabKey, ignoreCase = true) }
            }
        }
        raw.distinctBy {
            com.tunemind.app.domain.model.TrackIdentityResolver.buildNormalizedMatchKey(it.title, it.artist)
        }
    }

    val activeTabMeta = remember(
        detectedPlayerTabs,
        selectedTabKey,
        spotifySubTab,
        libraryTracks,
        likedTracks,
        spotifyTracks,
        spotifyLikedTracks,
        spotifyPlaylists,
        customTuneMindPlaylists
    ) {
        when (selectedTabKey) {
            "ALL" -> Triple(
                "All Music Library",
                "${libraryTracks.size} permanent Track Cards across ${detectedPlayerTabs.size.coerceAtLeast(1)} player(s)",
                theme.primaryAccent
            )
            spotifyPkg -> {
                val subSummary = when (spotifySubTab) {
                    "PLAYLISTS" -> "${spotifyPlaylists.size} synced Spotify playlist(s)"
                    "LIKED" -> "${spotifyLikedTracks.size} Spotify liked song(s)"
                    else -> "${spotifyTracks.size} Spotify song(s) · ${spotifyPlaylists.size} playlist(s)"
                }
                Triple("Spotify Library", subSummary, Color(0xFF1DB954))
            }
            "LIKED" -> Triple(
                "Liked Songs",
                "${likedTracks.size} favorited track(s) across all players",
                Color(0xFFFB7185)
            )
            "TUNEMIND_PLAYLISTS" -> Triple(
                "TuneMind Playlists",
                "${customTuneMindPlaylists.size} universal cross-app playlist(s)",
                Color(0xFF38BDF8)
            )
            else -> {
                val tab = detectedPlayerTabs.firstOrNull { it.packageName == selectedTabKey }
                val accent = playerAccentColor(selectedTabKey)
                val name = tab?.displayName ?: CrossAppPlayRecorder.resolveFriendlyPlayerName(selectedTabKey)
                val summary = if (tab != null) {
                    "${tab.trackCount} songs · ${tab.totalPlays} verified plays · ${formatDurationLabel(tab.totalListenedMs)}"
                } else {
                    "${visibleTracks.size} songs in $name"
                }
                Triple(name, summary, accent)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Clean Top-Level Player Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PlayerFilterPill(
                label = "All Music (${libraryTracks.size})",
                accent = theme.primaryAccent,
                selected = selectedTabKey == "ALL",
                onClick = { selectedTabKey = "ALL" }
            )

            if (showSpotifyTab) {
                PlayerFilterPill(
                    label = "Spotify (${spotifyTracks.size})",
                    accent = Color(0xFF1DB954),
                    selected = selectedTabKey == spotifyPkg,
                    onClick = { selectedTabKey = spotifyPkg }
                )
            }

            nonSpotifyPlayerTabs.forEach { tab ->
                PlayerFilterPill(
                    label = "${tab.displayName} (${tab.trackCount})",
                    accent = playerAccentColor(tab.packageName),
                    selected = selectedTabKey == tab.packageName,
                    onClick = { selectedTabKey = tab.packageName }
                )
            }

            PlayerFilterPill(
                label = "Liked (${likedTracks.size})",
                accent = Color(0xFFFB7185),
                selected = selectedTabKey == "LIKED",
                onClick = { selectedTabKey = "LIKED" }
            )

            PlayerFilterPill(
                label = "TuneMind Playlists (${customTuneMindPlaylists.size})",
                accent = Color(0xFF38BDF8),
                selected = selectedTabKey == "TUNEMIND_PLAYLISTS",
                onClick = { selectedTabKey = "TUNEMIND_PLAYLISTS" }
            )
        }

        // 2. Active Player / Section Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            activeTabMeta.third.copy(alpha = 0.16f),
                            Color(0xFF0E1422)
                        )
                    )
                )
                .border(1.dp, activeTabMeta.third.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(activeTabMeta.third.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = when (selectedTabKey) {
                                "LIKED" -> Icons.Rounded.Favorite
                                "TUNEMIND_PLAYLISTS" -> Icons.Rounded.QueueMusic
                                else -> Icons.Rounded.LibraryMusic
                            },
                            contentDescription = null,
                            tint = activeTabMeta.third,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = activeTabMeta.first,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (selectedTabKey == spotifyPkg && spotifySyncState.isSyncing && spotifySyncState.progressMessage.isNotBlank()) {
                                spotifySyncState.progressMessage
                            } else {
                                activeTabMeta.second
                            },
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (selectedTabKey == "TUNEMIND_PLAYLISTS") {
                    Text(
                        text = "Open Manager",
                        color = activeTabMeta.third,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(activeTabMeta.third.copy(alpha = 0.14f))
                            .clickable { onOpenPlaylistsManager() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                } else if (spotifyStatus.isConnected && (selectedTabKey == spotifyPkg || selectedTabKey == "ALL" || selectedTabKey == "LIKED")) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.16f))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.42f), RoundedCornerShape(10.dp))
                            .clickable(enabled = !spotifySyncState.isSyncing) {
                                scope.launch {
                                    SpotifyLibrarySyncEngine.syncAll(
                                        context = context,
                                        forceRefresh = true
                                    )
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (spotifySyncState.isSyncing) "Syncing..." else "Sync Spotify",
                            color = Color(0xFF34D399),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 3. Clean Spotify Hub Sub-Tabs (Songs | Liked | Playlists)
        if (selectedTabKey == spotifyPkg) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlayerFilterPill(
                    label = "Songs (${spotifyTracks.size})",
                    accent = Color(0xFF1DB954),
                    selected = spotifySubTab == "SONGS",
                    onClick = { spotifySubTab = "SONGS" }
                )
                PlayerFilterPill(
                    label = "Liked (${spotifyLikedTracks.size})",
                    accent = Color(0xFFFB7185),
                    selected = spotifySubTab == "LIKED",
                    onClick = { spotifySubTab = "LIKED" }
                )
                PlayerFilterPill(
                    label = "Playlists (${spotifyPlaylists.size})",
                    accent = Color(0xFF1DB954),
                    selected = spotifySubTab == "PLAYLISTS",
                    onClick = { spotifySubTab = "PLAYLISTS" }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. Scoped Content Body
        when {
            selectedTabKey == spotifyPkg && spotifySubTab == "PLAYLISTS" -> {
                if (spotifyPlaylists.isEmpty()) {
                    LibraryEmptyCard(
                        title = "No Spotify Playlists Synced Yet",
                        body = if (spotifyStatus.isConnected) {
                            "Tap Sync Spotify above to pull your Spotify playlists and all their songs directly into this Spotify tab."
                        } else {
                            "Connect Spotify in Settings to sync your Spotify playlists and songs here."
                        },
                        ctaLabel = if (spotifyStatus.isConnected && !spotifySyncState.isSyncing) "Sync Spotify Now" else null,
                        onCtaClick = {
                            scope.launch {
                                SpotifyLibrarySyncEngine.syncAll(context = context, forceRefresh = true)
                            }
                        }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 110.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(spotifyPlaylists, key = { it.playlist.playlistId }) { pl ->
                            val previewArtists = pl.tracks
                                .map { it.artist }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .take(2)
                                .joinToString(" · ")
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF101726))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                                    .clickable {
                                        onOpenPlaylistByName?.invoke(pl.playlist.name)
                                            ?: onOpenPlaylistsManager()
                                    }
                                    .padding(14.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(RoundedCornerShape(11.dp))
                                                .background(Color(0xFF1DB954).copy(alpha = 0.16f))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.QueueMusic,
                                                contentDescription = null,
                                                tint = Color(0xFF1DB954),
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = pl.playlist.name,
                                                color = Color.White,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = when {
                                                    pl.tracks.isNotEmpty() && previewArtists.isNotBlank() ->
                                                        "${pl.tracks.size} song${if (pl.tracks.size == 1) "" else "s"} · $previewArtists"
                                                    pl.tracks.isNotEmpty() ->
                                                        "${pl.tracks.size} song${if (pl.tracks.size == 1) "" else "s"} · Tap to view all"
                                                    else ->
                                                        "Spotify Playlist · Tap to open & play"
                                                },
                                                color = Color.White.copy(alpha = 0.64f),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        pl.tracks.firstOrNull()?.let { firstTrack ->
                                            IconButton(
                                                onClick = {
                                                    onPlayTrack(firstTrack.title, firstTrack.artist, spotifyPkg)
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.PlayArrow,
                                                    contentDescription = "Play Spotify playlist",
                                                    tint = Color(0xFF1DB954)
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = Icons.Rounded.ChevronRight,
                                            contentDescription = "Open playlist",
                                            tint = Color.White.copy(alpha = 0.55f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            selectedTabKey == "TUNEMIND_PLAYLISTS" -> {
                if (customTuneMindPlaylists.isEmpty()) {
                    LibraryEmptyCard(
                        title = "No TuneMind Playlists Yet",
                        body = "Create universal playlists in TuneMind and add songs manually or by voice from Spotify, YouTube Music, Boomplay, Audiomack, or phone audio.",
                        ctaLabel = "Create TuneMind Playlist",
                        onCtaClick = onOpenPlaylistsManager
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 110.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(customTuneMindPlaylists, key = { it.playlist.playlistId }) { pl ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF101726))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                                    .clickable {
                                        onOpenPlaylistByName?.invoke(pl.playlist.name)
                                            ?: onOpenPlaylistsManager()
                                    }
                                    .padding(14.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            imageVector = Icons.Rounded.QueueMusic,
                                            contentDescription = null,
                                            tint = Color(0xFF38BDF8),
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = pl.playlist.name,
                                                color = Color.White,
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${pl.tracks.size} song${if (pl.tracks.size == 1) "" else "s"} · ${pl.playlist.moodOrAudience.ifBlank { "Universal Cross-App" }}",
                                                color = Color.White.copy(alpha = 0.64f),
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                    pl.tracks.firstOrNull()?.let { firstTrack ->
                                        IconButton(
                                            onClick = {
                                                onPlayTrack(
                                                    firstTrack.title,
                                                    firstTrack.artist,
                                                    firstTrack.sourcePlayer.takeIf { it != "unknown_player" }
                                                )
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayArrow,
                                                contentDescription = "Play playlist",
                                                tint = Color(0xFF38BDF8)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            visibleTracks.isEmpty() -> {
                LibraryEmptyCard(
                    title = when {
                        selectedTabKey == "LIKED" -> "No Liked Songs Yet"
                        selectedTabKey == spotifyPkg -> "No Spotify Songs Yet"
                        else -> "Listening Across Your Music Apps"
                    },
                    body = when {
                        selectedTabKey == "LIKED" -> "Tap the heart icon on any song you listen to and it stays saved permanently in your TuneMind vault."
                        selectedTabKey == spotifyPkg -> "Tap Sync Spotify above to import your Spotify Liked Songs and Playlist tracks, or play any song on Spotify."
                        else -> "Play a song in YouTube Music, Boomplay, Spotify, Audiomack, or your phone's player — TuneMind creates a permanent player tab here automatically."
                    },
                    ctaLabel = if (selectedTabKey == spotifyPkg && spotifyStatus.isConnected && !spotifySyncState.isSyncing) "Sync Spotify Now" else null,
                    onCtaClick = {
                        scope.launch {
                            SpotifyLibrarySyncEngine.syncAll(context = context, forceRefresh = true)
                        }
                    }
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleTracks, key = { it.canonicalTrackKey }) { track ->
                        val resolvedVisual = rememberResolvedTrackMedia(
                            title = track.title,
                            artist = track.artist,
                            fallbackAlbumArtUrl = track.albumArtUrl
                        )
                        val isPlaying = isTrackCurrentlyPlaying(track.title, track.artist)
                        val (inVault, freshness) = resolveTrackVaultStatus(track.title, track.artist)
                        val playerPkg = if (selectedTabKey != "ALL" && selectedTabKey != "LIKED") {
                            selectedTabKey
                        } else {
                            track.primarySourcePackage
                        }
                        val playerLabel = CrossAppPlayRecorder.resolveFriendlyPlayerName(playerPkg)
                        val accent = if (isPlaying) Color(0xFF1DB954) else playerAccentColor(playerPkg)

                        // Exact verified play count resolved across permanent relational Track Cards & Play Ledger
                        val resolvedPlays = resolvePlayCount(
                            track.title,
                            track.artist,
                            track.trackId.takeIf { it.startsWith("spotify:track:") }
                        )
                        val truePlayCount = maxOf(resolvedPlays, track.playCount)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isPlaying) accent.copy(alpha = 0.14f) else Color(0xFF0E1422)
                                )
                                .border(
                                    width = if (isPlaying) 1.2.dp else 1.dp,
                                    color = if (isPlaying) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.07f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    onPlayTrack(
                                        track.title,
                                        track.artist,
                                        playerPkg.takeIf { it != "unknown_player" }
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            LayeredSongArtistBadge(
                                albumArtUrl = resolvedVisual.albumArtUrl ?: track.albumArtUrl,
                                artistImageUrl = resolvedVisual.artistImageUrl,
                                title = track.title,
                                artist = track.artist,
                                accentColor = accent,
                                size = 48.dp
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${track.artist} · ${formatTruePlayCount(truePlayCount)}",
                                    color = Color.White.copy(alpha = 0.68f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = playerLabel,
                                        color = accent,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(accent.copy(alpha = 0.14f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                    if (track.genre.isNotBlank() && track.genre != "Music") {
                                        Text(
                                            text = track.genre,
                                            color = Color.White.copy(alpha = 0.52f),
                                            fontSize = 10.5.sp,
                                            maxLines = 1
                                        )
                                    }
                                    if (inVault) {
                                        Icon(
                                            imageVector = Icons.Rounded.Lock,
                                            contentDescription = "In Resting Vault ($freshness% fresh)",
                                            tint = Color(0xFFF59E0B),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = { onToggleLike(track.canonicalTrackKey, !track.isLiked) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (track.isLiked) Color(0xFFFB7185) else Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = { onAskAgentAboutTrack(track.title, track.artist) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = "Ask TuneMind",
                                    tint = theme.primaryAccent.copy(alpha = 0.85f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerFilterPill(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.22f) else Color(0xFF101726),
        label = "player_pill_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) accent else Color.White.copy(alpha = 0.10f),
        label = "player_pill_border"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.68f),
        label = "player_pill_text"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun LibraryEmptyCard(
    title: String,
    body: String,
    ctaLabel: String?,
    onCtaClick: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0E1422))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(22.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null,
            tint = theme.primaryAccent,
            modifier = Modifier.size(34.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = body,
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 12.5.sp,
            lineHeight = 18.sp
        )
        if (!ctaLabel.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(theme.primaryAccent)
                    .clickable(onClick = onCtaClick)
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Text(
                    text = ctaLabel,
                    color = Color(0xFF06080E),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
