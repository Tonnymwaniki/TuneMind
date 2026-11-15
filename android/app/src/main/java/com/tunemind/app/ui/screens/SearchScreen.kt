package com.tunemind.app.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.provider.LocalAudioMatch
import com.tunemind.app.provider.TuneMindInAppPlayer
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SearchOrigin {
    HISTORY, SPOTIFY, VAULT, LOCAL, DISCOVERY
}

private data class UniversalSearchItem(
    val id: String,
    val title: String,
    val artist: String,
    val subtitle: String,
    val spotifyUri: String?,
    val sourcePkg: String,
    val origin: SearchOrigin,
    val playCount: Int = 0,
    val vaultId: String? = null,
    val localMatch: LocalAudioMatch? = null
)

@Composable
fun SearchScreen(
    vm: TuneMindViewModel,
    selectedTargetPlayer: String,
    onBack: () -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePkg: String) -> Unit,
    onAskAgentAboutTrack: (title: String, artist: String, freshness: Int, spotifyUri: String?) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val theme = LocalAdaptiveTheme.current

    val historyState by vm.historyState.collectAsState()
    val homeState by vm.homeState.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    val likedTracks by vm.spotifyLikedTracks.collectAsState()
    val playlists by vm.spotifyPlaylists.collectAsState()
    val podcasts by vm.spotifyPodcasts.collectAsState()
    val aiCrate by vm.aiDiscoveryCrate.collectAsState()

    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) } // 0=All, 1=History, 2=Spotify, 3=Vault, 4=Local
    var hasAudioPerm by remember { mutableStateOf(PermissionUtils.hasAudioStoragePermission(context)) }
    var localAudioTracks by remember { mutableStateOf<List<LocalAudioMatch>>(emptyList()) }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }

    val neonCyan = Color(0xFF00F0FF)
    val spotifyGreen = Color(0xFF1DB954)
    val vaultAmber = Color(0xFFFFB000)
    val electricPurple = Color(0xFF8A2BE2)

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPerm = granted || PermissionUtils.hasAudioStoragePermission(context)
    }

    LaunchedEffect(hasAudioPerm, query) {
        if (hasAudioPerm) {
            localAudioTracks = withContext(Dispatchers.IO) {
                TuneMindInAppPlayer.queryLocalAudioLibrary(context, query = query, limit = 80)
            }
        } else {
            localAudioTracks = emptyList()
        }
    }

    val allSearchItems = remember(
        historyState.tracks,
        homeState.recentTracks,
        likedTracks,
        playlists,
        podcasts,
        vaultTracks,
        aiCrate,
        localAudioTracks
    ) {
        val list = mutableListOf<UniversalSearchItem>()

        val recents = historyState.tracks.ifEmpty { homeState.recentTracks }
        recents.forEachIndexed { idx, entry ->
            val t = entry.title?.trim().orEmpty()
            if (t.isNotBlank()) {
                list.add(
                    UniversalSearchItem(
                        id = "hist_${entry.entryId}_$idx",
                        title = t,
                        artist = entry.artist?.trim()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist",
                        subtitle = entry.album?.takeIf { it.isNotBlank() } ?: "History",
                        spotifyUri = entry.providerTrackId?.takeIf { it.startsWith("spotify:") },
                        sourcePkg = entry.sourcePackage ?: selectedTargetPlayer,
                        origin = SearchOrigin.HISTORY,
                        playCount = entry.distinctAppearanceCount
                    )
                )
            }
        }

        likedTracks.forEach { lt ->
            list.add(
                UniversalSearchItem(
                    id = "sp_like_${lt.trackId}",
                    title = lt.trackTitle,
                    artist = lt.artistName,
                    subtitle = "Spotify Liked",
                    spotifyUri = lt.spotifyUri,
                    sourcePkg = "com.spotify.music",
                    origin = SearchOrigin.SPOTIFY,
                    playCount = vm.resolvePlayCount(lt.trackTitle, lt.artistName, lt.spotifyUri)
                )
            )
        }

        playlists.forEach { pl ->
            pl.decodeTracks().forEachIndexed { i, pt ->
                list.add(
                    UniversalSearchItem(
                        id = "sp_pl_${pl.playlistId}_${pt.trackId}_$i",
                        title = pt.title,
                        artist = pt.artist,
                        subtitle = "Playlist · ${pl.name}",
                        spotifyUri = pt.spotifyUri.ifBlank { null },
                        sourcePkg = "com.spotify.music",
                        origin = SearchOrigin.SPOTIFY,
                        playCount = vm.resolvePlayCount(pt.title, pt.artist, pt.spotifyUri)
                    )
                )
            }
        }

        podcasts.filter { !it.isShowSummary }.forEach { pod ->
            list.add(
                UniversalSearchItem(
                    id = "sp_pod_${pod.itemId}",
                    title = pod.episodeTitle,
                    artist = pod.showName,
                    subtitle = "Podcast",
                    spotifyUri = pod.uri,
                    sourcePkg = "com.spotify.music",
                    origin = SearchOrigin.SPOTIFY
                )
            )
        }

        vaultTracks.forEach { vt ->
            val pct = (vt.recoveryProgress * 100).toInt().coerceIn(0, 100)
            list.add(
                UniversalSearchItem(
                    id = "vault_${vt.id}",
                    title = vt.title,
                    artist = vt.artist,
                    subtitle = "Vault · $pct% recovered",
                    spotifyUri = null,
                    sourcePkg = selectedTargetPlayer,
                    origin = SearchOrigin.VAULT,
                    vaultId = vt.id
                )
            )
        }

        aiCrate.filter { !it.isDismissed }.forEach { ai ->
            list.add(
                UniversalSearchItem(
                    id = "ai_${ai.id}",
                    title = ai.title,
                    artist = ai.artist,
                    subtitle = "For You Pick",
                    spotifyUri = null,
                    sourcePkg = selectedTargetPlayer,
                    origin = SearchOrigin.DISCOVERY
                )
            )
        }

        localAudioTracks.forEachIndexed { i, local ->
            list.add(
                UniversalSearchItem(
                    id = "loc_${local.contentUri}_$i",
                    title = local.title,
                    artist = local.artist.ifBlank { "Device Audio" },
                    subtitle = local.album.ifBlank { "Local Audio" },
                    spotifyUri = null,
                    sourcePkg = context.packageName,
                    origin = SearchOrigin.LOCAL,
                    localMatch = local
                )
            )
        }

        list.distinctBy {
            "${it.origin.name}:${it.title.lowercase().trim()}:${it.artist.lowercase().trim()}"
        }
    }

    val filteredItems = remember(allSearchItems, query, selectedTab) {
        val cleanQuery = query.trim().lowercase()
        allSearchItems.filter { item ->
            val tabOk = when (selectedTab) {
                1 -> item.origin == SearchOrigin.HISTORY
                2 -> item.origin == SearchOrigin.SPOTIFY
                3 -> item.origin == SearchOrigin.VAULT
                4 -> item.origin == SearchOrigin.LOCAL
                else -> true
            }
            if (!tabOk) return@filter false
            if (cleanQuery.isEmpty()) return@filter true
            item.title.lowercase().contains(cleanQuery) ||
                item.artist.lowercase().contains(cleanQuery) ||
                item.subtitle.lowercase().contains(cleanQuery)
        }
    }

    fun playUniversalItem(item: UniversalSearchItem) {
        if (item.localMatch != null) {
            val played = TuneMindInAppPlayer.playLocalUri(
                context = context,
                uri = item.localMatch.contentUri,
                title = item.localMatch.title,
                artist = item.localMatch.artist,
                album = item.localMatch.album.ifBlank { null },
                expectedDurationMs = item.localMatch.durationMs
            )
            if (played) {
                vm.setAgentActiveTrack(item.localMatch.title, item.localMatch.artist, context.packageName)
                feedbackBanner = "Playing \"${item.title}\" in TuneMind"
                return
            }
        }
        onPlayTrack(item.title, item.artist, item.spotifyUri, item.sourcePkg)
        feedbackBanner = "Starting \"${item.title}\"…"
    }

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            // Top bar with Search Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFFF8FAFC)
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            text = "Search songs, artists, Spotify, Vault, local audio…",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = neonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear",
                                    tint = Color.White.copy(alpha = 0.68f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFF8FAFC),
                        unfocusedTextColor = Color(0xFFF8FAFC),
                        focusedContainerColor = Color(0xFF0D0D0D).copy(alpha = 0.78f),
                        unfocusedContainerColor = Color(0xFF0D0D0D).copy(alpha = 0.60f),
                        focusedBorderColor = neonCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                        cursorColor = neonCyan
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            MagicalSegmentedTabs(
                tabs = listOf(
                    MagicalTabItem("All", neonCyan),
                    MagicalTabItem("History", electricPurple),
                    MagicalTabItem("Spotify", spotifyGreen),
                    MagicalTabItem("Vault", vaultAmber),
                    MagicalTabItem("Local", theme.primaryAccent)
                ),
                selectedIndex = selectedTab,
                onSelectTab = { selectedTab = it }
            )

            if (!feedbackBanner.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = neonCyan.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = feedbackBanner!!,
                        color = Color(0xFFF8FAFC),
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (selectedTab == 4 && !hasAudioPerm) {
                SoftGlassCard(modifier = Modifier.fillMaxWidth(), accentTint = theme.primaryAccent) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Allow Local Audio Access",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "Grant audio library access so TuneMind can search and play songs stored directly on your device.",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.68f),
                            lineHeight = 19.sp
                        )
                        Button(
                            onClick = {
                                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    android.Manifest.permission.READ_MEDIA_AUDIO
                                } else {
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                                }
                                audioPermissionLauncher.launch(perm)
                            },
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = neonCyan,
                                contentColor = Color(0xFF0D0D0D)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text("Allow Audio Access", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            } else if (filteredItems.isEmpty()) {
                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = if (query.isBlank()) "Nothing in this category yet" else "No matches for \"$query\"",
                            color = Color(0xFFF8FAFC),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (query.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    onAskAgentAboutTrack(query.trim(), "", 90, null)
                                    onBack()
                                },
                                shape = RoundedCornerShape(28.dp),
                                border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "Ask TuneMind for \"$query\"",
                                    color = neonCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "${filteredItems.size} ${if (filteredItems.size == 1) "result" else "results"}",
                    color = Color.White.copy(alpha = 0.50f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        val accent = when (item.origin) {
                            SearchOrigin.SPOTIFY -> spotifyGreen
                            SearchOrigin.VAULT -> vaultAmber
                            SearchOrigin.LOCAL -> neonCyan
                            SearchOrigin.DISCOVERY -> electricPurple
                            SearchOrigin.HISTORY -> theme.primaryAccent
                        }
                        if (item.vaultId != null) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = vaultAmber.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, vaultAmber.copy(alpha = 0.28f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { playUniversalItem(item) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(vaultAmber.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = vaultAmber, modifier = Modifier.size(18.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            color = Color(0xFFF8FAFC),
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.artist} · ${item.subtitle}",
                                            color = Color.White.copy(alpha = 0.58f),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            vm.restoreFromVault(item.vaultId)
                                            feedbackBanner = "Restored \"${item.title}\" from Vault"
                                        }
                                    ) {
                                        Text("Restore", color = neonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        } else {
                            TuneMindTrackCard(
                                title = item.title,
                                artist = item.artist,
                                subtitle = item.subtitle,
                                playCount = item.playCount,
                                isCurrentlyPlaying = vm.isTrackCurrentlyPlaying(item.title, item.artist, item.spotifyUri),
                                accentColor = accent,
                                onPlayClick = { playUniversalItem(item) },
                                onSyncClick = {
                                    onAskAgentAboutTrack(item.title, item.artist, 85, item.spotifyUri)
                                    onBack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
