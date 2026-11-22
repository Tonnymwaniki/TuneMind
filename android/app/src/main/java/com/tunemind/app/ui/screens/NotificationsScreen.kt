package com.tunemind.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.domain.memory.StatsTimeWindow
import com.tunemind.app.domain.share.ShareCardFormat
import com.tunemind.app.domain.share.ShareRecapPeriod
import com.tunemind.app.domain.share.ViralShareCardEngine
import com.tunemind.app.domain.voice.AgentSettingsStore
import com.tunemind.app.sync.WeeklyShareDropWorker
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.components.ViralShareCardPreview
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.launch

private data class TuneMindAlertItem(
    val id: String,
    val title: String,
    val body: String,
    val icon: ImageVector,
    val accent: Color,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenHearing: () -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val theme = LocalAdaptiveTheme.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val authSession by TuneMindAuthSessionManager.getInstance(context).sessionFlow.collectAsState()
    val userProfile by vm.userProfile.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    val hearingInsights by vm.hearingInsightsState.collectAsState()
    val listeningStats by vm.listeningStats.collectAsState()
    val cleanPlayHistory by vm.cleanPlayHistory.collectAsState()
    val tasteShifts by vm.tasteShiftSummary.collectAsState()
    val homeState by vm.homeState.collectAsState()

    // 0 = Share Drops, 1 = Alerts, 2 = Schedule
    var selectedSectionTab by rememberSaveable { mutableStateOf(0) }
    // 0 = 7 Days (Sunday Drop), 1 = Monthly (30 Days)
    var selectedPeriodIndex by rememberSaveable { mutableStateOf(0) }
    val selectedPeriod = if (selectedPeriodIndex == 1) ShareRecapPeriod.MONTHLY else ShareRecapPeriod.SEVEN_DAYS
    val selectedWindow = if (selectedPeriodIndex == 1) StatsTimeWindow.THIRTY_DAYS else StatsTimeWindow.SEVEN_DAYS

    val windowedStats = remember(cleanPlayHistory, listeningStats, selectedWindow) {
        ListeningPlayDeduplicator.buildWindowedStats(
            cleanPlayHistory = cleanPlayHistory,
            overallStats = listeningStats,
            window = selectedWindow
        )
    }

    val weeklyTrackCount = remember(cleanPlayHistory, listeningStats) {
        ListeningPlayDeduplicator.buildWindowedStats(
            cleanPlayHistory = cleanPlayHistory,
            overallStats = listeningStats,
            window = StatsTimeWindow.SEVEN_DAYS
        ).uniqueTrackCount
    }

    val effectiveListenerName = remember(authSession.displayName, userProfile.name) {
        authSession.displayName.takeIf {
            it.isNotBlank() && !it.equals("TuneMind Listener", ignoreCase = true)
        } ?: userProfile.name
    }

    val shareCardModel = remember(
        selectedPeriod,
        effectiveListenerName,
        windowedStats,
        homeState.nowPlaying,
        vaultTracks,
        tasteShifts
    ) {
        ViralShareCardEngine.buildShareCardModel(
            context = context,
            period = selectedPeriod,
            listenerName = effectiveListenerName,
            stats = windowedStats,
            nowPlaying = homeState.nowPlaying,
            vaultTracks = vaultTracks,
            tasteShift = tasteShifts,
            resolvePlayCount = { t, a, uri -> vm.resolvePlayCount(t, a, uri) }
        )
    }

    val cardFormats = remember { ShareCardFormat.entries }
    val cardPagerState = rememberPagerState(initialPage = 0, pageCount = { cardFormats.size })
    val activeFormat = cardFormats[cardPagerState.currentPage.coerceIn(0, cardFormats.lastIndex)]

    var isExportingCard by remember { mutableStateOf(false) }
    var shareFeedback by remember { mutableStateOf<String?>(null) }

    val vaultAlertsEnabled by AgentSettingsStore.vaultRecoveryAlertsEnabled.collectAsState()
    val weeklyRecapEnabled by AgentSettingsStore.weeklyRecapAlertsEnabled.collectAsState()
    val monthlyRecapEnabled by AgentSettingsStore.monthlyRecapAlertsEnabled.collectAsState()
    val hearingAlertsEnabled by AgentSettingsStore.hearingAlertsEnabled.collectAsState()
    val syncAlertsEnabled by AgentSettingsStore.syncStatusAlertsEnabled.collectAsState()

    var dismissedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var hasListenerAccess by remember {
        mutableStateOf(PermissionUtils.hasNotificationListenerPermission(context))
    }
    var hasPostNotifAccess by remember {
        mutableStateOf(PermissionUtils.hasPostNotificationsPermission(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasListenerAccess = PermissionUtils.hasNotificationListenerPermission(context)
                hasPostNotifAccess = PermissionUtils.hasPostNotificationsPermission(context)
                if (hasListenerAccess) {
                    PermissionUtils.kickstartMediaObservation(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        AgentSettingsStore.ensureLoaded(context)
        vm.refreshLiveListeningStatsNow()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPostNotifAccess = granted || PermissionUtils.hasPostNotificationsPermission(context)
        if (hasPostNotifAccess) {
            val sent = WeeklyShareDropWorker.postInstantDropPreviewNotification(
                context = context,
                isMonthly = selectedPeriod == ShareRecapPeriod.MONTHLY
            )
            shareFeedback = if (sent) {
                "Notification sent — check your phone shade!"
            } else {
                "Notifications enabled for Sunday & Monthly drops."
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            PermissionUtils.isRuntimePermissionPermanentlyDeniedAfterResult(
                context = context,
                permission = Manifest.permission.POST_NOTIFICATIONS,
                granted = false
            )
        ) {
            PermissionUtils.openAppNotificationSettings(context)
            shareFeedback = "Turn on Notifications in Android Settings for Sunday drop alerts."
        } else {
            shareFeedback = "Notification permission denied. Enable notifications in Android Settings for Sunday drop alerts."
        }
    }

    val neonCyan = theme.primaryAccent
    val safeGreen = Color(0xFF10B981)
    val whatsAppGreen = Color(0xFF25D366)
    val warnAmber = Color(0xFFF59E0B)
    val electricPurple = theme.secondaryAccent
    val isSundayToday = remember { WeeklyShareDropWorker.isSundayToday() }

    val liveAlerts = remember(
        vaultTracks,
        hearingInsights,
        weeklyTrackCount,
        vaultAlertsEnabled,
        weeklyRecapEnabled,
        hearingAlertsEnabled,
        syncAlertsEnabled,
        dismissedIds,
        isSundayToday,
        hasListenerAccess,
        hasPostNotifAccess
    ) {
        buildList {
            if (syncAlertsEnabled && !hasListenerAccess) {
                add(
                    TuneMindAlertItem(
                        id = "perm_listener",
                        title = "Music detection paused",
                        body = "Notification access is off, so TuneMind cannot detect active tracks from your players.",
                        icon = Icons.Rounded.NotificationsActive,
                        accent = warnAmber,
                        actionLabel = "Enable Access",
                        onAction = {
                            PermissionUtils.openNotificationListenerSettings(context)
                        }
                    )
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotifAccess) {
                add(
                    TuneMindAlertItem(
                        id = "perm_post_notifications",
                        title = "Phone notifications are off",
                        body = "Allow notifications so TuneMind can send Sunday Roast Drop alerts and playback recovery banners.",
                        icon = Icons.Rounded.NotificationsOff,
                        accent = neonCyan,
                        actionLabel = "Allow Alerts",
                        onAction = {
                            runCatching {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }.onFailure {
                                PermissionUtils.openAppNotificationSettings(context)
                            }
                        }
                    )
                )
            }

            if (hearingAlertsEnabled && hearingInsights.currentHeadroomDb < 6f) {
                add(
                    TuneMindAlertItem(
                        id = "hearing_headroom",
                        title = "Safe volume headroom running low",
                        body = "Current headroom is +%.1f dB. Lowering volume slightly helps prevent listening fatigue."
                            .format(hearingInsights.currentHeadroomDb),
                        icon = Icons.Rounded.Hearing,
                        accent = warnAmber,
                        actionLabel = "View Hearing",
                        onAction = {
                            onBack()
                            onOpenHearing()
                        }
                    )
                )
            }

            if (vaultAlertsEnabled) {
                val recovered = vaultTracks.filter { it.recoveryProgress >= 0.75f }
                if (recovered.isNotEmpty()) {
                    val sample = recovered.first()
                    add(
                        TuneMindAlertItem(
                            id = "vault_ready_${sample.id}",
                            title = "${recovered.size} Vault ${if (recovered.size == 1) "track" else "tracks"} ready for comeback",
                            body = "\"${sample.title}\" by ${sample.artist} has refreshed cleanly and won't trigger fatigue.",
                            icon = Icons.Rounded.LockOpen,
                            accent = safeGreen,
                            actionLabel = "Open Discover",
                            onAction = {
                                onBack()
                                onOpenVault()
                            }
                        )
                    )
                }
            }

            if (weeklyRecapEnabled && weeklyTrackCount > 0) {
                add(
                    TuneMindAlertItem(
                        id = "weekly_recap_ready",
                        title = "Full Weekly Listening Breakdown",
                        body = "Deep-dive into your 7-day listening timeline, artists & completion scores.",
                        icon = Icons.Rounded.AutoGraph,
                        accent = electricPurple,
                        actionLabel = "Full Report",
                        onAction = {
                            onBack()
                            onOpenWeeklyReport()
                        }
                    )
                )
            }
        }.filterNot { dismissedIds.contains(it.id) }
    }

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFF8FAFC)
                        )
                    }
                    Column {
                        Text(
                            text = "Drops & Alerts",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = if (isSundayToday) "Sunday Drop Live · Ready to share" else "Sunday Roasts · Monthly Receipts · Alerts",
                            fontSize = 11.5.sp,
                            color = if (isSundayToday) whatsAppGreen else Color.White.copy(alpha = 0.56f),
                            fontWeight = if (isSundayToday) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
                if (selectedSectionTab == 1 && liveAlerts.isNotEmpty()) {
                    TextButton(
                        onClick = { dismissedIds = dismissedIds + liveAlerts.map { it.id } }
                    ) {
                        Text("Clear All", color = neonCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val alertsTabTitle = if (liveAlerts.isNotEmpty()) "Alerts (${liveAlerts.size})" else "Alerts"
            MagicalSegmentedTabs(
                tabs = listOf(
                    MagicalTabItem("Share Drops", whatsAppGreen),
                    MagicalTabItem(alertsTabTitle, neonCyan),
                    MagicalTabItem("Schedule", electricPurple)
                ),
                selectedIndex = selectedSectionTab,
                onSelectTab = { selectedSectionTab = it }
            )

            Spacer(modifier = Modifier.height(14.dp))

            when (selectedSectionTab) {
                0 -> {
                    // 1. VIRAL SHAREABLE CARDS STUDIO
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Compact Time Window Switcher: 7 Days (Sunday) vs Monthly (30d)
                        MagicalSegmentedTabs(
                            tabs = listOf(
                                MagicalTabItem("7-Day Sunday Drop", theme.primaryAccent),
                                MagicalTabItem("30-Day Monthly", theme.secondaryAccent)
                            ),
                            selectedIndex = selectedPeriodIndex,
                            onSelectTab = { selectedPeriodIndex = it }
                        )

                        // 3 Card Format Quick Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            cardFormats.forEachIndexed { idx, format ->
                                val selected = cardPagerState.currentPage == idx
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) neonCyan.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (selected) neonCyan.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.10f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            scope.launch { cardPagerState.animateScrollToPage(idx) }
                                        }
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${format.shortLabel}",
                                        color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .padding(vertical = 8.dp, horizontal = 8.dp)
                                            .wrapContentWidth(Alignment.CenterHorizontally)
                                    )
                                }
                            }
                        }

                        // Swipeable 3-Card Viral Carousel
                        HorizontalPager(
                            state = cardPagerState,
                            pageSpacing = 12.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) { page ->
                            val fmt = cardFormats[page]
                            ViralShareCardPreview(
                                format = fmt,
                                model = shareCardModel
                            )
                        }

                        // Page Indicator Dots
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(cardFormats.size) { idx ->
                                val active = cardPagerState.currentPage == idx
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .height(6.dp)
                                        .width(if (active) 22.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(if (active) neonCyan else Color.White.copy(alpha = 0.24f))
                                )
                            }
                        }

                        // Clean Viral Share Dock
                        Button(
                            onClick = {
                                if (isExportingCard) return@Button
                                isExportingCard = true
                                shareFeedback = null
                                scope.launch {
                                    val uri = ViralShareCardEngine.exportShareCardToCacheUri(
                                        context = context,
                                        format = activeFormat,
                                        model = shareCardModel,
                                        primaryAccentInt = theme.primaryAccent.toArgb(),
                                        secondaryAccentInt = theme.secondaryAccent.toArgb()
                                    )
                                    isExportingCard = false
                                    if (uri != null) {
                                        ViralShareCardEngine.shareToWhatsApp(
                                            context = context,
                                            imageUri = uri,
                                            caption = shareCardModel.shareCaption
                                        )
                                    } else {
                                        shareFeedback = "Could not render card image — please try again."
                                    }
                                }
                            },
                            enabled = !isExportingCard,
                            colors = ButtonDefaults.buttonColors(containerColor = whatsAppGreen),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                tint = Color(0xFF041008),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isExportingCard) "Rendering 9:16 Card..." else "Share to WhatsApp / Status",
                                color = Color(0xFF041008),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (isExportingCard) return@OutlinedButton
                                    isExportingCard = true
                                    shareFeedback = null
                                    scope.launch {
                                        val uri = ViralShareCardEngine.exportShareCardToCacheUri(
                                            context = context,
                                            format = activeFormat,
                                            model = shareCardModel,
                                            primaryAccentInt = theme.primaryAccent.toArgb(),
                                            secondaryAccentInt = theme.secondaryAccent.toArgb()
                                        )
                                        isExportingCard = false
                                        if (uri != null) {
                                            ViralShareCardEngine.shareToAllSocials(
                                                context = context,
                                                imageUri = uri,
                                                caption = shareCardModel.shareCaption
                                            )
                                        } else {
                                            shareFeedback = "Could not render card image — please try again."
                                        }
                                    }
                                },
                                enabled = !isExportingCard,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                                modifier = Modifier.weight(1.1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Public,
                                    contentDescription = null,
                                    tint = neonCyan,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "IG / TikTok / X",
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    ViralShareCardEngine.copyRoastCaption(context, shareCardModel.shareCaption)
                                    shareFeedback = "Roast caption copied — ready to paste!"
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "Copy Roast",
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.White.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = !isExportingCard) {
                                        isExportingCard = true
                                        shareFeedback = null
                                        scope.launch {
                                            val ok = ViralShareCardEngine.saveCardToGallery(
                                                context = context,
                                                format = activeFormat,
                                                model = shareCardModel,
                                                primaryAccentInt = theme.primaryAccent.toArgb(),
                                                secondaryAccentInt = theme.secondaryAccent.toArgb()
                                            )
                                            isExportingCard = false
                                            shareFeedback = if (ok) {
                                                "Saved 9:16 PNG to Pictures/TuneMind!"
                                            } else {
                                                "Unable to save image to Gallery."
                                            }
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = "Save 9:16 PNG to Gallery",
                                        tint = Color(0xFFF8FAFC),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }

                        if (!shareFeedback.isNullOrBlank()) {
                            Text(
                                text = shareFeedback!!,
                                color = neonCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                1 -> {
                    // 2. Live Activity Feed
                    SoftGlassCard(modifier = Modifier.fillMaxWidth(), accentTint = theme.primaryAccent) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StudioSectionHeader(title = "Activity Center")
                            if (liveAlerts.isEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = safeGreen,
                                        modifier = Modifier.size(34.dp)
                                    )
                                    Text(
                                        text = "You're all caught up",
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Vault comeback alerts, hearing safety notices, and sync updates will appear here.",
                                        color = Color.White.copy(alpha = 0.56f),
                                        fontSize = 12.5.sp
                                    )
                                }
                            } else {
                                liveAlerts.forEach { alert ->
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = alert.accent.copy(alpha = 0.08f),
                                        border = BorderStroke(1.dp, alert.accent.copy(alpha = 0.30f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(alert.accent.copy(alpha = 0.16f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = alert.icon,
                                                    contentDescription = null,
                                                    tint = alert.accent,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = alert.title,
                                                    color = Color(0xFFF8FAFC),
                                                    fontSize = 14.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = alert.body,
                                                    color = Color.White.copy(alpha = 0.68f),
                                                    fontSize = 12.5.sp,
                                                    lineHeight = 18.sp
                                                )
                                                if (!alert.actionLabel.isNullOrBlank() && alert.onAction != null) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        OutlinedButton(
                                                            onClick = alert.onAction,
                                                            shape = RoundedCornerShape(28.dp),
                                                            border = BorderStroke(1.dp, alert.accent.copy(alpha = 0.55f)),
                                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                                                        ) {
                                                            Text(alert.actionLabel, color = alert.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                        }
                                                        TextButton(
                                                            onClick = { dismissedIds = dismissedIds + alert.id }
                                                        ) {
                                                            Text("Dismiss", color = Color.White.copy(alpha = 0.52f), fontSize = 12.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    // 3. Sunday & Monthly Drop Schedule + Alert Preferences
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SoftGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            accentTint = whatsAppGreen
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = if (isSundayToday) "Sunday Drop is Live Today" else "Automated Drop Schedule",
                                        color = Color(0xFFF8FAFC),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "7-Day Roasts drop every Sunday · 30-Day Receipts drop on the 1st of each month.",
                                        color = Color.White.copy(alpha = 0.64f),
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            !WeeklyShareDropWorker.hasNotificationPermission(context)
                                        ) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            val sent = WeeklyShareDropWorker.postInstantDropPreviewNotification(
                                                context = context,
                                                isMonthly = selectedPeriod == ShareRecapPeriod.MONTHLY
                                            )
                                            shareFeedback = if (sent) {
                                                "Test notification sent — check your phone shade!"
                                            } else {
                                                "Enable notifications in Android Settings."
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.55f)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "Test Alert",
                                        color = neonCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (!shareFeedback.isNullOrBlank()) {
                            Text(
                                text = shareFeedback!!,
                                color = neonCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                StudioSectionHeader(title = "Alert Preferences")
                                Spacer(modifier = Modifier.height(4.dp))
                                StudioHairlineDivider()

                                AlertPrefSwitchRow(
                                    title = "Every Sunday Weekly Drop",
                                    description = "Notify every Sunday when your 7-day roast cards are ready to share.",
                                    checked = weeklyRecapEnabled,
                                    accent = whatsAppGreen,
                                    onCheckedChange = { AgentSettingsStore.setWeeklyRecapAlertsEnabled(context, it) }
                                )
                                StudioHairlineDivider()

                                AlertPrefSwitchRow(
                                    title = "Monthly Recap Drop",
                                    description = "Notify on the 1st of each month when your 30-day Music DNA Receipt is ready.",
                                    checked = monthlyRecapEnabled,
                                    accent = electricPurple,
                                    onCheckedChange = { AgentSettingsStore.setMonthlyRecapAlertsEnabled(context, it) }
                                )
                                StudioHairlineDivider()

                                AlertPrefSwitchRow(
                                    title = "Vault Comeback Alerts",
                                    description = "Notify when resting songs regain peak freshness in Cooldown Vault.",
                                    checked = vaultAlertsEnabled,
                                    accent = safeGreen,
                                    onCheckedChange = { AgentSettingsStore.setVaultRecoveryAlertsEnabled(context, it) }
                                )
                                StudioHairlineDivider()

                                AlertPrefSwitchRow(
                                    title = "Safe Listening Warnings",
                                    description = "Alert when continuous high-volume headroom gets low.",
                                    checked = hearingAlertsEnabled,
                                    accent = warnAmber,
                                    onCheckedChange = { AgentSettingsStore.setHearingAlertsEnabled(context, it) }
                                )
                                StudioHairlineDivider()

                                AlertPrefSwitchRow(
                                    title = "Sync & Permission Health",
                                    description = "Alert if music detection or background sync needs attention.",
                                    checked = syncAlertsEnabled,
                                    accent = neonCyan,
                                    onCheckedChange = { AgentSettingsStore.setSyncStatusAlertsEnabled(context, it) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AlertPrefSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
            Text(text = description, fontSize = 12.sp, color = Color.White.copy(alpha = 0.56f), lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0D0D0D),
                checkedTrackColor = accent,
                uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.16f)
            )
        )
    }
}
