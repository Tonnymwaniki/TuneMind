package com.tunemind.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine
import com.tunemind.app.data.local.CrossAppPlayRecorder
import com.tunemind.app.data.local.CrossAppTrackEntity
import com.tunemind.app.data.local.TuneMindPlaylistWithTracks
import com.tunemind.app.domain.execution.PlaylistTrackInput
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.provider.SpotifyPremiumConnectManager
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.rememberResolvedTrackMedia
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

private fun formatPlaylistPlayCount(plays: Int): String = when {
    plays <= 0 -> "Not played yet"
    plays == 1 -> "1 play"
    else -> "$plays plays"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuneMindPlaylistsScreen(
    vm: TuneMindViewModel,
    selectedTargetPlayer: String,
    initialSelectedPlaylistName: String? = null,
    onBack: () -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onAskAgentForPlaylist: (prompt: String) -> Unit
) {
    val colors = LocalAdaptiveTheme.current
    val context = LocalContext.current
    val playlists by vm.tuneMindPlaylists.collectAsState()
    val libraryTracks by vm.crossAppLibraryTracks.collectAsState()
    val homeState by vm.homeState.collectAsState()
    val spotifyStatus by SpotifyPremiumConnectManager.statusFlow.collectAsState()
    val spotifySyncState by vm.spotifyLibrarySyncState.collectAsState()
    val isExportingToSpotify by vm.isExportingSpotifyPlaylist.collectAsState()
    val libraryActionFeedback by vm.libraryActionFeedback.collectAsState()
    val nowPlaying = homeState.nowPlaying

    LaunchedEffect(Unit) {
        SpotifyPremiumConnectManager.ensureLoaded(context)
        SpotifyLibrarySyncEngine.ensureLoaded(context)
    }

    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    // 0 = TuneMind Playlists (universal cross-app), 1 = Spotify Playlists (synced)
    var activeDirectoryTab by rememberSaveable { mutableStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showAddSongsSheet by remember { mutableStateOf(false) }
    var showRenameDialogForId by remember { mutableStateOf<String?>(null) }
    var newPlaylistName by remember { mutableStateOf("") }
    var newPlaylistDescription by remember { mutableStateOf("") }
    var renameDraft by remember { mutableStateOf("") }

    LaunchedEffect(initialSelectedPlaylistName, playlists) {
        if (!initialSelectedPlaylistName.isNullOrBlank() && selectedPlaylistId == null) {
            val matched = playlists.firstOrNull {
                it.playlist.name.equals(initialSelectedPlaylistName.trim(), ignoreCase = true)
            }
            if (matched != null) {
                selectedPlaylistId = matched.playlist.playlistId
                if (SpotifyLibrarySyncEngine.isSpotifySyncedPlaylistId(matched.playlist.playlistId)) {
                    activeDirectoryTab = 1
                }
            }
        }
    }

    val activePlaylist = remember(playlists, selectedPlaylistId) {
        selectedPlaylistId?.let { id -> playlists.firstOrNull { it.playlist.playlistId == id } }
    }

    val tuneMindOwnedPlaylists = remember(playlists) {
        playlists.filter { !SpotifyLibrarySyncEngine.isSpotifySyncedPlaylistId(it.playlist.playlistId) }
    }
    val spotifySyncedPlaylists = remember(playlists) {
        playlists.filter { SpotifyLibrarySyncEngine.isSpotifySyncedPlaylistId(it.playlist.playlistId) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.primaryBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val openedDirectlyToPlaylist = !initialSelectedPlaylistName.isNullOrBlank()
                    IconButton(
                        onClick = {
                            if (activePlaylist != null && !openedDirectlyToPlaylist) {
                                selectedPlaylistId = null
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFF8FAFC)
                        )
                    }
                    Column {
                        Text(
                            text = activePlaylist?.playlist?.name ?: "Playlists",
                            color = Color(0xFFF8FAFC),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (activePlaylist != null) {
                                val originLabel = if (SpotifyLibrarySyncEngine.isSpotifySyncedPlaylistId(activePlaylist.playlist.playlistId)) {
                                    "Spotify Playlist"
                                } else {
                                    "TuneMind Universal Playlist"
                                }
                                "${activePlaylist.tracks.size} song${if (activePlaylist.tracks.size == 1) "" else "s"} · $originLabel"
                            } else {
                                "TuneMind Universal Playlists & Synced Spotify Playlists"
                            },
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (activePlaylist == null) {
                        IconButton(onClick = {
                            newPlaylistName = ""
                            newPlaylistDescription = ""
                            showCreateDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "New TuneMind Playlist",
                                tint = colors.primaryAccent
                            )
                        }
                    } else if (!SpotifyLibrarySyncEngine.isSpotifySyncedPlaylistId(activePlaylist.playlist.playlistId)) {
                        IconButton(onClick = {
                            renameDraft = activePlaylist.playlist.name
                            showRenameDialogForId = activePlaylist.playlist.playlistId
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Rename Playlist",
                                tint = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            StudioHairlineDivider()

            if (!libraryActionFeedback.isNullOrBlank()) {
                Surface(
                    color = Color(0xFF10B981).copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = libraryActionFeedback.orEmpty(),
                        color = Color(0xFFA7F3D0),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            if (activePlaylist != null) {
                val isSyncedFromSpotify = SpotifyLibrarySyncEngine
                    .isSpotifySyncedPlaylistId(activePlaylist.playlist.playlistId)
                PlaylistDetailContent(
                    playlistWithTracks = activePlaylist,
                    nowPlayingTitle = nowPlaying?.title,
                    nowPlayingArtist = nowPlaying?.artist,
                    selectedTargetPlayer = selectedTargetPlayer,
                    accentColor = if (isSyncedFromSpotify) Color(0xFF1DB954) else colors.primaryAccent,
                    cardSurface = colors.cardSurface,
                    isSpotifyConnected = spotifyStatus.isConnected,
                    isSyncedFromSpotify = isSyncedFromSpotify,
                    isExportingToSpotify = isExportingToSpotify,
                    isSyncingSpotify = spotifySyncState.isSyncing,
                    resolvePlayCount = { title, artist, uri, trackId ->
                        vm.resolvePlayCount(title, artist, uri, trackId)
                    },
                    onPlayAll = {
                        vm.playTuneMindPlaylist(activePlaylist, selectedTargetPlayer, onPlayTrack)
                    },
                    onAddSongsManual = {
                        showAddSongsSheet = true
                    },
                    onExportOrResyncSpotify = {
                        if (isSyncedFromSpotify) {
                            vm.syncSpotifyLibraryNow(forceRefresh = true)
                        } else {
                            vm.exportTuneMindPlaylistToSpotify(activePlaylist.playlist.playlistId)
                        }
                    },
                    onAddCurrentTrack = if (!isSyncedFromSpotify && !nowPlaying?.title.isNullOrBlank() && nowPlaying?.title != "Standing By") {
                        { vm.addCurrentTrackToTuneMindPlaylist(activePlaylist.playlist.playlistId) }
                    } else null,
                    onAskAgentAddMore = {
                        if (isSyncedFromSpotify) {
                            onAskAgentForPlaylist("Play my Spotify playlist called ${activePlaylist.playlist.name} or play songs with the same vibe")
                        } else {
                            onAskAgentForPlaylist("Suggest 3 songs that fit my TuneMind playlist called ${activePlaylist.playlist.name}")
                        }
                    },
                    onPlayTrack = { title, artist, uri, srcPkg ->
                        val pkg = if (isSyncedFromSpotify) {
                            com.tunemind.app.provider.SpotifyMusicProvider.SPOTIFY_PACKAGE
                        } else {
                            srcPkg.takeIf { it.isNotBlank() && it != "unknown_player" }
                                ?: selectedTargetPlayer.ifBlank { "unknown_player" }
                        }
                        onPlayTrack(title, artist, uri, pkg)
                    },
                    onRemoveTrack = { entryId ->
                        vm.removeTrackFromTuneMindPlaylist(activePlaylist.playlist.playlistId, entryId)
                    }
                )
            } else {
                PlaylistDirectoryContent(
                    activeTab = activeDirectoryTab,
                    onSelectTab = { activeDirectoryTab = it },
                    tuneMindPlaylists = tuneMindOwnedPlaylists,
                    spotifyPlaylists = spotifySyncedPlaylists,
                    nowPlayingTitle = nowPlaying?.title,
                    accentColor = colors.primaryAccent,
                    cardSurface = colors.cardSurface,
                    isSpotifyConnected = spotifyStatus.isConnected,
                    isSyncingSpotify = spotifySyncState.isSyncing,
                    spotifySyncSummary = spotifySyncState.summaryLabel,
                    onSyncFromSpotify = { vm.syncSpotifyLibraryNow(forceRefresh = true) },
                    onSelectPlaylist = { selectedPlaylistId = it.playlist.playlistId },
                    onPlayPlaylist = { pl -> vm.playTuneMindPlaylist(pl, selectedTargetPlayer, onPlayTrack) },
                    onDeletePlaylist = { vm.deleteTuneMindPlaylist(it.playlist.playlistId) },
                    onCreateManual = {
                        newPlaylistName = ""
                        newPlaylistDescription = ""
                        showCreateDialog = true
                    },
                    onCreateWithVoice = {
                        onAskAgentForPlaylist("")
                    }
                )
            }
        }
    }

    // Manual Cross-App Song Picker Bottom Sheet for TuneMind Universal Playlists
    if (showAddSongsSheet && activePlaylist != null) {
        AddSongsToPlaylistSheet(
            playlist = activePlaylist,
            allLibraryTracks = libraryTracks,
            accentColor = colors.primaryAccent,
            cardSurface = colors.cardSurface,
            resolvePlayCount = { title, artist, uri, trackId ->
                maxOf(vm.resolvePlayCount(title, artist, uri, trackId), 0)
            },
            onAddTrack = { track ->
                vm.createOrAddToTuneMindPlaylistAsync(
                    name = activePlaylist.playlist.name,
                    tracks = listOf(
                        PlaylistTrackInput(
                            title = track.title,
                            artist = track.artist,
                            spotify_uri = track.trackId.takeIf { it.startsWith("spotify:track:") },
                            track_id = track.trackId,
                            source_player = track.primarySourcePackage
                        )
                    ),
                    appendIfExists = true
                )
            },
            onRemoveEntry = { entryId ->
                vm.removeTrackFromTuneMindPlaylist(activePlaylist.playlist.playlistId, entryId)
            },
            onDismiss = { showAddSongsSheet = false }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = colors.cardSurface,
            titleContentColor = Color(0xFFF8FAFC),
            textContentColor = Color.White.copy(alpha = 0.8f),
            title = { Text("New TuneMind Playlist", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Add songs manually or by voice from Spotify, YouTube Music, Boomplay, Audiomack, or phone audio.",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Playlist Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPlaylistDescription,
                        onValueChange = { newPlaylistDescription = it },
                        label = { Text("Vibe / Description (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = newPlaylistName.trim()
                        if (clean.isNotEmpty()) {
                            vm.createOrAddToTuneMindPlaylistAsync(
                                name = clean,
                                description = newPlaylistDescription.trim(),
                                appendIfExists = false
                            ) { created ->
                                if (created != null) {
                                    activeDirectoryTab = 0
                                    selectedPlaylistId = created.playlistId
                                }
                            }
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primaryAccent)
                ) {
                    Text("Create", color = Color(0xFF04070B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    if (showRenameDialogForId != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialogForId = null },
            containerColor = colors.cardSurface,
            titleContentColor = Color(0xFFF8FAFC),
            title = { Text("Rename Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = showRenameDialogForId
                        if (id != null && renameDraft.isNotBlank()) {
                            vm.renameTuneMindPlaylist(id, renameDraft.trim())
                        }
                        showRenameDialogForId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primaryAccent)
                ) {
                    Text("Save", color = Color(0xFF04070B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialogForId = null }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}

@Composable
private fun PlaylistDirectoryContent(
    activeTab: Int,
    onSelectTab: (Int) -> Unit,
    tuneMindPlaylists: List<TuneMindPlaylistWithTracks>,
    spotifyPlaylists: List<TuneMindPlaylistWithTracks>,
    nowPlayingTitle: String?,
    accentColor: Color,
    cardSurface: Color,
    isSpotifyConnected: Boolean,
    isSyncingSpotify: Boolean,
    spotifySyncSummary: String,
    onSyncFromSpotify: () -> Unit,
    onSelectPlaylist: (TuneMindPlaylistWithTracks) -> Unit,
    onPlayPlaylist: (TuneMindPlaylistWithTracks) -> Unit,
    onDeletePlaylist: (TuneMindPlaylistWithTracks) -> Unit,
    onCreateManual: () -> Unit,
    onCreateWithVoice: () -> Unit
) {
    val visiblePlaylists = if (activeTab == 1) spotifyPlaylists else tuneMindPlaylists
    val tabAccent = if (activeTab == 1) Color(0xFF1DB954) else accentColor

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Clean 2-Tab Switcher separating TuneMind Universal Playlists vs Spotify Synced Playlists
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (activeTab == 0) accentColor.copy(alpha = 0.2f) else Color(0xFF101726),
                    border = BorderStroke(
                        1.dp,
                        if (activeTab == 0) accentColor else Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectTab(0) }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
                    ) {
                        Text(
                            text = "TuneMind (${tuneMindPlaylists.size})",
                            color = if (activeTab == 0) Color.White else Color.White.copy(alpha = 0.65f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Cross-App · Manual & AI",
                            color = Color.White.copy(alpha = 0.52f),
                            fontSize = 10.5.sp
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (activeTab == 1) Color(0xFF1DB954).copy(alpha = 0.2f) else Color(0xFF101726),
                    border = BorderStroke(
                        1.dp,
                        if (activeTab == 1) Color(0xFF1DB954) else Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectTab(1) }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
                    ) {
                        Text(
                            text = "Spotify (${spotifyPlaylists.size})",
                            color = if (activeTab == 1) Color.White else Color.White.copy(alpha = 0.65f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Synced Spotify Library",
                            color = Color.White.copy(alpha = 0.52f),
                            fontSize = 10.5.sp
                        )
                    }
                }
            }
        }

        // Contextual Header Card for the selected tab
        item {
            if (activeTab == 0) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = accentColor.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Universal TuneMind Playlists",
                            color = Color(0xFFF8FAFC),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (!nowPlayingTitle.isNullOrBlank() && nowPlayingTitle != "Standing By") {
                                "Mix songs from Spotify, YouTube Music, Boomplay, Audiomack, or phone audio. Add manually with '+ Add Songs' or say \"Hi TuneMind, add $nowPlayingTitle to a playlist\"."
                            } else {
                                "Mix songs from ANY music player into one playlist — add tracks manually from your library or let TuneMind Voice Agent curate them for you."
                            },
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onCreateManual,
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = Color(0xFF05080E),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("New Playlist", color = Color(0xFF05080E), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = onCreateWithVoice,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("Create by Voice", color = Color(0xFFF8FAFC), fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1DB954).copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color(0xFF1DB954).copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Synced Spotify Playlists",
                            color = Color(0xFFF8FAFC),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isSpotifyConnected) {
                                spotifySyncSummary.ifBlank { "Your personal Spotify playlists and tracks stay cleanly organized here." }
                            } else {
                                "Connect your Spotify Free or Premium account in Settings to sync all your Spotify playlists & liked songs."
                            },
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        )
                        if (isSpotifyConnected) {
                            Button(
                                onClick = onSyncFromSpotify,
                                enabled = !isSyncingSpotify,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = null,
                                    tint = Color(0xFF04070B),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isSyncingSpotify) {
                                        spotifySyncSummary.ifBlank { "Syncing Spotify playlists..." }
                                    } else {
                                        "Sync Spotify Playlists & Songs Now"
                                    },
                                    color = Color(0xFF04070B),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (visiblePlaylists.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = if (activeTab == 1) "No Spotify playlists synced yet" else "No TuneMind playlists yet",
                        color = Color(0xFFF8FAFC),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (activeTab == 1) {
                            "Tap 'Sync Spotify Playlists & Songs Now' above to import your Spotify playlists."
                        } else {
                            "Tap 'New Playlist' to build a universal playlist from any song across all your apps."
                        },
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            items(visiblePlaylists, key = { it.playlist.playlistId }) { pl ->
                val count = pl.tracks.size
                val previewArtists = pl.tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().take(3).joinToString(" · ")
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = cardSurface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelectPlaylist(pl) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(tabAccent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = tabAccent,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = pl.playlist.name,
                                color = Color(0xFFF8FAFC),
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    count > 0 && previewArtists.isNotBlank() -> "$count song${if (count == 1) "" else "s"} · $previewArtists"
                                    count > 0 -> "$count song${if (count == 1) "" else "s"} · Tap to open"
                                    activeTab == 1 -> "Spotify Mix · Tap to open & play"
                                    else -> "0 songs · Tap to add songs"
                                },
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (count > 0 || activeTab == 1) {
                            IconButton(onClick = { onPlayPlaylist(pl) }) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Play Playlist",
                                    tint = tabAccent,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        if (activeTab == 0) {
                            IconButton(onClick = { onDeletePlaylist(pl) }) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete Playlist",
                                    tint = Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier.size(20.dp)
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
private fun PlaylistDetailContent(
    playlistWithTracks: TuneMindPlaylistWithTracks,
    nowPlayingTitle: String?,
    nowPlayingArtist: String?,
    selectedTargetPlayer: String,
    accentColor: Color,
    cardSurface: Color,
    isSpotifyConnected: Boolean,
    isSyncedFromSpotify: Boolean,
    isExportingToSpotify: Boolean,
    isSyncingSpotify: Boolean,
    resolvePlayCount: (title: String?, artist: String?, uri: String?, trackId: String?) -> Int,
    onPlayAll: () -> Unit,
    onAddSongsManual: () -> Unit,
    onExportOrResyncSpotify: () -> Unit,
    onAddCurrentTrack: (() -> Unit)?,
    onAskAgentAddMore: () -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onRemoveTrack: (entryId: String) -> Unit
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (playlistWithTracks.playlist.description.isNotBlank()) {
                    Text(
                        text = playlistWithTracks.playlist.description,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }

                // Primary Actions Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onPlayAll,
                        // Spotify playlists can ALWAYS play directly via Spotify Connect / Spotify app even if personalized
                        enabled = isSyncedFromSpotify || playlistWithTracks.tracks.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF04070B),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isSyncedFromSpotify) {
                                "Play on Spotify"
                            } else {
                                "Play All"
                            },
                            color = Color(0xFF04070B),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!isSyncedFromSpotify) {
                        Button(
                            onClick = onAddSongsManual,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LibraryAdd,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("+ Add Songs", color = Color(0xFFF8FAFC), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = onAskAgentAddMore,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("Ask Agent", color = Color(0xFFF8FAFC), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Secondary Actions Row (Voice AI + Spotify Sync/Export)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isSyncedFromSpotify) {
                        OutlinedButton(
                            onClick = onAskAgentAddMore,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("Add with AI", color = Color(0xFFF8FAFC), fontSize = 12.sp, maxLines = 1)
                        }
                    }

                    if (isSpotifyConnected) {
                        OutlinedButton(
                            onClick = onExportOrResyncSpotify,
                            enabled = if (isSyncedFromSpotify) !isSyncingSpotify else (!isExportingToSpotify && playlistWithTracks.tracks.isNotEmpty()),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF34D399)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isSyncedFromSpotify) Icons.Rounded.Sync else Icons.Rounded.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = when {
                                    isSyncedFromSpotify && isSyncingSpotify -> "Syncing..."
                                    isSyncedFromSpotify -> "Refresh Songs"
                                    isExportingToSpotify -> "Exporting..."
                                    else -> "Export to Spotify"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (onAddCurrentTrack != null && !nowPlayingTitle.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.05f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onAddCurrentTrack() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "+ Add Now Playing: $nowPlayingTitle",
                                    color = accentColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!nowPlayingArtist.isNullOrBlank()) {
                                    Text(
                                        text = nowPlayingArtist,
                                        color = Color.White.copy(alpha = 0.56f),
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Rounded.AddCircleOutline,
                                contentDescription = null,
                                tint = accentColor
                            )
                        }
                    }
                }
            }
        }

        if (playlistWithTracks.tracks.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isSyncedFromSpotify) "Ready to Play on Spotify" else "No songs in this playlist yet",
                        color = Color(0xFFF8FAFC),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isSyncedFromSpotify) {
                            "Tap 'Refresh Songs' to load public playlist tracks, or tap 'Play on Spotify' / 'Ask Agent' above — TuneMind will launch this playlist directly and automatically capture every song you play."
                        } else {
                            "Tap '+ Add Songs' above to pick songs from Spotify, YouTube Music, Boomplay, or any player — or ask TuneMind Voice Agent to add songs."
                        },
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            itemsIndexed(playlistWithTracks.tracks, key = { _, tr -> tr.entryId }) { idx, tr ->
                val visuals = rememberResolvedTrackMedia(
                    title = tr.title,
                    artist = tr.artist,
                    spotifyUri = tr.externalUri.takeIf { it.isNotBlank() }
                )
                val truePlays = resolvePlayCount(
                    tr.title,
                    tr.artist,
                    tr.externalUri.takeIf { it.isNotBlank() },
                    tr.trackId.takeIf { it.isNotBlank() }
                )
                val sourceAppLabel = ListeningPlayDeduplicator.resolveAppDisplayName(
                    tr.sourcePlayer.ifBlank { selectedTargetPlayer }
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardSurface.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            onPlayTrack(
                                tr.title,
                                tr.artist,
                                tr.externalUri.takeIf { it.isNotBlank() },
                                tr.sourcePlayer.ifBlank { selectedTargetPlayer }
                            )
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "${idx + 1}",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(20.dp)
                        )
                        if (!visuals.albumArtUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(visuals.albumArtUrl).crossfade(true).build(),
                                contentDescription = tr.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(accentColor.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = tr.title,
                                color = Color(0xFFF8FAFC),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${tr.artist.ifBlank { "Music Track" }} · ${formatPlaylistPlayCount(truePlays)}",
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = sourceAppLabel,
                                color = accentColor.copy(alpha = 0.9f),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }

                        if (!isSyncedFromSpotify) {
                            IconButton(onClick = { onRemoveTrack(tr.entryId) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Remove from playlist",
                                    tint = Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Play song",
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSongsToPlaylistSheet(
    playlist: TuneMindPlaylistWithTracks,
    allLibraryTracks: List<CrossAppTrackEntity>,
    accentColor: Color,
    cardSurface: Color,
    resolvePlayCount: (title: String?, artist: String?, uri: String?, trackId: String?) -> Int,
    onAddTrack: (CrossAppTrackEntity) -> Unit,
    onRemoveEntry: (entryId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterPkg by remember { mutableStateOf("ALL") }

    val existingEntryByKey = remember(playlist.tracks) {
        playlist.tracks.associateBy {
            ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist)
        }
    }

    val availablePlayers = remember(allLibraryTracks) {
        allLibraryTracks
            .map { it.primarySourcePackage }
            .filter { it.isNotBlank() && it != "unknown_player" }
            .distinct()
            .sortedBy { CrossAppPlayRecorder.resolveFriendlyPlayerName(it) }
    }

    val filteredTracks = remember(allLibraryTracks, searchQuery, selectedFilterPkg) {
        val q = searchQuery.trim().lowercase()
        allLibraryTracks.filter { track ->
            val matchesPlayer = when (selectedFilterPkg) {
                "ALL" -> true
                "LIKED" -> track.isLiked
                else -> track.primarySourcePackage.equals(selectedFilterPkg, ignoreCase = true) ||
                    track.sourcePackagesCsv.split(",").any { it.trim().equals(selectedFilterPkg, ignoreCase = true) }
            }
            val matchesQuery = q.isBlank() ||
                track.title.lowercase().contains(q) ||
                track.artist.lowercase().contains(q) ||
                track.album.lowercase().contains(q)
            matchesPlayer && matchesQuery
        }.distinctBy {
            ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = cardSurface,
        contentColor = Color(0xFFF8FAFC)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add Songs to ${playlist.playlist.name}",
                        color = Color(0xFFF8FAFC),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Pick from ANY player — Spotify, YouTube Music, Boomplay, Audiomack & more",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.5.sp
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Done", color = accentColor, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search songs or artists...", color = Color.White.copy(alpha = 0.45f)) },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Cross-App Player Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilterPkg == "ALL",
                    onClick = { selectedFilterPkg = "ALL" },
                    label = { Text("All (${allLibraryTracks.size})", fontSize = 11.5.sp) }
                )
                FilterChip(
                    selected = selectedFilterPkg == "LIKED",
                    onClick = { selectedFilterPkg = "LIKED" },
                    label = { Text("Liked", fontSize = 11.5.sp) }
                )
                availablePlayers.forEach { pkg ->
                    FilterChip(
                        selected = selectedFilterPkg == pkg,
                        onClick = { selectedFilterPkg = pkg },
                        label = { Text(CrossAppPlayRecorder.resolveFriendlyPlayerName(pkg), fontSize = 11.5.sp) }
                    )
                }
            }

            if (filteredTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (allLibraryTracks.isEmpty()) {
                            "Play songs in any app or sync Spotify to see your music here."
                        } else {
                            "No matching tracks found."
                        },
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTracks, key = { it.canonicalTrackKey }) { track ->
                        val dedupKey = ListeningPlayDeduplicator.canonicalTrackKey(track.title, track.artist)
                        val existingEntry = existingEntryByKey[dedupKey]
                        val isAdded = existingEntry != null
                        val truePlays = maxOf(
                            resolvePlayCount(
                                track.title,
                                track.artist,
                                track.trackId.takeIf { it.startsWith("spotify:track:") },
                                track.trackId
                            ),
                            track.playCount
                        )
                        val visuals = rememberResolvedTrackMedia(
                            title = track.title,
                            artist = track.artist,
                            fallbackAlbumArtUrl = track.albumArtUrl
                        )

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isAdded) accentColor.copy(alpha = 0.12f) else Color(0xFF0E1422),
                            border = BorderStroke(
                                1.dp,
                                if (isAdded) accentColor.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    if (isAdded && existingEntry != null) {
                                        onRemoveEntry(existingEntry.entryId)
                                    } else {
                                        onAddTrack(track)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val art = visuals.albumArtUrl ?: track.albumArtUrl
                                if (!art.isNullOrBlank()) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context).data(art).crossfade(true).build(),
                                        contentDescription = track.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(accentColor.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = accentColor,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        color = Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${track.artist} · ${formatPlaylistPlayCount(truePlays)}",
                                        color = Color.White.copy(alpha = 0.62f),
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = CrossAppPlayRecorder.resolveFriendlyPlayerName(track.primarySourcePackage),
                                        color = accentColor.copy(alpha = 0.85f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isAdded) accentColor else Color.White.copy(alpha = 0.1f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isAdded) Icons.Rounded.Check else Icons.Rounded.Add,
                                            contentDescription = null,
                                            tint = if (isAdded) Color(0xFF04070B) else Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (isAdded) "Added" else "Add",
                                            color = if (isAdded) Color(0xFF04070B) else Color.White,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold
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
