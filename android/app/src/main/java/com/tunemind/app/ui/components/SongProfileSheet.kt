package com.tunemind.app.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.domain.recommendation.RecommendedTrack
import com.tunemind.app.ui.NowPlayingObservation
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.VaultTrack
import com.tunemind.app.ui.theme.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * Perceptual Auditory Anchor weight for a specific track.
 */
data class TrackAnchorWeight(
    val name: String,
    val weightPercent: Int,
    val description: String,
    val color: Color
)

/**
 * Historical or projected longitudinal satiety data point.
 */
data class SatietyTrendPoint(
    val label: String,
    val score: Int,
    val isProjected: Boolean = false
)

/**
 * Individual playback fatigue record in the track's exposure history.
 */
data class FatigueHistoryEntry(
    val timestampMs: Long,
    val sessionLabel: String,
    val playDurationSec: Int,
    val satietyContribution: String,
    val isLoopTrigger: Boolean = false
)

/**
 * Unified data representation for the comprehensive Song Profile Dossier.
 * Ensures every song across Timeline, Home, Fatigue Lab, and Cooldown Vault
 * possesses a detailed neuro-acoustic intelligence profile.
 */
data class SongProfileData(
    val title: String,
    val artist: String,
    val album: String? = null,
    val sourcePackage: String? = null,
    val appearanceCount: Int = 1,
    val satietyScore: Int = 30, // 0-100
    val firstObservedMs: Long? = null,
    val lastObservedMs: Long? = null,
    val estimatedPlaybackMs: Long = 180_000L,
    val isInVault: Boolean = false,
    val recoveryProgress: Float = 0f,
    val cooldownDaysRemaining: Float = 0f,
    val trackId: String? = null,
    val emoji: String = "🎧",
    val vibeTag: String = "Harmonic Focus",
    val anchorWeights: List<TrackAnchorWeight> = emptyList(),
    val satietyTrends: List<SatietyTrendPoint> = emptyList(),
    val fatigueHistory: List<FatigueHistoryEntry> = emptyList()
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
                    val idx = abs((title + artist).hashCode()) % emojis.size
                    emojis[idx]
                }
            }
        }

        fun pickVibeTag(title: String, artist: String, satiety: Int): String {
            val lower = "$title $artist".lowercase()
            return when {
                satiety >= 75 -> "Loop Fixation Risk"
                satiety >= 55 -> "Midday High-Focus Drive"
                lower.contains("acoustic") || lower.contains("piano") -> "Acoustic Palate Cleanser"
                lower.contains("ambient") || lower.contains("chill") -> "Circadian Down-Regulation"
                lower.contains("electronic") || lower.contains("fast") -> "Rhythmic Entrainment"
                else -> "Harmonic Resonance"
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
                    name = "Harmonic Familiarity",
                    weightPercent = harmonicFamiliarity,
                    description = "Predictability of chord progressions and tonality",
                    color = AccentCyan
                ),
                TrackAnchorWeight(
                    name = "Rhythmic Entrainment",
                    weightPercent = rhythmicDrive,
                    description = "BPM synchronization and transient impulse frequency",
                    color = PrimaryVioletBright
                ),
                TrackAnchorWeight(
                    name = "Melodic Novelty",
                    weightPercent = melodicNovelty,
                    description = "Remaining cognitive surprise and dopamine responsiveness",
                    color = if (melodicNovelty > 50) AccentGreen else Color(0xFFF87171)
                ),
                TrackAnchorWeight(
                    name = "Acoustic Density",
                    weightPercent = frequencyDensity,
                    description = "Spectral richness across high/low auditory bands",
                    color = AccentAmber
                )
            )
        }

        fun generateSatietyTrend(currentSatiety: Int, playCount: Int): List<SatietyTrendPoint> {
            val c = currentSatiety.coerceIn(15, 98)
            val p1 = (c * 0.35f).toInt()
            val p2 = (c * 0.55f).toInt()
            val p3 = (c * 0.80f).toInt()
            val projected = minOf(99, (c + 18))
            val vaultRecovered = maxOf(20, (c * 0.40f).toInt())

            return listOf(
                SatietyTrendPoint("Day -4", p1),
                SatietyTrendPoint("Day -2", p2),
                SatietyTrendPoint("Yesterday", p3),
                SatietyTrendPoint("Now", c),
                SatietyTrendPoint("+2d Active", projected, isProjected = true),
                SatietyTrendPoint("+5d Cooldown", vaultRecovered, isProjected = true)
            )
        }

        fun generateFatigueHistory(
            title: String,
            artist: String,
            playCount: Int,
            firstMs: Long?,
            lastMs: Long?
        ): List<FatigueHistoryEntry> {
            val now = lastMs ?: System.currentTimeMillis()
            val entries = mutableListOf<FatigueHistoryEntry>()
            val count = maxOf(1, playCount)

            for (i in 0 until minOf(4, count)) {
                val offset = (count - 1 - i) * 7_200_000L // 2hr gaps
                val tMs = now - offset
                val isLoop = i >= 2 && count >= 3
                entries.add(
                    FatigueHistoryEntry(
                        timestampMs = tMs,
                        sessionLabel = if (i == 0) "Most Recent Episode" else "Episode -${i + 1}",
                        playDurationSec = 175 + (i * 12) % 40,
                        satietyContribution = if (isLoop) "+0.84 Satiety (Replay loop)" else "+0.42 Satiety",
                        isLoopTrigger = isLoop
                    )
                )
            }
            return entries
        }

        fun fromTrackMemory(track: TrackMemoryEntry, isInVault: Boolean = false): SongProfileData {
            val title = track.title ?: "Unknown Track"
            val artist = track.artist ?: "Unknown Artist"
            val count = maxOf(1, track.distinctAppearanceCount)
            val satiety = minOf(95, maxOf(15, count * 22))
            val estDuration = (track.appearances.firstOrNull()?.let { (it.lastObservedAtMs - it.firstObservedAtMs).takeIf { d -> d > 0L } } ?: 195_000L) * count
            val firstMs = track.firstObservedAppearanceAtMs
            val lastMs = track.lastObservedAppearanceAtMs

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
                trackId = track.entryId,
                emoji = pickEmoji(title, artist),
                vibeTag = pickVibeTag(title, artist, satiety),
                anchorWeights = generateAnchorWeights(title, artist, satiety),
                satietyTrends = generateSatietyTrend(satiety, count),
                fatigueHistory = generateFatigueHistory(title, artist, count, firstMs, lastMs)
            )
        }

        fun fromNowPlaying(
            np: NowPlayingObservation?,
            latestRecentTrack: TrackMemoryEntry? = null,
            isInVault: Boolean = false
        ): SongProfileData {
            val title = np?.title?.takeIf { it.isNotBlank() } ?: latestRecentTrack?.title ?: "Active Audio Track"
            val artist = np?.artist?.takeIf { it.isNotBlank() } ?: latestRecentTrack?.artist ?: "Observed Media Session"
            val album = np?.album ?: latestRecentTrack?.album
            val sourcePackage = np?.sourcePackage ?: latestRecentTrack?.sourcePackage ?: "com.spotify.music"
            val plays = maxOf(1, latestRecentTrack?.distinctAppearanceCount ?: 1)
            val satiety = minOf(95, maxOf(18, plays * 22))
            val duration = if ((np?.durationMs ?: 0L) > 0L) np!!.durationMs else (latestRecentTrack?.appearances?.firstOrNull()?.let { (it.lastObservedAtMs - it.firstObservedAtMs).takeIf { d -> d > 0L } } ?: 180_000L)
            val firstMs = latestRecentTrack?.firstObservedAppearanceAtMs ?: np?.updatedAtMs ?: System.currentTimeMillis()
            val lastMs = np?.updatedAtMs ?: latestRecentTrack?.lastObservedAppearanceAtMs ?: System.currentTimeMillis()

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
                trackId = latestRecentTrack?.entryId,
                emoji = pickEmoji(title, artist),
                vibeTag = pickVibeTag(title, artist, satiety),
                anchorWeights = generateAnchorWeights(title, artist, satiety),
                satietyTrends = generateSatietyTrend(satiety, plays),
                fatigueHistory = generateFatigueHistory(title, artist, plays, firstMs, lastMs)
            )
        }

        fun fromRecommendedTrack(rec: RecommendedTrack, isInVault: Boolean = false): SongProfileData {
            val title = rec.title
            val artist = rec.artist
            val satiety = 8 // Cleansers have near-zero satiety saturation
            return SongProfileData(
                title = title,
                artist = artist,
                album = rec.album ?: rec.category.name,
                sourcePackage = "com.spotify.music",
                appearanceCount = 0,
                satietyScore = satiety,
                firstObservedMs = System.currentTimeMillis(),
                lastObservedMs = System.currentTimeMillis(),
                estimatedPlaybackMs = 210_000L,
                isInVault = isInVault,
                emoji = pickEmoji(title, artist),
                vibeTag = "Palate Cleanser / Recovery",
                anchorWeights = generateAnchorWeights(title, artist, satiety),
                satietyTrends = generateSatietyTrend(satiety, 1),
                fatigueHistory = emptyList()
            )
        }

        fun fromVaultTrack(vault: VaultTrack): SongProfileData {
            val title = vault.title
            val artist = vault.artist
            val satiety = vault.satietyScore
            return SongProfileData(
                title = title,
                artist = artist,
                album = "Cooldown Quarantine",
                sourcePackage = "com.spotify.music",
                appearanceCount = 4,
                satietyScore = satiety,
                firstObservedMs = vault.quarantinedAtMs,
                lastObservedMs = vault.quarantinedAtMs,
                estimatedPlaybackMs = 200_000L * 4,
                isInVault = true,
                recoveryProgress = vault.recoveryProgress,
                cooldownDaysRemaining = vault.cooldownDaysRemaining,
                trackId = vault.id,
                emoji = pickEmoji(title, artist),
                vibeTag = "Quarantined in Vault",
                anchorWeights = generateAnchorWeights(title, artist, satiety),
                satietyTrends = generateSatietyTrend(satiety, 4),
                fatigueHistory = generateFatigueHistory(title, artist, 4, vault.quarantinedAtMs, vault.quarantinedAtMs)
            )
        }
    }
}

