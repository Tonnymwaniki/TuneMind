package com.tunemind.app.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.StudioAmbientBackground
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

    var fatigueSensitivity by remember { mutableStateOf(0.75f) }
    var chronoReceptivity by remember { mutableStateOf(true) }
    var cloakingSession by remember { mutableStateOf(false) }
    var deduplicateLogs by remember { mutableStateOf(true) }

    val scrollState = rememberScrollState()
    val colors = TuneMindTheme.colors
    val cardBg = Color(0xFF0C1018).copy(alpha = 0.92f)
    val subCardBg = Color(0xFF121722)
    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFFFB000)

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

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = cardBg,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier
                            .size(38.dp)
                            .clickable { onBack() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Settings",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Playback, voice & permissions",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.60f)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (appRemoteConnected) safeGreen.copy(alpha = 0.14f) else colors.primaryAccent.copy(alpha = 0.12f),
                    border = BorderStroke(
                        1.dp,
                        if (appRemoteConnected) safeGreen.copy(alpha = 0.45f) else colors.primaryAccent.copy(alpha = 0.35f)
                    )
                ) {
                    Text(
                        text = if (appRemoteConnected) "Remote Connected" else "On Demand",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (appRemoteConnected) safeGreen else colors.primaryAccent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // 1. Voice & Playback Controls
            Text(
                text = "Voice & Playback",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cardBg,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SettingsSwitchRow(
                        title = "Hands-Free DJ",
                        description = if (autonomousDjEnabled) {
                            "Asks what to play next when a song ends or queues a fresh pick."
                        } else {
                            "Off: TuneMind stays quiet until you speak or tap."
                        },
                        checked = autonomousDjEnabled,
                        onCheckedChange = {
                            com.tunemind.app.domain.voice.AgentSettingsStore.setAutonomousDjEnabled(context, it)
                        }
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    SettingsSwitchRow(
                        title = "Never Leave TuneMind",
                        description = if (strictNoAppSwitchEnabled) {
                            "Always keeps playback inside TuneMind and blocks switching to external apps."
                        } else {
                            "Off: Plays in the background when supported and returns if an app opens."
                        },
                        checked = strictNoAppSwitchEnabled,
                        onCheckedChange = {
                            com.tunemind.app.domain.voice.AgentSettingsStore.setStrictNoAppSwitchEnabled(context, it)
                        }
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    SettingsSwitchRow(
                        title = "Stay in TuneMind",
                        description = if (stayInTuneMindEnabled) {
                            "Controls Spotify silently via Remote and bounces straight back if needed."
                        } else {
                            "Off: Opens your music app directly when starting playback."
                        },
                        checked = stayInTuneMindEnabled,
                        onCheckedChange = {
                            com.tunemind.app.domain.voice.AgentSettingsStore.setStayInTuneMindEnabled(context, it)
                        }
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    SettingsSwitchRow(
                        title = "Built-In Audio Player",
                        description = if (preferInAppPlayerEnabled) {
                            "Plays local audio files and previews directly inside TuneMind."
                        } else {
                            "Off: Always routes playback to your chosen music app."
                        },
                        checked = preferInAppPlayerEnabled,
                        onCheckedChange = {
                            com.tunemind.app.domain.voice.AgentSettingsStore.setPreferInAppPlayerEnabled(context, it)
                        }
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    SettingsSwitchRow(
                        title = "Auto-Switch Overplayed Songs",
                        description = if (backgroundAutoPlayEnabled) {
                            "Automatically transitions away when a track hits heavy repetition."
                        } else {
                            "Recommended off: You approve every track change."
                        },
                        checked = backgroundAutoPlayEnabled,
                        onCheckedChange = {
                            com.tunemind.app.domain.voice.AgentSettingsStore.setBackgroundAutoPlayEnabled(context, it)
                        }
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    // Spotify Remote & Account Card
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Spotify Remote & Account",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = when {
                                        isConnectingRemote -> "Connecting to Spotify Remote — approve prompt if asked…"
                                        appRemoteConnected -> "Connected for silent Free & Premium playback control."
                                        !isSpotifyInstalled -> "Spotify app not found on this device."
                                        isSpotifyAuthorized -> "Spotify linked — tap Connect Remote for in-app control."
                                        else -> "Link Spotify or connect Remote to control music without switching apps."
                                    },
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.64f),
                                    lineHeight = 15.sp
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (appRemoteConnected) safeGreen.copy(alpha = 0.16f) else colors.primaryAccent.copy(alpha = 0.12f),
                                border = BorderStroke(
                                    1.dp,
                                    if (appRemoteConnected) safeGreen.copy(alpha = 0.55f) else colors.primaryAccent.copy(alpha = 0.40f)
                                )
                            ) {
                                Text(
                                    text = when {
                                        isConnectingRemote -> "Connecting"
                                        appRemoteConnected -> "Enabled"
                                        else -> "Standby"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (appRemoteConnected) safeGreen else colors.primaryAccent,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        if (!appRemoteError.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = warnAmber.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, warnAmber.copy(alpha = 0.40f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = appRemoteError ?: "",
                                        fontSize = 11.sp,
                                        color = warnAmber,
                                        lineHeight = 16.sp
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
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, warnAmber.copy(alpha = 0.6f)),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "Open Spotify",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = warnAmber
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
                                        containerColor = if (appRemoteConnected) safeGreen else colors.primaryAccent
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isConnectingRemote) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(13.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF05060A)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = when {
                                            isConnectingRemote -> "Connecting…"
                                            appRemoteConnected -> "Remote Enabled"
                                            else -> "Connect Remote"
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF05060A)
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    spotifyAuthManager.launchSpotifyAuth(context)
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSpotifyAuthorized) safeGreen else colors.primaryAccent.copy(alpha = 0.55f)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (isSpotifyAuthorized) "Spotify Linked" else "Link Spotify",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSpotifyAuthorized) safeGreen else colors.primaryAccent
                                )
                            }
                        }
                    }
                }
            }

            // 2. Permissions Section
            Text(
                text = "Permissions",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cardBg,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsPermissionRow(
                        title = "Return to TuneMind",
                        description = if (hasOverlayPermission) {
                            "Enabled — TuneMind can return to focus if another player opens."
                        } else {
                            "Lets TuneMind bring you back automatically after starting a track."
                        },
                        isEnabled = hasOverlayPermission,
                        colors = colors,
                        onClick = {
                            runCatching {
                                context.startActivity(com.tunemind.app.util.PermissionUtils.getOverlayPermissionIntent(context))
                            }
                        }
                    )

                    SettingsPermissionRow(
                        title = "Music Detection",
                        description = if (hasNotificationAccess) {
                            "Enabled — Reads live now-playing info and controls playback cleanly."
                        } else {
                            "Detects what song is playing across your music apps."
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

                    SettingsPermissionRow(
                        title = "Local Audio Library",
                        description = if (hasAudioPermission) {
                            "Enabled — Built-in player can play downloaded tracks from your device."
                        } else {
                            "Lets the built-in player find music saved on your phone."
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

            // 3. Background & Battery
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cardBg,
                border = BorderStroke(
                    1.dp,
                    if (isBatteryIgnored) safeGreen.copy(alpha = 0.40f)
                    else warnAmber.copy(alpha = 0.45f)
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Rounded.BatteryChargingFull,
                                contentDescription = null,
                                tint = if (isBatteryIgnored) safeGreen else warnAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Unrestricted Background",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isBatteryIgnored) "Keeps music tracking active in the background"
                                    else "Battery saver may pause background tracking",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.64f)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isBatteryIgnored) safeGreen.copy(alpha = 0.16f) else warnAmber.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, if (isBatteryIgnored) safeGreen else warnAmber)
                        ) {
                            Text(
                                text = if (isBatteryIgnored) "Enabled" else "Off",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isBatteryIgnored) safeGreen else warnAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Button(
                        onClick = { vm.requestIgnoreBatteryOptimization(context) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isBatteryIgnored) safeGreen else warnAmber
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF05060A))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isBatteryIgnored) "Background Unrestricted" else "Allow Unrestricted Background",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF05060A)
                        )
                    }
                }
            }

            // 4. Hearing Protection & Audio Focus
            Text(
                text = "Audio & Hearing",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cardBg,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
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
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Rounded.Headphones, contentDescription = null, tint = safeGreen, modifier = Modifier.size(19.dp))
                            Column {
                                Text(
                                    text = "Safe Listening Alerts",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "WHO 85 dB daily exposure reminder",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.62f)
                                )
                            }
                        }

                        Switch(
                            checked = isDecibelSafetyEnabled,
                            onCheckedChange = { vm.setDecibelSafetyAlertEnabled(it) }
                        )
                    }

                    Text(
                        text = "Alerts you before extended high-volume headphone sessions exceed recommended daily listening limits.",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.62f),
                        lineHeight = 16.sp
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = colors.primaryAccent, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Audio Interruptions",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.DUCK) colors.primaryAccent.copy(alpha = 0.14f) else subCardBg,
                        border = BorderStroke(1.dp, if (audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.DUCK) colors.primaryAccent else Color.White.copy(alpha = 0.08f)),
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
                                Text("Lower volume briefly", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text("Softens music during alerts, then brings it back up", fontSize = 11.sp, color = Color.White.copy(alpha = 0.60f))
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.PAUSE) colors.primaryAccent.copy(alpha = 0.14f) else subCardBg,
                        border = BorderStroke(1.dp, if (audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.PAUSE) colors.primaryAccent else Color.White.copy(alpha = 0.08f)),
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
                                Text("Pause playback", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text("Pauses music completely until notifications finish", fontSize = 11.sp, color = Color.White.copy(alpha = 0.60f))
                            }
                        }
                    }
                }
            }

            // 5. Theme & Appearance
            AdaptiveAtmosphereSection(vm = vm)

            // 6. Default Music Player
            Text("Default Music Player", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cardBg,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = subCardBg,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
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
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Choose which player to use each time",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.62f)
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

                    providers.forEach { provider ->
                        val isSelected = provider.providerId == selectedProviderId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) colors.primaryAccent.copy(alpha = 0.14f) else subCardBg,
                            border = BorderStroke(1.dp, if (isSelected) colors.primaryAccent else Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
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
                                    colors = RadioButtonDefaults.colors(selectedColor = colors.primaryAccent)
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = provider.displayName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        val isInstalled = provider.isInstalled(context)
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isInstalled) safeGreen.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f)
                                        ) {
                                            Text(
                                                text = if (isInstalled) "Installed" else "Web",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isInstalled) safeGreen else Color.White.copy(alpha = 0.55f),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    val capDesc = if (provider.supportedCapabilities.contains(com.tunemind.app.provider.ProviderCapability.CAN_DEEP_LINK)) {
                                        "Supports direct track playback"
                                    } else {
                                        "Opens search in player"
                                    }
                                    Text(text = capDesc, fontSize = 11.sp, color = Color.White.copy(alpha = 0.58f))
                                }
                            }
                        }
                    }
                }
            }

            // 7. Track Variety & Privacy
            Text("Freshness & Privacy", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cardBg,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Repetition Sensitivity", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        Text("${(fatigueSensitivity * 100).toInt()}%", fontSize = 12.sp, color = colors.primaryAccent, fontWeight = FontWeight.SemiBold)
                    }

                    Slider(
                        value = fatigueSensitivity,
                        onValueChange = { fatigueSensitivity = it },
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
                        Text("Relaxed", fontSize = 10.sp, color = Color.White.copy(alpha = 0.52f))
                        Text("Balanced", fontSize = 10.sp, color = Color.White.copy(alpha = 0.52f))
                        Text("Strict", fontSize = 10.sp, color = Color.White.copy(alpha = 0.52f))
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    SettingsSwitchRow(
                        title = "Late-Night Comfort",
                        description = "Favors warmer, softer tracks after 10:00 PM",
                        checked = chronoReceptivity,
                        onCheckedChange = { chronoReceptivity = it }
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    SettingsSwitchRow(
                        title = "Private Session",
                        description = "Pause listening tracking temporarily",
                        checked = cloakingSession,
                        onCheckedChange = { cloakingSession = it }
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    SettingsSwitchRow(
                        title = "Merge Duplicate Plays",
                        description = "Combine identical tracks played across Spotify and other apps",
                        checked = deduplicateLogs,
                        onCheckedChange = { deduplicateLogs = it }
                    )
                }
            }

            // 8. Reset Profile
            Button(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A0D12),
                    contentColor = Color(0xFFFF4D6D)
                ),
                border = BorderStroke(1.dp, Color(0xFFFF4D6D).copy(alpha = 0.45f))
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset Listening Data", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text("Reset Listening Data?", color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "This clears your local listening history, freshness scores, and resting vault tracks so TuneMind starts fresh.",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showResetDialog = false
                                vm.resetListeningProfile()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D6D), contentColor = Color.White)
                        ) {
                            Text("Reset")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) {
                            Text("Cancel", color = Color.White.copy(alpha = 0.65f))
                        }
                    },
                    containerColor = Color(0xFF10131C),
                    shape = RoundedCornerShape(18.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = description,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.62f),
                lineHeight = 15.sp
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
    val warnAmber = Color(0xFFFFB000)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF121722),
        border = BorderStroke(
            1.dp,
            if (isEnabled) safeGreen.copy(alpha = 0.40f)
            else warnAmber.copy(alpha = 0.35f)
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isEnabled) safeGreen.copy(alpha = 0.16f) else warnAmber.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = if (isEnabled) "Enabled" else "Off",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEnabled) safeGreen else warnAmber,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.62f),
                    lineHeight = 15.sp
                )
            }
            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (isEnabled) safeGreen else colors.primaryAccent
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                if (isEnabled) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = safeGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = if (isEnabled) "Enabled" else "Enable",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isEnabled) safeGreen else colors.primaryAccent
                )
            }
        }
    }
}
