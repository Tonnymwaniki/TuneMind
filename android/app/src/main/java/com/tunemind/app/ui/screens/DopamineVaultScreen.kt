package com.tunemind.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.tunemind.app.data.ingestion.SpotifyNewReleaseItem
import com.tunemind.app.domain.ai.ForYouLearningSummary
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.recommendation.AiRecommendationEntity
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
    val trackId: String = ""
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
    onConsumeRequestedSubTab: () -> Unit = {}
) {
    val theme = LocalAdaptiveTheme.current
    val repetitionSensitivity by com.tunemind.app.domain.voice.AgentSettingsStore.repetitionSensitivity.collectAsState()
    var selectedTab by remember { mutableStateOf(0) } // 0 = Fresh, 1 = Resting, 2 = For You
    var activeSyncSong by remember { mutableStateOf<DopamineSongItem?>(null) }

    LaunchedEffect(requestedSubTab) {
        if (requestedSubTab != null && requestedSubTab in 0..2) {
            selectedTab = requestedSubTab
            onConsumeRequestedSubTab()
        }
    }

    val allSongs = remember(recentTracks, vaultTracks, resolvePlayCount, repetitionSensitivity) {
        val list = mutableListOf<DopamineSongItem>()
        val seenKeys = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        val satietyPerPlay = (12f + repetitionSensitivity.coerceIn(0.2f, 1.0f) * 12f).toInt().coerceIn(13, 25)
        val coolingPlayThreshold = when {
            repetitionSensitivity >= 0.85f -> 3
            repetitionSensitivity >= 0.50f -> 4
            else -> 6
        }

        for (v in vaultTracks) {
            val key = "${v.title.trim().lowercase()}|${v.artist.trim().lowercase()}"
            if (!seenKeys.add(key)) continue
            val safeId = v.id.takeIf { it.isNotBlank() && seenIds.add(it) } ?: "vault_${list.size}"
            seenIds.add(safeId)
            val freshness = (v.recoveryProgress * 100f).toInt().coerceIn(15, 75)
            val canonicalId = v.resolvedCanonicalTrackId()
            val cleanUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(v.spotifyUri)
            val resolvedPlays = resolvePlayCount(v.title, v.artist, cleanUri).coerceAtLeast(0)
            list.add(
                DopamineSongItem(
                    id = safeId,
                    title = v.title,
                    artist = v.artist,
                    freshnessPercent = freshness,
                    playCount = resolvedPlays,
                    spotifyUri = cleanUri,
                    sourcePackage = v.sourcePackage.ifBlank { "com.spotify.music" },
                    isCoolingDown = true,
                    isFromVault = true,
                    trackId = canonicalId
                )
            )
        }

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
            val satiety = if (plays <= 0) 0 else minOf(94, plays * satietyPerPlay)
            val freshness = (100 - satiety).coerceIn(12, 98)
            val cooling = plays >= coolingPlayThreshold || freshness < 45

            list.add(
                DopamineSongItem(
                    id = safeId,
                    title = title,
                    artist = artist,
                    freshnessPercent = freshness,
                    playCount = plays,
                    spotifyUri = uri,
                    sourcePackage = t.sourcePackage?.ifBlank { "com.spotify.music" } ?: "com.spotify.music",
                    isCoolingDown = cooling,
                    isFromVault = false,
                    trackId = canonicalId
                )
            )
        }

        list
    }

    val peakFreshSongs = remember(allSongs) {
        allSongs.filter { !it.isCoolingDown }.sortedByDescending { it.freshnessPercent }
            .ifEmpty { allSongs.sortedByDescending { it.freshnessPercent }.take(5) }
    }

    val coolingDownSongs = remember(allSongs) {
        allSongs.filter { it.isCoolingDown }.sortedBy { it.freshnessPercent }
    }

    val averageFreshness = remember(allSongs) {
        if (allSongs.isEmpty()) 0
        else allSongs.map { it.freshnessPercent }.average().toInt().coerceIn(20, 99)
    }

    val displayedSongs = if (selectedTab == 0) peakFreshSongs else coolingDownSongs
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
                                        Text(
                                            text = when {
                                                currentTab == 1 -> "${coolingDownSongs.size} resting tracks"
                                                allSongs.isEmpty() -> "No tracks tracked yet"
                                                else -> "${peakFreshSongs.size} fresh tracks"
                                            },
                                            color = Color(0xFFFAFAFA),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = when {
                                                currentTab == 1 -> "Resting in your vault to keep them feeling fresh."
                                                allSongs.isEmpty() -> "Play music in Spotify or any player to build your Fresh mix."
                                                else -> "Ready to play without feeling repetitive."
                                            },
                                            color = Color.White.copy(alpha = 0.62f),
                                            fontSize = 12.5.sp,
                                            lineHeight = 17.sp
                                        )
                                    }
                                }
                                com.tunemind.app.ui.components.StudioHairlineDivider()
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
                                playCount = accurateCount,
                                showUnplayedLabel = true,
                                isCurrentlyPlaying = isCurrentlyPlaying,
                                freshnessPercent = song.freshnessPercent,
                                isCoolingDown = song.isCoolingDown,
                                accentColor = theme.primaryAccent,
                                secondaryAccent = theme.secondaryAccent,
                                spotifyUri = song.spotifyUri,
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
                if (selectedSong.isFromVault) {
                    onRestoreVaultTrack(selectedSong.id)
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
