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
import kotlinx.coroutines.launch

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
    val playedCount by vm.playedRecCount.collectAsState()
    val savedCount by vm.savedRecCount.collectAsState()
    val dismissedCount by vm.dismissedRecCount.collectAsState()
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

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
        crate.filter { it.timeSlotKey == currentSlot.name && !it.isDismissed }
    }

    val displayList = when (selectedFilterTab) {
        0 -> activeDaypartCrate
        1 -> crate.filter { !it.isDismissed }
        else -> savedRecs.filter { !it.isDismissed }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
            // 1. COMPACT UNIFIED STUDIO CONTROL STRIP
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = DeepObsidianAmber.SecondaryBackground,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            DeepObsidianAmber.CopperAccent.copy(alpha = 0.5f),
                            DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.3f),
                            DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.4f)
                        )
                    )
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Top Row: Status LED, Section Title, Daypart Pill, and Re-Roll Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isDiscovering) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.Success)
                            )
                            Text(
                                text = "NEURO AI CRATE",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.TextPrimary,
                                letterSpacing = 0.8.sp
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DeepObsidianAmber.RecessedBackground,
                                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = currentSlot.label.uppercase(),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepObsidianAmber.PrimaryAccent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Compact Re-roll Action
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.triggerChronoDiscovery(forceRefresh = true)
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepObsidianAmber.PrimaryAccent,
                                contentColor = DeepObsidianAmber.AppBackground
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp),
                            enabled = !isDiscovering
                        ) {
                            if (isDiscovering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = DeepObsidianAmber.AppBackground,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "ROLLING...",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "RE-ROLL",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Bottom Row: Telemetry counters & Taste Calibrate quick chip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DeepObsidianAmber.RecessedBackground,
                                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.Success.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "PLAYED: $playedCount",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepObsidianAmber.Success,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DeepObsidianAmber.RecessedBackground,
                                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "SAVED: $savedCount",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepObsidianAmber.PrimaryAccent,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DeepObsidianAmber.RecessedBackground,
                                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.TextMuted.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "MUTED: $dismissedCount",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepObsidianAmber.TextMuted,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Calibrate Taste Chip
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DeepObsidianAmber.RecessedBackground,
                            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SecondaryAccent.copy(alpha = 0.5f)),
                            modifier = Modifier.clickable { showCalibrateTasteDialog = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Tune,
                                    contentDescription = null,
                                    tint = DeepObsidianAmber.SecondaryAccent,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = if (tasteAnchors.isNotEmpty()) "ANCHOR: ${tasteAnchors.first().uppercase()}" else "TASTE BLUEPRINT",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepObsidianAmber.SecondaryAccent
                                )
                            }
                        }
                    }
                }
            }

            // 2. FILTER TABS (Active 10-Set | Stored Crate | Saved Gems)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DeepObsidianAmber.RecessedBackground)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val tabs = listOf(
                    "Active 10-Set (${activeDaypartCrate.size})",
                    "Crate Vault (${crate.size})",
                    "Saved Gems (${savedRecs.size})"
                )

                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedFilterTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) DeepObsidianAmber.CardElevated else Color.Transparent)
                            .clickable { selectedFilterTab = index }
                            .padding(vertical = 7.dp),
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

            // 3. STRUCTURED 10-SONG DISCOVERY CRATE
            if (displayList.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
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
                            text = if (selectedFilterTab == 2) "No Saved Gems Yet" else "Crate Initializing...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.TextPrimary
                        )
                        Text(
                            text = if (selectedFilterTab == 2) "Tap bookmark on any card to save tracks permanently."
                            else "Tap 'RE-ROLL' above to synthesize 10 tailored discoveries.",
                            fontSize = 11.sp,
                            color = DeepObsidianAmber.TextSecondary,
                            lineHeight = 15.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // When in Active 10-Set mode, group items cleanly by Sub-Tier
                    if (selectedFilterTab == 0) {
                        val coreTracks = displayList.filter { it.subTier == "CORE_DOPAMINE" }
                        val deepCuts = displayList.filter { it.subTier == "DEEP_CUT" }
                        val cleansers = displayList.filter { it.subTier == "PALATE_CLEANSER" }
                        val frontiers = displayList.filter { it.subTier == "SONIC_FRONTIER" }
                        val others = displayList.filter { it.subTier !in listOf("CORE_DOPAMINE", "DEEP_CUT", "PALATE_CLEANSER", "SONIC_FRONTIER") }

                        if (coreTracks.isNotEmpty()) {
                            item {
                                TierHeaderBanner(
                                    tierNumber = "01",
                                    tierName = "CORE DOPAMINE ROTATION",
                                    tierSubtext = "Direct high-resonance hits matching your taste anchors",
                                    accentColor = DeepObsidianAmber.PrimaryAccent,
                                    count = coreTracks.size
                                )
                            }
                            items(coreTracks, key = { it.id }) { item ->
                                val index = displayList.indexOf(item) + 1
                                AiRecommendationCard(
                                    index = index,
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
                                    onDismiss = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.dismissAiRecommendation(item.id)
                                        scope.launch {
                                            val res = snackbarHostState.showSnackbar(
                                                message = "Dismissed '${item.title}' • AI updated taste boundary",
                                                actionLabel = "UNDO",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (res == SnackbarResult.ActionPerformed) {
                                                vm.restoreDismissedRecommendation(item.id)
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        if (deepCuts.isNotEmpty()) {
                            item {
                                TierHeaderBanner(
                                    tierNumber = "02",
                                    tierName = "SUBGENRE DEEP CUTS",
                                    tierSubtext = "Acclaimed b-sides & rising talent in your soundscape",
                                    accentColor = DeepObsidianAmber.NeuralViolet,
                                    count = deepCuts.size
                                )
                            }
                            items(deepCuts, key = { it.id }) { item ->
                                val index = displayList.indexOf(item) + 1
                                AiRecommendationCard(
                                    index = index,
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
                                    onDismiss = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.dismissAiRecommendation(item.id)
                                        scope.launch {
                                            val res = snackbarHostState.showSnackbar(
                                                message = "Dismissed '${item.title}' • AI updated taste boundary",
                                                actionLabel = "UNDO",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (res == SnackbarResult.ActionPerformed) {
                                                vm.restoreDismissedRecommendation(item.id)
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        if (cleansers.isNotEmpty()) {
                            item {
                                TierHeaderBanner(
                                    tierNumber = "03",
                                    tierName = "PALATE CLEANSER",
                                    tierSubtext = "Stripped-back acoustic melodies resetting ear fatigue",
                                    accentColor = DeepObsidianAmber.CryoCyan,
                                    count = cleansers.size
                                )
                            }
                            items(cleansers, key = { it.id }) { item ->
                                val index = displayList.indexOf(item) + 1
                                AiRecommendationCard(
                                    index = index,
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
                                    onDismiss = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.dismissAiRecommendation(item.id)
                                        scope.launch {
                                            val res = snackbarHostState.showSnackbar(
                                                message = "Dismissed '${item.title}' • AI updated taste boundary",
                                                actionLabel = "UNDO",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (res == SnackbarResult.ActionPerformed) {
                                                vm.restoreDismissedRecommendation(item.id)
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        if (frontiers.isNotEmpty()) {
                            item {
                                TierHeaderBanner(
                                    tierNumber = "04",
                                    tierName = "SONIC FRONTIER",
                                    tierSubtext = "Adjacent genre crossover safely expanding your dopamine reach",
                                    accentColor = DeepObsidianAmber.Success,
                                    count = frontiers.size
                                )
                            }
                            items(frontiers, key = { it.id }) { item ->
                                val index = displayList.indexOf(item) + 1
                                AiRecommendationCard(
                                    index = index,
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
                                    onDismiss = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.dismissAiRecommendation(item.id)
                                        scope.launch {
                                            val res = snackbarHostState.showSnackbar(
                                                message = "Dismissed '${item.title}' • AI updated taste boundary",
                                                actionLabel = "UNDO",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (res == SnackbarResult.ActionPerformed) {
                                                vm.restoreDismissedRecommendation(item.id)
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        if (others.isNotEmpty()) {
                            items(others, key = { it.id }) { item ->
                                val index = displayList.indexOf(item) + 1
                                AiRecommendationCard(
                                    index = index,
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
                                    onDismiss = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.dismissAiRecommendation(item.id)
                                        scope.launch {
                                            val res = snackbarHostState.showSnackbar(
                                                message = "Dismissed '${item.title}' • AI updated taste boundary",
                                                actionLabel = "UNDO",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (res == SnackbarResult.ActionPerformed) {
                                                vm.restoreDismissedRecommendation(item.id)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        // Plain list for Crate Vault and Saved Gems
                        items(displayList, key = { it.id }) { item ->
                            val index = displayList.indexOf(item) + 1
                            AiRecommendationCard(
                                index = index,
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
                                onDismiss = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    vm.dismissAiRecommendation(item.id)
                                    scope.launch {
                                        val res = snackbarHostState.showSnackbar(
                                            message = "Dismissed '${item.title}' • AI updated taste boundary",
                                            actionLabel = "UNDO",
                                            duration = SnackbarDuration.Short
                                        )
                                        if (res == SnackbarResult.ActionPerformed) {
                                            vm.restoreDismissedRecommendation(item.id)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
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
 * Visual Divider for each of the 4 Neuro-Acoustic Sub-Tiers.
 */
@Composable
private fun TierHeaderBanner(
    tierNumber: String,
    tierName: String,
    tierSubtext: String,
    accentColor: Color,
    count: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Text(
                    text = "$tierNumber // $tierName ($count)",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    letterSpacing = 0.8.sp
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .padding(start = 8.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                accentColor.copy(alpha = 0.5f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        Text(
            text = tierSubtext,
            fontSize = 9.sp,
            color = DeepObsidianAmber.TextMuted,
            lineHeight = 12.sp,
            modifier = Modifier.padding(start = 11.dp)
        )
    }
}

/**
 * Individual Chrono AI Recommendation Card.
 * Compact, high-density hardware cassette-style card.
 */
@Composable
private fun AiRecommendationCard(
    index: Int,
    item: AiRecommendationEntity,
    spoolRotation: Float,
    onPlay: () -> Unit,
    onToggleSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val (subTierLabel, subTierColor) = when (item.subTier.uppercase()) {
        "CORE_DOPAMINE" -> Pair("CORE", DeepObsidianAmber.PrimaryAccent)
        "DEEP_CUT" -> Pair("DEEP CUT", DeepObsidianAmber.NeuralViolet)
        "PALATE_CLEANSER" -> Pair("CLEANSER", DeepObsidianAmber.CryoCyan)
        "SONIC_FRONTIER" -> Pair("FRONTIER", DeepObsidianAmber.Success)
        else -> Pair("DISCOVERY", DeepObsidianAmber.SecondaryAccent)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DeepObsidianAmber.CardElevated,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(
                    subTierColor.copy(alpha = if (item.wasPlayed) 0.6f else 0.35f),
                    DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.25f)
                )
            )
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Track Number & Mini Cassette Spool
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = String.format("%02d", index),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = subTierColor
                )

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(DeepObsidianAmber.RecessedBackground)
                        .border(1.dp, subTierColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(if (item.wasPlayed) spoolRotation else 0f)
                            .clip(CircleShape)
                            .background(DeepObsidianAmber.SecondaryBackground)
                            .border(1.dp, subTierColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (item.wasPlayed) subTierColor else DeepObsidianAmber.RecessedBackground)
                        )
                    }
                }
            }

            // Song Info & Neuro Rationale
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = item.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = DeepObsidianAmber.RecessedBackground,
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, subTierColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = subTierLabel,
                            fontSize = 7.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = subTierColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Text(
                    text = item.artist,
                    fontSize = 11.sp,
                    color = DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = item.reason,
                    fontSize = 9.sp,
                    color = DeepObsidianAmber.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 12.sp
                )
            }

            // Right Action Cluster (Play / Bookmark / Dismiss)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Play Button
                Button(
                    onClick = onPlay,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = subTierColor,
                        contentColor = DeepObsidianAmber.AppBackground
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Icon(
                        imageVector = if (item.wasPlayed) Icons.Default.Replay else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = if (item.wasPlayed) "REPLAY" else "PLAY",
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Bookmark / Save Gem
                IconButton(
                    onClick = onToggleSave,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (item.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Save Gem",
                        tint = if (item.isSaved) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Dismiss / Negative Reinforcement
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Dismiss vibe",
                        tint = DeepObsidianAmber.TextMuted,
                        modifier = Modifier.size(15.dp)
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
            "Bongo Flava",
            "Mbosso",
            "Diamond Platnumz",
            "Harmonize",
            "Marioo",
            "Afrobeats",
            "Amapiano",
            "Hip-Hop",
            "R&B / Soul",
            "Dancehall",
            "Pop / Synthwave"
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
                    text = "Lock in your musical DNA so recommendations never default to generic ambient guesswork. Enter the artists and genres you actually love.",
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
                                "e.g. Mbosso, Bongo Flava, Diamond, Afrobeats...",
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
                            text = "ACTIVE TASTE ANCHORS (BOUND TO TUNE-MIND):",
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

