package com.tunemind.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.recommendation.AiRecommendationEntity
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.DeepObsidianAmber
import com.tunemind.app.ui.theme.TuneMindTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chrono-Receptive AI Music Discovery Crate Screen.
 * Uses Gemini 3.8 Flash to continuously recommend new, unplayed songs
 * tuned to the user's real-time daypart, auditory satiety, and daily listening growth.
 * All recommended songs are stored persistently in the user's local Room database crate.
 */
@Composable
fun AiDiscoveryScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val colors = TuneMindTheme.colors

    val crate by vm.aiDiscoveryCrate.collectAsState()
    val savedRecs by vm.savedAiRecommendations.collectAsState()
    val isDiscovering by vm.isDiscovering.collectAsState()
    val currentSlot = vm.currentTimeSlot
    val reasoningReport by vm.reasoningReport.collectAsState()
    val playbackFeedback by vm.playbackLaunchFeedback.collectAsState()

    val tasteAnchors by vm.userTasteAnchors.collectAsState()
    val historyState by vm.historyState.collectAsState()
    val homeState by vm.homeState.collectAsState()
    val topDetectedArtists = remember(historyState.tracks, homeState.nowPlaying) {
        val observed = (historyState.tracks.mapNotNull { it.artist } + listOfNotNull(homeState.nowPlaying?.artist))
            .filter { it.isNotBlank() }
            .distinct()
        observed.take(6)
    }
    var showCalibrateTasteDialog by remember { mutableStateOf(false) }

    var selectedFilterTab by remember { mutableIntStateOf(0) } // 0 = Active Daypart, 1 = Stored Archive, 2 = Saved Gems

    // Rotating Spool Animation
    val infiniteTransition = rememberInfiniteTransition(label = "crateAnimations")
    val spoolRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spoolRotation"
    )

    val activeDaypartCrate = remember(crate, currentSlot) {
        crate.filter { it.timeSlotKey == currentSlot.name }
    }

    val displayList = when (selectedFilterTab) {
        0 -> activeDaypartCrate
        1 -> crate
        else -> savedRecs
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepObsidianAmber.CircadianMorning.copy(alpha = 0.08f),
                        DeepObsidianAmber.AppBackground,
                        DeepObsidianAmber.RecessedBackground
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. TOP HARDWARE TELEMETRY HEADER
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DeepObsidianAmber.SecondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(DeepObsidianAmber.Success)
                    )
                    Text(
                        text = "CHRONO-RECEPTIVE DISCOVERY // GEMINI 3.8",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.TextSecondary,
                        letterSpacing = 1.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DeepObsidianAmber.RecessedBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CopperAccent.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "AUTO-CHANGING",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.PrimaryAccent,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // Section Title & Mission
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "NEURO-ACOUSTIC DISCOVERY CRATE",
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = DeepObsidianAmber.TextPrimary,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "New, unplayed tracks dynamically curated by Gemini. Matches your ears and dopamine receptors across the clock.",
                fontSize = 12.sp,
                color = DeepObsidianAmber.TextSecondary,
                lineHeight = 17.sp
            )
        }

        // 2. DAYPART & DOPAMINE HORIZON STATUS CARD
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DeepObsidianAmber.CardElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Outlined.AccessTime,
                            contentDescription = null,
                            tint = DeepObsidianAmber.SecondaryAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "CURRENT CIRCADIAN DAYPART",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.SecondaryAccent,
                            letterSpacing = 0.8.sp
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.Success.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "ACTIVE WINDOW",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.Success,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = currentSlot.label,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = DeepObsidianAmber.TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${currentSlot.startHour}:00 - ${currentSlot.endHour}:00 Window",
                            fontSize = 11.sp,
                            color = DeepObsidianAmber.TextSecondary
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        val score = reasoningReport?.receptorPotencyScore ?: 88
                        Text(
                            text = "$score% Receptive",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.PrimaryAccent,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Auditory Headroom",
                            fontSize = 10.sp,
                            color = DeepObsidianAmber.TextSecondary
                        )
                    }
                }

                // Neuro-Pulse Reroll Button
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.triggerChronoDiscovery(forceRefresh = true)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepObsidianAmber.PrimaryAccent,
                        contentColor = DeepObsidianAmber.AppBackground
                    ),
                    enabled = !isDiscovering
                ) {
                    if (isDiscovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = DeepObsidianAmber.AppBackground,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Synthesizing Neuro-Pulse via Gemini...",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "TRIGGER NEURO-PULSE // RE-ROLL CRATE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 2.5. SONIC TASTE BLUEPRINT & CALIBRATION CARD
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DeepObsidianAmber.CardElevated,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        DeepObsidianAmber.CopperAccent.copy(alpha = 0.7f),
                        DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Outlined.Fingerprint,
                            contentDescription = null,
                            tint = DeepObsidianAmber.PrimaryAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "SONIC TASTE BLUEPRINT",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.PrimaryAccent,
                            letterSpacing = 0.8.sp
                        )
                    }

                    TextButton(
                        onClick = { showCalibrateTasteDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = DeepObsidianAmber.SecondaryAccent,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "CALIBRATE TASTE",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.SecondaryAccent
                        )
                    }
                }

                // Detected Real Artists
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "DEMONSTRATED:",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DeepObsidianAmber.TextSecondary
                    )
                    Text(
                        text = topDetectedArtists.joinToString(", ").ifEmpty { "Listening for active songs on Spotify/Players..." },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Taste Anchors Chips (if any added)
                if (tasteAnchors.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ANCHORS:",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = DeepObsidianAmber.TextSecondary
                        )
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(tasteAnchors) { anchor ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DeepObsidianAmber.RecessedBackground,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = anchor,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = DeepObsidianAmber.PrimaryAccent,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = DeepObsidianAmber.TextSecondary,
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clickable { vm.removeTasteAnchor(anchor) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. FILTER TABS (Active Daypart | Stored Crate | Saved Gems)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DeepObsidianAmber.RecessedBackground)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val tabs = listOf(
                "Active Daypart (${activeDaypartCrate.size})",
                "Stored Crate (${crate.size})",
                "Saved Gems (${savedRecs.size})"
            )

            tabs.forEachIndexed { index, title ->
                val isSelected = selectedFilterTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) DeepObsidianAmber.CardElevated else Color.Transparent)
                        .clickable { selectedFilterTab = index }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextSecondary
                    )
                }
            }
        }

        // 4. LIST OF STORED & ACTIVE RECOMMENDATIONS
        if (displayList.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = DeepObsidianAmber.SecondaryBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.QueueMusic,
                        contentDescription = null,
                        tint = DeepObsidianAmber.TextMuted,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = if (selectedFilterTab == 2) "No Saved Gems Yet" else "Crate Initializing",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.TextPrimary
                    )
                    Text(
                        text = if (selectedFilterTab == 2) "Tap the bookmark icon on any recommendation to save it permanently."
                        else "Tap 'TRIGGER NEURO-PULSE' above to let Gemini synthesize discoveries for your current daypart.",
                        fontSize = 11.sp,
                        color = DeepObsidianAmber.TextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayList, key = { it.id }) { item ->
                    AiRecommendationCard(
                        item = item,
                        spoolRotation = spoolRotation,
                        onPlay = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.playAiRecommendation(context, item)
                        },
                        onToggleSave = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.toggleSaveRecommendation(item.id, !item.isSaved)
                        },
                        onDelete = {
                            vm.deleteRecommendation(item.id)
                        }
                    )
                }
            }
        }
    }

    if (showCalibrateTasteDialog) {
        CalibrateTasteDialog(
            currentAnchors = tasteAnchors,
            detectedArtists = topDetectedArtists,
            onDismiss = { showCalibrateTasteDialog = false },
            onAddAnchor = { anchor -> vm.addTasteAnchor(anchor) },
            onRemoveAnchor = { anchor -> vm.removeTasteAnchor(anchor) }
        )
    }
}

