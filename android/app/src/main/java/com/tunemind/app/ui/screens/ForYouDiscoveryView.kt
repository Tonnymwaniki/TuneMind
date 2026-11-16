package com.tunemind.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.ai.ForYouLearningSummary
import com.tunemind.app.domain.ai.NoonDropSchedule
import com.tunemind.app.domain.recommendation.AiRecommendationEntity
import com.tunemind.app.ui.components.AnimatedEqualizerBars
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Modular representation of an interactive Wildcard Deck.
 * Currently initialized with 1 daily deck (3 distinct uncharted-genre cards),
 * designed so additional themed decks can be appended seamlessly later.
 */
data class WildcardDeckModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val cards: List<AiRecommendationEntity>
)

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
 * 2. Option A: Interactive Swipeable & Flippable 1-Wildcard Deck (3 uncharted-genre cards)
 * 3. "2 Picks Per Your Genre" Self-Learned Mix below
 * 4. "What AI Learned About You" live memory telemetry
 */
fun LazyListScope.forYouDiscoveryItems(
    activeRecs: List<AiRecommendationEntity>,
    learningState: ForYouLearningSummary,
    isDiscovering: Boolean,
    totalWildcardsInCycle: Int = 0,
    lockedWildcardsCount: Int = 0,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onRefreshForYou: () -> Unit,
    onPlayTrack: (AiRecommendationEntity) -> Unit,
    onPreviewTrack: (AiRecommendationEntity) -> Unit,
    onSaveTrack: (AiRecommendationEntity, Boolean) -> Unit,
    onDismissTrack: (AiRecommendationEntity) -> Unit,
    onInspectTrack: (AiRecommendationEntity) -> Unit
) {
    // Partition into Global Wildcards (1 deck · 3 songs) vs Per-Genre Picks (2 per active genre)
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

    // Build 1 modular Wildcard Deck initially (expandable cleanly to multiple decks later)
    val wildcardDecks = if (wildcards.isNotEmpty() || totalWildcardsInCycle > 0) {
        listOf(
            WildcardDeckModel(
                id = "wildcard_deck_primary",
                title = "WILDCARD DECK · 3 SONIC LEAPS",
                subtitle = "Swipe right to Lock · Swipe left to Pass · Tap card to flip",
                cards = wildcards
            )
        )
    } else {
        emptyList()
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
    if (activeRecs.isEmpty() && totalWildcardsInCycle == 0) {
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
                            text = "Predicting 2 unheard songs per genre + 1 Wildcard Deck...",
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

    // 2. Option A: Interactive 1-Wildcard Deck (3 Swipeable & Flippable Cards)
    wildcardDecks.forEach { deck ->
        item(key = "foryou_${deck.id}") {
            WildcardDeckSection(
                deck = deck,
                totalInCycle = totalWildcardsInCycle,
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
                        "$genreCount genres × 2 + 1 deck ($wildcardCount cards)"
                    } else {
                        "2 per genre + 1 Wildcard Deck (3 cards)"
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

/**
 * Option A: Dedicated Swipeable & Flippable 1-Wildcard Deck (3 uncharted-genre cards).
 * Supports:
 * - Swipe Right -> Lock (saves track & boosts genre/acoustic bridge in Bandit + Room + Supabase)
 * - Swipe Left  -> Pass (dismisses card, updates negative reward, deals next card)
 * - Tap Card    -> 3D flip between Front Hook and Full Claude Sonic Rationale
 * - Explicit Pass / Play (+30s) / Lock action buttons below deck + dot steppers
 */
@Composable
private fun WildcardDeckSection(
    deck: WildcardDeckModel,
    totalInCycle: Int,
    lockedCount: Int,
    isTrackCurrentlyPlaying: (title: String?, artist: String?, uri: String?) -> Boolean,
    onPlayTrack: (AiRecommendationEntity) -> Unit,
    onPreviewTrack: (AiRecommendationEntity) -> Unit,
    onSaveTrack: (AiRecommendationEntity, Boolean) -> Unit,
    onDismissTrack: (AiRecommendationEntity) -> Unit,
    onInspectTrack: (AiRecommendationEntity) -> Unit
) {
    val cards = deck.cards
    var activeIndex by remember(cards.size) { mutableIntStateOf(0) }
    val safeIndex = if (cards.isEmpty()) 0 else activeIndex.coerceIn(0, cards.lastIndex)
    val currentCard = cards.getOrNull(safeIndex)
    val nextCard = cards.getOrNull((safeIndex + 1).takeIf { cards.size > 1 && it < cards.size } ?: -1)
    val thirdCard = cards.getOrNull((safeIndex + 2).takeIf { cards.size > 2 && it < cards.size } ?: -1)

    var isFlipped by remember(currentCard?.id) { mutableStateOf(false) }
    val dragOffsetX = remember(currentCard?.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 105.dp.toPx() }
    val flyOutOffsetPx = with(density) { 420.dp.toPx() }

    val amberGold = Color(0xFFF59E0B)
    val aiCyan = Color(0xFF38BDF8)
    val lockGreen = Color(0xFF10B981)
    val passRose = Color(0xFFFB7185)
    val aiViolet = Color(0xFFA855F7)

    fun handlePass(rec: AiRecommendationEntity) {
        scope.launch {
            dragOffsetX.animateTo(-flyOutOffsetPx, animationSpec = tween(200))
            isFlipped = false
            onDismissTrack(rec)
            if (safeIndex >= cards.lastIndex && safeIndex > 0) {
                activeIndex = safeIndex - 1
            }
        }
    }

    fun handleLock(rec: AiRecommendationEntity) {
        val nextSaved = !rec.isSaved
        scope.launch {
            if (nextSaved) {
                dragOffsetX.animateTo(flyOutOffsetPx * 0.45f, animationSpec = tween(160))
                dragOffsetX.animateTo(0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            }
            onSaveTrack(rec, nextSaved)
            if (nextSaved && cards.size > 1) {
                val nextUnlocked = cards.indexOfFirst { it.id != rec.id && !it.isSaved }
                if (nextUnlocked != -1) {
                    activeIndex = nextUnlocked
                } else {
                    activeIndex = (safeIndex + 1) % cards.size
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Deck Header + Card Counter
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
                    tint = amberGold,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = deck.title,
                    color = Color(0xFFFBBF24),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp
                )
            }

            if (cards.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (cards.size > 1) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    isFlipped = false
                                    activeIndex = if (safeIndex == 0) cards.lastIndex else safeIndex - 1
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronLeft,
                                contentDescription = "Previous Wildcard",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "${safeIndex + 1} / ${cards.size}",
                            color = Color(0xFFF8FAFC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (cards.size > 1) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    isFlipped = false
                                    activeIndex = (safeIndex + 1) % cards.size
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = "Next Wildcard",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = deck.subtitle,
            color = Color.White.copy(alpha = 0.60f),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (currentCard == null) {
            // Deck Complete state when all 3 cards in today's deck were passed
            val tzShort = remember { NoonDropSchedule.getLocalTimeZoneShortLabel() }
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = amberGold,
                cornerRadius = 20.dp,
                contentPadding = PaddingValues(18.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Wildcard Deck Complete",
                        color = Color(0xFFF8FAFC),
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Claude logged your $totalInCycle reactions ($lockedCount locked) and updated tomorrow's sonic exploration weights. Next 3-card deck deals at 12:00 PM ($tzShort).",
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            return
        }

        // Stacked 3D Deck Container
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            // Back depth layer (#3)
            if (thirdCard != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, start = 18.dp, end = 18.dp)
                        .height(176.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.62f))
                        .border(1.dp, aiViolet.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
                )
            }

            // Middle depth layer (#2)
            if (nextCard != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 9.dp, start = 9.dp, end = 9.dp)
                        .height(184.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF17142B).copy(alpha = 0.80f))
                        .border(1.dp, amberGold.copy(alpha = 0.34f), RoundedCornerShape(22.dp))
                )
            }

            // Active Top Swipeable & Flippable Wildcard Card
            val rotationY by animateFloatAsState(
                targetValue = if (isFlipped) 180f else 0f,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                label = "wildcard_flip_rotation"
            )
            val dragX = dragOffsetX.value
            val dragProgress = (dragX / swipeThresholdPx).coerceIn(-1.2f, 1.2f)
            val dragTiltDeg = (dragX / with(density) { 26.dp.toPx() }).coerceIn(-12f, 12f)
            val cardBorderColor = when {
                dragX > 24f -> lockGreen.copy(alpha = 0.35f + 0.55f * abs(dragProgress).coerceAtMost(1f))
                dragX < -24f -> passRose.copy(alpha = 0.35f + 0.55f * abs(dragProgress).coerceAtMost(1f))
                currentCard.isSaved -> lockGreen.copy(alpha = 0.72f)
                else -> amberGold.copy(alpha = 0.68f)
            }
            val cardShape = RoundedCornerShape(22.dp)
            val isPlaying = isTrackCurrentlyPlaying(currentCard.title, currentCard.artist, currentCard.spotifyUri)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 198.dp)
                    .offset { IntOffset(dragX.roundToInt(), 0) }
                    .graphicsLayer {
                        rotationZ = dragTiltDeg
                        this.rotationY = rotationY
                        cameraDistance = 14f * density.density
                    }
                    .pointerInput(currentCard.id, cards.size) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    when {
                                        dragOffsetX.value >= swipeThresholdPx -> {
                                            handleLock(currentCard)
                                        }
                                        dragOffsetX.value <= -swipeThresholdPx -> {
                                            handlePass(currentCard)
                                        }
                                        else -> {
                                            dragOffsetX.animateTo(
                                                0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.72f,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            )
                                        }
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    dragOffsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    dragOffsetX.snapTo(dragOffsetX.value + dragAmount)
                                }
                            }
                        )
                    }
                    .clip(cardShape)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF21163A).copy(alpha = 0.96f),
                                Color(0xFF0E1B2D).copy(alpha = 0.97f)
                            )
                        )
                    )
                    .border(
                        width = 1.4.dp,
                        color = cardBorderColor,
                        shape = cardShape
                    )
                    .clickable { isFlipped = !isFlipped }
                    .padding(16.dp)
            ) {
                if (rotationY <= 90f) {
                    // ─── FRONT FACE ─────────────────────────────────────────────────
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
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
                                    text = currentCard.genreTag.ifBlank { "Global Wildcard" },
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
                                if (currentCard.isSaved) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(lockGreen.copy(alpha = 0.22f))
                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Lock,
                                            contentDescription = "Locked",
                                            tint = Color(0xFF6EE7B7),
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Text(
                                            text = "LOCKED",
                                            color = Color(0xFF6EE7B7),
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Text(
                                    text = "${currentCard.matchScorePercent}% bridge",
                                    color = aiCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .clickable { onInspectTrack(currentCard) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreHoriz,
                                        contentDescription = "Track Options",
                                        tint = Color.White.copy(alpha = 0.72f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isPlaying) {
                                AnimatedEqualizerBars(
                                    color = lockGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = currentCard.title,
                                color = Color(0xFFF8FAFC),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentCard.artist,
                            color = Color(0xFFE2E8F0).copy(alpha = 0.82f),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = currentCard.reason,
                            color = Color.White.copy(alpha = 0.74f),
                            fontSize = 12.5.sp,
                            lineHeight = 17.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Tap card to flip for AI sonic notes",
                                color = aiCyan.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (abs(dragX) > 28f) {
                                Text(
                                    text = if (dragX > 0f) "RELEASE TO LOCK" else "RELEASE TO PASS",
                                    color = if (dragX > 0f) Color(0xFF6EE7B7) else Color(0xFFFDA4AF),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                } else {
                    // ─── BACK FACE (3D COUNTER-ROTATED) ─────────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { rotationY = 180f },
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "WHY CLAUDE PICKED THIS LEAP",
                                color = aiCyan,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.0.sp
                            )
                            Text(
                                text = "${currentCard.matchScorePercent}% bridge",
                                color = Color(0xFFFDE68A),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "${currentCard.title} — ${currentCard.artist}",
                            color = Color(0xFFF8FAFC),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = currentCard.reason,
                            color = Color.White.copy(alpha = 0.86f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Genre: ${currentCard.genreTag}",
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Tap to flip front",
                                color = aiCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Deck Indicator Dots (1..3)
        if (cards.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                cards.forEachIndexed { idx, card ->
                    val isActive = idx == safeIndex
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .width(if (isActive) 22.dp else 8.dp)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isActive -> amberGold
                                    card.isSaved -> lockGreen.copy(alpha = 0.75f)
                                    else -> Color.White.copy(alpha = 0.22f)
                                }
                            )
                            .clickable {
                                isFlipped = false
                                activeIndex = idx
                            }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Explicit Option A Deck Controls: Pass | Play (+30s) | Lock
        val isPlayingNow = isTrackCurrentlyPlaying(currentCard.title, currentCard.artist, currentCard.spotifyUri)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // PASS button
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(passRose.copy(alpha = 0.13f))
                    .border(1.dp, passRose.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                    .clickable { handlePass(currentCard) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Pass Wildcard",
                    tint = Color(0xFFFDA4AF),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Pass",
                    color = Color(0xFFFDA4AF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // PLAY button
            Row(
                modifier = Modifier
                    .weight(1.35f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isPlayingNow) lockGreen.copy(alpha = 0.22f) else amberGold.copy(alpha = 0.22f)
                    )
                    .border(
                        1.dp,
                        if (isPlayingNow) lockGreen.copy(alpha = 0.60f) else amberGold.copy(alpha = 0.62f),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onPlayTrack(currentCard) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isPlayingNow) {
                    AnimatedEqualizerBars(
                        color = lockGreen,
                        modifier = Modifier.size(15.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play Wildcard",
                        tint = Color(0xFFFDE68A),
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isPlayingNow) "Playing" else "Play",
                    color = Color(0xFFF8FAFC),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Optional 30s Preview pill button when stream URL is available
            if (!currentCard.previewUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(aiCyan.copy(alpha = 0.14f))
                        .border(1.dp, aiCyan.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                        .clickable { onPreviewTrack(currentCard) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Headphones,
                        contentDescription = "30s Preview",
                        tint = aiCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // LOCK button
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (currentCard.isSaved) lockGreen.copy(alpha = 0.22f) else aiCyan.copy(alpha = 0.14f)
                    )
                    .border(
                        1.dp,
                        if (currentCard.isSaved) lockGreen.copy(alpha = 0.64f) else aiCyan.copy(alpha = 0.48f),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { handleLock(currentCard) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (currentCard.isSaved) Icons.Rounded.Check else Icons.Rounded.Lock,
                    contentDescription = if (currentCard.isSaved) "Locked" else "Lock Wildcard",
                    tint = if (currentCard.isSaved) Color(0xFF6EE7B7) else Color(0xFF67E8F9),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (currentCard.isSaved) "Locked" else "Lock",
                    color = if (currentCard.isSaved) Color(0xFF6EE7B7) else Color(0xFFF8FAFC),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
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
