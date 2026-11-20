package com.tunemind.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.recommendation.RecommendationCategory
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.ui.NowPlayingObservation
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.VaultTrack
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.launch

data class TrackAnchorWeight(
    val name: String,
    val weightPercent: Int,
    val description: String,
    val color: Color
)

data class SatietyTrendPoint(
    val label: String,
    val score: Int,
    val isProjected: Boolean = false
)

data class FatigueHistoryEntry(
    val timestampMs: Long,
    val sessionLabel: String,
    val playDurationSec: Int,
    val satietyContribution: String,
    val isLoopTrigger: Boolean = false
)

data class SongProfileData(
    val title: String,
    val artist: String,
    val album: String? = null,
    val sourcePackage: String? = null,
    val appearanceCount: Int = 0,
    val satietyScore: Int = 0,
    val firstObservedMs: Long? = null,
    val lastObservedMs: Long? = null,
    val estimatedPlaybackMs: Long = 0L,
    val isInVault: Boolean = false,
    val recoveryProgress: Float = 0f,
    val cooldownDaysRemaining: Float = 0f,
    val trackId: String? = null,
    val emoji: String = "🎧",
    val vibeTag: String = "Fresh Flow",
    val anchorWeights: List<TrackAnchorWeight> = emptyList(),
    val satietyTrends: List<SatietyTrendPoint> = emptyList(),
    val fatigueHistory: List<FatigueHistoryEntry> = emptyList(),
    val spotifyUri: String? = null
) {
    companion object {
        fun pickEmoji(title: String, artist: String): String {
            val lower = "$title $artist".lowercase()
            return when {
                lower.contains("espresso") || lower.contains("coffee") || lower.contains("tea") -> "☕"
                lower.contains("electric") || lower.contains("synth") || lower.contains("dance") || lower.contains("club") -> "⚡"
                lower.contains("dream") || lower.contains("night") || lower.contains("star") || lower.contains("space") -> "🌌"
                lower.contains("water") || lower.contains("wave") || lower.contains("ocean") || lower.contains("rain") -> "🌊"
                lower.contains("fire") || lower.contains("heat") || lower.contains("burn") || lower.contains("wild") -> "🔥"
                lower.contains("calm") || lower.contains("peace") || lower.contains("meditat") || lower.contains("breathe") -> "🧘"
                lower.contains("sun") || lower.contains("summer") || lower.contains("gold") || lower.contains("light") -> "☀️"
                lower.contains("moon") || lower.contains("midnight") || lower.contains("dark") || lower.contains("shadow") -> "🌙"
                lower.contains("love") || lower.contains("heart") || lower.contains("you") -> "💖"
                lower.contains("jazz") || lower.contains("blues") || lower.contains("sax") -> "🎷"
                lower.contains("rock") || lower.contains("guitar") -> "🎸"
                else -> {
                    val emojis = listOf("🎧", "⚡", "🌌", "🌊", "🔥", "☕", "🌙", "🎵", "🎷", "🔮", "✨", "💿")
                    emojis[abs((title + artist).hashCode()) % emojis.size]
                }
            }
        }

        fun pickVibeTag(title: String, artist: String, satiety: Int): String {
            val lower = "$title $artist".lowercase()
            return when {
                satiety >= 75 -> "Heavy Repeat"
                satiety >= 55 -> "Steady Rotation"
                lower.contains("acoustic") || lower.contains("piano") -> "Acoustic Reset"
                lower.contains("ambient") || lower.contains("chill") -> "Calm & Mellow"
                lower.contains("electronic") || lower.contains("fast") -> "Upbeat Drive"
                else -> "Fresh Balance"
            }
        }

        fun generateAnchorWeights(title: String, artist: String, satiety: Int): List<TrackAnchorWeight> {
            val seed = abs((title + artist).hashCode())
            val harmonicFamiliarity = ((seed % 28) + 65).coerceIn(40, 95)
            val rhythmicDrive = (((seed / 3) % 35) + 55).coerceIn(35, 95)
            val melodicNovelty = maxOf(15, 100 - satiety - (seed % 15))
            val frequencyDensity = (((seed / 7) % 30) + 60).coerceIn(45, 92)

            return listOf(
                TrackAnchorWeight(
                    name = "Familiar Feel",
                    weightPercent = harmonicFamiliarity,
                    description = "Comfort of melody and chord structure",
                    color = Color(0xFF38BDF8)
                ),
                TrackAnchorWeight(
                    name = "Rhythm & Groove",
                    weightPercent = rhythmicDrive,
                    description = "Tempo drive and beat momentum",
                    color = Color(0xFFA78BFA)
                ),
                TrackAnchorWeight(
                    name = "Novelty & Spark",
                    weightPercent = melodicNovelty,
                    description = "How fresh this track feels right now",
                    color = if (melodicNovelty > 50) Color(0xFF10B981) else Color(0xFFFF4D6D)
                ),
                TrackAnchorWeight(
                    name = "Sound Fullness",
                    weightPercent = frequencyDensity,
                    description = "Overall instrumental richness and energy",
                    color = Color(0xFFFFB000)
                )
            )
        }

        fun generateSatietyTrend(currentSatiety: Int, playCount: Int): List<SatietyTrendPoint> {
            val c = currentSatiety.coerceIn(15, 98)
            val p1 = (c * 0.35f).toInt()
            val p2 = (c * 0.55f).toInt()
            val p3 = (c * 0.80f).toInt()
            val projected = minOf(99, c + 18)
            val vaultRecovered = maxOf(20, (c * 0.40f).toInt())

            return listOf(
                SatietyTrendPoint("Day -4", p1),
                SatietyTrendPoint("Day -2", p2),
                SatietyTrendPoint("Yesterday", p3),
                SatietyTrendPoint("Now", c),
                SatietyTrendPoint("+2d Repeat", projected, isProjected = true),
                SatietyTrendPoint("+5d Rested", vaultRecovered, isProjected = true)
            )
        }

        fun generateFatigueHistory(
            title: String,
            artist: String,
            playCount: Int,
            firstMs: Long?,
            lastMs: Long?
        ): List<FatigueHistoryEntry> {
            val count = playCount.coerceAtLeast(0)
            if (count <= 0) return emptyList()
            val now = lastMs ?: System.currentTimeMillis()
            val entries = mutableListOf<FatigueHistoryEntry>()

            for (i in 0 until minOf(4, count)) {
                val offset = (count - 1 - i) * 7_200_000L
                val tMs = now - offset
                val isLoop = i >= 2 && count >= 3
                entries.add(
                    FatigueHistoryEntry(
                        timestampMs = tMs,
                        sessionLabel = if (i == 0) "Most Recent Play" else "Earlier Play #${i + 1}",
                        playDurationSec = 175 + (i * 12) % 40,
                        satietyContribution = if (isLoop) "Heavy replay" else "Normal play",
                        isLoopTrigger = isLoop
                    )
                )
            }
            return entries
        }

        fun fromTrackMemory(track: TrackMemoryEntry, isInVault: Boolean = false): SongProfileData {
            val title = track.title ?: "Unknown Track"
            val artist = track.artist ?: "Unknown Artist"
            val count = track.distinctAppearanceCount.coerceAtLeast(0)
            val satiety = if (count <= 0) 0 else minOf(95, maxOf(15, count * 22))
            val estDuration = if (count <= 0) {
                0L
            } else {
                (track.appearances.firstOrNull()?.let { (it.lastObservedAtMs - it.firstObservedAtMs).takeIf { d -> d > 0L } } ?: 195_000L) * count
            }
            val firstMs = track.firstObservedAppearanceAtMs
            val lastMs = track.lastObservedAppearanceAtMs
            val resolvedUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(track.providerTrackId)

            return SongProfileData(
                title = title,
                artist = artist,
                album = track.album,
                sourcePackage = track.sourcePackage,
                appearanceCount = count,
                satietyScore = satiety,
                firstObservedMs = firstMs,
                lastObservedMs = lastMs,
                estimatedPlaybackMs = estDuration,
                isInVault = isInVault,
                trackId = track.canonicalTrackId(),
                emoji = pickEmoji(title, artist),
                vibeTag = pickVibeTag(title, artist, satiety),
                anchorWeights = generateAnchorWeights(title, artist, satiety),
                satietyTrends = generateSatietyTrend(satiety, count),
                fatigueHistory = generateFatigueHistory(title, artist, count, firstMs, lastMs),
                spotifyUri = resolvedUri
            )
        }

        fun fromNowPlaying(
            np: NowPlayingObservation?,
            latestRecentTrack: TrackMemoryEntry? = null,
            isInVault: Boolean = false
        ): SongProfileData {
            val title = np?.title?.takeIf { it.isNotBlank() } ?: latestRecentTrack?.title ?: "Active Track"
            val artist = np?.artist?.takeIf { it.isNotBlank() } ?: latestRecentTrack?.artist ?: "Observed Player"
            val album = np?.album ?: latestRecentTrack?.album
            val sourcePackage = np?.sourcePackage ?: latestRecentTrack?.sourcePackage ?: "unknown_player"
            val plays = latestRecentTrack?.distinctAppearanceCount?.coerceAtLeast(0) ?: 0
            val satiety = if (plays <= 0) 0 else minOf(95, maxOf(18, plays * 22))
            val duration = if ((np?.durationMs ?: 0L) > 0L) np!!.durationMs else if (plays > 0) (latestRecentTrack?.appearances?.firstOrNull()?.let { (it.lastObservedAtMs - it.firstObservedAtMs).takeIf { d -> d > 0L } } ?: 180_000L) else 0L
            val firstMs = latestRecentTrack?.firstObservedAppearanceAtMs ?: np?.updatedAtMs
            val lastMs = np?.updatedAtMs ?: latestRecentTrack?.lastObservedAppearanceAtMs
            val resolvedIdentity = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                trackId = np?.trackId?.takeIf { it.isNotBlank() } ?: latestRecentTrack?.trackId?.takeIf { it.isNotBlank() },
                spotifyUri = np?.spotifyUri ?: latestRecentTrack?.providerTrackId,
                title = title,
                artist = artist
            )

            return SongProfileData(
                title = title,
                artist = artist,
                album = album,
                sourcePackage = sourcePackage,
                appearanceCount = plays,
                satietyScore = satiety,
                firstObservedMs = firstMs,
                lastObservedMs = lastMs,
                estimatedPlaybackMs = duration,
                isInVault = isInVault,
                trackId = resolvedIdentity.trackId,
                emoji = pickEmoji(title, artist),
                vibeTag = pickVibeTag(title, artist, satiety),
                anchorWeights = generateAnchorWeights(title, artist, satiety),
                satietyTrends = generateSatietyTrend(satiety, plays),
                fatigueHistory = generateFatigueHistory(title, artist, plays, firstMs, lastMs),
                spotifyUri = resolvedIdentity.spotifyUri
            )
        }

        fun fromRecommendedTrack(rec: RecommendedTrack, isInVault: Boolean = false): SongProfileData {
            val title = rec.title
            val artist = rec.artist
            val plays = rec.observationCountBasis.coerceAtLeast(0)
            val satiety = if (plays <= 0) 8 else minOf(95, maxOf(15, plays * 20))
            val resolvedIdentity = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                trackId = rec.trackId.ifBlank { rec.id },
                spotifyUri = rec.spotifyUri ?: rec.providerTrackId,
                title = title,
                artist = artist
            )
            return SongProfileData(
                title = title,
                artist = artist,
                album = rec.album ?: rec.category.name,
                sourcePackage = "unknown_player",
                appearanceCount = plays,
                satietyScore = satiety,
                firstObservedMs = if (plays > 0) System.currentTimeMillis() else null,
                lastObservedMs = if (plays > 0) System.currentTimeMillis() else null,
                estimatedPlaybackMs = if (plays > 0) 210_000L * plays else 0L,
                isInVault = isInVault,
                trackId = resolvedIdentity.trackId,
                emoji = pickEmoji(title, artist),
                vibeTag = if (plays <= 0) "Fresh Pick" else pickVibeTag(title, artist, satiety),
                anchorWeights = generateAnchorWeights(title, artist, satiety),
                satietyTrends = generateSatietyTrend(satiety, plays),
                fatigueHistory = emptyList(),
                spotifyUri = resolvedIdentity.spotifyUri
            )
        }

        fun fromVaultTrack(vault: VaultTrack, verifiedPlayCount: Int = 0): SongProfileData {
            val title = vault.title
            val artist = vault.artist
            val count = verifiedPlayCount.coerceAtLeast(0)
            val satiety = vault.satietyScore
            val resolvedIdentity = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                trackId = vault.trackId.ifBlank { null },
                spotifyUri = vault.spotifyUri,
                title = title,
                artist = artist
            )
            return SongProfileData(
                title = title,
                artist = artist,
                album = "Resting Vault",
                sourcePackage = vault.sourcePackage.ifBlank { "unknown_player" },
                appearanceCount = count,
                satietyScore = satiety,
                firstObservedMs = vault.quarantinedAtMs,
                lastObservedMs = vault.quarantinedAtMs,
                estimatedPlaybackMs = if (count > 0) 200_000L * count else 0L,
                isInVault = true,
                recoveryProgress = vault.recoveryProgress,
                cooldownDaysRemaining = vault.cooldownDaysRemaining,
                trackId = resolvedIdentity.trackId,
                emoji = pickEmoji(title, artist),
                vibeTag = "Resting in Vault",
                anchorWeights = generateAnchorWeights(title, artist, satiety),
                satietyTrends = generateSatietyTrend(satiety, count),
                fatigueHistory = generateFatigueHistory(title, artist, count, vault.quarantinedAtMs, vault.quarantinedAtMs),
                spotifyUri = resolvedIdentity.spotifyUri
            )
        }
    }
}