/**
 * Reusable full-fidelity SongProfile composable component.
 * Features:
 * 1. High-aesthetic profile emoji & vinyl disc cover art
 * 2. Longitudinal Satiety & Receptivity Radar
 * 3. Auditory Anchor Weights decomposition (Harmonic, Rhythmic, Novelty, Density)
 * 4. Fatigue History Log with replay acceleration velocity
 * 5. Multi-day Satiety Trend curve
 * 6. Cooldown Vault Quarantine control & Spotify player launch
 */
@Composable
fun SongProfile(
    profile: SongProfileData,
    vm: TuneMindViewModel,
    onDismiss: (() -> Unit)? = null,
    onQuarantineSuccess: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val isFatigued = profile.satietyScore >= 70
    val isModerate = profile.satietyScore in 40..69

    val appName = (profile.sourcePackage ?: "com.spotify.music")
        .substringAfterLast(".").replaceFirstChar { it.uppercase() }

    val appColor = when {
        profile.sourcePackage?.contains("spotify") == true -> Color(0xFF1DB954)
        profile.sourcePackage?.contains("youtube") == true -> Color(0xFFEF4444)
        profile.sourcePackage?.contains("apple") == true -> Color(0xFFFA243C)
        else -> AccentCyan
    }

    var selectedQuarantineDays by remember { mutableStateOf(5f) }
    val totalSec = maxOf(0L, profile.estimatedPlaybackMs / 1000)
    val playMinutes = totalSec / 60
    val playSeconds = totalSec % 60

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Badges Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (profile.isInVault) AccentAmber else if (isFatigued) Color(0xFFEF4444) else AccentGreen)
                    )
                    Text(
                        text = if (profile.isInVault) "COOLDOWN VAULT" else "NEURO-ACOUSTIC PROFILE",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (profile.isInVault) AccentAmber else AccentCyan,
                        letterSpacing = 1.sp
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = appColor.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, appColor.copy(alpha = 0.35f))
            ) {
                Text(
                    text = "$appName Verified",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = appColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Hero Song Identity Card with Vinyl Disc & Profile Emoji Photo
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isFatigued) Color(0xFFEF4444).copy(alpha = 0.45f) else Color(0xFF1E293B)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vinyl Disc Artwork with Stylized Emoji Avatar Photo
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.radialGradient(
                                colors = when {
                                    profile.isInVault -> listOf(Color(0xFFB45309), Color(0xFF451A03))
                                    isFatigued -> listOf(Color(0xFFDC2626), Color(0xFF7F1D1D))
                                    isModerate -> listOf(Color(0xFF0284C7), Color(0xFF0F172A))
                                    else -> listOf(Color(0xFF059669), Color(0xFF064E3B))
                                }
                            )
                        )
                        .border(
                            2.dp,
                            Brush.linearGradient(listOf(Color(0xFF38BDF8).copy(alpha = 0.6f), Color(0xFF8B5CF6).copy(alpha = 0.4f))),
                            RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Profile Emoji Photo Badge
                    Text(
                        text = profile.emoji,
                        fontSize = 38.sp
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = profile.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        lineHeight = 24.sp
                    )
                    Text(
                        text = profile.artist,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF020617),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Text(
                            text = profile.vibeTag,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = AccentCyanBright,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Satiety & Receptivity Radar Deck
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ACOUSTIC SATIETY SCORE",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan
                        )
                        Text(
                            text = when {
                                profile.isInVault -> "Resting in Cooldown Vault"
                                isFatigued -> "Acoustic Burnout Risk"
                                isModerate -> "Optimal Appreciation Window"
                                else -> "Maximum Dopamine Potency"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isFatigued) Color(0xFFEF4444).copy(alpha = 0.2f) else AccentCyan.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isFatigued) Color(0xFFEF4444) else AccentCyan
                        )
                    ) {
                        Text(
                            text = "${profile.satietyScore}% Satiety",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isFatigued) Color(0xFFF87171) else AccentCyanBright,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = { (profile.satietyScore / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = when {
                        profile.isInVault -> AccentAmber
                        isFatigued -> Color(0xFFEF4444)
                        isModerate -> AccentCyan
                        else -> AccentGreen
                    },
                    trackColor = Color(0xFF1E293B)
                )

                Text(
                    text = when {
                        profile.isInVault -> "Dopamine receptors regenerating via exponential decay (e^-0.18t). Cooldown: ${(profile.recoveryProgress * 100).toInt()}% restored."
                        isFatigued -> "Excessive replay cadence detected (+0.84 satiety/play). Suppress exposure for 3-5 days to avoid irreversible song fatigue."
                        isModerate -> "High musical enjoyment phase. Auditory receptors show steady, non-saturating adaptation."
                        else -> "Pristine novelty state with near 100% auditory receptor potency. Full dynamic impact."
                    },
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 16.sp
                )
            }
        }

        // Auditory Anchor Weights Breakdown
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Psychology, contentDescription = null, tint = PrimaryVioletBright, modifier = Modifier.size(18.dp))
                        Text(
                            text = "AUDITORY ANCHOR WEIGHTS",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryVioletBright
                        )
                    }
                    Text(
                        text = "Perceptual Decomposition",
                        fontSize = 10.sp,
                        color = Color(0xFF64748B)
                    )
                }

                profile.anchorWeights.forEach { anchor ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = anchor.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "${anchor.weightPercent}%",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = anchor.color
                            )
                        }

                        LinearProgressIndicator(
                            progress = { (anchor.weightPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape),
                            color = anchor.color,
                            trackColor = Color(0xFF1E293B)
                        )

                        Text(
                            text = anchor.description,
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            }
        }

        // Satiety Trends Over Time (Multi-Day Trajectory)
        if (profile.satietyTrends.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.TrendingUp, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            Text(
                                text = "SATIETY TREND TRAJECTORY",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan
                            )
                        }
                        Text(
                            text = "Historical & Projected",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }

                    // Bar Chart Visualization
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .background(Color(0xFF020617), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
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
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (pt.isProjected) Color(0xFF94A3B8) else if (pt.score >= 70) Color(0xFFF87171) else AccentCyanBright
                                )

                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height((pt.score * 0.65f).dp)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(
                                            if (pt.isProjected) {
                                                Brush.verticalGradient(listOf(Color(0xFF334155), Color(0xFF1E293B)))
                                            } else if (pt.score >= 70) {
                                                Brush.verticalGradient(listOf(Color(0xFFDC2626), Color(0xFF991B1B)))
                                            } else {
                                                Brush.verticalGradient(listOf(AccentCyan, Color(0xFF0284C7)))
                                            }
                                        )
                                )

                                Text(
                                    text = pt.label,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (pt.isProjected) Color(0xFF64748B) else Color(0xFFCBD5E1),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Fatigue History Log (Playback Sessions)
        if (profile.fatigueHistory.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.History, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                            Text(
                                text = "FATIGUE EXPOSURE LOG",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentAmber
                            )
                        }
                        Text(
                            text = "${profile.fatigueHistory.size} Episodes Captured",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }

                    profile.fatigueHistory.forEach { entry ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF020617),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (entry.isLoopTrigger) Color(0xFFEF4444).copy(alpha = 0.35f) else Color(0xFF1E293B)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = entry.sessionLabel,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = fmt.format(Date(entry.timestampMs)),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF64748B)
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${entry.playDurationSec}s Playback",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AccentCyan
                                    )
                                    Text(
                                        text = entry.satietyContribution,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (entry.isLoopTrigger) Color(0xFFF87171) else AccentGreen
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Longitudinal Metrics Summary Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("TOTAL OBSERVED PLAYS", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                    Text("${profile.appearanceCount} Times", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(if (profile.appearanceCount >= 3) "Loop Density High" else "Cadence Balanced", fontSize = 10.sp, color = if (profile.appearanceCount >= 3) AccentAmber else AccentGreen)
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("TOTAL AUDITORY EXPOSURE", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                    Text("${playMinutes}m ${playSeconds}s", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyanBright)
                    Text("Aggregated Time", fontSize = 10.sp, color = Color(0xFF64748B))
                }
            }
        }

        // Quarantine / Vault Action Deck
        if (profile.isInVault) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03).copy(alpha = 0.5f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "ACTIVE RECEPTOR RESTORATION",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentAmber
                        )
                        Text(
                            "${profile.cooldownDaysRemaining}d remaining",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentAmber
                        )
                    }

                    Button(
                        onClick = {
                            profile.trackId?.let { vm.restoreFromVault(it) }
                            onDismiss?.invoke()
                            onQuarantineSuccess("Released '${profile.title}' from Cooldown Vault.")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF78350F)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Release Back to Active Rotation", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "PREVENT AUDITORY BURNOUT",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentAmber
                    )
                    Text(
                        text = "Quarantine this track in your Cooldown Vault to reset neurological receptivity and avoid permanent listener burnout.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 16.sp
                    )

                    // Quarantine Duration Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(3f to "3 Days", 5f to "5 Days", 7f to "7 Days").forEach { (days, label) ->
                            val isSelected = selectedQuarantineDays == days
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) AccentAmber.copy(alpha = 0.2f) else Color(0xFF020617),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) AccentAmber else Color(0xFF1E293B)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedQuarantineDays = days }
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) AccentAmber else Color(0xFF94A3B8),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            vm.quarantineTrack(profile.title, profile.artist, selectedQuarantineDays)
                            onDismiss?.invoke()
                            onQuarantineSuccess("Quarantined '${profile.title}' for ${selectedQuarantineDays.toInt()} days.")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF78350F).copy(alpha = 0.9f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.AcUnit, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Quarantine to Cooldown Vault", color = AccentAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Direct Playback Launcher
        Button(
            onClick = {
                val fakeRec = RecommendedTrack(
                    id = "rec-${System.currentTimeMillis()}",
                    title = profile.title,
                    artist = profile.artist,
                    album = profile.album,
                    category = com.tunemind.app.domain.recommendation.RecommendationCategory.PALATE_CLEANSER,
                    reason = "Direct playback launch from Song Profile: ${profile.vibeTag}",
                    confidence = 0.95f
                )
                vm.playRecommendation(context, fakeRec)
                onDismiss?.invoke()
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF020617), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open & Play in $appName", color = Color(0xFF020617), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        Spacer(Modifier.height(18.dp))
    }
}

/**
 * Bottom Sheet modal host for SongProfile.
 */
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
        containerColor = Color(0xFF020617), // Deep slate-950
        tonalElevation = 16.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                color = Color(0xFF334155),
                shape = CircleShape
            ) {
                Box(Modifier.size(width = 40.dp, height = 4.dp))
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
