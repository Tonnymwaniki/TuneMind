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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.data.ingestion.SpotifyLikedTrackEntity
import com.tunemind.app.data.ingestion.SpotifyNewReleaseItem
import com.tunemind.app.data.ingestion.SpotifyPlaylistEntity
import com.tunemind.app.domain.ai.ForYouLearningSummary
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.recommendation.AiRecommendationEntity
import com.tunemind.app.domain.vault.RestingVaultEngine
import com.tunemind.app.ui.GraduatedVaultSummary
import com.tunemind.app.ui.TrackVaultUiStatus
import com.tunemind.app.ui.VaultTrack
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

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
    val readinessScore: Int = freshnessPercent
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
    spotifyNewReleases: List<SpotifyNewReleaseItem> = emptyList(),
    resolvePlayCount: (title: String?, artist: String?, uri: String?) -> Int = { _, _, _ -> 0 },
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean = { t, _, _ ->
        !nowPlayingTitle.isNullOrBlank() && !t.isNullOrBlank() && t.equals(nowPlayingTitle.trim(), ignoreCase = true)
    },
    onRefreshForYou: () -> Unit = {},
    onSaveForYou: (AiRecommendationEntity, Boolean) -> Unit = { _, _ -> },
    onDismissForYou: (AiRecommendationEntity) -> Unit = {},
    onPlayForYouTrack: (AiRecommendationEntity) -> Unit = { rec ->
        onPlayTrack(rec.title, rec.artist, rec.spotifyUri, "com.spotify.music")
    },
    onPreviewForYouTrack: (AiRecommendationEntity) -> Unit = { rec ->
        onPlayTrack(rec.title, rec.artist, rec.spotifyUri, "com.spotify.music")
    },
    onAddToQueue: (title: String, artist: String, spotifyUri: String?) -> Unit = { _, _, _ -> },
    onSaveToSpotifyLiked: (title: String, artist: String, spotifyUri: String?) -> Unit = { _, _, _ -> },
    onExportForYouPlaylist: (List<AiRecommendationEntity>) -> Unit = {},
    requestedSubTab: Int? = null,
    onConsumeRequestedSubTab: () -> Unit = {},
    graduatedTracks: List<GraduatedVaultSummary> = emptyList(),
    onTickVaultLifecycle: () -> Unit = {},
    resolveTrackVaultStatus: (title: String?, artist: String?, uri: String?, trackId: String?) -> TrackVaultUiStatus = { _, _, _, _ -> TrackVaultUiStatus() },
    likedTracks: List<SpotifyLikedTrackEntity> = emptyList(),
    playlists: List<SpotifyPlaylistEntity> = emptyList(),
    savedRecommendations: List<AiRecommendationEntity> = emptyList(),
    restoredAtMap: Map<String, Long> = emptyMap()
) {
    val theme = LocalAdaptiveTheme.current
    val repetitionSensitivity by com.tunemind.app.domain.voice.AgentSettingsStore.repetitionSensitivity.collectAsState()
    var selectedTab by remember { mutableStateOf(0) } // 0 = Fresh, 1 = Resting, 2 = For You
    var freshFilter by remember { mutableStateOf(0) } // 0 = All, 1 = Comebacks, 2 = Library Gems, 3 = Played
    var activeSyncSong by remember { mutableStateOf<DopamineSongItem?>(null) }

    LaunchedEffect(selectedTab) {
        onTickVaultLifecycle()
    }

    LaunchedEffect(requestedSubTab) {
        if (requestedSubTab != null && requestedSubTab in 0..2) {
            selectedTab = requestedSubTab
            onConsumeRequestedSubTab()
        }
    }

    val allSongs = remember(
        recentTracks,
        vaultTracks,
        graduatedTracks,
        likedTracks,
        playlists,
        savedRecommendations,
        restoredAtMap,
        resolvePlayCount,
        repetitionSensitivity,
        resolveTrackVaultStatus
    ) {
        val list = mutableListOf<DopamineSongItem>()
        val seenKeys = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        val nowMs = System.currentTimeMillis()
        val sevenDaysMs = 7L * RestingVaultEngine.DAY_MS

        // 1. Active Resting Vault entries (live ease-out recovery curve & remaining cooldown)
        for (v in vaultTracks) {
            val key = "${v.title.trim().lowercase()}|${v.artist.trim().lowercase()}"
            if (!seenKeys.add(key)) continue
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
                    sourcePackage = v.sourcePackage.ifBlank { "com.spotify.music" },
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
            val key = "${grad.title.trim().lowercase()}|${grad.artist.trim().lowercase()}"
            if (!seenKeys.add(key)) continue
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
                    sourcePackage = "com.spotify.music",
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
            val key = "${title.lowercase()}|${artist.lowercase()}"
            if (!seenKeys.add(key)) continue

            val safeId = t.entryId.takeIf { it.isNotBlank() && seenIds.add(it) }
                ?: "track_${key.hashCode()}_${list.size}".also { seenIds.add(it) }
            val uri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(t.providerTrackId)
            val canonicalId = t.canonicalTrackId()
            val plays = resolvePlayCount(title, artist, uri).takeIf { it > 0 }
                ?: t.distinctAppearanceCount.coerceAtLeast(0)
            val vaultStatus = resolveTrackVaultStatus(title, artist, uri, canonicalId)
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
            val cooling = vaultStatus.isInVault || vaultStatus.isCoolingDown
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
                    sourcePackage = t.sourcePackage?.ifBlank { "com.spotify.music" } ?: "com.spotify.music",
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
            val key = "${title.lowercase()}|${artist.lowercase()}"
            if (!seenKeys.add(key)) continue
            val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(rec.spotifyUri)
            val canonicalId = rec.resolvedCanonicalTrackId()
            val vaultStatus = resolveTrackVaultStatus(title, artist, cleanUri, canonicalId)
            if (vaultStatus.isInVault || vaultStatus.isCoolingDown) continue
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
                    sourcePackage = "com.spotify.music",
                    isCoolingDown = false,
                    trackId = canonicalId,
                    freshCategory = readiness.category,
                    freshReason = readiness.freshReason,
                    readinessScore = readiness.readinessScore
                )
            )
        }

        // 5. Untouched / Low-Play Spotify Liked Songs (genuine library Fresh gems)
        for (liked in likedTracks.take(80)) {
            val title = liked.trackTitle.trim().takeIf { it.isNotEmpty() } ?: continue
            val artist = liked.artistName.trim().ifBlank { "Spotify Library" }
            val key = "${title.lowercase()}|${artist.lowercase()}"
            if (!seenKeys.add(key)) continue
            val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(liked.spotifyUri)
            val canonicalId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalTrackId(
                trackId = liked.trackId,
                spotifyUri = cleanUri,
                title = title,
                artist = artist
            )
            val vaultStatus = resolveTrackVaultStatus(title, artist, cleanUri, canonicalId)
            if (vaultStatus.isInVault || vaultStatus.isCoolingDown) continue
            val plays = resolvePlayCount(title, artist, cleanUri).coerceAtLeast(0)
            val readiness = RestingVaultEngine.computeFreshReadiness(
                baseFreshnessPercent = if (plays == 0) 99 else vaultStatus.freshnessPercent,
                playCount = plays,
                isLikedOrSaved = true,
                sourceLabel = "Liked Song",
                nowMs = nowMs
            )
            val safeId = "liked_${canonicalId}".let { if (seenIds.add(it)) it else "liked_${list.size}" }
            list.add(
                DopamineSongItem(
                    id = safeId,
                    title = title,
                    artist = artist,
                    freshnessPercent = readiness.readinessScore,
                    playCount = plays,
                    spotifyUri = cleanUri,
                    sourcePackage = "com.spotify.music",
                    isCoolingDown = false,
                    trackId = canonicalId,
                    albumArtUrl = liked.albumArtUrl,
                    freshCategory = readiness.category,
                    freshReason = readiness.freshReason,
                    readinessScore = readiness.readinessScore
                )
            )
        }

        // 6. Spotify Playlist Tracks (untouched gems from synced playlists)
        var playlistGemsAdded = 0
        for (pl in playlists.take(8)) {
            if (playlistGemsAdded >= 60) break
            val decoded = pl.decodeTracks()
            for (tr in decoded) {
                if (playlistGemsAdded >= 60) break
                val title = tr.title.trim().takeIf { it.isNotEmpty() } ?: continue
                val artist = tr.artist.trim().ifBlank { "Spotify Playlist" }
                val key = "${title.lowercase()}|${artist.lowercase()}"
                if (!seenKeys.add(key)) continue
                val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(tr.spotifyUri)
                val canonicalId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalTrackId(
                    trackId = tr.trackId,
                    spotifyUri = cleanUri,
                    title = title,
                    artist = artist
                )
                val vaultStatus = resolveTrackVaultStatus(title, artist, cleanUri, canonicalId)
                if (vaultStatus.isInVault || vaultStatus.isCoolingDown) continue
                val plays = resolvePlayCount(title, artist, cleanUri).coerceAtLeast(0)
                val readiness = RestingVaultEngine.computeFreshReadiness(
                    baseFreshnessPercent = if (plays == 0) 98 else vaultStatus.freshnessPercent,
                    playCount = plays,
                    isLikedOrSaved = true,
                    sourceLabel = pl.name.take(18).ifBlank { "Playlist" },
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
                        spotifyUri = cleanUri,
                        sourcePackage = "com.spotify.music",
                        isCoolingDown = false,
                        trackId = canonicalId,
                        albumArtUrl = tr.albumArtUrl,
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

    val peakFreshSongs = remember(allSongs) {
        allSongs
            .filter { !it.isCoolingDown }
            .sortedWith(
                compareByDescending<DopamineSongItem> {
                    if (it.freshCategory == RestingVaultEngine.FreshCategory.COMEBACK) 1 else 0
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
                                    sourcePackage = "com.spotify.music",
                                    isCoolingDown = false,
                                    isFromVault = false,
                                    trackId = rec.resolvedCanonicalTrackId()
                                )
                            },
                            onExportPlaylist = onExportForYouPlaylist
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
        NeuralTrackActionDialog(
            title = selectedSong.title,
            artist = selectedSong.artist,
            freshnessPercent = selectedSong.freshnessPercent,
            playCount = selectedSong.playCount,
            isCoolingDown = selectedSong.isCoolingDown,
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
            onAddToSpotifyQueue = {
                onAddToQueue(selectedSong.title, selectedSong.artist, selectedSong.spotifyUri)
            },
            onSaveToSpotifyLiked = {
                onSaveToSpotifyLiked(selectedSong.title, selectedSong.artist, selectedSong.spotifyUri)
            }
        )
    }
}
