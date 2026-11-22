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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunemind.app.provider.LocalAudioMatch
import com.tunemind.app.provider.TuneMindInAppPlayer
import com.tunemind.app.domain.vault.RestingVaultEngine
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.LayeredSongArtistBadge
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.components.rememberResolvedTrackMedia
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SearchOrigin {
    HISTORY, LIBRARY, VAULT, LOCAL, CATALOG, DISCOVERY
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
    val localMatch: LocalAudioMatch? = null,
    val trackId: String = ""
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
    val libraryTracks by vm.crossAppLibraryTracks.collectAsState()
    val playlists by vm.tuneMindPlaylists.collectAsState()
    val aiCrate by vm.aiDiscoveryCrate.collectAsState()

    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) } // 0=All, 1=History, 2=Library, 3=Vault, 4=Local
    var hasAudioPerm by remember { mutableStateOf(PermissionUtils.hasAudioStoragePermission(context)) }
    var localAudioTracks by remember { mutableStateOf<List<LocalAudioMatch>>(emptyList()) }
    var onlineCatalogHits by remember { mutableStateOf<List<com.tunemind.app.provider.OpenCatalogSearchTrack>>(emptyList()) }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }
    var inspectedSearchItem by remember { mutableStateOf<UniversalSearchItem?>(null) }

    val neonCyan = theme.primaryAccent
    val libraryTeal = Color(0xFF38BDF8)
    val vaultAmber = Color(0xFFF59E0B)
    val electricPurple = theme.secondaryAccent

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAudioPerm = PermissionUtils.hasAudioStoragePermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPerm = granted || PermissionUtils.hasAudioStoragePermission(context)
        if (!hasAudioPerm &&
            PermissionUtils.isRuntimePermissionPermanentlyDeniedAfterResult(
                context = context,
                permission = PermissionUtils.audioStoragePermissionName(),
                granted = false
            )
        ) {
            PermissionUtils.openAppDetailsSettings(context)
            feedbackBanner = "Open Permissions → Music & Audio → Allow so TuneMind can scan your phone's songs."
        }
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

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length >= 2) {
            kotlinx.coroutines.delay(320L)
            val kidsOnly = com.tunemind.app.domain.voice.AgentSettingsStore.kidsModeEnabled.value
            onlineCatalogHits = withContext(Dispatchers.IO) {
                com.tunemind.app.provider.UniversalTrackResolver.searchCatalogBlocking(
                    context = context,
                    query = q,
                    limit = 14,
                    excludeExplicit = kidsOnly
                )
            }
        } else {
            onlineCatalogHits = emptyList()
        }
    }

    val allSearchItems = remember(
        historyState.tracks,
        homeState.recentTracks,
        libraryTracks,
        playlists,
        vaultTracks,
        aiCrate,
        localAudioTracks,
        onlineCatalogHits
    ) {
        val list = mutableListOf<UniversalSearchItem>()

        val recents = historyState.tracks.ifEmpty { homeState.recentTracks }
        recents.forEachIndexed { idx, entry ->
            val t = entry.title?.trim().orEmpty()
            if (t.isNotBlank()) {
                val a = entry.artist?.trim()?.ifBlank { "Unknown Artist" } ?: "Unknown Artist"
                val canonicalId = entry.canonicalTrackId()
                val truePlays = vm.resolvePlayCount(t, a, null, canonicalId)
                    .coerceAtLeast(0)
                list.add(
                    UniversalSearchItem(
                        id = "hist_${entry.entryId}_$idx",
                        title = t,
                        artist = a,
                        subtitle = entry.album?.takeIf { it.isNotBlank() } ?: "History",
                        spotifyUri = null,
                        sourcePkg = entry.sourcePackage ?: selectedTargetPlayer,
                        origin = SearchOrigin.HISTORY,
                        playCount = truePlays,
                        trackId = canonicalId
                    )
                )
            }
        }

        libraryTracks.forEach { lib ->
            val appName = com.tunemind.app.data.local.CrossAppPlayRecorder.resolveFriendlyPlayerName(lib.primarySourcePackage)
            list.add(
                UniversalSearchItem(
                    id = "lib_${lib.canonicalTrackKey}",
                    title = lib.title,
                    artist = lib.artist,
                    subtitle = if (lib.isLiked) "Liked · $appName" else "$appName Library",
                    spotifyUri = null,
                    sourcePkg = lib.primarySourcePackage.ifBlank { selectedTargetPlayer },
                    origin = SearchOrigin.LIBRARY,
                    playCount = vm.resolvePlayCount(lib.title, lib.artist, null, lib.trackId),
                    trackId = lib.trackId
                )
            )
        }

        playlists.forEach { pl ->
            pl.tracks.forEachIndexed { i, pt ->
                val canonicalId = pt.trackId.ifBlank {
                    com.tunemind.app.domain.model.TrackIdentityResolver.buildDeterministicTrackId(pt.title, pt.artist)
                }
                list.add(
                    UniversalSearchItem(
                        id = "pl_${pl.playlist.playlistId}_${pt.entryId}_$i",
                        title = pt.title,
                        artist = pt.artist,
                        subtitle = "Playlist · ${pl.playlist.name}",
                        spotifyUri = null,
                        sourcePkg = pt.sourcePlayer.ifBlank { selectedTargetPlayer },
                        origin = SearchOrigin.LIBRARY,
                        playCount = vm.resolvePlayCount(pt.title, pt.artist, null, canonicalId),
                        trackId = canonicalId
                    )
                )
            }
        }

        onlineCatalogHits.forEachIndexed { idx, hit ->
            list.add(
                UniversalSearchItem(
                    id = "cat_${hit.trackId}_$idx",
                    title = hit.title,
                    artist = hit.artist,
                    subtitle = "Open Catalog · ${hit.genre}",
                    spotifyUri = hit.ytmVideoId,
                    sourcePkg = selectedTargetPlayer,
                    origin = SearchOrigin.CATALOG,
                    playCount = vm.resolvePlayCount(hit.title, hit.artist, null, hit.trackId),
                    trackId = hit.trackId
                )
            )
        }

        val nowMs = System.currentTimeMillis()
        vaultTracks.forEach { vt ->
            val rec = RestingVaultEngine.computeRecoveryState(vt.quarantinedAtMs, vt.totalDays, vt.satietyScore, nowMs)
            val pct = (rec.recoveryProgress * 100).toInt().coerceIn(0, 100)
            val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(vt.spotifyUri)
            val canonicalId = vt.resolvedCanonicalTrackId()
            list.add(
                UniversalSearchItem(
                    id = "vault_${vt.id}",
                    title = vt.title,
                    artist = vt.artist,
                    subtitle = "Resting · ${rec.remainingLabel} · $pct% recovered",
                    spotifyUri = cleanUri,
                    sourcePkg = vt.sourcePackage.ifBlank { selectedTargetPlayer },
                    origin = SearchOrigin.VAULT,
                    playCount = vm.resolvePlayCount(vt.title, vt.artist, cleanUri, canonicalId),
                    vaultId = vt.id,
                    trackId = canonicalId
                )
            )
        }

        aiCrate.filter { !it.isDismissed }.forEach { ai ->
            val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(ai.spotifyUri)
            val canonicalId = ai.resolvedCanonicalTrackId()
            list.add(
                UniversalSearchItem(
                    id = "ai_${ai.id}",
                    title = ai.title,
                    artist = ai.artist,
                    subtitle = "For You Pick",
                    spotifyUri = cleanUri,
                    sourcePkg = selectedTargetPlayer,
                    origin = SearchOrigin.DISCOVERY,
                    playCount = vm.resolvePlayCount(ai.title, ai.artist, cleanUri, canonicalId),
                    trackId = canonicalId
                )
            )
        }

        localAudioTracks.forEachIndexed { i, local ->
            val localArtist = local.artist.ifBlank { "Device Audio" }
            val canonicalId = com.tunemind.app.domain.model.TrackIdentityResolver.buildDeterministicTrackId(
                title = local.title,
                artist = localArtist
            )
            list.add(
                UniversalSearchItem(
                    id = "loc_${local.contentUri}_$i",
                    title = local.title,
                    artist = localArtist,
                    subtitle = local.album.ifBlank { "Local Audio" },
                    spotifyUri = null,
                    sourcePkg = context.packageName,
                    origin = SearchOrigin.LOCAL,
                    playCount = vm.resolvePlayCount(local.title, localArtist, null, canonicalId),
                    localMatch = local,
                    trackId = canonicalId
                )
            )
        }

        list.distinctBy {
            "${it.origin.name}:${com.tunemind.app.domain.memory.ListeningPlayDeduplicator.canonicalTrackKey(it.title, it.artist)}"
        }
    }

    val filteredItems = remember(allSearchItems, query, selectedTab) {
        val cleanQuery = query.trim().lowercase()
        val tabMatched = allSearchItems.filter { item ->
            val tabOk = when (selectedTab) {
                1 -> item.origin == SearchOrigin.HISTORY
                2 -> item.origin == SearchOrigin.LIBRARY
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
        if (selectedTab != 0) {
            tabMatched
        } else {
            val deduped = mutableListOf<UniversalSearchItem>()
            for (item in tabMatched) {
                val dup = deduped.any { prev ->
                    com.tunemind.app.domain.memory.ListeningPlayDeduplicator.isSameTrack(
                        prev.title, prev.artist, prev.spotifyUri,
                        item.title, item.artist, item.spotifyUri
                    )
                }
                if (!dup) deduped.add(item)
            }
            deduped
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
                vm.setAgentActiveTrack(
                    title = item.localMatch.title,
                    artist = item.localMatch.artist,
                    sourcePackage = context.packageName,
                    trackId = item.trackId
                )
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
                            text = "Search songs, artists, or playlists",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 14.sp,
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
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFFAFAFA),
                        unfocusedTextColor = Color(0xFFFAFAFA),
                        focusedContainerColor = theme.cardSurface.copy(alpha = 0.92f),
                        unfocusedContainerColor = theme.cardSurface.copy(alpha = 0.72f),
                        focusedBorderColor = neonCyan.copy(alpha = 0.65f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.10f),
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
                    MagicalTabItem("Library", libraryTeal),
                    MagicalTabItem("Vault", vaultAmber),
                    MagicalTabItem("Local", theme.primaryAccent)
                ),
                selectedIndex = selectedTab,
                onSelectTab = { selectedTab = it },
                height = 42.dp
            )

            if (!feedbackBanner.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = neonCyan.copy(alpha = 0.14f),
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
                            color = Color(0xFFD6D1E6),
                            lineHeight = 19.sp
                        )
                        Button(
                            onClick = {
                                runCatching {
                                    audioPermissionLauncher.launch(PermissionUtils.audioStoragePermissionName())
                                }.onFailure {
                                    PermissionUtils.openAppDetailsSettings(context)
                                }
                            },
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = neonCyan,
                                contentColor = Color(0xFF0D0D0D)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
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
                    color = Color(0xFFB8B2CC),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(filteredItems, key = { idx, item -> "search_${item.id}_$idx" }) { _, item ->
                        val accent = when (item.origin) {
                            SearchOrigin.LIBRARY -> libraryTeal
                            SearchOrigin.CATALOG -> Color(0xFF10B981)
                            SearchOrigin.VAULT -> vaultAmber
                            SearchOrigin.LOCAL -> neonCyan
                            SearchOrigin.DISCOVERY -> electricPurple
                            SearchOrigin.HISTORY -> theme.primaryAccent
                        }
                        val accuratePlayCount = vm.resolvePlayCount(
                            item.title,
                            item.artist,
                            item.spotifyUri,
                            item.trackId
                        )
                        if (item.vaultId != null) {
                            val vStatus = vm.resolveTrackVaultStatus(item.title, item.artist, item.spotifyUri, item.trackId)
                            TuneMindTrackCard(
                                title = item.title,
                                artist = item.artist,
                                playCount = accuratePlayCount,
                                showUnplayedLabel = true,
                                isCurrentlyPlaying = vm.isTrackCurrentlyPlaying(item.title, item.artist, item.spotifyUri),
                                freshnessPercent = vStatus.freshnessPercent,
                                isCoolingDown = true,
                                cooldownRemainingLabel = vStatus.cooldownRemainingLabel ?: item.subtitle,
                                recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                                accentColor = vaultAmber,
                                spotifyUri = item.spotifyUri,
                                trackId = item.trackId,
                                trailingActions = {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(neonCyan.copy(alpha = 0.12f))
                                            .clickable {
                                                vm.restoreFromVault(item.vaultId)
                                                feedbackBanner = "Restored \"${item.title}\" from Vault"
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Restore", color = neonCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    }
                                },
                                onPlayClick = { playUniversalItem(item) },
                                onSyncClick = { inspectedSearchItem = item }
                            )
                        } else {
                            val vStatus = vm.resolveTrackVaultStatus(item.title, item.artist, item.spotifyUri, item.trackId)
                            TuneMindTrackCard(
                                title = item.title,
                                artist = item.artist,
                                subtitle = item.subtitle,
                                playCount = accuratePlayCount,
                                showUnplayedLabel = true,
                                isCurrentlyPlaying = vm.isTrackCurrentlyPlaying(item.title, item.artist, item.spotifyUri),
                                freshnessPercent = vStatus.freshnessPercent,
                                isCoolingDown = vStatus.isCoolingDown,
                                cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                                recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                                accentColor = accent,
                                spotifyUri = item.spotifyUri,
                                trackId = item.trackId,
                                onPlayClick = { playUniversalItem(item) },
                                onSyncClick = { inspectedSearchItem = item }
                            )
                        }
                    }
                }
            }
        }

        inspectedSearchItem?.let { sel ->
            val vStatus = vm.resolveTrackVaultStatus(sel.title, sel.artist, sel.spotifyUri, sel.trackId)
            NeuralTrackActionDialog(
                title = sel.title,
                artist = sel.artist,
                freshnessPercent = vStatus.freshnessPercent,
                playCount = sel.playCount,
                isCoolingDown = vStatus.isCoolingDown,
                isInFresh = vStatus.isInFresh && !vStatus.isCoolingDown,
                spotifyUri = sel.spotifyUri,
                trackId = sel.trackId,
                cooldownRemainingLabel = vStatus.cooldownRemainingLabel,
                recoveryPercent = vStatus.recoveryPercent.takeIf { vStatus.isInVault },
                vaultReason = vStatus.entryReason,
                onDismiss = { inspectedSearchItem = null },
                onPlayNow = { playUniversalItem(sel) },
                onAgentNeuralBreakdown = {
                    onAskAgentAboutTrack(sel.title, sel.artist, vStatus.freshnessPercent, sel.spotifyUri)
                    onBack()
                },
                onSyncAtmosphereToTrack = { vm.syncAtmosphereToTrack(sel.title, sel.artist) },
                onToggleCooldownVault = {
                    vm.toggleTrackVaultStatus(sel.title, sel.artist, sel.sourcePkg, sel.spotifyUri, sel.trackId)
                    feedbackBanner = if (vStatus.isInVault) {
                        "Restored \"${sel.title}\" back to Fresh"
                    } else {
                        "Moved \"${sel.title}\" to Resting Vault"
                    }
                },
                onToggleFreshList = {
                    vm.toggleTrackFreshStatus(sel.title, sel.artist, sel.sourcePkg, sel.spotifyUri, sel.trackId)
                    feedbackBanner = if (vStatus.isInFresh && !vStatus.isCoolingDown) {
                        "Removed \"${sel.title}\" from Fresh"
                    } else {
                        "Added \"${sel.title}\" to Fresh"
                    }
                }
            )
        }
    }
}
