package com.tunemind.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.data.local.CrossAppPlayRecorder
import com.tunemind.app.data.local.CrossAppTrackEntity
import com.tunemind.app.data.local.TuneMindPlaylistWithTracks
import com.tunemind.app.domain.ai.ForYouLearningSummary
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.recommendation.AiRecommendationEntity
import com.tunemind.app.domain.vault.RestingVaultEngine
import com.tunemind.app.ui.GraduatedVaultSummary
import com.tunemind.app.ui.ManualFreshTrack
import com.tunemind.app.ui.TrackVaultUiStatus
import com.tunemind.app.ui.VaultTrack
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DopamineSongItem(
    val id: String,
    val title: String,
    val artist: String,
    val freshnessPercent: Int,
    val playCount: Int,
    val spotifyUri: String?,
    val sourcePackage: String,
    val isCoolingDown: Boolean,
    val isFromVault: Boolean = false,
    val trackId: String = "",
    val vaultId: String? = null,
    val cooldownRemainingLabel: String? = null,
    val recoveryPercent: Int? = null,
    val entryReason: String? = null,
    val albumArtUrl: String? = null,
    val freshCategory: RestingVaultEngine.FreshCategory = RestingVaultEngine.FreshCategory.PEAK_FRESH,
    val freshReason: String? = null,
    val readinessScore: Int = freshnessPercent,
    val isManualFresh: Boolean = false
)