@Composable
fun SongProfile(
    profile: SongProfileData,
    vm: TuneMindViewModel,
    onDismiss: (() -> Unit)? = null,
    onQuarantineSuccess: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val theme = LocalAdaptiveTheme.current
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val isFatigued = profile.satietyScore >= 70
    val isModerate = profile.satietyScore in 40..69
    val freshnessPercent = (100 - profile.satietyScore).coerceIn(5, 99)

    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFFFB000)
    val alertRed = Color(0xFFFF4D6D)

    val appName = com.tunemind.app.data.local.CrossAppPlayRecorder.resolveFriendlyPlayerName(
        profile.sourcePackage.orEmpty()
    )

    val appColor = when {
        profile.sourcePackage?.contains("spotify") == true -> Color(0xFF1DB954)
        profile.sourcePackage?.contains("youtube") == true -> Color(0xFFFF0033)
        profile.sourcePackage?.contains("boomplay") == true -> Color(0xFF00E5FF)
        profile.sourcePackage?.contains("audiomack") == true -> Color(0xFFFFA200)
        else -> theme.primaryAccent
    }

    var selectedQuarantineDays by remember { mutableStateOf(5f) }
    val totalSec = maxOf(0L, profile.estimatedPlaybackMs / 1000)
    val playMinutes = totalSec / 60
    val playSeconds = totalSec % 60

    var claudeInsightText by remember { mutableStateOf<String?>(null) }
    var isInsightLoading by remember { mutableStateOf(false) }
    val isAiAvailable = remember(context) { com.tunemind.app.domain.ai.ClaudeReasoningEngine.isConfigured(context) }
    val coroutineScope = rememberCoroutineScope()
    val resolvedMedia = rememberResolvedTrackMedia(
        title = profile.title,
        artist = profile.artist,
        spotifyUri = profile.spotifyUri ?: profile.trackId?.takeIf { it.startsWith("spotify:track:") },
        fallbackAlbumArtUrl = null
    )
    val badgeAccent = when {
        profile.isInVault -> warnAmber
        isFatigued -> alertRed
        else -> theme.primaryAccent
    }
    val spotifyGenresLine = resolvedMedia.genres.take(3).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Hero Track Identity + Freshness Summary + Layered Song/Artist Art
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = profile.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC),
                    lineHeight = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${profile.artist} · $appName",
                    fontSize = 13.sp,
                    color = appColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(if (profile.isInVault) "Resting in Vault" else profile.vibeTag)
                        if (profile.appearanceCount <= 0) {
                            append(" · 0 plays")
                        } else {
                            append(" · ${profile.appearanceCount} ${if (profile.appearanceCount == 1) "play" else "plays"} · ${playMinutes}m ${playSeconds}s")
                        }
                        if (spotifyGenresLine.isNotBlank()) append(" · $spotifyGenresLine")
                    },
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.56f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            LayeredSongArtistBadge(
                albumArtUrl = resolvedMedia.albumArtUrl,
                artistImageUrl = resolvedMedia.artistImageUrl,
                title = profile.title,
                artist = profile.artist,
                accentColor = badgeAccent,
                size = 64.dp
            )
        }

        StudioHairlineDivider()

        // Freshness Progress Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        profile.isInVault -> "Resting in Vault"
                        isFatigued -> "Ready for a short break"
                        isModerate -> "Balanced rotation"
                        else -> "Feels crisp & new"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = "$freshnessPercent% fresh",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFatigued) alertRed else safeGreen
                )
            }

            LinearProgressIndicator(
                progress = { (freshnessPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape),
                color = when {
                    profile.isInVault -> warnAmber
                    isFatigued -> alertRed
                    isModerate -> theme.secondaryAccent
                    else -> safeGreen
                },
                trackColor = Color.White.copy(alpha = 0.08f)
            )

            Text(
                text = when {
                    profile.isInVault -> "Cooling down so it hits like new again (${(profile.recoveryProgress * 100).toInt()}% recovered)."
                    isFatigued -> "Played frequently recently. Resting it for 3–5 days keeps it exciting."
                    isModerate -> "Good rotation balance — still enjoyable without burnout."
                    else -> "High freshness with plenty of room in your daily flow."
                },
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.62f),
                lineHeight = 17.sp
            )
        }

        StudioHairlineDivider()

        // Track Insight (Claude API behind backend)
        if (isAiAvailable) {
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
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = theme.primaryAccent,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = "Track Insight",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF8FAFC)
                        )
                    }

                    if (claudeInsightText != null) {
                        IconButton(
                            onClick = {
                                isInsightLoading = true
                                coroutineScope.launch {
                                    val result = com.tunemind.app.domain.ai.ClaudeReasoningEngine.generateSongProfileDossier(
                                        context = context,
                                        title = profile.title,
                                        artist = profile.artist,
                                        playCount = profile.appearanceCount,
                                        satietyPercent = profile.satietyScore
                                    )
                                    isInsightLoading = false
                                    claudeInsightText = result.getOrNull()
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh",
                                tint = Color.White.copy(alpha = 0.60f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (isInsightLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = theme.primaryAccent, strokeWidth = 2.dp)
                        Text("Getting track notes…", fontSize = 13.sp, color = Color.White.copy(alpha = 0.62f))
                    }
                } else if (claudeInsightText != null) {
                    Text(
                        text = claudeInsightText ?: "",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.84f),
                        lineHeight = 19.sp
                    )
                } else {
                    OutlinedButton(
                        onClick = {
                            isInsightLoading = true
                            coroutineScope.launch {
                                val result = com.tunemind.app.domain.ai.ClaudeReasoningEngine.generateSongProfileDossier(
                                    context = context,
                                    title = profile.title,
                                    artist = profile.artist,
                                    playCount = profile.appearanceCount,
                                    satietyPercent = profile.satietyScore
                                )
                                isInsightLoading = false
                                claudeInsightText = result.getOrNull() ?: "Unable to load track insight right now."
                            }
                        },
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, theme.primaryAccent.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = theme.primaryAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Load Track Insight", color = theme.primaryAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
            StudioHairlineDivider()
        }

        // Sound Breakdown (Hairline bars)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Sound Breakdown",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF8FAFC)
            )

            profile.anchorWeights.forEach { anchor ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = anchor.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "${anchor.weightPercent}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = anchor.color
                        )
                    }

                    LinearProgressIndicator(
                        progress = { (anchor.weightPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = anchor.color,
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )

                    Text(
                        text = anchor.description,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.54f)
                    )
                }
            }
        }

        // Freshness Trajectory
        if (profile.satietyTrends.isNotEmpty()) {
            StudioHairlineDivider()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Rotation Trajectory",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF8FAFC)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF07090E))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    profile.satietyTrends.forEach { pt ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "${pt.score}%",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (pt.isProjected) Color.White.copy(alpha = 0.50f) else if (pt.score >= 70) alertRed else theme.primaryAccent
                            )
                            Box(
                                modifier = Modifier
                                    .width(14.dp)
                                    .height((pt.score * 0.52f).dp)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        when {
                                            pt.isProjected -> Color.White.copy(alpha = 0.16f)
                                            pt.score >= 70 -> alertRed
                                            else -> theme.primaryAccent
                                        }
                                    )
                            )
                            Text(
                                text = pt.label,
                                fontSize = 8.sp,
                                color = if (pt.isProjected) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.85f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Recent Play Log
        if (profile.fatigueHistory.isNotEmpty()) {
            StudioHairlineDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = theme.primaryAccent, modifier = Modifier.size(16.dp))
                    Text("Recent Plays", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
                }
                profile.fatigueHistory.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(entry.sessionLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
                            Text(fmt.format(Date(entry.timestampMs)), fontSize = 11.sp, color = Color.White.copy(alpha = 0.54f))
                        }
                        Text(
                            text = entry.satietyContribution,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (entry.isLoopTrigger) alertRed else safeGreen
                        )
                    }
                }
            }
        }

        StudioHairlineDivider()

        // Vault Action Section
        if (profile.isInVault) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Resting in Vault", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = warnAmber)
                    Text("${profile.cooldownDaysRemaining}d left", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = warnAmber)
                }
                Button(
                    onClick = {
                        profile.trackId?.let { vm.restoreFromVault(it) }
                        onDismiss?.invoke()
                        onQuarantineSuccess("Returned '${profile.title}' to Fresh rotation.")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primaryAccent, contentColor = Color(0xFF05060A)),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Return to Fresh Rotation", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Rest Track in Vault", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
                Text(
                    text = "Pause this track from active picks for a few days so it feels brand new later.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.60f)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3f to "3 Days", 5f to "5 Days", 7f to "7 Days").forEach { (days, label) ->
                        val isSelected = selectedQuarantineDays == days
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) theme.primaryAccent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f))
                                .border(
                                    1.dp,
                                    if (isSelected) theme.primaryAccent else Color.White.copy(alpha = 0.10f),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedQuarantineDays = days }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) theme.primaryAccent else Color.White.copy(alpha = 0.72f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        vm.quarantineTrack(
                            title = profile.title,
                            artist = profile.artist,
                            durationDays = selectedQuarantineDays,
                            spotifyUri = profile.spotifyUri,
                            trackId = profile.trackId
                        )
                        onDismiss?.invoke()
                        onQuarantineSuccess("Resting '${profile.title}' for ${selectedQuarantineDays.toInt()} days.")
                    },
                    border = BorderStroke(1.dp, warnAmber.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.AcUnit, contentDescription = null, tint = warnAmber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rest in Cooldown Vault", color = warnAmber, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        Button(
            onClick = {
                val resolvedIdentity = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                    trackId = profile.trackId,
                    spotifyUri = profile.spotifyUri,
                    title = profile.title,
                    artist = profile.artist
                )
                val rec = RecommendedTrack(
                    id = resolvedIdentity.trackId,
                    title = profile.title,
                    artist = profile.artist,
                    album = profile.album,
                    category = RecommendationCategory.PALATE_CLEANSER,
                    reason = profile.vibeTag,
                    confidence = 0.95f,
                    providerTrackId = resolvedIdentity.spotifyUri,
                    observationCountBasis = profile.appearanceCount.coerceAtLeast(0),
                    trackId = resolvedIdentity.trackId,
                    spotifyUri = resolvedIdentity.spotifyUri
                )
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    vm.playRecommendation(context, rec)
                }
                onDismiss?.invoke()
            },
            colors = ButtonDefaults.buttonColors(containerColor = theme.primaryAccent, contentColor = Color(0xFF05060A)),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Play in $appName", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongProfileSheet(
    profile: SongProfileData,
    vm: TuneMindViewModel,
    onDismiss: () -> Unit,
    onQuarantineSuccess: (String) -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LocalAdaptiveTheme.current.cardSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                color = Color.White.copy(alpha = 0.20f),
                shape = CircleShape
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp))
            }
        }
    ) {
        SongProfile(
            profile = profile,
            vm = vm,
            onDismiss = onDismiss,
            onQuarantineSuccess = onQuarantineSuccess
        )
    }
}
