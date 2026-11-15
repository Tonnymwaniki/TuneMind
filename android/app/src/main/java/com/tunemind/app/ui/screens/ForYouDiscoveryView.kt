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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PlayArrow
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
import com.tunemind.app.domain.ai.ForYouLearningSummary
import com.tunemind.app.domain.ai.NoonDropSchedule
import com.tunemind.app.domain.recommendation.AiRecommendationEntity
import com.tunemind.app.ui.components.AnimatedEqualizerBars
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import kotlinx.coroutines.delay

/**
 * Custom animated AI Neural Emblem rendered via Compose Canvas so the For You discovery
 * experience feels unmistakably intelligent and alive.
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
            animation = tween(if (isThinking) 2400 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ai_orbit"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.72f,
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

        // Soft outer halo
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

        // Outer orbital arc ring
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

        // Counter-rotating inner telemetry ring
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

        // Four-point AI Spark / Neural Star in the center
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
 * 1. AI Daily Discovery & Evolution IQ Header Card + live 12:00 PM local countdown
 * 2. "Wildcards · 3 Uncharted Genres" Spotlight Carousel
 * 3. "2 Picks Per Your Genre" Grouped Showcase
 * 4. "What AI Learned About You" live memory telemetry
 */
fun LazyListScope.forYouDiscoveryItems(
    activeRecs: List<AiRecommendationEntity>,
    learningState: ForYouLearningSummary,
    isDiscovering: Boolean,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onRefreshForYou: () -> Unit,
    onPlayTrack: (AiRecommendationEntity) -> Unit,
    onPreviewTrack: (AiRecommendationEntity) -> Unit,
    onSaveTrack: (AiRecommendationEntity, Boolean) -> Unit,
    onDismissTrack: (AiRecommendationEntity) -> Unit,
    onInspectTrack: (AiRecommendationEntity) -> Unit
) {
    // Partition into Global Wildcards vs Per-Genre Picks
    val wildcards = activeRecs
        .filter { it.isWildcard || it.subTier.equals("GLOBAL_WILDCARD", ignoreCase = true) }
        .take(3)

    val genrePicks = activeRecs
        .filter { !(it.isWildcard || it.subTier.equals("GLOBAL_WILDCARD", ignoreCase = true)) }

    // Preserve genre ranking order returned from the ML backend, grouped up to 2 picks per genre
    val orderedGenreGroups: List<Pair<String, List<AiRecommendationEntity>>> = run {
        val map = LinkedHashMap<String, MutableList<AiRecommendationEntity>>()
        genrePicks.forEach { rec ->
            val key = rec.genreTag.trim().ifBlank { "Tailored Mix" }
            val list = map.getOrPut(key) { mutableListOf() }
            if (list.size < 2) {
                list.add(rec)
            }
        }
        map.entries.map { it.key to it.value }
    }

    // 1. AI Neural Intelligence Hero + 12:00 PM Local Timezone Auto-Drop Header
    item(key = "foryou_ai_hero") {
        ForYouAiHeroCard(
            learningState = learningState,
            totalTracks = activeRecs.size,
            genreCount = orderedGenreGroups.size,
            wildcardCount = wildcards.size,
            isDiscovering = isDiscovering,
            onRefresh = onRefreshForYou
        )
        Spacer(modifier = Modifier.height(14.dp))
    }

    // Empty / Loading State
    if (activeRecs.isEmpty()) {
        item(key = "foryou_empty_state") {
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = Color(0xFF38BDF8)
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
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Predicting 2 unheard songs per genre + 3 Wildcards...",
                            color = Color(0xFFF8FAFC),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = "Your 12:00 PM AI Daily Drop is ready to calibrate.",
                            color = Color(0xFFF8FAFC),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Tap Refresh below to run Claude Reasoning + Neural Bandit across your listening behavior.",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.5.sp
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF38BDF8).copy(alpha = 0.16f))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .clickable(enabled = !isDiscovering) { onRefreshForYou() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
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
                            text = if (isDiscovering) "Thinking..." else "Generate Today's AI Drop",
                            color = Color(0xFFF8FAFC),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    // 2. Wildcards Spotlight Section (3 Uncharted Foreign Genres)
    if (wildcards.isNotEmpty()) {
        item(key = "foryou_wildcards_header") {
            WildcardsSpotlightSection(
                wildcards = wildcards,
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

    // 3. Per-Genre Showcase (2 Unheard Picks Per Active User Genre)
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
                        text = "2 PICKS PER YOUR GENRE",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Unheard predictions matched to your rotation",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = "${orderedGenreGroups.size} genres · ${genrePicks.size} picks",
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
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
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "· ${tracks.size} picks",
                                color = Color.White.copy(alpha = 0.56f),
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "$topMatch% taste fit",
                            color = LocalAdaptiveTheme.current.secondaryAccent,
                            fontSize = 12.sp,
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

    // 4. What AI Learned About You Card (Live Self-Learning Memory Evolution)
    item(key = "foryou_learned_memory") {
        Spacer(modifier = Modifier.height(4.dp))
        AiMemoryEvolutionCard(learningState = learningState)
    }
}

@Composable
private fun ForYouAiHeroCard(
    learningState: ForYouLearningSummary,
    totalTracks: Int,
    genreCount: Int,
    wildcardCount: Int,
    isDiscovering: Boolean,
    onRefresh: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val aiCyan = Color(0xFF38BDF8)
    val aiViolet = Color(0xFFA855F7)

    // Live ticker updating the exact countdown to 12:00 PM local time
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
        cornerRadius = 22.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top Row: Animated AI Core + Title + Refresh CTA
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AiNeuralCoreEmblem(
                    accentPrimary = aiCyan,
                    accentSecondary = aiViolet,
                    isThinking = isDiscovering,
                    modifier = Modifier.size(46.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "TUNEMIND AI DISCOVERY",
                            color = aiCyan,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.0.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(aiViolet.copy(alpha = 0.22f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "IQ ${learningState.intelligenceScorePercent}%",
                                color = Color(0xFFE9D5FF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = learningState.evolutionLabel.ifBlank { "Stage 1 · Calibrating Your Taste" },
                        color = Color(0xFFF8FAFC),
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Refresh Button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(aiCyan.copy(alpha = 0.14f))
                        .border(1.dp, aiCyan.copy(alpha = 0.42f), RoundedCornerShape(12.dp))
                        .clickable(enabled = !isDiscovering) { onRefresh() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isDiscovering) {
                        CircularProgressIndicator(
                            color = aiCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(13.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh AI Picks",
                            tint = Color(0xFF67E8F9),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = if (isDiscovering) "Syncing" else "Refresh",
                        color = Color(0xFFF8FAFC),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Intelligence Evolution Progress Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((learningState.intelligenceScorePercent / 100f).coerceIn(0.18f, 1.0f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(aiCyan, aiViolet, theme.secondaryAccent)
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 12:00 PM Daily Auto-Change Schedule + Engine Summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "New songs daily at 12:00 PM ($tzShort)",
                        color = Color(0xFFFDE68A),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "Next in $countdownText",
                    color = Color(0xFFF8FAFC),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Telemetry Strip: Today's Slate + Self-Learning Counters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (totalTracks > 0) {
                        "$genreCount genres × 2 + $wildcardCount wildcards"
                    } else {
                        "2 per genre + 3 uncharted wildcards"
                    },
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${learningState.learningIterations} signals · ${learningState.hitRatePercent}% hit rate",
                    color = aiCyan.copy(alpha = 0.90f),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun WildcardsSpotlightSection(
    wildcards: List<AiRecommendationEntity>,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onPlayTrack: (AiRecommendationEntity) -> Unit,
    onPreviewTrack: (AiRecommendationEntity) -> Unit,
    onSaveTrack: (AiRecommendationEntity, Boolean) -> Unit,
    onDismissTrack: (AiRecommendationEntity) -> Unit,
    onInspectTrack: (AiRecommendationEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Explore,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "WILDCARDS · ${wildcards.size} UNCHARTED GENRES",
                    color = Color(0xFFFBBF24),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp
                )
            }
            Text(
                text = "Sonic leaps",
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 11.5.sp
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = "Outside your usual genres — picked because rhythm & mood overlap",
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(wildcards, key = { "wildcard_${it.id}" }) { rec ->
                val isPlaying = isTrackCurrentlyPlaying(rec.title, rec.artist, rec.spotifyUri)
                WildcardSpotlightCard(
                    rec = rec,
                    isPlaying = isPlaying,
                    onPlayClick = { onPlayTrack(rec) },
                    onPreviewClick = { onPreviewTrack(rec) },
                    onSaveToggle = { onSaveTrack(rec, !rec.isSaved) },
                    onDismissClick = { onDismissTrack(rec) },
                    onCardClick = { onInspectTrack(rec) }
                )
            }
        }
    }
}

@Composable
private fun WildcardSpotlightCard(
    rec: AiRecommendationEntity,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onPreviewClick: () -> Unit,
    onSaveToggle: () -> Unit,
    onDismissClick: () -> Unit,
    onCardClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(20.dp)
    val amberGold = Color(0xFFF59E0B)
    val aiViolet = Color(0xFFA855F7)
    val aiCyan = Color(0xFF38BDF8)

    Box(
        modifier = Modifier
            .width(276.dp)
            .clip(cardShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1F1635).copy(alpha = 0.94f),
                        Color(0xFF0E1B2B).copy(alpha = 0.94f)
                    )
                )
            )
            .border(
                width = 1.25.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        amberGold.copy(alpha = 0.72f),
                        aiViolet.copy(alpha = 0.56f),
                        aiCyan.copy(alpha = 0.56f)
                    )
                ),
                shape = cardShape
            )
            .clickable { onCardClick() }
            .padding(15.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top badge row: Exotic Foreign Genre + Dismiss
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(amberGold.copy(alpha = 0.16f))
                        .border(0.8.dp, amberGold.copy(alpha = 0.48f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFFFDE68A),
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = rec.genreTag.ifBlank { "Global Wildcard" },
                        color = Color(0xFFFDE68A),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${rec.matchScorePercent}% bridge",
                        color = aiCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onDismissClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Not my vibe",
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = rec.title,
                color = Color(0xFFF8FAFC),
                fontSize = 16.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = rec.artist,
                color = Color(0xFFE2E8F0).copy(alpha = 0.78f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = rec.reason,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                lineHeight = 16.5.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action Footer: Play + Optional 30s Preview + Save Bookmark
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(amberGold.copy(alpha = 0.22f))
                            .border(1.dp, amberGold.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .clickable { onPlayClick() }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isPlaying) {
                            AnimatedEqualizerBars(
                                color = Color(0xFF10B981),
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Play Wildcard",
                                tint = Color(0xFFFDE68A),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Text(
                            text = if (isPlaying) "Playing" else "Play",
                            color = Color(0xFFF8FAFC),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (!rec.previewUrl.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onPreviewClick() }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Headphones,
                                contentDescription = "30s Preview",
                                tint = aiCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "30s",
                                color = Color(0xFFF8FAFC),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (rec.isSaved) aiCyan.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.07f)
                        )
                        .clickable { onSaveToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (rec.isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = "Save Wildcard",
                        tint = if (rec.isSaved) aiCyan else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(theme.elevatedSurface.copy(alpha = 0.62f))
            .border(
                width = 1.dp,
                color = if (isPlaying) Color(0xFF10B981).copy(alpha = 0.55f) else theme.border.copy(alpha = 0.42f),
                shape = cardShape
            )
            .clickable { onCardClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Play / Live Equalizer Ring
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f))
                        .border(1.dp, accent.copy(alpha = 0.45f), CircleShape)
                        .clickable { onPlayClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying) {
                        AnimatedEqualizerBars(
                            color = Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play Track",
                            tint = Color(0xFFF8FAFC),
                            modifier = Modifier.size(22.dp)
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
                            fontSize = 15.sp,
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
                        text = "${rec.artist} · Unheard",
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Quick action icons: Optional 30s Preview + Save Bookmark + Dismiss
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!rec.previewUrl.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF38BDF8).copy(alpha = 0.14f))
                                .clickable { onPreviewClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = "30s Preview",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                if (rec.isSaved) theme.primaryAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f)
                            )
                            .clickable { onSaveToggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (rec.isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            contentDescription = "Save Pick",
                            tint = if (rec.isSaved) theme.primaryAccent else Color.White.copy(alpha = 0.68f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable { onDismissClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss Pick",
                            tint = Color.White.copy(alpha = 0.56f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            if (rec.reason.isNotBlank()) {
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = rec.reason,
                    color = Color.White.copy(alpha = 0.66f),
                    fontSize = 12.sp,
                    lineHeight = 16.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AiMemoryEvolutionCard(learningState: ForYouLearningSummary) {
    val aiCyan = Color(0xFF38BDF8)
    val aiViolet = Color(0xFFA855F7)

    SoftGlassCard(
        modifier = Modifier.fillMaxWidth(),
        accentTint = aiViolet,
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
                    tint = aiViolet,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "WHAT AI LEARNED ABOUT YOU",
                    color = Color(0xFFE9D5FF),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.9.sp
                )
            }

            if (learningState.narrative.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = learningState.narrative,
                    color = Color(0xFFF8FAFC).copy(alpha = 0.88f),
                    fontSize = 13.sp,
                    lineHeight = 18.5.sp
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
                            text = "✦",
                            color = aiCyan,
                            fontSize = 11.sp
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