@Composable
fun DopamineVaultScreen(
    recentTracks: List<TrackMemoryEntry>,
    vaultTracks: List<VaultTrack>,
    nowPlayingTitle: String?,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onAskAgentAboutTrack: (title: String, artist: String, freshnessPercent: Int, spotifyUri: String?) -> Unit,
    onSyncAtmosphereToTrack: (title: String, artist: String) -> Unit,
    onQuarantineTrack: (title: String, artist: String) -> Unit,
    onRestoreVaultTrack: (id: String) -> Unit,
    onCycleAtmospherePalette: () -> Unit,
    forYouPicks: List<AiRecommendationEntity> = emptyList(),
    learningState: ForYouLearningSummary = ForYouLearningSummary(),
    isDiscovering: Boolean = false,
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int = { _, _, _ -> 0 },
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean = { t, _, _ ->
        !nowPlayingTitle.isNullOrBlank() && !t.isNullOrBlank() && t.equals(nowPlayingTitle.trim(), ignoreCase = true)
    },
    onRefreshForYou: () -> Unit = {},
    onSaveForYou: (AiRecommendationEntity, Boolean) -> Unit = { _, _ -> },
    onDismissForYou: (AiRecommendationEntity) -> Unit = {},
    onPlayForYouTrack: (AiRecommendationEntity) -> Unit = { rec ->
        onPlayTrack(rec.title, rec.artist, rec.spotifyUri, "unknown_player")
    },
    onPreviewForYouTrack: (AiRecommendationEntity) -> Unit = { rec ->
        onPlayTrack(rec.title, rec.artist, rec.spotifyUri, "tunemind_inapp")
    },
    onAddToQueue: (title: String, artist: String, spotifyUri: String?) -> Unit = { _, _, _ -> },
    onSaveToLiked: (title: String, artist: String, spotifyUri: String?) -> Unit = { _, _, _ -> },
    onExportForYouPlaylist: (List<AiRecommendationEntity>) -> Unit = {},
    requestedSubTab: Int? = null,
    onConsumeRequestedSubTab: () -> Unit = {},
    graduatedTracks: List<GraduatedVaultSummary> = emptyList(),
    onTickVaultLifecycle: () -> Unit = {},
    resolveTrackVaultStatus: (title: String?, artist: String?, uri: String?, trackId: String?) -> TrackVaultUiStatus = { _, _, _, _ -> TrackVaultUiStatus() },
    crossAppLibraryTracks: List<CrossAppTrackEntity> = emptyList(),
    tuneMindPlaylists: List<TuneMindPlaylistWithTracks> = emptyList(),
    savedRecommendations: List<AiRecommendationEntity> = emptyList(),
    restoredAtMap: Map<String, Long> = emptyMap(),
    manualFreshTracks: List<ManualFreshTrack> = emptyList(),
    freshExcludedKeys: Set<String> = emptySet(),
    onAddToFresh: (title: String, artist: String, sourcePackage: String?, spotifyUri: String?, trackId: String?, albumArtUrl: String?) -> Unit = { _, _, _, _, _, _ -> },
    onRemoveFromFresh: (title: String, artist: String, spotifyUri: String?, trackId: String?) -> Unit = { _, _, _, _ -> },
    hasProAccess: Boolean = true,
    onOpenProUpgrade: ((String?) -> Unit)? = null
) {
    val theme = LocalAdaptiveTheme.current
    val repetitionSensitivity by com.tunemind.app.domain.voice.AgentSettingsStore.repetitionSensitivity.collectAsState()
    var selectedTab by remember { mutableStateOf(0) } // 0 = Fresh, 1 = Resting, 2 = For You
    var freshFilter by remember { mutableStateOf(0) } // 0 = All, 1 = Comebacks, 2 = Library Gems, 3 = Played
    var activeSyncSong by remember { mutableStateOf<DopamineSongItem?>(null) }
    var showManualPickerSheet by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTab) {
        onTickVaultLifecycle()
    }

    LaunchedEffect(requestedSubTab) {
        if (requestedSubTab != null && requestedSubTab in 0..2) {
            selectedTab = requestedSubTab
            onConsumeRequestedSubTab()
        }
    }

    val allSongs by produceState<List<DopamineSongItem>>(
        initialValue = emptyList(),
        recentTracks,
        vaultTracks,
        graduatedTracks,
        crossAppLibraryTracks,
        tuneMindPlaylists,
        savedRecommendations,
        restoredAtMap,
        manualFreshTracks,
        freshExcludedKeys,
        repetitionSensitivity
    ) {
        value = withContext(Dispatchers.Default) {
        val list = mutableListOf<DopamineSongItem>()
        val seenKeys = mutableSetOf<String>()
        val seenTitlesWithKnownArtist = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        val nowMs = System.currentTimeMillis()
        val sevenDaysMs = 7L * RestingVaultEngine.DAY_MS

        fun claimTrackSlot(title: String, artist: String): Boolean {
            val cKey = com.tunemind.app.domain.memory.ListeningPlayDeduplicator.canonicalTrackKey(title, artist)
            if (cKey.isBlank() || cKey == "|") return false
            val normTitle = cKey.substringBefore('|', "")
            val normArtist = cKey.substringAfter('|', "")
            if (normArtist.isBlank()) {
                if (normTitle in seenTitlesWithKnownArtist) return false
            } else {
                if ("$normTitle|" in seenKeys) {
                    // Already added an Unknown Artist stub for this title; avoid duplicate row
                    return false
                }
                seenTitlesWithKnownArtist.add(normTitle)
            }
            return seenKeys.add(cKey)
        }

        fun isFreshExcluded(title: String, artist: String, canonicalId: String?): Boolean {
            if (freshExcludedKeys.isEmpty()) return false
            val nKey = RestingVaultEngine.normalizeTrackKey(title, artist)
            return freshExcludedKeys.contains(nKey) ||
                (!canonicalId.isNullOrBlank() && freshExcludedKeys.contains(canonicalId))
        }

        // 0. User Manual Fresh Additions (pinned at peak freshness by user)
        for (mf in manualFreshTracks) {
            val title = mf.title.trim().takeIf { it.isNotEmpty() } ?: continue
            val artist = mf.artist.trim().ifBlank { "Music" }
            if (!claimTrackSlot(title, artist)) continue
            val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(mf.spotifyUri)
            val canonicalId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalTrackId(
                trackId = mf.trackId,
                spotifyUri = cleanUri,
                title = title,
                artist = artist
            )
            val vaultStatus = resolveTrackVaultStatus(title, artist, cleanUri, canonicalId)
            if (vaultStatus.isInVault || isFreshExcluded(title, artist, canonicalId)) continue
            val plays = resolvePlayCount(title, artist, cleanUri).coerceAtLeast(0)
            val safeId = "mf_${canonicalId}".let { if (seenIds.add(it)) it else "mf_${list.size}" }
            list.add(
                DopamineSongItem(
                    id = safeId,
                    title = title,
                    artist = artist,
                    freshnessPercent = 99,
                    playCount = plays,
                    spotifyUri = cleanUri,
                    sourcePackage = mf.sourcePackage ?: "unknown_player",
                    isCoolingDown = false,
                    isFromVault = false,
                    trackId = canonicalId,
                    albumArtUrl = mf.albumArtUrl,
                    freshCategory = RestingVaultEngine.FreshCategory.UNPLAYED_GEM,
                    freshReason = "Pinned to Fresh · Ready now",
                    readinessScore = 99,
                    isManualFresh = true
                )
            )
        }

        // 1. Active Resting Vault entries (live ease-out recovery curve & remaining cooldown)
        for (v in vaultTracks) {
            if (!claimTrackSlot(v.title, v.artist)) continue
            val safeId = v.id.takeIf { it.isNotBlank() && seenIds.add(it) } ?: "vault_${list.size}"
            seenIds.add(safeId)
            val rec = RestingVaultEngine.computeRecoveryState(
                quarantinedAtMs = v.quarantinedAtMs,
                totalDays = v.totalDays,
                baseSatietyScore = v.satietyScore,
                nowMs = nowMs
            )
            if (rec.isGraduated) continue
            val canonicalId = v.resolvedCanonicalTrackId()
            val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(v.spotifyUri)
            val resolvedPlays = resolvePlayCount(v.title, v.artist, cleanUri).coerceAtLeast(0)
            list.add(
                DopamineSongItem(
                    id = safeId,
                    title = v.title,
                    artist = v.artist,
                    freshnessPercent = rec.freshnessPercent,
                    playCount = resolvedPlays,
                    spotifyUri = cleanUri,
                    sourcePackage = v.sourcePackage.ifBlank { "unknown_player" },
                    isCoolingDown = true,
                    isFromVault = true,
                    trackId = canonicalId,
                    vaultId = v.id,
                    cooldownRemainingLabel = rec.remainingLabel,
                    recoveryPercent = (rec.recoveryProgress * 100f).toInt().coerceIn(0, 100),
                    entryReason = v.entryReason,
                    readinessScore = rec.freshnessPercent
                )
            )
        }

        // 2. Graduated Vault Comebacks (completed full rest cleanly & ready to replay)
        for (grad in graduatedTracks) {
            if (isFreshExcluded(grad.title, grad.artist, grad.trackId)) continue
            if (!claimTrackSlot(grad.title, grad.artist)) continue
            val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(grad.spotifyUri)
            val resolvedPlays = resolvePlayCount(grad.title, grad.artist, cleanUri).coerceAtLeast(0)
            val readiness = RestingVaultEngine.computeFreshReadiness(
                baseFreshnessPercent = 96,
                playCount = resolvedPlays,
                isGraduatedComeback = true,
                restedDays = grad.totalRestedDays,
                nowMs = nowMs
            )
            val safeId = "comeback_${grad.id}".let { if (seenIds.add(it)) it else "comeback_${list.size}" }
            list.add(
                DopamineSongItem(
                    id = safeId,
                    title = grad.title,
                    artist = grad.artist,
                    freshnessPercent = readiness.readinessScore,
                    playCount = resolvedPlays,
                    spotifyUri = cleanUri,
                    sourcePackage = "unknown_player",
                    isCoolingDown = false,
                    isFromVault = false,
                    trackId = grad.trackId,
                    freshCategory = readiness.category,
                    freshReason = readiness.freshReason,
                    readinessScore = readiness.readinessScore
                )
            )
        }

        // 3. Recent Played Rotation (evaluated by temporal satiety & dormancy spacing)
        for (t in recentTracks) {
            val title = t.title?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val artist = t.artist?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown Artist"
            if (!claimTrackSlot(title, artist)) continue
            val key = com.tunemind.app.domain.memory.ListeningPlayDeduplicator.canonicalTrackKey(title, artist)

            val safeId = t.entryId.takeIf { it.isNotBlank() && seenIds.add(it) }
                ?: "track_${key.hashCode()}_${list.size}".also { seenIds.add(it) }
            val uri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(t.providerTrackId)
            val canonicalId = t.canonicalTrackId()
            val plays = resolvePlayCount(title, artist, uri).takeIf { it > 0 }
                ?: t.distinctAppearanceCount.coerceAtLeast(0)
            val vaultStatus = resolveTrackVaultStatus(title, artist, uri, canonicalId)
            val cooling = vaultStatus.isInVault || vaultStatus.isCoolingDown
            if (!cooling && isFreshExcluded(title, artist, canonicalId)) continue
            val lastPlayedMs = t.lastObservedAppearanceAtMs ?: 0L
            val restoredAt = maxOf(
                restoredAtMap[canonicalId] ?: 0L,
                restoredAtMap[RestingVaultEngine.normalizeTrackKey(title, artist)] ?: 0L
            )
            val recentComeback = restoredAt > 0L && (nowMs - restoredAt) <= sevenDaysMs
            val fallbackFresh = if (plays <= 0) 99 else RestingVaultEngine.evaluateTrackFatigue(
                playTimestampsMs = listOfNotNull(lastPlayedMs.takeIf { it > 0L }),
                sensitivity = repetitionSensitivity,
                lastRestoredAtMs = restoredAt,
                nowMs = nowMs
            ).freshnessPercent
            val baseFresh = if (vaultStatus.satietyScore > 0 || vaultStatus.isInVault) {
                vaultStatus.freshnessPercent
            } else {
                fallbackFresh
            }
            val readiness = RestingVaultEngine.computeFreshReadiness(
                baseFreshnessPercent = baseFresh,
                playCount = plays,
                lastPlayedAtMs = lastPlayedMs,
                isGraduatedComeback = !cooling && recentComeback,
                nowMs = nowMs
            )

            list.add(
                DopamineSongItem(
                    id = safeId,
                    title = title,
                    artist = artist,
                    freshnessPercent = if (cooling) baseFresh else readiness.readinessScore,
                    playCount = plays,
                    spotifyUri = uri,
                    sourcePackage = t.sourcePackage?.ifBlank { "unknown_player" } ?: "unknown_player",
                    isCoolingDown = cooling,
                    isFromVault = vaultStatus.isInVault,
                    trackId = canonicalId,
                    vaultId = vaultStatus.vaultId,
                    cooldownRemainingLabel = vaultStatus.cooldownRemainingLabel,
                    recoveryPercent = vaultStatus.recoveryPercent.takeIf { vaultStatus.isInVault },
                    entryReason = vaultStatus.entryReason,
                    freshCategory = readiness.category,
                    freshReason = readiness.freshReason,
                    readinessScore = if (cooling) baseFresh else readiness.readinessScore
                )
            )
        }

        // 4. Saved For You Discoveries (bookmarked by user & ready to spin)
        for (rec in savedRecommendations.filter { !it.isDismissed }) {
            val title = rec.title.trim().takeIf { it.isNotEmpty() } ?: continue
            val artist = rec.artist.trim().ifBlank { "Discovery" }
            if (!claimTrackSlot(title, artist)) continue
            val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(rec.spotifyUri)
            val canonicalId = rec.resolvedCanonicalTrackId()
            val vaultStatus = resolveTrackVaultStatus(title, artist, cleanUri, canonicalId)
            if (vaultStatus.isInVault || vaultStatus.isCoolingDown || isFreshExcluded(title, artist, canonicalId)) continue
            val plays = resolvePlayCount(title, artist, cleanUri).coerceAtLeast(0)
            val readiness = RestingVaultEngine.computeFreshReadiness(
                baseFreshnessPercent = if (plays == 0) 99 else vaultStatus.freshnessPercent,
                playCount = plays,
                isLikedOrSaved = true,
                sourceLabel = "Saved Pick",
                nowMs = nowMs
            )
            val safeId = "saved_${rec.id}".let { if (seenIds.add(it)) it else "saved_${list.size}" }
            list.add(
                DopamineSongItem(
                    id = safeId,
                    title = title,
                    artist = artist,
                    freshnessPercent = readiness.readinessScore,
                    playCount = plays,
                    spotifyUri = cleanUri,
                    sourcePackage = "unknown_player",
                    isCoolingDown = false,
                    trackId = canonicalId,
                    freshCategory = readiness.category,
                    freshReason = readiness.freshReason,
                    readinessScore = readiness.readinessScore
                )
            )
        }

        // 5. Liked & Cross-App Library Songs (permanent tracks from Boomplay, YTM, Spotify passive, Audiomack, etc.)
        for (lib in crossAppLibraryTracks.take(100)) {
            val title = lib.title.trim().takeIf { it.isNotEmpty() } ?: continue
            val artist = lib.artist.trim().ifBlank { "Music" }
            if (!claimTrackSlot(title, artist)) continue
            val canonicalId = lib.trackId.ifBlank {
                com.tunemind.app.domain.model.TrackIdentityResolver.buildDeterministicTrackId(title, artist)
            }
            val vaultStatus = resolveTrackVaultStatus(title, artist, null, canonicalId)
            if (vaultStatus.isInVault || vaultStatus.isCoolingDown || isFreshExcluded(title, artist, canonicalId)) continue
            val plays = resolvePlayCount(title, artist, null).takeIf { it > 0 } ?: lib.playCount.coerceAtLeast(0)
            val appName = CrossAppPlayRecorder.resolveFriendlyPlayerName(lib.primarySourcePackage)
            val readiness = RestingVaultEngine.computeFreshReadiness(
                baseFreshnessPercent = if (plays == 0) 99 else vaultStatus.freshnessPercent,
                playCount = plays,
                isLikedOrSaved = lib.isLiked,
                sourceLabel = if (lib.isLiked) "Liked in $appName" else "$appName Library",
                nowMs = nowMs
            )
            val safeId = "lib_${canonicalId}".let { if (seenIds.add(it)) it else "lib_${list.size}" }
            list.add(
                DopamineSongItem(
                    id = safeId,
                    title = title,
                    artist = artist,
                    freshnessPercent = readiness.readinessScore,
                    playCount = plays,
                    spotifyUri = null,
                    sourcePackage = lib.primarySourcePackage.ifBlank { "unknown_player" },
                    isCoolingDown = false,
                    trackId = canonicalId,
                    albumArtUrl = lib.albumArtUrl,
                    freshCategory = readiness.category,
                    freshReason = readiness.freshReason,
                    readinessScore = readiness.readinessScore
                )
            )
        }

        // 6. TuneMind Playlist Tracks
        var playlistGemsAdded = 0
        for (pl in tuneMindPlaylists.take(8)) {
            if (playlistGemsAdded >= 60) break
            for (tr in pl.tracks) {
                if (playlistGemsAdded >= 60) break
                val title = tr.title.trim().takeIf { it.isNotEmpty() } ?: continue
                val artist = tr.artist.trim().ifBlank { "Playlist" }
                if (!claimTrackSlot(title, artist)) continue
                val canonicalId = tr.trackId.ifBlank {
                    com.tunemind.app.domain.model.TrackIdentityResolver.buildDeterministicTrackId(title, artist)
                }
                val vaultStatus = resolveTrackVaultStatus(title, artist, null, canonicalId)
                if (vaultStatus.isInVault || vaultStatus.isCoolingDown) continue
                val plays = resolvePlayCount(title, artist, null).coerceAtLeast(0)
                val readiness = RestingVaultEngine.computeFreshReadiness(
                    baseFreshnessPercent = if (plays == 0) 98 else vaultStatus.freshnessPercent,
                    playCount = plays,
                    isLikedOrSaved = true,
                    sourceLabel = pl.playlist.name.take(18).ifBlank { "Playlist" },
                    nowMs = nowMs
                )
                val safeId = "pl_${canonicalId}".let { if (seenIds.add(it)) it else "pl_${list.size}" }
                list.add(
                    DopamineSongItem(
                        id = safeId,
                        title = title,
                        artist = artist,
                        freshnessPercent = readiness.readinessScore,
                        playCount = plays,
                        spotifyUri = null,
                        sourcePackage = tr.sourcePlayer.ifBlank { "unknown_player" },
                        isCoolingDown = false,
                        trackId = canonicalId,
                        albumArtUrl = null,
                        freshCategory = readiness.category,
                        freshReason = readiness.freshReason,
                        readinessScore = readiness.readinessScore
                    )
                )
                playlistGemsAdded++
            }
        }

            list
        }
    }

    val peakFreshSongs = remember(allSongs) {
        allSongs
            .filter { !it.isCoolingDown }
            .sortedWith(
                compareByDescending<DopamineSongItem> {
                    when {
                        it.isManualFresh -> 2
                        it.freshCategory == RestingVaultEngine.FreshCategory.COMEBACK -> 1
                        else -> 0
                    }
                }
                    .thenByDescending { it.readinessScore }
                    .thenBy { it.playCount }
            )
    }

    val filteredFreshSongs = remember(peakFreshSongs, freshFilter) {
        when (freshFilter) {
            1 -> peakFreshSongs.filter { it.freshCategory == RestingVaultEngine.FreshCategory.COMEBACK }
            2 -> peakFreshSongs.filter { it.freshCategory == RestingVaultEngine.FreshCategory.UNPLAYED_GEM || it.playCount == 0 }
            3 -> peakFreshSongs.filter { it.playCount > 0 }
            else -> peakFreshSongs
        }
    }

    val freshComebackCount = remember(peakFreshSongs) {
        peakFreshSongs.count { it.freshCategory == RestingVaultEngine.FreshCategory.COMEBACK }
    }
    val freshGemCount = remember(peakFreshSongs) {
        peakFreshSongs.count { it.freshCategory == RestingVaultEngine.FreshCategory.UNPLAYED_GEM || it.playCount == 0 }
    }

    val coolingDownSongs = remember(allSongs) {
        allSongs.filter { it.isCoolingDown }.sortedBy { it.recoveryPercent ?: (100 - it.freshnessPercent) }
    }

    val averageFreshness = remember(peakFreshSongs, coolingDownSongs, selectedTab) {
        if (selectedTab == 1) {
            if (coolingDownSongs.isEmpty()) 100
            else coolingDownSongs.map { it.recoveryPercent ?: it.freshnessPercent }.average().toInt().coerceIn(0, 100)
        } else {
            if (peakFreshSongs.isEmpty()) 0
            else peakFreshSongs.map { it.readinessScore }.average().toInt().coerceIn(20, 100)
        }
    }

    val displayedSongs = if (selectedTab == 0) filteredFreshSongs else coolingDownSongs
    val activeTabAccent = when (selectedTab) {
        1 -> Color(0xFFFFB000)
        2 -> Color(0xFF38BDF8)
        else -> theme.primaryAccent
    }

    StudioAmbientBackground(accentOverride = activeTabAccent) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            val hasFreshDrop = remember(forYouPicks) {
                forYouPicks.any { !it.isDismissed && !it.wasPlayed }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (selectedTab == 2) {
                        AiNeuralCoreEmblem(
                            accentPrimary = theme.primaryAccent,
                            accentSecondary = theme.secondaryAccent,
                            isThinking = isDiscovering,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(
                        text = if (selectedTab == 2) "For You" else "Fresh",
                        color = Color(0xFFF8FAFC),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp
                    )
                }
                Text(
                    text = if (selectedTab == 2) {
                        "Taste Match ${learningState.intelligenceScorePercent}%"
                    } else {
                        "${peakFreshSongs.size} ready · ${coolingDownSongs.size} resting"
                    },
                    color = if (selectedTab == 2) theme.primaryAccent else Color.White.copy(alpha = 0.56f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Spring-sliding capsule tabs (Fresh | Resting | For You) — permanently anchored at top
            MagicalSegmentedTabs(
                tabs = listOf(
                    MagicalTabItem("Fresh (${peakFreshSongs.size})", theme.primaryAccent),
                    MagicalTabItem("Resting (${coolingDownSongs.size})", Color(0xFFFFB000)),
                    MagicalTabItem(
                        label = "For You",
                        accent = Color(0xFF38BDF8),
                        isAiHighlighted = true,
                        hasFreshDrop = hasFreshDrop
                    )
                ),
                selectedIndex = selectedTab,
                onSelectTab = { selectedTab = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Smooth crossfading list viewport
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                },
                modifier = Modifier.weight(1f),
                label = "fresh_tab_content"
            ) { currentTab ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 124.dp)
                ) {
                    if (currentTab != 2) {
                        item(key = "fresh_readiness_strip") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(54.dp)
                                    ) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            drawArc(
                                                color = Color.White.copy(alpha = 0.10f),
                                                startAngle = 135f,
                                                sweepAngle = 270f,
                                                useCenter = false,
                                                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                                            )
                                            drawArc(
                                                color = activeTabAccent,
                                                startAngle = 135f,
                                                sweepAngle = if (allSongs.isEmpty()) 0f else 270f * (averageFreshness / 100f),
                                                useCenter = false,
                                                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                                            )
                                        }
                                        Text(
                                            text = if (allSongs.isEmpty()) "--" else "$averageFreshness%",
                                            color = Color(0xFFFAFAFA),
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        val entryGate = RestingVaultEngine.computeEntryThreshold(repetitionSensitivity)
                                        Text(
                                            text = when {
                                                currentTab == 1 -> "${coolingDownSongs.size} resting · $averageFreshness% avg recovery"
                                                peakFreshSongs.isEmpty() -> "No fresh tracks ready yet"
                                                else -> "${peakFreshSongs.size} fresh · $averageFreshness% peak readiness"
                                            },
                                            color = Color(0xFFFAFAFA),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = when {
                                                currentTab == 1 -> "Auto-rests above $entryGate% satiety (2–7d) · Graduates cleanly at 100%"
                                                peakFreshSongs.isEmpty() -> "Play music or connect Spotify to unlock Fresh rotation & gems."
                                                else -> listOfNotNull(
                                                    if (freshComebackCount > 0) "$freshComebackCount graduated" else null,
                                                    if (freshGemCount > 0) "$freshGemCount library gems" else null,
                                                    "Temporal freshness + dormancy ranked"
                                                ).joinToString(" · ")
                                            },
                                            color = Color.White.copy(alpha = 0.62f),
                                            fontSize = 12.5.sp,
                                            lineHeight = 17.sp
                                        )
                                        if (currentTab == 1 && graduatedTracks.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            val latestGrad = graduatedTracks.first()
                                            Text(
                                                text = "Graduated to Fresh: \"${latestGrad.title}\" (${latestGrad.totalRestedDays}d rest complete)",
                                                color = Color(0xFF10B981),
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }

                                    // Manual Add pill for Fresh (0) & Resting Vault (1)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(100.dp))
                                            .background(activeTabAccent.copy(alpha = 0.16f))
                                            .border(
                                                width = 1.dp,
                                                color = activeTabAccent.copy(alpha = 0.48f),
                                                shape = RoundedCornerShape(100.dp)
                                            )
                                            .clickable { showManualPickerSheet = true }
                                            .padding(horizontal = 12.dp, vertical = 7.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Add,
                                                contentDescription = if (currentTab == 1) "Add to Resting" else "Add to Fresh",
                                                tint = activeTabAccent,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = if (currentTab == 1) "Rest" else "Fresh",
                                                color = Color(0xFFFAFAFA),
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                                com.tunemind.app.ui.components.StudioHairlineDivider()
                                if (currentTab == 0 && peakFreshSongs.isNotEmpty()) {
                                    val filterLabels = listOf(
                                        "All (${peakFreshSongs.size})",
                                        "Comebacks ($freshComebackCount)",
                                        "Library Gems ($freshGemCount)",
                                        "Played (${peakFreshSongs.count { it.playCount > 0 }})"
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        itemsIndexed(filterLabels) { idx, label ->
                                            val isSelected = freshFilter == idx
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(100.dp))
                                                    .background(
                                                        if (isSelected) theme.primaryAccent.copy(alpha = 0.22f)
                                                        else Color.White.copy(alpha = 0.05f)
                                                    )
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (isSelected) theme.primaryAccent.copy(alpha = 0.72f)
                                                        else Color.White.copy(alpha = 0.09f),
                                                        shape = RoundedCornerShape(100.dp)
                                                    )
                                                    .clickable { freshFilter = idx }
                                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = label,
                                                    color = if (isSelected) Color(0xFFF8FAFC) else Color.White.copy(alpha = 0.68f),
                                                    fontSize = 11.5.sp,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }

                    if (currentTab == 2) {
                        val todayKey = learningState.dropDateKey.ifBlank {
                            com.tunemind.app.domain.ai.NoonDropSchedule.getNoonCycleKey()
                        }
                        val todayScopedRecs = forYouPicks.filter {
                            !it.isDismissed && it.dropDateKey == todayKey
                        }
                        val activeRecs = if (todayScopedRecs.isNotEmpty()) {
                            todayScopedRecs
                        } else {
                            forYouPicks.filter { !it.isDismissed }
                        }
                        val allWildcardsInCycle = forYouPicks.filter {
                            (it.isWildcard || it.subTier.equals("GLOBAL_WILDCARD", ignoreCase = true)) &&
                                it.dropDateKey == todayKey
                        }.ifEmpty {
                            activeRecs.filter { it.isWildcard || it.subTier.equals("GLOBAL_WILDCARD", ignoreCase = true) }
                        }
                        forYouDiscoveryItems(
                            activeRecs = activeRecs,
                            learningState = learningState,
                            isDiscovering = isDiscovering,
                            totalWildcardsInCycle = allWildcardsInCycle.size,
                            lockedWildcardsCount = allWildcardsInCycle.count { it.isSaved && !it.isDismissed },
                            resolvePlayCount = resolvePlayCount,
                            isTrackCurrentlyPlaying = isTrackCurrentlyPlaying,
                            onRefreshForYou = onRefreshForYou,
                            onPlayTrack = onPlayForYouTrack,
                            onPreviewTrack = onPreviewForYouTrack,
                            onSaveTrack = onSaveForYou,
                            onDismissTrack = onDismissForYou,
                            onInspectTrack = { rec ->
                                val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(rec.spotifyUri)
                                activeSyncSong = DopamineSongItem(
                                    id = rec.id,
                                    title = rec.title,
                                    artist = rec.artist,
                                    freshnessPercent = rec.matchScorePercent.coerceIn(72, 99),
                                    playCount = resolvePlayCount(rec.title, rec.artist, cleanUri).coerceAtLeast(0),
                                    spotifyUri = cleanUri,
                                    sourcePackage = "unknown_player",
                                    isCoolingDown = false,
                                    isFromVault = false,
                                    trackId = rec.resolvedCanonicalTrackId()
                                )
                            },
                            onExportPlaylist = onExportForYouPlaylist,
                            hasProAccess = hasProAccess,
                            onOpenProUpgrade = onOpenProUpgrade
                        )
                    } else if (displayedSongs.isEmpty()) {
                        item(key = "vault_empty") {
                            SoftGlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Text(
                                    text = if (currentTab == 0) {
                                        "Play a song in Spotify or your music player to build your Fresh list."
                                    } else {
                                        "Nothing resting right now. Everything in your rotation sounds fresh."
                                    },
                                    color = Color.White.copy(alpha = 0.68f),
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = displayedSongs,
                            key = { index, song -> "fresh_${currentTab}_${song.id}_$index" }
                        ) { _, song ->
                            val isCurrentlyPlaying = isTrackCurrentlyPlaying(song.title, song.artist, song.spotifyUri)
                            val accurateCount = resolvePlayCount(song.title, song.artist, song.spotifyUri)
                                .takeIf { it > 0 } ?: song.playCount

                            TuneMindTrackCard(
                                title = song.title,
                                artist = song.artist,
                                subtitle = if (currentTab == 1) song.entryReason else song.freshReason,
                                playCount = accurateCount,
                                showUnplayedLabel = true,
                                isCurrentlyPlaying = isCurrentlyPlaying,
                                freshnessPercent = if (currentTab == 0) song.readinessScore else song.freshnessPercent,
                                isCoolingDown = song.isCoolingDown,
                                cooldownRemainingLabel = song.cooldownRemainingLabel,
                                recoveryPercent = song.recoveryPercent,
                                accentColor = theme.primaryAccent,
                                secondaryAccent = theme.secondaryAccent,
                                spotifyUri = song.spotifyUri,
                                albumArtUrl = song.albumArtUrl,
                                trackId = song.trackId,
                                onPlayClick = {
                                    onPlayTrack(song.title, song.artist, song.spotifyUri, song.sourcePackage)
                                },
                                onSyncClick = {
                                    activeSyncSong = song.copy(playCount = accurateCount)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    activeSyncSong?.let { selectedSong ->
        val selVaultStatus = resolveTrackVaultStatus(
            selectedSong.title,
            selectedSong.artist,
            selectedSong.spotifyUri,
            selectedSong.trackId
        )
        NeuralTrackActionDialog(
            title = selectedSong.title,
            artist = selectedSong.artist,
            freshnessPercent = selectedSong.freshnessPercent,
            playCount = selectedSong.playCount,
            isCoolingDown = selectedSong.isCoolingDown,
            isInFresh = selVaultStatus.isInFresh && !selectedSong.isCoolingDown,
            spotifyUri = selectedSong.spotifyUri,
            trackId = selectedSong.trackId,
            cooldownRemainingLabel = selectedSong.cooldownRemainingLabel,
            recoveryPercent = selectedSong.recoveryPercent,
            vaultReason = selectedSong.entryReason,
            onDismiss = { activeSyncSong = null },
            onPlayNow = {
                onPlayTrack(selectedSong.title, selectedSong.artist, selectedSong.spotifyUri, selectedSong.sourcePackage)
            },
            onAgentNeuralBreakdown = {
                onAskAgentAboutTrack(
                    selectedSong.title,
                    selectedSong.artist,
                    selectedSong.freshnessPercent,
                    selectedSong.spotifyUri
                )
            },
            onSyncAtmosphereToTrack = {
                onSyncAtmosphereToTrack(selectedSong.title, selectedSong.artist)
            },
            onToggleCooldownVault = {
                if (selectedSong.isFromVault || selectedSong.vaultId != null) {
                    onRestoreVaultTrack(selectedSong.vaultId ?: selectedSong.id)
                } else {
                    onQuarantineTrack(selectedSong.title, selectedSong.artist)
                    selectedTab = 1
                }
            },
            onToggleFreshList = {
                if (selVaultStatus.isInFresh && !selectedSong.isCoolingDown) {
                    onRemoveFromFresh(
                        selectedSong.title,
                        selectedSong.artist,
                        selectedSong.spotifyUri,
                        selectedSong.trackId
                    )
                } else {
                    onAddToFresh(
                        selectedSong.title,
                        selectedSong.artist,
                        selectedSong.sourcePackage,
                        selectedSong.spotifyUri,
                        selectedSong.trackId,
                        selectedSong.albumArtUrl
                    )
                }
            },
            onAddToQueue = {
                onAddToQueue(selectedSong.title, selectedSong.artist, selectedSong.spotifyUri)
            },
            onSaveToLiked = {
                onSaveToLiked(selectedSong.title, selectedSong.artist, selectedSong.spotifyUri)
            }
        )
    }

    if (showManualPickerSheet) {
        ManualVaultFreshPickerSheet(
            isRestingMode = selectedTab == 1,
            recentTracks = recentTracks,
            crossAppLibraryTracks = crossAppLibraryTracks,
            tuneMindPlaylists = tuneMindPlaylists,
            resolvePlayCount = resolvePlayCount,
            resolveTrackVaultStatus = resolveTrackVaultStatus,
            onAddToFresh = { title, artist, pkg, uri, tid, art ->
                onAddToFresh(title, artist, pkg, uri, tid, art)
            },
            onRemoveFromFresh = { title, artist, uri, tid ->
                onRemoveFromFresh(title, artist, uri, tid)
            },
            onQuarantineTrack = { title, artist ->
                onQuarantineTrack(title, artist)
            },
            onRestoreVaultTrack = { vaultId ->
                onRestoreVaultTrack(vaultId)
            },
            onDismiss = { showManualPickerSheet = false }
        )
    }
}

private data class ManualPickerCandidate(
    val title: String,
    val artist: String,
    val sourcePackage: String,
    val spotifyUri: String?,
    val trackId: String,
    val albumArtUrl: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualVaultFreshPickerSheet(
    isRestingMode: Boolean,
    recentTracks: List<TrackMemoryEntry>,
    crossAppLibraryTracks: List<CrossAppTrackEntity>,
    tuneMindPlaylists: List<TuneMindPlaylistWithTracks>,
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int,
    resolveTrackVaultStatus: (title: String?, artist: String?, uri: String?, trackId: String?) -> TrackVaultUiStatus,
    onAddToFresh: (String, String, String?, String?, String?, String?) -> Unit,
    onRemoveFromFresh: (String, String, String?, String?) -> Unit,
    onQuarantineTrack: (String, String) -> Unit,
    onRestoreVaultTrack: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val accent = if (isRestingMode) Color(0xFFFFB000) else theme.primaryAccent

    val candidates by produceState<List<ManualPickerCandidate>>(
        initialValue = emptyList(),
        recentTracks,
        crossAppLibraryTracks,
        tuneMindPlaylists
    ) {
        value = withContext(Dispatchers.Default) {
            val seen = mutableListOf<Pair<String, String>>()
            val items = mutableListOf<ManualPickerCandidate>()
            fun claimPickerTrack(t: String, a: String): Boolean {
                val dup = seen.any { (prevT, prevA) ->
                    com.tunemind.app.domain.memory.ListeningPlayDeduplicator.isSameTrack(prevT, prevA, null, t, a, null)
                }
                if (dup) return false
                seen.add(t to a)
                return true
            }
        for (t in recentTracks) {
            val title = t.title?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val artist = t.artist?.trim()?.ifBlank { "Music" } ?: "Music"
            if (claimPickerTrack(title, artist)) {
                items.add(
                    ManualPickerCandidate(
                        title = title,
                        artist = artist,
                        sourcePackage = t.sourcePackage?.ifBlank { "unknown_player" } ?: "unknown_player",
                        spotifyUri = null,
                        trackId = t.canonicalTrackId(),
                        albumArtUrl = null
                    )
                )
            }
        }
        for (lib in crossAppLibraryTracks.take(100)) {
            val title = lib.title.trim().takeIf { it.isNotEmpty() } ?: continue
            val artist = lib.artist.trim().ifBlank { "Library" }
            if (claimPickerTrack(title, artist)) {
                val tid = lib.trackId.ifBlank {
                    com.tunemind.app.domain.model.TrackIdentityResolver.buildDeterministicTrackId(title, artist)
                }
                items.add(
                    ManualPickerCandidate(
                        title = title,
                        artist = artist,
                        sourcePackage = lib.primarySourcePackage.ifBlank { "unknown_player" },
                        spotifyUri = null,
                        trackId = tid,
                        albumArtUrl = lib.albumArtUrl
                    )
                )
            }
        }
        for (pl in tuneMindPlaylists.take(8)) {
            for (tr in pl.tracks.take(30)) {
                val title = tr.title.trim().takeIf { it.isNotEmpty() } ?: continue
                val artist = tr.artist.trim().ifBlank { "Playlist" }
                if (claimPickerTrack(title, artist)) {
                    val tid = tr.trackId.ifBlank {
                        com.tunemind.app.domain.model.TrackIdentityResolver.buildDeterministicTrackId(title, artist)
                    }
                    items.add(
                        ManualPickerCandidate(
                            title = title,
                            artist = artist,
                            sourcePackage = tr.sourcePlayer.ifBlank { "unknown_player" },
                            spotifyUri = null,
                            trackId = tid,
                            albumArtUrl = null
                        )
                    )
                }
            }
        }
            items
        }
    }

    val filtered = remember(candidates, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) candidates.take(60)
        else candidates.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }.take(60)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0E1420),
        contentColor = Color(0xFFFAFAFA)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.76f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isRestingMode) "Add Songs to Resting Vault" else "Add Songs to Fresh",
                        color = Color(0xFFFAFAFA),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isRestingMode) "Rest overplayed tracks to restore their spark" else "Pin songs from your library or recent plays into Fresh",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search recent plays or cross-app library...", color = Color.White.copy(alpha = 0.42f), fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.05f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
                    focusedBorderColor = accent.copy(alpha = 0.65f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { "${it.title}|${it.artist}|${it.trackId}" }) { item ->
                    val status = resolveTrackVaultStatus(item.title, item.artist, item.spotifyUri, item.trackId)
                    val plays = resolvePlayCount(item.title, item.artist, item.spotifyUri)
                    val playsLabel = "$plays ${if (plays == 1) "play" else "plays"}"
                    val activeInTarget = if (isRestingMode) status.isInVault || status.isCoolingDown else status.isInFresh && !status.isCoolingDown
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(
                                width = 1.dp,
                                color = if (activeInTarget) accent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.07f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                color = Color(0xFFFAFAFA),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${item.artist} · $playsLabel · ${status.freshnessPercent}% fresh",
                                color = Color.White.copy(alpha = 0.58f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(
                                    if (activeInTarget) Color(0xFF10B981).copy(alpha = 0.16f)
                                    else accent.copy(alpha = 0.18f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (activeInTarget) Color(0xFF10B981).copy(alpha = 0.55f) else accent.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(100.dp)
                                )
                                .clickable {
                                    if (isRestingMode) {
                                        if (status.isInVault && status.vaultId != null) {
                                            onRestoreVaultTrack(status.vaultId)
                                        } else {
                                            onQuarantineTrack(item.title, item.artist)
                                        }
                                    } else {
                                        if (activeInTarget) {
                                            onRemoveFromFresh(item.title, item.artist, item.spotifyUri, item.trackId)
                                        } else {
                                            onAddToFresh(item.title, item.artist, item.sourcePackage, item.spotifyUri, item.trackId, item.albumArtUrl)
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isRestingMode) {
                                    if (activeInTarget) "In Vault · Remove" else "+ Rest"
                                } else {
                                    if (activeInTarget) "In Fresh · Remove" else "+ Fresh"
                                },
                                color = if (activeInTarget) Color(0xFF34D399) else accent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
