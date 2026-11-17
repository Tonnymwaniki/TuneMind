package com.tunemind.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.data.ingestion.SpotifyNewReleaseItem
import com.tunemind.app.domain.ai.ForYouLearningSummary
import com.tunemind.app.domain.ai.NoonDropSchedule
import com.tunemind.app.domain.recommendation.AiRecommendationEntity
import com.tunemind.app.ui.components.AnimatedEqualizerBars
import com.tunemind.app.ui.components.LayeredSongArtistBadge
import com.tunemind.app.ui.components.NeuralSonicActionButton
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.TuneMindTrackCard
import com.tunemind.app.ui.components.rememberResolvedTrackMedia
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import kotlinx.coroutines.delay

/**
 * Custom animated AI Neural Emblem rendered via Compose Canvas.
 */
@Composable
fun AiNeuralCoreEmblem(
    accentPrimary: Color,
    accentSecondary: Color,
    isThinking: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "ai_core_emblem")
    val orbitDeg by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isThinking) 2200 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ai_orbit"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.74f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ai_pulse"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension / 2f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentPrimary.copy(alpha = 0.28f * pulse),
                    accentSecondary.copy(alpha = 0.12f * pulse),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = radius
            ),
            radius = radius
        )

        rotate(degrees = orbitDeg, pivot = Offset(cx, cy)) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        accentPrimary,
                        accentSecondary,
                        Color(0xFFF59E0B),
                        accentPrimary.copy(alpha = 0.12f)
                    )
                ),
                startAngle = 0f,
                sweepAngle = 290f,
                useCenter = false,
                topLeft = Offset(cx - radius * 0.78f, cy - radius * 0.78f),
                size = Size(radius * 1.56f, radius * 1.56f),
                style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        rotate(degrees = -orbitDeg * 0.65f, pivot = Offset(cx, cy)) {
            drawArc(
                color = accentSecondary.copy(alpha = 0.58f),
                startAngle = 45f,
                sweepAngle = 210f,
                useCenter = false,
                topLeft = Offset(cx - radius * 0.56f, cy - radius * 0.56f),
                size = Size(radius * 1.12f, radius * 1.12f),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        val starR = radius * 0.34f * (0.90f + 0.10f * pulse)
        val innerR = starR * 0.30f
        val sparkPath = Path().apply {
            moveTo(cx, cy - starR)
            lineTo(cx + innerR, cy - innerR)
            lineTo(cx + starR, cy)
            lineTo(cx + innerR, cy + innerR)
            lineTo(cx, cy + starR)
            lineTo(cx - innerR, cy + innerR)
            lineTo(cx - starR, cy)
            lineTo(cx - innerR, cy - innerR)
            close()
        }
        drawPath(
            path = sparkPath,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFF8FAFC), accentPrimary)
            )
        )
    }
}

/**
 * Populates the LazyColumn inside DopamineVaultScreen's "For You" tab with:
 * 1. Compact Daily Discovery Hero + 12:00 PM schedule ticker + Export CTA
 * 2. 1 Unified Wildcard Showcase Card holding all 3 completely new-genre songs together
 * 3. 12 Unheard Picks grouped across all active genres you listen to
 * 4. Optional Spotify New Releases + Self-Learning Taste Insights footer
 */
