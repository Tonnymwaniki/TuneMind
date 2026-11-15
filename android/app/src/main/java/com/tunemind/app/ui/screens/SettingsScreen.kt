package com.tunemind.app.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
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
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.ui.AudioFocusBehavior
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onOpenCrossApp: () -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenAccountAuth: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val registry = remember(context) { com.tunemind.app.provider.MusicProviderRegistry.getInstance(context) }
    var selectedProviderId by remember { mutableStateOf(registry.getPreferredProviderId()) }
    var alwaysAsk by remember { mutableStateOf(registry.isAlwaysAskEnabled()) }
    val providers = remember { registry.getInstalledProviders() }
    var showResetSheet by remember { mutableStateOf(false) }

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
    val appRemoteSuccessMsg by com.tunemind.app.provider.SpotifyAppRemoteManager.connectionSuccessMessageFlow.collectAsState()
    var awaitingSpotifyWakeReturn by remember { mutableStateOf(false) }

    val spotifyAuthManager = remember(context) { com.tunemind.app.data.ingestion.SpotifyAuthManager(context) }
    val spotifyOAuthRepo = remember(context) { com.tunemind.app.data.ingestion.SpotifyOAuthRepository.getInstance(context) }
    val needsLibraryScopeUpgrade by vm.needsLibraryScopeUpgrade.collectAsState()
    var isSpotifyAuthorized by remember {
        mutableStateOf(spotifyAuthManager.isAuthenticated() || spotifyOAuthRepo.tokenManager.isAuthenticated())
    }
    var hasExtendedLibraryScopes by remember {
        mutableStateOf(spotifyOAuthRepo.hasExtendedLibraryScopes())
    }
    var hasOverlayPermission by remember { mutableStateOf(com.tunemind.app.util.PermissionUtils.hasOverlayPermission(context)) }
    var hasNotificationAccess by remember { mutableStateOf(com.tunemind.app.util.PermissionUtils.hasNotificationListenerPermission(context)) }
    var hasAudioPermission by remember { mutableStateOf(com.tunemind.app.util.PermissionUtils.hasAudioStoragePermission(context)) }
    var isSpotifyInstalled by remember { mutableStateOf(com.tunemind.app.provider.SpotifyAppRemoteManager.isSpotifyInstalled(context)) }

    val fatigueSensitivity by com.tunemind.app.domain.voice.AgentSettingsStore.repetitionSensitivity.collectAsState()
    val chronoReceptivity by com.tunemind.app.domain.voice.AgentSettingsStore.lateNightComfortEnabled.collectAsState()
    val cloakingSession by com.tunemind.app.domain.voice.AgentSettingsStore.privateSessionEnabled.collectAsState()
    val deduplicateLogs by com.tunemind.app.domain.voice.AgentSettingsStore.mergeDuplicatePlaysEnabled.collectAsState()

    val scrollState = rememberScrollState()
    val colors = TuneMindTheme.colors
    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFFFB000)
    val dangerRed = Color(0xFFFF4D6D)

    val refreshAllSystemStates: () -> Unit = remember(context) {
        {
            com.tunemind.app.domain.voice.AgentSettingsStore.ensureLoaded(context)
            vm.checkBatteryOptimization(context)
            isSpotifyAuthorized = spotifyAuthManager.isAuthenticated() || spotifyOAuthRepo.tokenManager.isAuthenticated()
            hasExtendedLibraryScopes = spotifyOAuthRepo.hasExtendedLibraryScopes()
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
                if (awaitingSpotifyWakeReturn) {
                    awaitingSpotifyWakeReturn = false
                    com.tunemind.app.provider.SpotifyAppRemoteManager.connectAsync(
                        context = context,
                        showAuthView = true
                    ) { _, _ ->
                        refreshAllSystemStates()
                    }
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

            // 0. Account & Sign-In Card
            val authSessionManager = remember(context) { TuneMindAuthSessionManager.getInstance(context) }
            val authSession by authSessionManager.sessionFlow.collectAsState()
            val hasExplicitAccount = authSession.isGoogleConnected || authSession.email.isNotBlank()

            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = colors.primaryAccent
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioSectionHeader(
                        title = "Account",
                        actionLabel = if (hasExplicitAccount) "Switch / Manage" else "Sign In / Register",
                        onActionClick = onOpenAccountAuth
                    )
                    Text(
                        text = if (hasExplicitAccount) {
                            listOfNotNull(
                                authSession.displayName.takeIf { it.isNotBlank() },
                                authSession.email.takeIf { it.isNotBlank() },
                                authSession.primaryProvider.takeIf { it.isNotBlank() }
                            ).joinToString(" · ")
                        } else {
                            "Guest session active. Sign in or create an account to save your TuneMind profile."
                        },
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.70f),
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Voice & Playback Card
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
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
                title = "Never Leave TuneMind",
                description = if (strictNoAppSwitchEnabled) {
                    "Keeps playback inside TuneMind and blocks switching to other apps."
                } else {
                    "Off: Plays in the background and returns when supported."
                },
                checked = strictNoAppSwitchEnabled,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setStrictNoAppSwitchEnabled(context, it)
                }
            )
            StudioHairlineDivider()

            SettingsSwitchRow(
                title = "Stay in TuneMind",
                description = if (stayInTuneMindEnabled) {
                    "Controls Spotify quietly in the background."
                } else {
                    "Off: Opens your music app directly when starting a track."
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
                    "Plays local files and previews directly in TuneMind."
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

            // Spotify Remote & Account inline block
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Spotify Remote & Account",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = when {
                            isConnectingRemote -> "Connecting…"
                            appRemoteConnected -> "Connected"
                            else -> "Standby"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (appRemoteConnected) safeGreen else Color.White.copy(alpha = 0.56f)
                    )
                }

                Text(
                    text = when {
                        isConnectingRemote -> "Connecting to Spotify Remote — approve the prompt if asked."
                        appRemoteConnected && isSpotifyAuthorized && hasExtendedLibraryScopes && !needsLibraryScopeUpgrade ->
                            appRemoteSuccessMsg ?: "Silent playback control and full Spotify library sync are active."
                        appRemoteConnected -> "Silent Spotify playback control is active."
                        !isSpotifyInstalled -> "Spotify app not found on this device."
                        !appRemoteError.isNullOrBlank() -> appRemoteError!!
                        isSpotifyAuthorized && (needsLibraryScopeUpgrade || !hasExtendedLibraryScopes) ->
                            "Spotify linked — update permissions to sync Liked Songs, Playlists & Podcasts."
                        isSpotifyAuthorized ->
                            "Spotify library linked — connect Remote for in-app playback control."
                        else -> "Link Spotify or connect Remote to control music without switching apps."
                    },
                    fontSize = 12.sp,
                    color = if (!appRemoteError.isNullOrBlank() && !appRemoteConnected) warnAmber else Color.White.copy(alpha = 0.60f),
                    lineHeight = 17.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                                containerColor = if (appRemoteConnected) safeGreen else Color(0xFFF8FAFC),
                                contentColor = Color(0xFF0D0D0D)
                            ),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Text(
                                text = when {
                                    isConnectingRemote -> "Connecting…"
                                    appRemoteConnected -> "Remote Active"
                                    else -> "Connect Remote"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { spotifyAuthManager.launchSpotifyAuth(context) },
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(
                            1.dp,
                            when {
                                isSpotifyAuthorized && (needsLibraryScopeUpgrade || !hasExtendedLibraryScopes) -> warnAmber
                                isSpotifyAuthorized -> safeGreen.copy(alpha = 0.65f)
                                else -> Color.White.copy(alpha = 0.22f)
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(
                            text = when {
                                isSpotifyAuthorized && (needsLibraryScopeUpgrade || !hasExtendedLibraryScopes) -> "Update Access"
                                isSpotifyAuthorized -> "Spotify Linked"
                                else -> "Link Spotify"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                isSpotifyAuthorized && (needsLibraryScopeUpgrade || !hasExtendedLibraryScopes) -> warnAmber
                                isSpotifyAuthorized -> safeGreen
                                else -> Color(0xFFF8FAFC)
                            }
                        )
                    }
                }

                if (!appRemoteConnected && !appRemoteError.isNullOrBlank() && isSpotifyInstalled) {
                    Text(
                        text = "Open Spotify to wake it up →",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = warnAmber,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                awaitingSpotifyWakeReturn = true
                                runCatching {
                                    context.packageManager.getLaunchIntentForPackage("com.spotify.music")?.let { wakeIntent ->
                                        wakeIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(wakeIntent)
                                    }
                                }
                            }
                            .padding(vertical = 4.dp)
                    )
                }
            }
            }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Permissions Card
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StudioSectionHeader(title = "Permissions")
                    Spacer(modifier = Modifier.height(6.dp))
                    StudioHairlineDivider()

            SettingsPermissionRow(
                title = "Return to TuneMind",
                description = if (hasOverlayPermission) {
                    "Can return automatically when another music app opens."
                } else {
                    "Lets TuneMind bring you back after starting playback."
                },
                isEnabled = hasOverlayPermission,
                colors = colors,
                onClick = {
                    runCatching {
                        context.startActivity(com.tunemind.app.util.PermissionUtils.getOverlayPermissionIntent(context))
                    }
                }
            )
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
                    runCatching {
                        context.startActivity(
                            com.tunemind.app.util.PermissionUtils.getNotificationListenerIntent().apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }
            )
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Audio & Hearing Card
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Appearance Card
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = colors.primaryAccent
            ) {
                AdaptiveAtmosphereSection(vm = vm)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Default Music Player Card
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
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

            providers.forEach { provider ->
                val isSelected = provider.providerId == selectedProviderId
                val isInstalled = provider.isInstalled(context)
                val capDesc = if (provider.supportedCapabilities.contains(com.tunemind.app.provider.ProviderCapability.CAN_DEEP_LINK)) {
                    "Direct track playback"
                } else {
                    "Opens track search"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedProviderId = provider.providerId
                            vm.setPreferredProviderId(context, provider.providerId)
                        }
                        .padding(vertical = 12.dp, horizontal = 2.dp),
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
                        Text(
                            text = provider.displayName,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = Color(0xFFF8FAFC)
                        )
                        Text(
                            text = "$capDesc · ${if (isInstalled) "Installed" else "Web"}",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.56f)
                        )
                    }
                }
                StudioHairlineDivider()
            }

            }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 6. Freshness & Privacy Card
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
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
                description = "Combines identical songs played across Spotify and other players.",
                checked = deduplicateLogs,
                onCheckedChange = {
                    com.tunemind.app.domain.voice.AgentSettingsStore.setMergeDuplicatePlaysEnabled(context, it)
                }
            )
                }
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