/**
 * Individual Chrono AI Recommendation Card with Category Badging,
 * Cognitive Dopamine Rationale, and 1-Tap Provider Playback.
 */
@Composable
private fun AiRecommendationCard(
    item: AiRecommendationEntity,
    spoolRotation: Float,
    onPlay: () -> Unit,
    onToggleSave: () -> Unit,
    onDelete: () -> Unit
) {
    val categoryColor = when (item.category.uppercase()) {
        "DOPAMINE_FLOW" -> DeepObsidianAmber.PrimaryAccent
        "PALATE_CLEANSER" -> DeepObsidianAmber.CryoCyan
        "CHRONO_RESTORE" -> DeepObsidianAmber.SecondaryAccent
        else -> DeepObsidianAmber.NeuralViolet
    }

    val categoryLabel = when (item.category.uppercase()) {
        "DOPAMINE_FLOW" -> "DOPAMINE FLOW"
        "PALATE_CLEANSER" -> "PALATE CLEANSER"
        "CHRONO_RESTORE" -> "CHRONO RESTORE"
        else -> "SONIC NOVELTY"
    }

    val formattedDate = remember(item.recommendedAtMs) {
        val sdf = SimpleDateFormat("MMM d • h:mm a", Locale.getDefault())
        sdf.format(Date(item.recommendedAtMs))
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DeepObsidianAmber.CardElevated,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(
                    categoryColor.copy(alpha = 0.5f),
                    DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.3f)
                )
            )
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Animated Mini Cassette Spool
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DeepObsidianAmber.RecessedBackground)
                            .border(1.dp, categoryColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .rotate(if (item.wasPlayed) spoolRotation else 0f)
                                .clip(CircleShape)
                                .background(DeepObsidianAmber.SecondaryBackground)
                                .border(1.dp, categoryColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(DeepObsidianAmber.RecessedBackground)
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.artist,
                            fontSize = 11.sp,
                            color = DeepObsidianAmber.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Save Gem Icon
                IconButton(
                    onClick = onToggleSave,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (item.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Save Gem",
                        tint = if (item.isSaved) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Cognitive Dopamine Rationale
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DeepObsidianAmber.SecondaryBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.reason,
                    fontSize = 11.sp,
                    color = DeepObsidianAmber.TextPrimary,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }

            // Bottom Strip: Category Badge, Daypart Context, and Play Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, categoryColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = categoryLabel,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = categoryColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = item.timeSlotLabel,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DeepObsidianAmber.TextSecondary
                    )
                }

                Button(
                    onClick = onPlay,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = categoryColor,
                        contentColor = DeepObsidianAmber.AppBackground
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = if (item.wasPlayed) Icons.Default.Replay else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (item.wasPlayed) "REPLAY" else "PLAY",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Interactive Taste Calibration Dialog.
 * Allows the user to anchor specific genres, artists, or styles so Gemini
 * grounds recommendations precisely in what their ears and dopamine want,
 * completely eliminating blind guesswork or random ambient fallbacks.
 */
@Composable
private fun CalibrateTasteDialog(
    currentAnchors: List<String>,
    detectedArtists: List<String>,
    onDismiss: () -> Unit,
    onAddAnchor: (String) -> Unit,
    onRemoveAnchor: (String) -> Unit
) {
    var newAnchorInput by remember { mutableStateOf("") }
    val suggestedGenres = remember {
        listOf(
            "Afrobeats",
            "Hip-Hop",
            "R&B / Soul",
            "Amapiano",
            "Pop / Synthwave",
            "Dancehall",
            "Neo-Soul / Jazz",
            "Indie / Alt"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = DeepObsidianAmber.PrimaryAccent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "CALIBRATE TASTE BLUEPRINT",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Lock in your musical DNA so Gemini never defaults to generic ambient guesswork. Enter the artists and genres you actually love.",
                    fontSize = 12.sp,
                    color = DeepObsidianAmber.TextSecondary,
                    lineHeight = 17.sp
                )

                // Input field to add custom artist or genre
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newAnchorInput,
                        onValueChange = { newAnchorInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "e.g. Burna Boy, Drake, Afrobeats...",
                                fontSize = 11.sp,
                                color = DeepObsidianAmber.TextSecondary.copy(alpha = 0.6f)
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DeepObsidianAmber.PrimaryAccent,
                            unfocusedBorderColor = DeepObsidianAmber.SurfaceBorder,
                            focusedTextColor = DeepObsidianAmber.TextPrimary,
                            unfocusedTextColor = DeepObsidianAmber.TextPrimary,
                            cursorColor = DeepObsidianAmber.PrimaryAccent
                        )
                    )

                    Button(
                        onClick = {
                            if (newAnchorInput.isNotBlank()) {
                                onAddAnchor(newAnchorInput.trim())
                                newAnchorInput = ""
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepObsidianAmber.PrimaryAccent,
                            contentColor = DeepObsidianAmber.AppBackground
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        enabled = newAnchorInput.isNotBlank()
                    ) {
                        Text(
                            text = "ADD",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Currently active anchors
                if (currentAnchors.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "ACTIVE TASTE ANCHORS (BOUND TO GEMINI):",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.PrimaryAccent
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(currentAnchors) { anchor ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DeepObsidianAmber.RecessedBackground,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.6f)
                                    )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = anchor,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = DeepObsidianAmber.PrimaryAccent
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove $anchor",
                                            tint = DeepObsidianAmber.TextSecondary,
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clickable { onRemoveAnchor(anchor) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 1-Tap add from detected playback artists
                if (detectedArtists.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "DETECTED FROM PLAYBACK (TAP TO ANCHOR):",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.SecondaryAccent
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(detectedArtists) { artist ->
                                val isAlreadyAnchored = currentAnchors.any { it.equals(artist, ignoreCase = true) }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isAlreadyAnchored) DeepObsidianAmber.RecessedBackground else DeepObsidianAmber.CardElevated,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isAlreadyAnchored) DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.4f) else DeepObsidianAmber.SurfaceBorder
                                    ),
                                    modifier = Modifier.clickable(enabled = !isAlreadyAnchored) {
                                        onAddAnchor(artist)
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            if (isAlreadyAnchored) Icons.Default.Check else Icons.Default.Add,
                                            contentDescription = null,
                                            tint = if (isAlreadyAnchored) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.SecondaryAccent,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = artist,
                                            fontSize = 10.sp,
                                            color = if (isAlreadyAnchored) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Suggested genres quick tap
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "POPULAR TASTE STYLES (TAP TO ADD):",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.TextSecondary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(suggestedGenres) { genre ->
                            val isAlreadyAnchored = currentAnchors.any { it.equals(genre, ignoreCase = true) }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isAlreadyAnchored) DeepObsidianAmber.RecessedBackground else DeepObsidianAmber.CardElevated,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isAlreadyAnchored) DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.4f) else DeepObsidianAmber.SurfaceBorder
                                ),
                                modifier = Modifier.clickable(enabled = !isAlreadyAnchored) {
                                    onAddAnchor(genre)
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        if (isAlreadyAnchored) Icons.Default.Check else Icons.Default.Add,
                                        contentDescription = null,
                                        tint = if (isAlreadyAnchored) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextSecondary,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = genre,
                                        fontSize = 10.sp,
                                        color = if (isAlreadyAnchored) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeepObsidianAmber.PrimaryAccent,
                    contentColor = DeepObsidianAmber.AppBackground
                )
            ) {
                Text(
                    text = "APPLY & CLOSE",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        containerColor = DeepObsidianAmber.CardElevated,
        shape = RoundedCornerShape(16.dp)
    )
}

