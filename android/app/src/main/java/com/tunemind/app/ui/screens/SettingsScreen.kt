package com.tunemind.app.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onOpenCrossApp: () -> Unit,
    onOpenWeeklyReport: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val registry = remember(context) { com.tunemind.app.provider.MusicProviderRegistry.getInstance(context) }
    var selectedProviderId by remember { mutableStateOf(registry.getPreferredProviderId()) }
    var alwaysAsk by remember { mutableStateOf(registry.isAlwaysAskEnabled()) }
    val providers = remember { registry.getInstalledProviders() }
    var showResetDialog by remember { mutableStateOf(false) }

    val isBatteryIgnored by vm.isBatteryOptimizationIgnored.collectAsState()
    val audioFocusBehavior by vm.audioFocusBehavior.collectAsState()
    val isDecibelSafetyEnabled by vm.isDecibelSafetyAlertEnabled.collectAsState()

    val autonomousDjEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.autonomousDjEnabled.collectAsState()
    val stayInTuneMindEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.stayInTuneMindEnabled.collectAsState()
    val strictNoAppSwitchEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.strictNoAppSwitchEnabled.collectAsState()
    val preferInAppPlayerEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.preferInAppPlayerEnabled.collectAsState()
    val backgroundAutoPlayEnabled by com.tunemind.app.domain.voice.AgentSettingsStore.backgroundAutoPlayEnabled.collectAsState()
    val appRemoteConnected by com.tunemind.app.provider.SpotifyAppRemoteManager.isConnectedFlow.collectAsState()
    val isConnectingRemote by com.tunemind.app.provider.SpotifyAppRemoteManager.isConnectingFlow.collectAsState()
    val appRemoteError by com.tunemind.app.provider.SpotifyAppRemoteManager.lastConnectionErrorFlow.collectAsState()

    val spotifyAuthManager = remember(context) { com.tunemind.app.data.ingestion.SpotifyAuthManager(context) }
    val spotifyOAuthRepo = remember(context) { com.tunemind.app.data.ingestion.SpotifyOAuthRepository.getInstance(context) }
    var isSpotifyAuthorized by remember {
        mutableStateOf(spotifyAuthManager.isAuthenticated() || spotifyOAuthRepo.tokenManager.isAuthenticated())
    }
    var hasOverlayPermission by remember { mutableStateOf(com.tunemind.app.util.PermissionUtils.hasOverlayPermission(context)) }
    var hasNotificationAccess by remember { mutableStateOf(com.tunemind.app.util.PermissionUtils.hasNotificationListenerPermission(context)) }
    var hasAudioPermission by remember { mutableStateOf(com.tunemind.app.util.PermissionUtils.hasAudioStoragePermission(context)) }
    var isSpotifyInstalled by remember { mutableStateOf(com.tunemind.app.provider.SpotifyAppRemoteManager.isSpotifyInstalled(context)) }

    var persistentListener by remember { mutableStateOf(true) }
    var fatigueSensitivity by remember { mutableStateOf(0.75f) }
    var chronoReceptivity by remember { mutableStateOf(true) }
    var cloakingSession by remember { mutableStateOf(false) }
    var deduplicateLogs by remember { mutableStateOf(true) }

    val scrollState = rememberScrollState()
    val colors = TuneMindTheme.colors

    val refreshAllSystemStates: () -> Unit = remember(context) {
        {
            com.tunemind.app.domain.voice.AgentSettingsStore.ensureLoaded(context)
            vm.checkBatteryOptimization(context)
            isSpotifyAuthorized = spotifyAuthManager.isAuthenticated() || spotifyOAuthRepo.tokenManager.isAuthenticated()
            hasOverlayPermission = com.tunemind.app.util.PermissionUtils.hasOverlayPermission(context)
            hasNotificationAccess = com.tunemind.app.util.PermissionUtils.hasNotificationListenerPermission(context)
            hasAudioPermission = com.tunemind.app.util.PermissionUtils.hasAudioStoragePermission(context)
            isSpotifyInstalled = com.tunemind.app.provider.SpotifyAppRemoteManager.isSpotifyInstalled(context)
            com.tunemind.app.provider.SpotifyAppRemoteManager.isConnected()
            selectedProviderId = registry.getPreferredProviderId()
            alwaysAsk = registry.isAlwaysAskEnabled()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted || com.tunemind.app.util.PermissionUtils.hasAudioStoragePermission(context)
        refreshAllSystemStates()
    }

    LaunchedEffect(Unit) {
        refreshAllSystemStates()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAllSystemStates()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.primaryBackground)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 0. TOP BACK BAR
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = colors.secondaryBackground,
                    border = BorderStroke(1.dp, colors.border.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .size(38.dp)
                        .clickable { onBack() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.primaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "TuneMind Settings",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText
                    )
                    Text(
                        text = "Agent Autonomy • Stay-in-TuneMind • Spotify SDK",
                        fontSize = 11.sp,
                        color = colors.secondaryText
                    )
                }
            }
        }

        // 1. TOP SWITCHBOARD HEADER STRIP
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.primaryAccent)
                    )
                    Text(
                        text = "AGENT AUTONOMY & STAY-IN-TUNEMIND",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = if (appRemoteConnected) "REMOTE READY" else "ON-DEMAND",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (appRemoteConnected) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.SecondaryAccent
                )
            }
        }

        // 1B. AUTONOMOUS DJ & STAY-IN-TUNEMIND AGENT CONTROLS
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.2.dp, colors.primaryAccent.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Toggle 1: Autonomous End-of-Song DJ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Autonomous End-of-Song DJ",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                        Text(
                            text = if (autonomousDjEnabled) {
                                "ON: Asks what to play next when a track ends, or auto-spins Claude's curated pick (works with screen off)."
                            } else {
                                "OFF (Default): TuneMind never speaks or plays music unprompted when songs change."
                            },
                            fontSize = 11.sp,
                            color = colors.secondaryText,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = autonomousDjEnabled,
                        onCheckedChange = {
                            com.tunemind.app.domain.voice.AgentSettingsStore.setAutonomousDjEnabled(context, it)
                        }
                    )
                }

                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f))

                // Toggle 2: Option 1 - Strict Never-Leave-TuneMind Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Strict Never-Leave-TuneMind Mode",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                        Text(
                            text = if (strictNoAppSwitchEnabled) {
                                "ON (Option 1): Strictly blocks launching Spotify or any external player UI. Plays silently or natively inside TuneMind and reports in-app if remote isn't ready."
                            } else {
                                "OFF: Allows silent background playback first, with automatic instant bounce-back when a cold app wake is needed."
                            },
                            fontSize = 11.sp,
                            color = colors.secondaryText,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = strictNoAppSwitchEnabled,
                        onCheckedChange = {
                            com.tunemind.app.domain.voice.AgentSettingsStore.setStrictNoAppSwitchEnabled(context, it)
                        }
                    )
                }

                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f))

                // Toggle 3: Option 2 - Stay-in-TuneMind Smart Remote + Bounce-Back
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Stay-in-TuneMind Playback",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                        Text(
                            text = if (stayInTuneMindEnabled) {
                                "ON (Option 2): Streams songs via Spotify App Remote (Free & Premium) + Web API, and bounces straight back to TuneMind if external wake occurs."
                            } else {
                                "OFF: Opens the external music app directly when playing a song."
                            },
                            fontSize = 11.sp,
                            color = colors.secondaryText,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = stayInTuneMindEnabled,
                        onCheckedChange = {
                            com.tunemind.app.domain.voice.AgentSettingsStore.setStayInTuneMindEnabled(context, it)
                        }
                    )
                }

                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f))

                // Toggle 4: Option 3 - Built-In TuneMind Native In-App Audio Engine
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Built-In TuneMind In-App Audio Engine",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                        Text(
                            text = if (preferInAppPlayerEnabled) {
                                "ON (Option 3): Plays matching local phone audio files and instant Spotify catalog previews natively inside TuneMind without touching another app."
                            } else {
                                "OFF: Skips built-in native player and routes strictly through external/remote providers."
                            },
                            fontSize = 11.sp,
                            color = colors.secondaryText,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = preferInAppPlayerEnabled,
                        onCheckedChange = {
                            com.tunemind.app.domain.voice.AgentSettingsStore.setPreferInAppPlayerEnabled(context, it)
                        }
                    )
                }

                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f))

                // Toggle 5: Background Auto-Intervention (Off by default for consent)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Allow Background Auto-Intervention",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                        Text(
                            text = if (backgroundAutoPlayEnabled) {
                                "ON: Background satiety checks can switch fatigued tracks automatically."
                            } else {
                                "OFF (Recommended): Only you approve and trigger music playback."
                            },
                            fontSize = 11.sp,
                            color = colors.secondaryText,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = backgroundAutoPlayEnabled,
                        onCheckedChange = {
                            com.tunemind.app.domain.voice.AgentSettingsStore.setBackgroundAutoPlayEnabled(context, it)
                        }
                    )
                }

                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f))

                // Spotify App Remote + OAuth Connection Card
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Spotify Remote SDK & Account",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryText
                            )
                            Text(
                                text = when {
                                    isConnectingRemote -> "Opening Spotify App Remote handshake — accept if Spotify prompts you..."
                                    appRemoteConnected -> "Connected to Spotify App Remote (Free & Premium silent control active)"
                                    !isSpotifyInstalled -> "Spotify app not detected on device"
                                    isSpotifyAuthorized -> "Spotify OAuth linked • Tap Connect Remote to enable zero-switch playback"
                                    else -> "Authorize Spotify or connect Remote so TuneMind controls playback inside TuneMind"
                                },
                                fontSize = 10.sp,
                                color = colors.secondaryText
                            )
                        }
                        val badgeColor = when {
                            appRemoteConnected -> DeepObsidianAmber.DecibelSafe
                            isConnectingRemote -> colors.primaryAccent
                            else -> colors.primaryAccent
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (appRemoteConnected) DeepObsidianAmber.DecibelSafe.copy(alpha = 0.15f) else colors.cardSurface,
                            border = BorderStroke(
                                1.dp,
                                if (appRemoteConnected) DeepObsidianAmber.DecibelSafe else colors.border
                            )
                        ) {
                            Text(
                                text = when {
                                    isConnectingRemote -> "CONNECTING..."
                                    appRemoteConnected -> "ENABLED"
                                    else -> "STANDBY"
                                },
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (!appRemoteError.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DeepObsidianAmber.Warning.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, DeepObsidianAmber.Warning.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = appRemoteError ?: "",
                                    fontSize = 11.sp,
                                    color = DeepObsidianAmber.Warning,
                                    lineHeight = 15.sp
                                )
                                if (isSpotifyInstalled) {
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                context.packageManager.getLaunchIntentForPackage("com.spotify.music")?.let { wakeIntent ->
                                                    wakeIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(wakeIntent)
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, DeepObsidianAmber.Warning.copy(alpha = 0.6f)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "Wake Spotify App",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = DeepObsidianAmber.Warning
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isSpotifyInstalled) {
                            Button(
                                enabled = !isConnectingRemote,
                                onClick = {
                                    com.tunemind.app.provider.SpotifyAppRemoteManager.connectAsync(
                                        context = context,
                                        showAuthView = true
                                    ) { _, _ ->
                                        refreshAllSystemStates()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (appRemoteConnected) DeepObsidianAmber.DecibelSafe else colors.primaryAccent
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isConnectingRemote) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = DeepObsidianAmber.AppBackground
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    text = when {
                                        isConnectingRemote -> "Connecting..."
                                        appRemoteConnected -> "Remote Enabled ✓"
                                        else -> "Connect Remote"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepObsidianAmber.AppBackground
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                spotifyAuthManager.launchSpotifyAuth(context)
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSpotifyAuthorized) DeepObsidianAmber.DecibelSafe else colors.primaryAccent.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (isSpotifyAuthorized) "Spotify Linked ✓" else "Link Spotify",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSpotifyAuthorized) DeepObsidianAmber.DecibelSafe else colors.primaryAccent
                            )
                        }
                    }
                }

                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f))

                // Always-visible System Permissions Section (live reactive on ON_RESUME)
                Text(
                    text = "SYSTEM PERMISSIONS & STAY-IN-APP CONTROLS",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.primaryAccent,
                    letterSpacing = 0.8.sp
                )

                // Permission 1: Return-to-TuneMind Overlay (SYSTEM_ALERT_WINDOW)
                SettingsPermissionRow(
                    title = "Return-to-TuneMind Overlay",
                    description = if (hasOverlayPermission) {
                        "Enabled — Android can bounce straight back into TuneMind after cold-starting Spotify."
                    } else {
                        "Allows Android to return straight into TuneMind if Spotify cold-starts."
                    },
                    isEnabled = hasOverlayPermission,
                    colors = colors,
                    onClick = {
                        runCatching {
                            context.startActivity(com.tunemind.app.util.PermissionUtils.getOverlayPermissionIntent(context))
                        }
                    }
                )

                // Permission 2: Notification Listener Access
                SettingsPermissionRow(
                    title = "Notification Listener Access",
                    description = if (hasNotificationAccess) {
                        "Enabled — TuneMind reads active MediaSession track metadata and sends silent play/pause commands."
                    } else {
                        "Required to detect active music tracks across apps and control MediaSessions silently."
                    },
                    isEnabled = hasNotificationAccess,
                    colors = colors,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                com.tunemind.app.util.PermissionUtils.getNotificationListenerIntent().apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }
                )

                // Permission 3: Local Audio Files Permission (Option 3 In-App Audio)
                SettingsPermissionRow(
                    title = "Local Audio Files Permission",
                    description = if (hasAudioPermission) {
                        "Enabled — Built-in In-App Player can play songs stored on your phone without leaving TuneMind."
                    } else {
                        "Lets TuneMind's built-in audio player find and play downloaded/local songs directly on-device."
                    },
                    isEnabled = hasAudioPermission,
                    colors = colors,
                    onClick = {
                        if (!hasAudioPermission) {
                            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                android.Manifest.permission.READ_MEDIA_AUDIO
                            } else {
                                android.Manifest.permission.READ_EXTERNAL_STORAGE
                            }
                            audioPermissionLauncher.launch(perm)
                        } else {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    ).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }

        // 2. ANDROID BATTERY SHIELD & DAEMON HEALTH (SYSTEM CAPABILITY)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(
                1.2.dp,
                if (isBatteryIgnored) DeepObsidianAmber.DecibelSafe.copy(alpha = 0.5f)
                else DeepObsidianAmber.Warning.copy(alpha = 0.6f)
            ),
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
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.BatteryChargingFull,
                            contentDescription = null,
                            tint = if (isBatteryIgnored) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Android Battery Optimization Shield",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryText
                            )
                            Text(
                                text = if (isBatteryIgnored) "Unrestricted background monitoring enabled" else "System Doze may kill background daemon",
                                fontSize = 10.sp,
                                color = colors.secondaryText
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isBatteryIgnored) DeepObsidianAmber.DecibelSafe.copy(alpha = 0.15f) else DeepObsidianAmber.Warning.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, if (isBatteryIgnored) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning)
                    ) {
                        Text(
                            text = if (isBatteryIgnored) "ENABLED" else "DISABLED",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isBatteryIgnored) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Button(
                    onClick = { vm.requestIgnoreBatteryOptimization(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBatteryIgnored) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(14.dp), tint = DeepObsidianAmber.AppBackground)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isBatteryIgnored) "Battery Exemption Enabled ✓" else "Enable Battery Exemption",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeepObsidianAmber.AppBackground
                    )
                }
            }
        }

        // 3. DECIBEL HEADROOM & IN-EAR SAFETY GUARD (SYSTEM AUDIO CAPABILITY)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Headphones, contentDescription = null, tint = DeepObsidianAmber.DecibelSafe, modifier = Modifier.size(18.dp))
                        Column {
                            Text(
                                text = "Safe Decibel Exposure Warnings",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryText
                            )
                            Text(
                                text = "WHO 85 dBA Threshold Guard for in-ear listening",
                                fontSize = 10.sp,
                                color = colors.secondaryText
                            )
                        }
                    }

                    Switch(
                        checked = isDecibelSafetyEnabled,
                        onCheckedChange = { vm.setDecibelSafetyAlertEnabled(it) }
                    )
                }

                Text(
                    text = "Monitors real-time acoustic sound pressure levels and notifies you before inner ear stereocilia fatigue exceeds the WHO 8-hour daily dose limit.",
                    fontSize = 11.sp,
                    color = colors.secondaryText,
                    lineHeight = 15.sp
                )
            }
        }

        // 4. ANDROID AUDIO FOCUS BEHAVIOR (SYSTEM AUDIO CAPABILITY)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.VolumeUp, contentDescription = null, tint = colors.primaryAccent, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Android Audio Focus Behavior",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText
                    )
                }

                Text(
                    text = "Choose how TuneMind and your active player handle interruptions from navigation, notifications, or other apps:",
                    fontSize = 11.sp,
                    color = colors.secondaryText
                )

                // Option A: Duck Volume
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.DUCK) DeepObsidianAmber.CardElevated else DeepObsidianAmber.RecessedBackground,
                    border = BorderStroke(1.dp, if (audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.DUCK) colors.primaryAccent else DeepObsidianAmber.SurfaceBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setAudioFocusBehavior(com.tunemind.app.ui.AudioFocusBehavior.DUCK) }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.DUCK,
                            onClick = { vm.setAudioFocusBehavior(com.tunemind.app.ui.AudioFocusBehavior.DUCK) },
                            colors = RadioButtonDefaults.colors(selectedColor = colors.primaryAccent)
                        )
                        Column {
                            Text("Duck Volume (Smooth Transient)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)
                            Text("Lowers music volume while other apps play sound, then restores", fontSize = 10.sp, color = colors.secondaryText)
                        }
                    }
                }

                // Option B: Pause Playback
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.PAUSE) DeepObsidianAmber.CardElevated else DeepObsidianAmber.RecessedBackground,
                    border = BorderStroke(1.dp, if (audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.PAUSE) colors.primaryAccent else DeepObsidianAmber.SurfaceBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setAudioFocusBehavior(com.tunemind.app.ui.AudioFocusBehavior.PAUSE) }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.PAUSE,
                            onClick = { vm.setAudioFocusBehavior(com.tunemind.app.ui.AudioFocusBehavior.PAUSE) },
                            colors = RadioButtonDefaults.colors(selectedColor = colors.primaryAccent)
                        )
                        Column {
                            Text("Pause Playback (Exclusive Focus)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)
                            Text("Pauses stream completely and resumes once notification ends", fontSize = 10.sp, color = colors.secondaryText)
                        }
                    }
                }
            }
        }

        // 5. ADAPTIVE PERSONAL ATMOSPHERE ENGINE
        AdaptiveAtmosphereSection(vm = vm)

        // 6. DEFAULT MUSIC SERVICE & HANDOFF
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Default Music Service", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)
            Text("Agent Handoff", fontSize = 11.sp, color = colors.secondaryAccent)
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "When you tap 'Play' on a recommendation, TuneMind dispatches directly to your chosen player or opens the player switcher.",
                    fontSize = 11.sp,
                    color = colors.secondaryText,
                    lineHeight = 15.sp
                )

                // Always Prompt Toggle
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DeepObsidianAmber.RecessedBackground,
                    border = BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Always Ask Before Playing",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.TextPrimary
                            )
                            Text(
                                text = "Show player chooser bottom sheet on each track play",
                                fontSize = 10.sp,
                                color = DeepObsidianAmber.TextSecondary
                            )
                        }
                        Switch(
                            checked = alwaysAsk,
                            onCheckedChange = {
                                alwaysAsk = it
                                vm.setAlwaysAskEnabled(context, it)
                            }
                        )
                    }
                }

                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f))

                Text(
                    text = "DEFAULT MUSIC PLAYER (IF NOT PROMPTED):",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.PrimaryAccent
                )

                providers.forEach { provider ->
                    val isSelected = provider.providerId == selectedProviderId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) DeepObsidianAmber.CardElevated else DeepObsidianAmber.CardBackground,
                        border = BorderStroke(1.dp, if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.SurfaceBorder),
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedProviderId = provider.providerId
                            vm.setPreferredProviderId(context, provider.providerId)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    selectedProviderId = provider.providerId
                                    vm.setPreferredProviderId(context, provider.providerId)
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = DeepObsidianAmber.PrimaryAccent)
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = provider.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) DeepObsidianAmber.TextPrimary else DeepObsidianAmber.TextSecondary
                                    )
                                    val isInstalled = provider.isInstalled(context)
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isInstalled) DeepObsidianAmber.Success.copy(alpha = 0.15f) else DeepObsidianAmber.RecessedBackground,
                                        border = BorderStroke(1.dp, if (isInstalled) DeepObsidianAmber.Success.copy(alpha = 0.4f) else DeepObsidianAmber.SurfaceBorder)
                                    ) {
                                        Text(
                                            text = if (isInstalled) "Installed" else "Web Handoff",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isInstalled) DeepObsidianAmber.Success else DeepObsidianAmber.TextMuted,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }

                                val capDesc = if (provider.supportedCapabilities.contains(com.tunemind.app.provider.ProviderCapability.CAN_DEEP_LINK)) {
                                    "Supports direct deep links & track playback"
                                } else {
                                    "Supports search intent handoff"
                                }
                                Text(text = capDesc, fontSize = 10.sp, color = DeepObsidianAmber.TextMuted)
                            }
                        }
                    }
                }
            }
        }

        // 7. INTELLIGENCE & CALIBRATION (TM-ENGINE)
        Text("Intelligence & Calibration (TM-Engine 4.2)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Song Fatigue Sensitivity", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.primaryText)
                    Text("Balanced (${(fatigueSensitivity * 100).toInt()}%)", fontSize = 11.sp, color = DeepObsidianAmber.SecondaryAccent, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = fatigueSensitivity,
                    onValueChange = { fatigueSensitivity = it },
                    colors = SliderDefaults.colors(
                        thumbColor = DeepObsidianAmber.PrimaryAccent,
                        activeTrackColor = DeepObsidianAmber.PrimaryAccent,
                        inactiveTrackColor = DeepObsidianAmber.RecessedBackground
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tolerant (50%)", fontSize = 9.sp, color = colors.secondaryText)
                    Text("Adaptive", fontSize = 9.sp, color = colors.secondaryText)
                    Text("Aggressive (90%)", fontSize = 9.sp, color = colors.secondaryText)
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Chrono-Receptivity Profiling", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.primaryText)
                        Text("Dampens treble and vocal fatigue after 22:00", fontSize = 10.sp, color = colors.secondaryText)
                    }
                    Switch(checked = chronoReceptivity, onCheckedChange = { chronoReceptivity = it })
                }
            }
        }

        // 8. PRIVACY & LOCAL DATABASE CONTROL
        Text("Privacy & Zero-Cloud Local Vault", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryBackground,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Incognito / Cloak Session", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.primaryText)
                        Text("Pause telemetry during guest or DJ sets", fontSize = 10.sp, color = colors.secondaryText)
                    }
                    Switch(checked = cloakingSession, onCheckedChange = { cloakingSession = it })
                }

                HorizontalDivider(color = DeepObsidianAmber.SurfaceBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Deduplicate Cross-Platform Logs", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.primaryText)
                        Text("Unify matching ISRC tags across Spotify, Apple & YT Music", fontSize = 10.sp, color = colors.secondaryText)
                    }
                    Switch(checked = deduplicateLogs, onCheckedChange = { deduplicateLogs = it })
                }
            }
        }

        // 9. PROFILE RESET ACTION
        Button(
            onClick = { showResetDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DeepObsidianAmber.RecessedBackground,
                contentColor = DeepObsidianAmber.Error
            ),
            border = BorderStroke(1.dp, DeepObsidianAmber.Error.copy(alpha = 0.5f))
        ) {
            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Reset & Delete Listening Profile", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset Listening Profile?", color = DeepObsidianAmber.TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "This will permanently erase all local Room database playback history, observed listening patterns, satiety models, and quarantined cooldowns. TuneMind will begin a fresh observation cycle.",
                        fontSize = 12.sp,
                        color = DeepObsidianAmber.TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showResetDialog = false
                            vm.resetListeningProfile()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepObsidianAmber.Error, contentColor = DeepObsidianAmber.TextPrimary)
                    ) {
                        Text("Erase Profile")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text("Cancel", color = DeepObsidianAmber.TextMuted)
                    }
                },
                containerColor = DeepObsidianAmber.CardBackground,
                shape = RoundedCornerShape(16.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsPermissionRow(
    title: String,
    description: String,
    isEnabled: Boolean,
    colors: TuneMindThemeColors,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.cardSurface.copy(alpha = 0.7f),
        border = BorderStroke(
            1.dp,
            if (isEnabled) DeepObsidianAmber.DecibelSafe.copy(alpha = 0.45f)
            else DeepObsidianAmber.Warning.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isEnabled) DeepObsidianAmber.DecibelSafe.copy(alpha = 0.16f) else DeepObsidianAmber.Warning.copy(alpha = 0.16f),
                        border = BorderStroke(
                            1.dp,
                            if (isEnabled) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning
                        )
                    ) {
                        Text(
                            text = if (isEnabled) "ENABLED" else "DISABLED",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isEnabled) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = description,
                    fontSize = 10.sp,
                    color = colors.secondaryText,
                    lineHeight = 14.sp
                )
            }
            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    1.dp,
                    if (isEnabled) DeepObsidianAmber.DecibelSafe else colors.primaryAccent
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isEnabled) "Enabled ✓" else "Enable",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled) DeepObsidianAmber.DecibelSafe else colors.primaryAccent
                )
            }
        }
    }
}

