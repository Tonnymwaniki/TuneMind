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
            if (weeklyRecapEnabled && (isSundayToday || weeklyTrackCount > 0)) {
                add(
                    TuneMindAlertItem(
                        id = "sunday_share_drop_ready",
                        title = if (isSundayToday) {
                            "Sunday Roast Drop is Live"
                        } else {
                            "Your 7-Day & Monthly Share Cards Are Ready"
                        },
                        body = if (weeklyTrackCount > 0) {
                            "$weeklyTrackCount songs tracked this week. Post your Roast, DNA Receipt, or Vibe Check to WhatsApp Status, Instagram, TikTok & X."
                        } else {
                            "Every Sunday your 7-day listening roast drops here — swipe the cards above to preview or share anytime."
                        },
                        icon = Icons.Rounded.Whatshot,
                        accent = whatsAppGreen,
                        actionLabel = "View Cards",
                        onAction = {
                            scope.launch { scrollState.animateScrollTo(0) }
                        }
                    )
                )
            }

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
                            text = "Notifications & Drops",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = if (isSundayToday) "Sunday Drop Day · Share Your Cards" else "Weekly Sunday Drops · Monthly Wraps · Alerts",
                            fontSize = 11.5.sp,
                            color = if (isSundayToday) whatsAppGreen else Color.White.copy(alpha = 0.56f),
                            fontWeight = if (isSundayToday) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
                if (liveAlerts.isNotEmpty()) {
                    TextButton(
                        onClick = { dismissedIds = dismissedIds + liveAlerts.map { it.id } }
                    ) {
                        Text("Clear", color = neonCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. VIRAL SHAREABLE CARDS STUDIO
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = theme.primaryAccent
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Viral Share Cards",
                                color = Color(0xFFF8FAFC),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Swipe 3 playful roast cards · 9:16 WhatsApp & social ready",
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 11.5.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(100.dp),
                            color = whatsAppGreen.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, whatsAppGreen.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = if (isSundayToday) "SUNDAY LIVE" else "SUNDAY DROP",
                                color = whatsAppGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Time Window Switcher: 7 Days (Every Sunday) vs Monthly
                    MagicalSegmentedTabs(
                        tabs = listOf(
                            MagicalTabItem("7 Days (Sunday)", theme.primaryAccent),
                            MagicalTabItem("Monthly (30d)", theme.secondaryAccent)
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
                                    .width(if (active) 20.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(if (active) neonCyan else Color.White.copy(alpha = 0.24f))
                            )
                        }
                    }

                    // Direct Social Share Actions: WhatsApp, All Socials (IG/TikTok/X), Save PNG
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
                            modifier = Modifier.weight(1.35f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                tint = Color(0xFF041008),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isExportingCard) "Rendering..." else "WhatsApp / Status",
                                color = Color(0xFF041008),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

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
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                tint = neonCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "All Socials",
                                color = Color(0xFFF8FAFC),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
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
                                    contentDescription = "Save Card to Gallery",
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

                    // Compact 3-Step Posting Procedure Across Social Platforms
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = "HOW TO POST ANYWHERE",
                                color = neonCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                            Text(
                                text = "1. Pick 7 Days (Sunday) or Monthly, then swipe Roast, Receipt, or Vibe.",
                                color = Color.White.copy(alpha = 0.74f),
                                fontSize = 11.5.sp
                            )
                            Text(
                                text = "2. Tap WhatsApp / Status to drop straight into Status or friend chats.",
                                color = Color.White.copy(alpha = 0.74f),
                                fontSize = 11.5.sp
                            )
                            Text(
                                text = "3. Tap All Socials (or Save) to post crisp 9:16 PNG on Instagram Stories, TikTok, X, Telegram & Facebook.",
                                color = Color.White.copy(alpha = 0.74f),
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Live Activity Feed
            SoftGlassCard(modifier = Modifier.fillMaxWidth(), accentTint = theme.primaryAccent) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudioSectionHeader(title = "Activity Center")
                    if (liveAlerts.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = safeGreen,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "You're all caught up",
                                color = Color(0xFFF8FAFC),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Sunday share drops, Vault comeback alerts, hearing safety notices, and sync updates will appear here.",
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

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Sunday & Monthly Drop Reminders + Alert Preferences
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StudioSectionHeader(title = "Drop & Alert Preferences")
                        Text(
                            text = "Test Sunday Alert",
                            color = neonCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
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
                                            "Notification sent — tap it in your shade to test!"
                                        } else {
                                            "Enable notifications in Android Settings."
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    StudioHairlineDivider()

                    AlertPrefSwitchRow(
                        title = "Every Sunday Weekly Drop",
                        description = "Notify every Sunday when your 7-day roast cards are ready to post to WhatsApp & socials.",
                        checked = weeklyRecapEnabled,
                        accent = whatsAppGreen,
                        onCheckedChange = { AgentSettingsStore.setWeeklyRecapAlertsEnabled(context, it) }
                    )
                    StudioHairlineDivider()

                    AlertPrefSwitchRow(
                        title = "Monthly Recap Drop",
                        description = "Notify at the start of each month when your 30-day Music DNA Receipt is ready.",
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
