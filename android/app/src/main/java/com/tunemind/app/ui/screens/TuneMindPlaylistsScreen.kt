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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.tunemind.app.data.local.TuneMindPlaylistWithTracks
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.rememberResolvedTrackMedia
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

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
    val homeState by vm.homeState.collectAsState()
    val spotifyStatus by com.tunemind.app.provider.SpotifyPremiumConnectManager.statusFlow.collectAsState()
    val spotifySyncState by vm.spotifyLibrarySyncState.collectAsState()
    val isExportingToSpotify by vm.isExportingSpotifyPlaylist.collectAsState()
    val libraryActionFeedback by vm.libraryActionFeedback.collectAsState()
    val nowPlaying = homeState.nowPlaying

    LaunchedEffect(Unit) {
        com.tunemind.app.provider.SpotifyPremiumConnectManager.ensureLoaded(context)
        com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine.ensureLoaded(context)
    }

    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
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
            }
        }
    }

    val activePlaylist = remember(playlists, selectedPlaylistId) {
        selectedPlaylistId?.let { id -> playlists.firstOrNull { it.playlist.playlistId == id } }
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
                    IconButton(
                        onClick = {
                            if (activePlaylist != null) {
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
                            text = activePlaylist?.playlist?.name ?: "TuneMind Playlists",
                            color = Color(0xFFF8FAFC),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (activePlaylist != null) {
                                "${activePlaylist.tracks.size} song${if (activePlaylist.tracks.size == 1) "" else "s"} · Plays via ${ListeningPlayDeduplicator.resolveAppDisplayName(selectedTargetPlayer)}"
                            } else {
                                "Stored inside TuneMind · Plays on your chosen player"
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
                                contentDescription = "New Playlist",
                                tint = colors.primaryAccent
                            )
                        }
                    } else {
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
                val isSyncedFromSpotify = com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine
                    .isSpotifySyncedPlaylistId(activePlaylist.playlist.playlistId)
                PlaylistDetailContent(
                    playlistWithTracks = activePlaylist,
                    nowPlayingTitle = nowPlaying?.title,
                    nowPlayingArtist = nowPlaying?.artist,
                    selectedTargetPlayer = selectedTargetPlayer,
                    accentColor = colors.primaryAccent,
                    cardSurface = colors.cardSurface,
                    isSpotifyConnected = spotifyStatus.isConnected,
                    isSyncedFromSpotify = isSyncedFromSpotify,
                    isExportingToSpotify = isExportingToSpotify,
                    isSyncingSpotify = spotifySyncState.isSyncing,
                    onPlayAll = {
                        vm.playTuneMindPlaylist(activePlaylist, selectedTargetPlayer, onPlayTrack)
                    },
                    onExportOrResyncSpotify = {
                        if (isSyncedFromSpotify) {
                            vm.syncSpotifyLibraryNow(forceRefresh = true)
                        } else {
                            vm.exportTuneMindPlaylistToSpotify(activePlaylist.playlist.playlistId)
                        }
                    },
                    onAddCurrentTrack = if (!nowPlaying?.title.isNullOrBlank() && nowPlaying?.title != "Standing By") {
                        { vm.addCurrentTrackToTuneMindPlaylist(activePlaylist.playlist.playlistId) }
                    } else null,
                    onAskAgentAddMore = {
                        onAskAgentForPlaylist("Suggest 3 songs that fit my TuneMind playlist called ${activePlaylist.playlist.name}")
                    },
                    onPlayTrack = { title, artist, uri, srcPkg ->
                        val pkg = selectedTargetPlayer.ifBlank { srcPkg.ifBlank { "unknown_player" } }
                        onPlayTrack(title, artist, uri, pkg)
                    },
                    onRemoveTrack = { entryId ->
                        vm.removeTrackFromTuneMindPlaylist(activePlaylist.playlist.playlistId, entryId)
                    }
                )
            } else {
                PlaylistDirectoryContent(
                    playlists = playlists,
                    nowPlayingTitle = nowPlaying?.title,
                    accentColor = colors.primaryAccent,
                    cardSurface = colors.cardSurface,
                    selectedTargetPlayer = selectedTargetPlayer,
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

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = colors.cardSurface,
            titleContentColor = Color(0xFFF8FAFC),
            textContentColor = Color.White.copy(alpha = 0.8f),
            title = { Text("New TuneMind Playlist", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    playlists: List<TuneMindPlaylistWithTracks>,
    nowPlayingTitle: String?,
    accentColor: Color,
    cardSurface: Color,
    selectedTargetPlayer: String,
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
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
                        text = "Built by Voice, Synced with Spotify",
                        color = Color(0xFFF8FAFC),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (!nowPlayingTitle.isNullOrBlank() && nowPlayingTitle != "Standing By") {
                            "While \"$nowPlayingTitle\" is playing, say \"Hi TuneMind, add this song to a new playlist\" — or sync all your Spotify playlists & liked songs right here."
                        } else {
                            "Say \"Hi TuneMind, sync my Spotify playlists\" or \"create a late night playlist\". Saved inside TuneMind and exportable back to Spotify anytime."
                        },
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onCreateWithVoice,
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = null,
                                tint = Color(0xFF05080E),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Create by Voice", color = Color(0xFF05080E), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = onCreateManual,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                        ) {
                            Text("+ New Playlist", color = Color(0xFFF8FAFC), fontSize = 13.sp)
                        }
                    }

                    if (isSpotifyConnected) {
                        OutlinedButton(
                            onClick = onSyncFromSpotify,
                            enabled = !isSyncingSpotify,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF34D399)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isSyncingSpotify) {
                                    spotifySyncSummary.ifBlank { "Syncing from Spotify..." }
                                } else {
                                    "Sync Spotify Playlists & Liked Songs"
                                },
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (playlists.isEmpty()) {
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
                        text = "No TuneMind playlists yet",
                        color = Color(0xFFF8FAFC),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Your voice-curated playlists and songs will appear right here.",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            items(playlists, key = { it.playlist.playlistId }) { pl ->
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
                                .background(accentColor.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = pl.playlist.name,
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (pl.playlist.moodOrAudience.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = accentColor.copy(alpha = 0.14f)
                                    ) {
                                        Text(
                                            text = pl.playlist.moodOrAudience,
                                            color = accentColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "$count song${if (count == 1) "" else "s"}" +
                                    if (previewArtists.isNotBlank()) " · $previewArtists" else " · Tap to inspect",
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (count > 0) {
                            IconButton(onClick = { onPlayPlaylist(pl) }) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Play Playlist",
                                    tint = accentColor,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

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
    onPlayAll: () -> Unit,
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
                        fontSize = 13.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onPlayAll,
                        enabled = playlistWithTracks.tracks.isNotEmpty(),
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
                            text = "Play on ${ListeningPlayDeduplicator.resolveAppDisplayName(selectedTargetPlayer)}",
                            color = Color(0xFF04070B),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onAskAgentAddMore,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("Add with AI", color = Color(0xFFF8FAFC), fontSize = 12.5.sp)
                    }
                }

                if (isSpotifyConnected) {
                    OutlinedButton(
                        onClick = onExportOrResyncSpotify,
                        enabled = if (isSyncedFromSpotify) !isSyncingSpotify else (!isExportingToSpotify && playlistWithTracks.tracks.isNotEmpty()),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF34D399)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (isSyncedFromSpotify) Icons.Rounded.Sync else Icons.Rounded.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                isSyncedFromSpotify && isSyncingSpotify -> "Refreshing from Spotify..."
                                isSyncedFromSpotify -> "Refresh Playlist from Spotify"
                                isExportingToSpotify -> "Exporting to Spotify..."
                                else -> "Export Playlist to Spotify"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
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
                                    text = "+ Add Current Song: $nowPlayingTitle",
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
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "No songs in this playlist yet",
                        color = Color(0xFFF8FAFC),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Play any song and say \"Hi TuneMind, add this song to ${playlistWithTracks.playlist.name}\".",
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 12.5.sp
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
                                text = tr.artist.ifBlank { "Music Track" },
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = { onRemoveTrack(tr.entryId) }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Remove from playlist",
                                tint = Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