fun LazyListScope.forYouDiscoveryItems(
    activeRecs: List<AiRecommendationEntity>,
    learningState: ForYouLearningSummary,
    isDiscovering: Boolean,
    totalWildcardsInCycle: Int = 0,
    lockedWildcardsCount: Int = 0,
    newReleases: List<SpotifyNewReleaseItem> = emptyList(),
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onRefreshForYou: () -> Unit,
    onPlayTrack: (AiRecommendationEntity) -> Unit,
    onPreviewTrack: (AiRecommendationEntity) -> Unit,
    onSaveTrack: (AiRecommendationEntity, Boolean) -> Unit,
    onDismissTrack: (AiRecommendationEntity) -> Unit,
    onInspectTrack: (AiRecommendationEntity) -> Unit,
    onExportPlaylist: ((List<AiRecommendationEntity>) -> Unit)? = null,
    onPlayNewRelease: ((SpotifyNewReleaseItem) -> Unit)? = null
) {
    val wildcards = activeRecs
        .filter { it.isWildcard || it.subTier.equals("GLOBAL_WILDCARD", ignoreCase = true) }
        .take(3)

    val genrePicks = activeRecs
        .filter { !(it.isWildcard || it.subTier.equals("GLOBAL_WILDCARD", ignoreCase = true)) }
        .take(12)

    val orderedGenreGroups: List<Pair<String, List<AiRecommendationEntity>>> = run {
        val map = LinkedHashMap<String, MutableList<AiRecommendationEntity>>()
        genrePicks.forEach { rec ->
            val key = rec.genreTag.trim().ifBlank { "Tailored Mix" }
            map.getOrPut(key) { mutableListOf() }.add(rec)
        }
        map.entries.map { it.key to it.value }
    }

    // 1. Studio Daily Discovery Header Card
    item(key = "foryou_ai_hero") {
        ForYouAiHeroCard(
            learningState = learningState,
            genrePickCount = genrePicks.size,
            genreCount = orderedGenreGroups.size,
            wildcardCount = wildcards.size,
            isDiscovering = isDiscovering,
            onRefresh = onRefreshForYou,
            onExportPlaylist = if (activeRecs.isNotEmpty() && onExportPlaylist != null) {
                { onExportPlaylist(activeRecs) }
            } else null
        )
        Spacer(modifier = Modifier.height(14.dp))
    }

    // Empty / Background Calibration State
    if (activeRecs.isEmpty()) {
        item(key = "foryou_empty_state") {
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = Color(0xFF38BDF8),
                cornerRadius = 20.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isDiscovering) {
                        CircularProgressIndicator(
                            color = Color(0xFF38BDF8),
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = "Curating 12 genre picks + 3 new-genre wildcards...",
                            color = Color(0xFFF8FAFC),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = "Today's 12:00 PM Drop is ready to sync",
                            color = Color(0xFFF8FAFC),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "12 fresh unheard tracks across your genres plus 3 songs from new global genres, calibrated from your listening behavior.",
                            color = Color.White.copy(alpha = 0.64f),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF38BDF8).copy(alpha = 0.16f))
                                .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .clickable { onRefreshForYou() }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF67E8F9),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Sync Today's 15 Picks",
                                color = Color(0xFFF8FAFC),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    // 2. Unified 1-Wildcard Showcase Card holding all 3 Completely New-Genre Songs Together
    if (wildcards.isNotEmpty()) {
        item(key = "foryou_wildcard_trio") {
            WildcardTrioShowcaseCard(
                wildcards = wildcards,
                totalInCycle = maxOf(wildcards.size, totalWildcardsInCycle),
                lockedCount = lockedWildcardsCount,
                isTrackCurrentlyPlaying = isTrackCurrentlyPlaying,
                onPlayTrack = onPlayTrack,
                onPreviewTrack = onPreviewTrack,
                onSaveTrack = onSaveTrack,
                onDismissTrack = onDismissTrack,
                onInspectTrack = onInspectTrack
            )
            Spacer(modifier = Modifier.height(18.dp))
        }
    }

    // 3. 12 Unheard Songs Grouped Across All Active Genres
    if (orderedGenreGroups.isNotEmpty()) {
        item(key = "foryou_genre_section_title") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Across Your Genres",
                        color = LocalAdaptiveTheme.current.primaryAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Unheard daily picks never repeated from yesterday",
                        color = Color.White.copy(alpha = 0.60f),
                        fontSize = 12.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${genrePicks.size} picks · ${orderedGenreGroups.size} genres",
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        orderedGenreGroups.forEach { (genreName, tracks) ->
            item(key = "foryou_genre_group_${genreName.lowercase()}") {
                val topMatch = tracks.maxOfOrNull { it.matchScorePercent } ?: 90
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(LocalAdaptiveTheme.current.primaryAccent)
                            )
                            Text(
                                text = genreName,
                                color = Color(0xFFF8FAFC),
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "· ${tracks.size}",
                                color = Color.White.copy(alpha = 0.56f),
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "$topMatch% fit",
                            color = LocalAdaptiveTheme.current.secondaryAccent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    tracks.forEach { rec ->
                        ForYouGenreTrackCard(
                            rec = rec,
                            isPlaying = isTrackCurrentlyPlaying(rec.title, rec.artist, rec.spotifyUri),
                            onPlayClick = { onPlayTrack(rec) },
                            onPreviewClick = { onPreviewTrack(rec) },
                            onSaveToggle = { onSaveTrack(rec, !rec.isSaved) },
                            onDismissClick = { onDismissTrack(rec) },
                            onCardClick = { onInspectTrack(rec) }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }

    // 4. Optional New Releases Radar
    if (newReleases.isNotEmpty()) {
        item(key = "foryou_new_releases_section") {
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioHairlineDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "New Releases",
                        color = Color(0xFF1DB954),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${newReleases.size} drops",
                        color = Color(0xFF1DB954),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                newReleases.take(6).forEach { rel ->
                    val canonicalId = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalTrackId(
                        spotifyUri = rel.spotifyUri,
                        title = rel.title,
                        artist = rel.artistName
                    )
                    TuneMindTrackCard(
                        title = rel.title,
                        artist = rel.artistName,
                        subtitle = "${rel.albumType.replaceFirstChar { it.uppercase() }} · ${rel.releaseDate}",
                        playCount = 0,
                        showUnplayedLabel = true,
                        zeroPlayLabel = "New",
                        isCurrentlyPlaying = isTrackCurrentlyPlaying(rel.title, rel.artistName, rel.spotifyUri),
                        accentColor = Color(0xFF1DB954),
                        spotifyUri = rel.spotifyUri,
                        albumArtUrl = rel.imageUrl,
                        trackId = canonicalId,
                        onPlayClick = { onPlayNewRelease?.invoke(rel) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // 5. Self-Learning Taste Evolution Footer
    item(key = "foryou_learned_memory") {
        Spacer(modifier = Modifier.height(4.dp))
        AiMemoryEvolutionCard(learningState = learningState)
    }
}

@Composable
private fun ForYouAiHeroCard(
    learningState: ForYouLearningSummary,
    genrePickCount: Int,
    genreCount: Int,
    wildcardCount: Int,
    isDiscovering: Boolean,
    onRefresh: () -> Unit,
    onExportPlaylist: (() -> Unit)? = null
) {
    val theme = LocalAdaptiveTheme.current
    val aiCyan = theme.primaryAccent
    val aiViolet = theme.secondaryAccent
    var countdownText by remember { mutableStateOf(NoonDropSchedule.formatCountdownToNoon()) }
    val tzShort = remember { NoonDropSchedule.getLocalTimeZoneShortLabel() }

    LaunchedEffect(Unit) {
        while (true) {
            countdownText = NoonDropSchedule.formatCountdownToNoon()
            delay(30_000L)
        }
    }

    SoftGlassCard(
        modifier = Modifier.fillMaxWidth(),
        accentTint = aiCyan,
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(15.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AiNeuralCoreEmblem(
                    accentPrimary = aiCyan,
                    accentSecondary = aiViolet,
                    isThinking = isDiscovering,
                    modifier = Modifier.size(40.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = learningState.evolutionLabel.ifBlank { "Stage 1 · Taste Engine" },
                            color = Color(0xFFF8FAFC),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(aiCyan.copy(alpha = 0.16f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${learningState.intelligenceScorePercent}%",
                                color = aiCyan,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (genrePickCount + wildcardCount > 0) {
                            "$genrePickCount genre picks ($genreCount genres) + $wildcardCount wildcards"
                        } else {
                            "Daily 12:00 PM drop ($tzShort) · Next in $countdownText"
                        },
                        color = Color.White.copy(alpha = 0.64f),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (onExportPlaylist != null) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1DB954).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFF1DB954).copy(alpha = 0.40f), CircleShape)
                            .clickable { onExportPlaylist() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlaylistAdd,
                            contentDescription = "Save Drop as Spotify Playlist",
                            tint = Color(0xFF1DB954),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(aiCyan.copy(alpha = 0.15f))
                        .border(1.dp, aiCyan.copy(alpha = 0.42f), CircleShape)
                        .clickable(enabled = !isDiscovering) { onRefresh() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isDiscovering) {
                        CircularProgressIndicator(
                            color = aiCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(15.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh Daily Drop",
                            tint = aiCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((learningState.intelligenceScorePercent / 100f).coerceIn(0.18f, 1.0f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            brush = Brush.horizontalGradient(colors = listOf(aiCyan, aiViolet))
                        )
                )
            }

            Spacer(modifier = Modifier.height(9.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Auto-syncs 12:00 PM ($tzShort) · $countdownText",
                        color = Color(0xFFFDE68A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "${learningState.learningIterations} signals · ${learningState.hitRatePercent}% hit",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Unified 1-Wildcard Showcase Card presenting all 3 completely new-genre songs together cleanly.
 */
@Composable
private fun WildcardTrioShowcaseCard(
    wildcards: List<AiRecommendationEntity>,
    totalInCycle: Int,
    lockedCount: Int,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onPlayTrack: (AiRecommendationEntity) -> Unit,
    onPreviewTrack: (AiRecommendationEntity) -> Unit,
    onSaveTrack: (AiRecommendationEntity, Boolean) -> Unit,
    onDismissTrack: (AiRecommendationEntity) -> Unit,
    onInspectTrack: (AiRecommendationEntity) -> Unit
) {
    val amberGold = Color(0xFFF59E0B)
    val theme = LocalAdaptiveTheme.current

    SoftGlassCard(
        modifier = Modifier.fillMaxWidth(),
        accentTint = amberGold,
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(amberGold.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Explore,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "WILDCARD · ${wildcards.size} NEW GENRES",
                            color = Color(0xFFFBBF24),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )
                        Text(
                            text = "Uncharted global genres outside your daily lanes",
                            color = Color.White.copy(alpha = 0.60f),
                            fontSize = 11.5.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(amberGold.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "$lockedCount/${totalInCycle.coerceAtLeast(wildcards.size)} saved",
                        color = Color(0xFFFDE68A),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            wildcards.forEachIndexed { idx, rec ->
                val isPlaying = isTrackCurrentlyPlaying(rec.title, rec.artist, rec.spotifyUri)
                val rowAccent = if (isPlaying) Color(0xFF10B981) else amberGold
                val resolvedMedia = rememberResolvedTrackMedia(
                    title = rec.title,
                    artist = rec.artist,
                    spotifyUri = rec.spotifyUri,
                    fallbackAlbumArtUrl = null
                )

                if (idx > 0) {
                    StudioHairlineDivider()
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onInspectTrack(rec) }
                        .padding(vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(rowAccent.copy(alpha = 0.16f))
                                .border(1.dp, rowAccent.copy(alpha = 0.45f), CircleShape)
                                .clickable { onPlayTrack(rec) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isPlaying) {
                                AnimatedEqualizerBars(color = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Play Wildcard",
                                    tint = Color(0xFFFDE68A),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = rec.title,
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(amberGold.copy(alpha = 0.18f))
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = rec.genreTag.ifBlank { "New Genre" },
                                        color = Color(0xFFFDE68A),
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                            Text(
                                text = rec.artist,
                                color = Color.White.copy(alpha = 0.66f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        LayeredSongArtistBadge(
                            albumArtUrl = resolvedMedia.albumArtUrl,
                            artistImageUrl = resolvedMedia.artistImageUrl,
                            title = rec.title,
                            artist = rec.artist,
                            accentColor = rowAccent,
                            size = 40.dp
                        )
                    }

                    if (rec.reason.isNotBlank()) {
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            text = rec.reason,
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.5.sp,
                            lineHeight = 15.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!rec.previewUrl.isNullOrBlank()) {
                            SmallActionChip(
                                icon = Icons.Rounded.GraphicEq,
                                label = "Preview",
                                tint = Color(0xFF38BDF8),
                                bg = Color(0xFF38BDF8).copy(alpha = 0.14f),
                                onClick = { onPreviewTrack(rec) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        SmallActionChip(
                            icon = if (rec.isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            label = if (rec.isSaved) "Locked" else "Lock",
                            tint = if (rec.isSaved) theme.primaryAccent else Color.White.copy(alpha = 0.72f),
                            bg = if (rec.isSaved) theme.primaryAccent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f),
                            onClick = { onSaveTrack(rec, !rec.isSaved) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SmallActionChip(
                            icon = Icons.Rounded.Close,
                            label = "Pass",
                            tint = Color.White.copy(alpha = 0.56f),
                            bg = Color.White.copy(alpha = 0.06f),
                            onClick = { onDismissTrack(rec) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    bg: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ForYouGenreTrackCard(
    rec: AiRecommendationEntity,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onPreviewClick: () -> Unit,
    onSaveToggle: () -> Unit,
    onDismissClick: () -> Unit,
    onCardClick: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val accent = if (isPlaying) Color(0xFF10B981) else theme.primaryAccent
    val cardShape = RoundedCornerShape(16.dp)
    val resolvedMedia = rememberResolvedTrackMedia(
        title = rec.title,
        artist = rec.artist,
        spotifyUri = rec.spotifyUri,
        fallbackAlbumArtUrl = null
    )
    val statusBadge = when {
        rec.completedListenCount > 0 -> "Completed"
        rec.wasPlayed -> "Played"
        else -> "New"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(theme.elevatedSurface.copy(alpha = 0.62f))
            .border(
                width = 1.dp,
                color = if (isPlaying) Color(0xFF10B981).copy(alpha = 0.55f) else theme.border.copy(alpha = 0.40f),
                shape = cardShape
            )
            .clickable { onCardClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f))
                        .border(1.dp, accent.copy(alpha = 0.45f), CircleShape)
                        .clickable { onPlayClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying) {
                        AnimatedEqualizerBars(color = Color(0xFF10B981), modifier = Modifier.size(17.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play Track",
                            tint = Color(0xFFF8FAFC),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = rec.title,
                            color = Color(0xFFF8FAFC),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(theme.secondaryAccent.copy(alpha = 0.18f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${rec.matchScorePercent}%",
                                color = theme.secondaryAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = "${rec.artist} · $statusBadge",
                        color = Color.White.copy(alpha = 0.66f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                NeuralSonicActionButton(
                    accentColor = accent,
                    secondaryColor = theme.secondaryAccent,
                    onClick = onCardClick
                )

                LayeredSongArtistBadge(
                    albumArtUrl = resolvedMedia.albumArtUrl,
                    artistImageUrl = resolvedMedia.artistImageUrl,
                    title = rec.title,
                    artist = rec.artist,
                    accentColor = accent,
                    size = 44.dp
                )
            }

            if (rec.reason.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = rec.reason,
                    color = Color.White.copy(alpha = 0.64f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!rec.previewUrl.isNullOrBlank()) {
                    SmallActionChip(
                        icon = Icons.Rounded.GraphicEq,
                        label = "Preview",
                        tint = Color(0xFF38BDF8),
                        bg = Color(0xFF38BDF8).copy(alpha = 0.14f),
                        onClick = onPreviewClick
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                SmallActionChip(
                    icon = if (rec.isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    label = if (rec.isSaved) "Saved" else "Save",
                    tint = if (rec.isSaved) theme.primaryAccent else Color.White.copy(alpha = 0.72f),
                    bg = if (rec.isSaved) theme.primaryAccent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f),
                    onClick = onSaveToggle
                )
                Spacer(modifier = Modifier.width(6.dp))

                SmallActionChip(
                    icon = Icons.Rounded.Close,
                    label = "Pass",
                    tint = Color.White.copy(alpha = 0.56f),
                    bg = Color.White.copy(alpha = 0.06f),
                    onClick = onDismissClick
                )
            }
        }
    }
}

@Composable
private fun AiMemoryEvolutionCard(learningState: ForYouLearningSummary) {
    val theme = LocalAdaptiveTheme.current
    val aiCyan = theme.primaryAccent

    SoftGlassCard(
        modifier = Modifier.fillMaxWidth(),
        accentTint = aiCyan,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = aiCyan,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Self-Learning Taste Model",
                    color = Color(0xFFFAFAFA),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (learningState.narrative.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = learningState.narrative,
                    color = Color(0xFFF8FAFC).copy(alpha = 0.86f),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }

            if (learningState.risingGenres.isNotEmpty() || learningState.coolingGenres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (learningState.risingGenres.isNotEmpty()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.TrendingUp,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Rising affinity",
                                    color = Color(0xFF6EE7B7),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = learningState.risingGenres.joinToString(" · "),
                                color = Color(0xFFF8FAFC),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sweet-spot volume",
                            color = aiCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${learningState.avgVolumePercent}% output",
                            color = Color(0xFFF8FAFC),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (learningState.learnedTraits.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                learningState.learnedTraits.take(4).forEach { trait ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            color = aiCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = trait,
                            color = Color.White.copy(alpha = 0.74f),
                            fontSize = 12.sp,
                            lineHeight = 16.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Engine: ${learningState.modelUsed}",
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 11.sp
            )
        }
    }
}
