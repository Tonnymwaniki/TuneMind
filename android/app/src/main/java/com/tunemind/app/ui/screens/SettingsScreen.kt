package com.tunemind.app.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunemind.app.ui.AudioFocusBehavior
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onOpenCrossApp: () -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenAccountAuth: () -> Unit = {},
    onOpenSupport: () -> Unit = {},
    onOpenLegal: (Int) -> Unit = {},
    openSpotifySetupInitially: Boolean = false,
    onSpotifySetupSheetConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val registry = remember(context) { com.tunemind.app.provider.MusicProviderRegistry.getInstance(context) }
    var selectedProviderId by remember { mutableStateOf(registry.getPreferredProviderId()) }
    var alwaysAsk by remember { mutableStateOf(registry.isAlwaysAskEnabled()) }
    val providers = remember(registry) {
        com.tunemind.app.ui.components.MusicPlayerUiHelper.filterMusicPlayers(
            context = context,
            rawProviders = registry.getInstalledProviders()
        )
    }
    var showResetSheet by remember { mutableStateOf(false) }

    val isBatteryIgnored by vm.isBatteryOptimizationIgnored.collectAsState()
    val audioFocusBehavior by vm.audioFocusBehavior.collectAsState()
    val isDecibelSafetyEnabled by vm.isDecibelSafetyAlertEnabled.collectAsState()

    val autonomousDjEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.autonomousDjEnabled.collectAsState()
    val upNextAutoOverrideEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.upNextAutoOverrideEnabled.collectAsState()
    val stayInTuneMindEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.stayInTuneMindEnabled.collectAsState()
    val strictNoAppSwitchEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.strictNoAppSwitchEnabled.collectAsState()
    val preferInAppPlayerEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.preferInAppPlayerEnabled.collectAsState()
    val backgroundAutoPlayEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.backgroundAutoPlayEnabled.collectAsState()
    val kidsModeEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.kidsModeEnabled.collectAsState()
    val spotifyFreeOpenInSpotify by com.tunemind.app.domain.voice.AgentSettingsStore.spotifyFreeOpenInSpotify.collectAsState()
    val autoReturnAfterSpotifyWake by com.tunemind.app.domain.voice.AgentSettingsStore.autoReturnAfterSpotifyWakeEnabled.collectAsState()

    var hasNotificationAccess by remember { mutableStateOf(com.tunemind.app.util.PermissionUtils.hasNotificationListenerPermission(context)) }
    var hasAudioPermission by remember { mutableStateOf(com.tunemind.app.util.PermissionUtils.hasAudioStoragePermission(context)) }
    var hasOverlayPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)
        )
    }

    val fatigueSensitivity by com.tunemind.app.domain.voice.AgentSettingsStore.repetitionSensitivity.collectAsState()
    val chronoReceptivity by com.tunemind.app.domain.voice.AgentSettingsStore.lateNightComfortEnabled.collectAsState()
    val cloakingSession by com.tunemind.app.domain.voice.AgentSettingsStore.privateSessionEnabled.collectAsState()
    val deduplicateLogs by com.tunemind.app.domain.voice.AgentSettingsStore.mergeDuplicatePlaysEnabled.collectAsState()
    val spotifyConnectStatus by com.tunemind.app.provider.SpotifyPremiumConnectManager.statusFlow.collectAsState()
    val spotifyLibrarySyncState by vm.spotifyLibrarySyncState.collectAsState()
    val billingState by vm.billingState.collectAsState()
    var spotifyClientIdInput by remember { mutableStateOf(spotifyConnectStatus.clientId) }
    var detectedClipboardClientId by remember { mutableStateOf<String?>(null) }
    var copiedRedirectUri by remember { mutableStateOf(false) }
    var showSpotifySetupSheet by remember { mutableStateOf(openSpotifySetupInitially) }
    var isCheckingSpotifyDevice by remember { mutableStateOf(false) }
    var spotifyDeviceCheckResult by remember { mutableStateOf<com.tunemind.app.provider.SpotifyDeviceCheckResult?>(null) }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(openSpotifySetupInitially) {
        if (openSpotifySetupInitially) {
            showSpotifySetupSheet = true
            onSpotifySetupSheetConsumed()
        }
    }

    LaunchedEffect(spotifyConnectStatus.clientId) {
        if (spotifyClientIdInput.isBlank() && spotifyConnectStatus.clientId.isNotBlank()) {
            spotifyClientIdInput = spotifyConnectStatus.clientId
        }
    }

    val scrollState = rememberScrollState()
    val colors = TuneMindTheme.colors
    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFF59E0B)
    val dangerRed = Color(0xFFFF4D6D)

    val refreshAllSystemStates: () -> Unit = remember(context) {
        {
            com.tunemind.app.domain.voice.AgentSettingsStore.ensureLoaded(context)
            com.tunemind.app.provider.SpotifyPremiumConnectManager.ensureLoaded(context)
            com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine.ensureLoaded(context)
            vm.checkBatteryOptimization(context)
            hasNotificationAccess = com.tunemind.app.util.PermissionUtils.hasNotificationListenerPermission(context)
            hasAudioPermission = com.tunemind.app.util.PermissionUtils.hasAudioStoragePermission(context)
            hasOverlayPermission = com.tunemind.app.util.PermissionUtils.hasOverlayPermission(context)
            if (hasNotificationAccess) {
                com.tunemind.app.util.PermissionUtils.kickstartMediaObservation(context)
            }
            selectedProviderId = registry.getPreferredProviderId()
            alwaysAsk = registry.isAlwaysAskEnabled()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted || com.tunemind.app.util.PermissionUtils.hasAudioStoragePermission(context)
        if (!hasAudioPermission &&
            com.tunemind.app.util.PermissionUtils.isRuntimePermissionPermanentlyDeniedAfterResult(
                context = context,
                permission = com.tunemind.app.util.PermissionUtils.audioStoragePermissionName(),
                granted = false
            )
        ) {
            com.tunemind.app.util.PermissionUtils.openAppDetailsSettings(context)
        }
        refreshAllSystemStates()
    }

    LaunchedEffect(Unit) {
        refreshAllSystemStates()
        withContext(Dispatchers.IO) {
            com.tunemind.app.provider.SpotifyPremiumConnectManager.syncAppDefaultClientId(context)
            com.tunemind.app.provider.SpotifyPremiumConnectManager.restoreConnectionFromCloudIfNeeded(context)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAllSystemStates()
                val clipCandidate = clipboardManager.getText()?.text?.trim().orEmpty()
                detectedClipboardClientId = if (
                    com.tunemind.app.provider.SpotifyPremiumConnectManager.isValidClientIdFormat(clipCandidate) &&
                    clipCandidate != spotifyClientIdInput.trim()
                ) {
                    clipCandidate
                } else {
                    null
                }
                coroutineScope.launch {
                    delay(350L)
                    refreshAllSystemStates()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                Text(
                    text = "Settings",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 0. TuneMind Pro & Subscription Plan
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioSectionHeader(title = "TuneMind Pro & Plan")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.openPaywall(
                                if (billingState.hasProAccess) null
                                else "Start your 7-day free trial for Pulse Voice Agent, 15 daily For You picks & Vault insights."
                            )
                        }
                        .padding(vertical = 13.dp, horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = if (billingState.hasProAccess) safeGreen else colors.primaryAccent,
                            modifier = Modifier.size(19.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            val planTitle = when {
                                billingState.isTrial -> "7-Day Free Trial Active (${billingState.trialDaysRemaining}d left)"
                                billingState.hasProAccess -> "TuneMind Pro Active"
                                else -> "Free Forever · Upgrade to Pro"
                            }
                            val planDesc = when {
                                billingState.hasProAccess && billingState.dailyAgentLimit > 0 ->
                                    "${billingState.dailyAgentUsed}/${billingState.dailyAgentLimit} daily AI chats used · Tap to manage"
                                billingState.hasProAccess ->
                                    "Full Conversational Agent & 15 Daily Picks active"
                                else ->
                                    "Unlimited tracking & Spotify playback free · Try Pro 7 days free"
                            }
                            Text(
                                text = planTitle,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = planDesc,
                                fontSize = 12.sp,
                                color = if (billingState.hasProAccess) safeGreen.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.58f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f)
                    )
                }
                StudioHairlineDivider()
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 1. Voice & Playback (Open-Canvas Section)
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioSectionHeader(title = "Voice & Playback")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Hands-Free DJ",
                description = if (autonomousDjEnabled) {
                    "Asks what to play next when a song finishes."
                } else {
                    "Off: TuneMind stays quiet until you speak or tap."
                },
                checked = autonomousDjEnabled,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setAutonomousDjEnabled(context, it)
                }
            )
            StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Auto Queue Takeover",
                description = if (upNextAutoOverrideEnabled) {
                    "Stops external autoplay at song end and spins TuneMind's Up Next track."
                } else {
                    "Off: Lets your music app advance unless you tap Next."
                },
                checked = upNextAutoOverrideEnabled,
                onCheckedChange = {
                    vm.setUpNextAutoOverrideEnabled(it)
                }
            )
            StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Never Leave TuneMind",
                description = if (strictNoAppSwitchEnabled) {
                    "Strictly keeps playback in TuneMind (Spotify Premium Direct Connect, Local, Audius, warm session, or built-in full player)."
                } else {
                    "Hybrid Mode: Plays inside TuneMind first, then opens exact links when external apps are cold."
                },
                checked = strictNoAppSwitchEnabled,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setStrictNoAppSwitchEnabled(context, it)
                }
            )
            StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Kids & Family Safe Mode",
                description = if (kidsModeEnabled) {
                    "Active: Filters explicit songs and favors kid-safe music."
                } else {
                    "Off: Ask TuneMind for kids songs anytime or toggle safe mode here."
                },
                checked = kidsModeEnabled,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setKidsModeEnabled(context, it)
                }
            )
            StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Stay in TuneMind",
                description = if (stayInTuneMindEnabled) {
                    "Controls active Boomplay, YouTube Music, Spotify, and Audiomack sessions quietly without app-switching."
                } else {
                    "Off: Opens your selected music app directly when starting a track."
                },
                checked = stayInTuneMindEnabled,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setStayInTuneMindEnabled(context, it)
                }
            )
            StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Built-In Audio Player",
                description = if (preferInAppPlayerEnabled) {
                    "Plays local files, Audius streams, and previews directly in TuneMind."
                } else {
                    "Off: Routes playback to your selected music app."
                },
                checked = preferInAppPlayerEnabled,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setPreferInAppPlayerEnabled(context, it)
                }
            )
            StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Auto-Switch Overplayed Songs",
                description = if (backgroundAutoPlayEnabled) {
                    "Automatically switches away when a song gets overplayed."
                } else {
                    "Recommended off: You approve every track change."
                },
                checked = backgroundAutoPlayEnabled,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setBackgroundAutoPlayEnabled(context, it)
                }
            )
            StudioHairlineDivider()

            // Hybrid Cross-App Detection inline status
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cross-App Hybrid Engine",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = if (hasNotificationAccess) "Ready" else "Grant Access",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasNotificationAccess) safeGreen else warnAmber
                    )
                }

                Text(
                    text = if (hasNotificationAccess) {
                        "Detected ${providers.size} installed music player(s). Songs played across any app are saved permanently into dynamic Library tabs."
                    } else {
                        "Grant Music Detection access below so TuneMind detects songs from Boomplay, YouTube Music, Spotify, Audiomack, and phone audio automatically."
                    },
                    fontSize = 12.sp,
                    color = if (hasNotificationAccess) Color.White.copy(alpha = 0.60f) else warnAmber,
                    lineHeight = 17.sp
                )
            }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 1b. Spotify Connect (Premium Silent Playback + Free Smart Mode + Encrypted Cloud Persistence)
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioSectionHeader(title = "Spotify Connect (Premium & Free)")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = when {
                                    spotifyConnectStatus.isConnected && spotifyConnectStatus.isPremium ->
                                        "Spotify Premium · Full Silent Control"
                                    spotifyConnectStatus.isFreeTier ->
                                        "Spotify Free · Smart Full-Track Mode"
                                    else ->
                                        "Connect Spotify Once, Stay Linked"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = when {
                                    spotifyConnectStatus.isConnected && spotifyConnectStatus.isPremium -> {
                                        val who = spotifyConnectStatus.displayName.ifBlank { "Spotify Premium" }
                                        val dev = spotifyConnectStatus.lastDeviceName
                                        val cloudNote = if (spotifyConnectStatus.cloudBackedUp) " · Cloud synced" else ""
                                        if (dev.isNotBlank()) "Signed in as $who · Device: $dev$cloudNote"
                                        else "Signed in as $who · Silent full songs, queue & likes$cloudNote"
                                    }
                                    spotifyConnectStatus.isFreeTier -> {
                                        val who = spotifyConnectStatus.displayName.ifBlank { "Spotify listener" }
                                        val cloudNote = if (spotifyConnectStatus.cloudBackedUp) " · Cloud synced" else ""
                                        "Signed in as $who$cloudNote · Likes sync to Spotify. Full tracks play in TuneMind when available or open directly in Spotify (no 30s previews)."
                                    }
                                    spotifyConnectStatus.isReadyForOneTapConnect -> {
                                        "Connect once (Premium or Free). TuneMind saves an encrypted cloud backup so you won't have to reconnect again."
                                    }
                                    else -> {
                                        "Link Spotify in 60 seconds (Premium gets silent in-app full tracks & queue; Free gets Likes sync & direct full-song handoff). Ask the voice agent anytime for help."
                                    }
                                },
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.62f),
                                lineHeight = 17.sp
                            )
                        }
                        val statusLabel = when {
                            spotifyConnectStatus.isAuthorizing -> "Connecting..."
                            spotifyConnectStatus.isConnected && spotifyConnectStatus.isPremium && spotifyConnectStatus.cloudBackedUp -> "Synced"
                            spotifyConnectStatus.isConnected && spotifyConnectStatus.isPremium -> "Premium"
                            spotifyConnectStatus.isFreeTier -> "Free Smart"
                            spotifyConnectStatus.isReadyForOneTapConnect -> "Ready"
                            else -> "Setup"
                        }
                        val statusColor = when {
                            spotifyConnectStatus.isAuthorizing -> colors.primaryAccent
                            spotifyConnectStatus.isConnected && spotifyConnectStatus.isPremium -> safeGreen
                            spotifyConnectStatus.isFreeTier -> colors.primaryAccent
                            spotifyConnectStatus.isReadyForOneTapConnect -> colors.primaryAccent
                            else -> Color.White.copy(alpha = 0.56f)
                        }
                        Text(
                            text = statusLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                    }

                    spotifyConnectStatus.lastError?.takeIf { it.isNotBlank() }?.let { errMsg ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = errMsg,
                                fontSize = 12.sp,
                                color = warnAmber,
                                lineHeight = 16.sp
                            )
                            when (spotifyConnectStatus.errorHintType) {
                                com.tunemind.app.provider.SpotifyErrorHintType.REDIRECT_URI -> {
                                    OutlinedButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(com.tunemind.app.provider.SpotifyPremiumConnectManager.REDIRECT_URI))
                                            copiedRedirectUri = true
                                        },
                                        modifier = Modifier.height(38.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        border = BorderStroke(1.dp, colors.primaryAccent.copy(alpha = 0.55f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primaryAccent)
                                    ) {
                                        Text(
                                            text = if (copiedRedirectUri) "Copied Redirect URI" else "Copy tunemind://spotify-callback",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                com.tunemind.app.provider.SpotifyErrorHintType.DEV_MODE_ALLOWLIST -> {
                                    OutlinedButton(
                                        onClick = {
                                            com.tunemind.app.provider.SpotifyPremiumConnectManager.openSpotifyDeveloperDashboard(context)
                                        },
                                        modifier = Modifier.height(38.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        border = BorderStroke(1.dp, colors.primaryAccent.copy(alpha = 0.55f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primaryAccent)
                                    ) {
                                        Text("Open Spotify Dashboard", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                com.tunemind.app.provider.SpotifyErrorHintType.NO_ACTIVE_DEVICE -> {
                                    OutlinedButton(
                                        onClick = {
                                            com.tunemind.app.provider.SpotifyPremiumConnectManager.openSpotifyAppToWakeDevice(context)
                                        },
                                        modifier = Modifier.height(38.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        border = BorderStroke(1.dp, colors.primaryAccent.copy(alpha = 0.55f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primaryAccent)
                                    ) {
                                        Text("Wake Spotify", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                com.tunemind.app.provider.SpotifyErrorHintType.INVALID_CLIENT,
                                com.tunemind.app.provider.SpotifyErrorHintType.REVOKED -> {
                                    OutlinedButton(
                                        onClick = { showSpotifySetupSheet = true },
                                        modifier = Modifier.height(38.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        border = BorderStroke(1.dp, colors.primaryAccent.copy(alpha = 0.55f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primaryAccent)
                                    ) {
                                        Text("Open Setup Guide", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }

                    spotifyDeviceCheckResult?.let { check ->
                        Text(
                            text = check.message,
                            fontSize = 12.sp,
                            color = if (check.hasActiveDevice) safeGreen else warnAmber,
                            lineHeight = 16.sp
                        )
                    }

                    if (spotifyConnectStatus.isConnected) {
                        // 1-Tap Full Spotify Library & Playlists Sync (works for both Premium & Free!)
                        Button(
                            onClick = { vm.syncSpotifyLibraryNow(forceRefresh = true) },
                            enabled = !spotifyLibrarySyncState.isSyncing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = safeGreen,
                                contentColor = Color(0xFF04100B)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (spotifyLibrarySyncState.isSyncing) {
                                    spotifyLibrarySyncState.progressMessage.ifBlank { "Syncing Spotify Library..." }
                                } else if (spotifyLibrarySyncState.hasSyncedBefore) {
                                    "Re-sync Spotify Library & Playlists"
                                } else {
                                    "Sync Spotify Liked Songs & Playlists"
                                },
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = spotifyLibrarySyncState.lastError ?: spotifyLibrarySyncState.summaryLabel,
                            fontSize = 12.sp,
                            color = when {
                                !spotifyLibrarySyncState.lastError.isNullOrBlank() -> warnAmber
                                spotifyLibrarySyncState.hasSyncedBefore -> safeGreen.copy(alpha = 0.9f)
                                else -> Color.White.copy(alpha = 0.62f)
                            },
                            lineHeight = 16.sp
                        )

                        if (spotifyLibrarySyncState.optionalInsightsNeedReconnect) {
                            Text(
                                text = "Tip: Reconnect Spotify once to also unlock Recently Played & Top Artists sync.",
                                fontSize = 11.5.sp,
                                color = colors.primaryAccent.copy(alpha = 0.85f),
                                lineHeight = 15.sp,
                                modifier = Modifier.clickable {
                                    com.tunemind.app.provider.SpotifyPremiumConnectManager.startDefaultAuthorization(context)
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (spotifyConnectStatus.isPremium) {
                                OutlinedButton(
                                    onClick = {
                                        isCheckingSpotifyDevice = true
                                        coroutineScope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                com.tunemind.app.provider.SpotifyPremiumConnectManager.checkActiveDevice(context)
                                            }
                                            spotifyDeviceCheckResult = result
                                            isCheckingSpotifyDevice = false
                                        }
                                    },
                                    enabled = !isCheckingSpotifyDevice,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    border = BorderStroke(1.dp, colors.primaryAccent.copy(alpha = 0.55f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primaryAccent)
                                ) {
                                    Text(
                                        text = if (isCheckingSpotifyDevice) "Checking..." else "Check Device",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    spotifyDeviceCheckResult = null
                                    com.tunemind.app.provider.SpotifyPremiumConnectManager.disconnect(context)
                                },
                                modifier = Modifier
                                    .weight(if (spotifyConnectStatus.isPremium) 0.9f else 1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(24.dp),
                                border = BorderStroke(1.dp, dangerRed.copy(alpha = 0.45f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = dangerRed)
                            ) {
                                Text("Disconnect", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (spotifyConnectStatus.isPremium && spotifyDeviceCheckResult?.hasActiveDevice == false) {
                            Button(
                                onClick = {
                                    com.tunemind.app.provider.SpotifyPremiumConnectManager.openSpotifyAppToWakeDevice(context)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.primaryAccent,
                                    contentColor = Color(0xFF061018)
                                )
                            ) {
                                Text("Cold-Wake Spotify Device", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (spotifyConnectStatus.isPremium) {
                            StudioHairlineDivider()
                            SettingsSwitchRow(
                                title = "Auto-Return After Spotify Cold-Wake",
                                description = if (hasOverlayPermission && autoReturnAfterSpotifyWake) {
                                    "Active: TuneMind wakes Spotify silently first — and if Android requires a quick screen wake after clearing Recent Apps, TuneMind starts your song and returns automatically."
                                } else if (!hasOverlayPermission) {
                                    "Turn on to grant 'Appear on top' so TuneMind automatically snaps back to this screen after waking Spotify when Recent Apps were cleared."
                                } else {
                                    "Off: Uses silent cold-wake first and shows a 1-tap 'Back to TuneMind' banner if Spotify had to open."
                                },
                                checked = hasOverlayPermission && autoReturnAfterSpotifyWake,
                                onCheckedChange = { enable ->
                                    if (enable && !hasOverlayPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        com.tunemind.app.domain.voice.AgentSettingsStore.setAutoReturnAfterSpotifyWakeEnabled(context, true)
                                        com.tunemind.app.util.PermissionUtils.openOverlayPermissionSettings(context)
                                    } else {
                                        com.tunemind.app.domain.voice.AgentSettingsStore.setAutoReturnAfterSpotifyWakeEnabled(context, enable)
                                    }
                                }
                            )
                        }

                        Text(
                            text = if (spotifyConnectStatus.usesCustomClientId) {
                                "Using your personal Spotify Client ID · Tap to edit setup"
                            } else {
                                "Advanced: Switch to your own Spotify Client ID"
                            },
                            fontSize = 12.sp,
                            color = colors.primaryAccent.copy(alpha = 0.85f),
                            modifier = Modifier
                                .clickable { showSpotifySetupSheet = true }
                                .padding(vertical = 2.dp)
                        )
                    } else if (spotifyConnectStatus.isReadyForOneTapConnect) {
                        Button(
                            onClick = {
                                com.tunemind.app.provider.SpotifyPremiumConnectManager.startDefaultAuthorization(context)
                            },
                            enabled = !spotifyConnectStatus.isAuthorizing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primaryAccent,
                                contentColor = Color(0xFF061018)
                            )
                        ) {
                            Text(
                                text = if (spotifyConnectStatus.isAuthorizing) "Opening Spotify..." else "Connect Spotify",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = if (spotifyConnectStatus.usesCustomClientId) {
                                "Personal Client ID saved (••••${spotifyConnectStatus.clientId.takeLast(4)}) · Edit or view setup guide"
                            } else {
                                "Advanced: Use your own Spotify Client ID instead"
                            },
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.54f),
                            modifier = Modifier
                                .clickable { showSpotifySetupSheet = true }
                                .padding(vertical = 2.dp)
                        )
                    } else {
                        Button(
                            onClick = { showSpotifySetupSheet = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primaryAccent,
                                contentColor = Color(0xFF061018)
                            )
                        ) {
                            Text(
                                text = "Set Up Spotify Connect (1 min)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Spotify Free Smart Mode toggle (visible for Free users & unconnected listeners)
                    if (!spotifyConnectStatus.isPremium) {
                        StudioHairlineDivider()
                        SettingsSwitchRow(
                            title = "Spotify Free: Never Play 30s Previews",
                            description = "When a full track can't play inside TuneMind, open it straight in Spotify for the full song instead of a 30-second preview.",
                            checked = spotifyFreeOpenInSpotify,
                            onCheckedChange = {
                                com.tunemind.app.domain.voice.AgentSettingsStore.setSpotifyFreeOpenInSpotifyEnabled(context, it)
                            }
                        )
                    }

                    Text(
                        text = "Secured with OAuth 2.0 PKCE + encrypted cloud backup so you never have to reconnect. Works alongside MediaSession tracking for YouTube Music, Boomplay, Audiomack, Deezer, SoundCloud, and local audio.",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.46f),
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 2. Permissions (Open-Canvas Section)
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioSectionHeader(title = "Permissions")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()

            SettingsPermissionRow(
                title = "Music Detection",
                description = if (hasNotificationAccess) {
                    "Reads what song is playing across your music apps."
                } else {
                    "Needed to track plays and freshness automatically."
                },
                isEnabled = hasNotificationAccess,
                colors = colors,
                onClick = {
                    com.tunemind.app.util.PermissionUtils.openNotificationListenerSettings(context)
                }
            )
            if (!hasNotificationAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Text(
                    text = "Grayed out in Android Settings? Tap here → App Info → ⋮ → Allow restricted settings",
                    fontSize = 11.5.sp,
                    color = warnAmber.copy(alpha = 0.88f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            com.tunemind.app.util.PermissionUtils.openAppDetailsSettings(context)
                        }
                        .padding(bottom = 10.dp)
                )
            }
            StudioHairlineDivider()

            SettingsPermissionRow(
                title = "Local Audio Library",
                description = if (hasAudioPermission) {
                    "Built-in player can play songs saved on your phone."
                } else {
                    "Lets the built-in player find local audio files."
                },
                isEnabled = hasAudioPermission,
                colors = colors,
                onClick = {
                    if (!hasAudioPermission) {
                        runCatching {
                            audioPermissionLauncher.launch(
                                com.tunemind.app.util.PermissionUtils.audioStoragePermissionName()
                            )
                        }.onFailure {
                            com.tunemind.app.util.PermissionUtils.openAppDetailsSettings(context)
                        }
                    } else {
                        com.tunemind.app.util.PermissionUtils.openAppDetailsSettings(context)
                    }
                }
            )
            StudioHairlineDivider()

            SettingsPermissionRow(
                title = "Unrestricted Background",
                description = if (isBatteryIgnored) {
                    "Keeps background sync and tracking running smoothly."
                } else {
                    "Allow unrestricted battery so tracking does not pause."
                },
                isEnabled = isBatteryIgnored,
                colors = colors,
                onClick = { vm.requestIgnoreBatteryOptimization(context) }
            )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 3. Audio & Hearing (Open-Canvas Section)
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioSectionHeader(title = "Audio & Hearing")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Safe Listening Alerts",
                description = "Alerts you before extended high-volume headphone listening passes daily guidelines.",
                checked = isDecibelSafetyEnabled,
                onCheckedChange = { vm.setDecibelSafetyAlertEnabled(it) }
            )
            StudioHairlineDivider()

            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(
                    text = "When alerts interrupt music",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.56f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setAudioFocusBehavior(AudioFocusBehavior.DUCK) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RadioButton(
                        selected = audioFocusBehavior == AudioFocusBehavior.DUCK,
                        onClick = { vm.setAudioFocusBehavior(AudioFocusBehavior.DUCK) },
                        colors = RadioButtonDefaults.colors(selectedColor = colors.primaryAccent)
                    )
                    Column {
                        Text("Lower volume briefly", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
                        Text("Softens music during alerts, then restores it", fontSize = 12.sp, color = Color.White.copy(alpha = 0.56f))
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setAudioFocusBehavior(AudioFocusBehavior.PAUSE) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RadioButton(
                        selected = audioFocusBehavior == AudioFocusBehavior.PAUSE,
                        onClick = { vm.setAudioFocusBehavior(AudioFocusBehavior.PAUSE) },
                        colors = RadioButtonDefaults.colors(selectedColor = colors.primaryAccent)
                    )
                    Column {
                        Text("Pause playback", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
                        Text("Pauses music completely until notifications finish", fontSize = 12.sp, color = Color.White.copy(alpha = 0.56f))
                    }
                }
            }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 4. Appearance Section
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = colors.primaryAccent
            ) {
                AdaptiveAtmosphereSection(vm = vm)
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 5. Default Music Player (Open-Canvas Section)
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioSectionHeader(title = "Default Music Player")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Always Ask Before Playing",
                description = "Choose which player to use each time",
                checked = alwaysAsk,
                onCheckedChange = {
                    alwaysAsk = it
                    vm.setAlwaysAskEnabled(context, it)
                }
            )
            StudioHairlineDivider()

            Spacer(modifier = Modifier.height(12.dp))

            com.tunemind.app.ui.components.CompactMusicPlayerGrid(
                providers = providers,
                isProviderSelected = { provider -> provider.providerId == selectedProviderId },
                onSelectProvider = { provider ->
                    selectedProviderId = provider.providerId
                    vm.setPreferredProviderId(context, provider.providerId)
                },
                context = context
            )

            }

            Spacer(modifier = Modifier.height(22.dp))

            // 6. Freshness & Privacy (Open-Canvas Section)
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioSectionHeader(title = "Freshness & Privacy")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Repetition Sensitivity",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        "${(fatigueSensitivity * 100).toInt()}%",
                        fontSize = 13.sp,
                        color = colors.primaryAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = fatigueSensitivity,
                    valueRange = 0.2f..1.0f,
                    onValueChange = {
                        com.tunemind.app.domain.voice.AgentSettingsStore.setRepetitionSensitivity(context, it)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primaryAccent,
                        activeTrackColor = colors.primaryAccent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Relaxed", fontSize = 11.sp, color = Color.White.copy(alpha = 0.50f))
                    Text("Balanced", fontSize = 11.sp, color = Color.White.copy(alpha = 0.50f))
                    Text("Strict", fontSize = 11.sp, color = Color.White.copy(alpha = 0.50f))
                }
            }
            StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Late-Night Comfort",
                description = "Favors warmer, softer tracks after 10:00 PM.",
                checked = chronoReceptivity,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setLateNightComfortEnabled(context, it)
                }
            )
            StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Private Session",
                description = "Temporarily pauses listening tracking and history logging.",
                checked = cloakingSession,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setPrivateSessionEnabled(context, it)
                }
            )
            StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Merge Duplicate Plays",
                description = "Combines identical songs played across music apps and local players.",
                checked = deduplicateLogs,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setMergeDuplicatePlaysEnabled(context, it)
                }
            )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 6b. Help & Legal (Open-Canvas Section)
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioSectionHeader(title = "Help & Legal")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSupport() }
                            .padding(vertical = 13.dp, horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Help & Send Feedback", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
                            Text("Troubleshooting guides & bug report form", fontSize = 12.sp, color = Color.White.copy(alpha = 0.56f))
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.45f))
                    }
                    StudioHairlineDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenLegal(0) }
                            .padding(vertical = 13.dp, horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Privacy Policy, Terms & About", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFFAFAFA))
                            Text("Version, permissions disclosure & open-source notices", fontSize = 12.sp, color = Color.White.copy(alpha = 0.56f))
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.45f))
                    }
                    StudioHairlineDivider()
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 7. Reset Listening Data
            OutlinedButton(
                onClick = { showResetSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, dangerRed.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = dangerRed)
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset Listening Data", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showResetSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showResetSheet = false },
            sheetState = sheetState,
            containerColor = colors.cardSurface,
            contentColor = Color(0xFFF8FAFC),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.22f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Reset Listening Data?",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = "This clears your local listening history, freshness scores, and resting vault tracks so TuneMind starts fresh.",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.66f),
                    lineHeight = 20.sp
                )
                StudioHairlineDivider()
                Button(
                    onClick = {
                        showResetSheet = false
                        vm.resetListeningProfile()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dangerRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Reset Everything", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = { showResetSheet = false },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.64f), fontSize = 14.sp)
                }
            }
        }
    }

    if (showSpotifySetupSheet) {
        val setupSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val setupScrollState = rememberScrollState()
        val trimmedInput = spotifyClientIdInput.trim()
        val isClientIdValid = com.tunemind.app.provider.SpotifyPremiumConnectManager.isValidClientIdFormat(trimmedInput)

        ModalBottomSheet(
            onDismissRequest = { showSpotifySetupSheet = false },
            sheetState = setupSheetState,
            containerColor = colors.cardSurface,
            contentColor = Color(0xFFF8FAFC),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.22f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(setupScrollState)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Connect Your Spotify Account",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = "Follow these 3 quick steps right on your phone (takes about 60 seconds). No Client Secret is needed.",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.66f),
                    lineHeight = 19.sp
                )

                StudioHairlineDivider()

                // Step 1
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Step 1 · Create a free Spotify app",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "Open Spotify Developer Dashboard, sign in with your Spotify Premium account, tap Create app, enter any name (like TuneMind), and tick Web API.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.62f),
                        lineHeight = 17.sp
                    )
                    OutlinedButton(
                        onClick = {
                            com.tunemind.app.provider.SpotifyPremiumConnectManager.openSpotifyDeveloperDashboard(context)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, colors.primaryAccent.copy(alpha = 0.60f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primaryAccent)
                    ) {
                        Text("Open Spotify Developer Dashboard", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                StudioHairlineDivider()

                // Step 2
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Step 2 · Copy & paste this Redirect URI",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "In Spotify Dashboard, paste this exact callback link into Redirect URIs, tap Add, then Save.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.62f),
                        lineHeight = 17.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable {
                                clipboardManager.setText(
                                    AnnotatedString(com.tunemind.app.provider.SpotifyPremiumConnectManager.REDIRECT_URI)
                                )
                                copiedRedirectUri = true
                                coroutineScope.launch {
                                    delay(2200L)
                                    copiedRedirectUri = false
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = com.tunemind.app.provider.SpotifyPremiumConnectManager.REDIRECT_URI,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.primaryAccent,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (copiedRedirectUri) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Copied",
                                    tint = safeGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = if (copiedRedirectUri) "Copied" else "Copy",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (copiedRedirectUri) safeGreen else Color(0xFFF8FAFC)
                            )
                        }
                    }
                }

                StudioHairlineDivider()

                // Step 3
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Step 3 · Paste your Client ID & sign in",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "Open your app's Settings in Spotify Dashboard, copy the Client ID, then tap Paste below.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.62f),
                        lineHeight = 17.sp
                    )
                    detectedClipboardClientId?.let { detected ->
                        OutlinedButton(
                            onClick = {
                                spotifyClientIdInput = detected
                                detectedClipboardClientId = null
                            },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, safeGreen.copy(alpha = 0.65f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = safeGreen)
                        ) {
                            Text(
                                text = "Use Detected Client ID from Clipboard (••••${detected.takeLast(4)})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = spotifyClientIdInput,
                            onValueChange = { spotifyClientIdInput = it.trim() },
                            label = { Text("Spotify Client ID", fontSize = 12.sp) },
                            placeholder = {
                                Text(
                                    "32-character Client ID",
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.35f)
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFF8FAFC),
                                unfocusedTextColor = Color(0xFFF8FAFC),
                                focusedBorderColor = if (isClientIdValid) safeGreen else colors.primaryAccent,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.20f),
                                focusedLabelColor = colors.primaryAccent,
                                unfocusedLabelColor = Color.White.copy(alpha = 0.56f),
                                cursorColor = colors.primaryAccent
                            )
                        )
                        OutlinedButton(
                            onClick = {
                                val pasted = clipboardManager.getText()?.text?.trim().orEmpty()
                                if (pasted.isNotBlank()) {
                                    spotifyClientIdInput = pasted
                                    detectedClipboardClientId = null
                                }
                            },
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF8FAFC))
                        ) {
                            Text("Paste", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (trimmedInput.isNotBlank()) {
                        Text(
                            text = if (isClientIdValid) {
                                "Valid Spotify Client ID format"
                            } else {
                                "Spotify Client IDs are usually 32 letters/numbers with no spaces."
                            },
                            fontSize = 11.sp,
                            color = if (isClientIdValid) safeGreen else warnAmber
                        )
                    }
                }

                Button(
                    onClick = {
                        showSpotifySetupSheet = false
                        com.tunemind.app.provider.SpotifyPremiumConnectManager.startAuthorization(
                            context = context,
                            rawClientId = trimmedInput,
                            saveAsCustomClientId = true
                        )
                    },
                    enabled = isClientIdValid && !spotifyConnectStatus.isAuthorizing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primaryAccent,
                        contentColor = Color(0xFF061018)
                    )
                ) {
                    Text("Connect Spotify (Premium or Free)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                Text(
                    text = "Done once — TuneMind stores an encrypted backup tied to your TuneMind account so you won't need to set this up again.",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.50f),
                    lineHeight = 16.sp
                )

                if (spotifyConnectStatus.hasAppDefaultClientId && spotifyConnectStatus.usesCustomClientId) {
                    TextButton(
                        onClick = {
                            com.tunemind.app.provider.SpotifyPremiumConnectManager.clearCustomClientId(context)
                            spotifyClientIdInput = ""
                            showSpotifySetupSheet = false
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Switch back to TuneMind 1-Tap Default", color = colors.primaryAccent, fontSize = 13.sp)
                    }
                }

                TextButton(
                    onClick = { showSpotifySetupSheet = false },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Close", color = Color.White.copy(alpha = 0.64f), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 14.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 14.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFF8FAFC)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.56f),
                lineHeight = 17.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsPermissionRow(
    title: String,
    description: String,
    isEnabled: Boolean,
    colors: AdaptiveThemeState,
    onClick: () -> Unit
) {
    val safeGreen = Color(0xFF10B981)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFF8FAFC)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.56f),
                lineHeight = 17.sp
            )
        }

        if (isEnabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = safeGreen,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Allowed",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = safeGreen
                )
            }
        } else {
            Text(
                text = "Allow",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.primaryAccent
            )
        }
    }
}
