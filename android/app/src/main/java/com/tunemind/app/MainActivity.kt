package com.tunemind.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.tunemind.app.domain.voice.AgentSettingsStore
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.ui.AppHolder
import com.tunemind.app.ui.components.FloatingGlassDock
import com.tunemind.app.ui.onboarding.OnboardingScreen
import com.tunemind.app.ui.screens.DopamineVaultScreen
import com.tunemind.app.ui.screens.PulseAgentScreen
import com.tunemind.app.ui.screens.RhythmDnaScreen
import com.tunemind.app.ui.theme.TuneMindTheme
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class MainActivity : ComponentActivity() {

    private var startupError: Throwable? = null
    private val pendingRecoveryBundleFlow =
        kotlinx.coroutines.flow.MutableStateFlow<Bundle?>(null)

    private fun captureRecoveryIntent(intent: Intent?) {
        if (intent?.action == com.tunemind.app.provider.MusicPlayerAutoLauncher.ACTION_RECOVER_PLAYBACK_IN_TUNEMIND) {
            val extras = intent.extras ?: return
            pendingRecoveryBundleFlow.value = Bundle(extras)
            intent.action = Intent.ACTION_MAIN
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Initialize official AndroidX splash screen before super.onCreate
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)
        AppHolder.context = applicationContext
        captureRecoveryIntent(intent)

        var isInitializationComplete by mutableStateOf(false)

        // 2. Keep native splash on screen until core auth/settings/DB init finishes (zero double-splash)
        splashScreen.setKeepOnScreenCondition {
            !isInitializationComplete
        }

        // 3. Background startup initialization (auth session, settings, Room warm-up)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.tunemind.app.domain.voice.AgentSettingsStore.ensureLoaded(applicationContext)
                    com.tunemind.app.data.auth.TuneMindAuthSessionManager
                        .getInstance(applicationContext)
                        .refreshSessionState()
                    runCatching {
                        com.tunemind.app.data.local.TuneMindDatabase.getInstance(applicationContext)
                    }
                }
            } finally {
                isInitializationComplete = true
            }
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("MainActivity", "UNCAUGHT EXCEPTION on thread ${thread.name}: ${throwable.message}", throwable)
            startupError = throwable
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Safe rebind attempt without touching component enabled settings
        forceRebindListener(this)

        enableEdgeToEdge()
        try {
            setContent {
                if (!isInitializationComplete) return@setContent
                var interceptedError by remember { mutableStateOf(startupError) }
                var showBrandSplash by rememberSaveable { mutableStateOf(true) }

                if (interceptedError != null) {
                    CrashDiagnosticsScreen(
                        error = interceptedError!!,
                        onReset = {
                            interceptedError = null
                            try {
                                com.tunemind.app.data.local.TuneMindDatabase.resetDatabase(applicationContext)
                            } catch (_: Exception) {}
                            recreate()
                        }
                    )
                } else {
                    val context = LocalContext.current
                    val vm: com.tunemind.app.ui.TuneMindViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                        factory = com.tunemind.app.ui.TuneMindViewModelFactory()
                    )
                    val homeState by vm.homeState.collectAsState()
                    val historyState by vm.historyState.collectAsState()
                    val vaultState by vm.vaultState.collectAsState()
                    val hearingInsights by vm.hearingInsightsState.collectAsState()
                    val adaptiveState by vm.adaptiveThemeState.collectAsState()
                    val themePreferences by vm.themePreferences.collectAsState()

                    val listeningStats by vm.listeningStats.collectAsState()
                    val cleanPlayHistory by vm.cleanPlayHistory.collectAsState()
                    val crossAppLibraryTracks by vm.crossAppLibraryTracks.collectAsState()
                    val detectedPlayerTabs by vm.detectedPlayerTabs.collectAsState()
                    val aiDiscoveryCrate by vm.aiDiscoveryCrate.collectAsState()
                    val savedAiRecommendations by vm.savedAiRecommendations.collectAsState()
                    val forYouLearningState by vm.forYouLearningState.collectAsState()
                    val isDiscovering by vm.isDiscovering.collectAsState()
                    val tasteShiftSummary by vm.tasteShiftSummary.collectAsState()
                    val libraryActionFeedback by vm.libraryActionFeedback.collectAsState()
                    val manualFreshTracks by vm.manualFreshTracks.collectAsState()
                    val freshExcludedKeys by vm.freshExcludedKeys.collectAsState()

                    val playerPrefs = remember {
                        context.getSharedPreferences("tunemind_player_prefs", Context.MODE_PRIVATE)
                    }
                    var selectedTargetPlayer by remember {
                        mutableStateOf(
                            playerPrefs.getString("target_player_pkg", "") ?: ""
                        )
                    }

                    val authSessionManager = remember {
                        com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(context)
                    }
                    val authSession by authSessionManager.sessionFlow.collectAsState()

                    // Check onboarding permissions
                    var hasAllPermissions by remember {
                        mutableStateOf(
                            PermissionUtils.hasMicrophonePermission(context) &&
                            PermissionUtils.hasNotificationListenerPermission(context) &&
                            PermissionUtils.isIgnoringBatteryOptimizations(context)
                        )
                    }

                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                authSessionManager.refreshSessionState()
                                hasAllPermissions = PermissionUtils.hasMicrophonePermission(context) &&
                                                   PermissionUtils.hasNotificationListenerPermission(context) &&
                                                   PermissionUtils.isIgnoringBatteryOptimizations(context)
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }

                    val coroutineScope = rememberCoroutineScope()
                    var requestedScreenPage by remember { mutableStateOf<Int?>(null) }
                    var requestedDopamineSubTab by remember { mutableStateOf<Int?>(null) }
                    var requestedRhythmSubDeck by remember { mutableStateOf<Int?>(null) }
                    var showSettingsScreen by remember { mutableStateOf(false) }
                    var openSpotifySetupInSettings by remember { mutableStateOf(false) }
                    var showProfileScreen by remember { mutableStateOf(false) }
                    var showStatsScreen by remember { mutableStateOf(false) }
                    var showWeeklyReportScreen by remember { mutableStateOf(false) }
                    var showHearingScreen by remember { mutableStateOf(false) }
                    var showMusicProfileScreen by remember { mutableStateOf(false) }
                    var showSearchScreen by remember { mutableStateOf(false) }
                    var showNowPlayingScreen by remember { mutableStateOf(false) }
                    var showPlaylistsScreen by remember { mutableStateOf(false) }
                    var selectedPlaylistNavName by remember { mutableStateOf<String?>(null) }
                    var showLegalScreen by remember { mutableStateOf(false) }
                    var legalInitialTab by remember { mutableStateOf(0) }
                    var showSupportScreen by remember { mutableStateOf(false) }
                    var showNotificationsScreen by remember { mutableStateOf(false) }
                    var showAgentGuideSheet by remember { mutableStateOf(false) }
                    var currentPagerPage by remember { mutableStateOf(1) }

                    val autonomousDjEnabled by AgentSettingsStore.autonomousDjEnabled.collectAsState()
                    val stayInTuneMindEnabled by AgentSettingsStore.stayInTuneMindEnabled.collectAsState()
                    val strictNoAppSwitchEnabled by AgentSettingsStore.strictNoAppSwitchEnabled.collectAsState()
                    val kidsModeEnabled by AgentSettingsStore.kidsModeEnabled.collectAsState()
                    val tuneMindPlaylists by vm.tuneMindPlaylists.collectAsState()
                    val playbackLaunchFeedback by vm.playbackLaunchFeedback.collectAsState()

                    LaunchedEffect(Unit) {
                        com.tunemind.app.domain.voice.AgentSettingsStore.ensureLoaded(context)
                        com.tunemind.app.sync.SyncScheduler.scheduleSpotifyTokenKeepAlive(context)
                    }

                    // Auto-restore encrypted Spotify connection from TuneMind cloud on startup / sign-in
                    LaunchedEffect(authSession.userId, authSession.isSupabaseAuthenticated) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.tunemind.app.provider.SpotifyPremiumConnectManager.syncAppDefaultClientId(context)
                            com.tunemind.app.provider.SpotifyPremiumConnectManager.restoreConnectionFromCloudIfNeeded(context)
                            if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnected(context)) {
                                com.tunemind.app.data.ingestion.SpotifyLibrarySyncEngine.syncAll(
                                    context = context,
                                    forceRefresh = false,
                                    minIntervalMs = 60 * 60 * 1000L
                                )
                            }
                        }
                    }

                    fun applyPlayerPreferenceUpdate(rawPlayer: String?, rememberDefault: Boolean) {
                        val target = rawPlayer?.trim()?.takeIf { it.isNotBlank() } ?: return
                        val registry = com.tunemind.app.provider.MusicProviderRegistry.getInstance(context)
                        val matched = registry.getInstalledProviders().firstOrNull { p ->
                            p.packageName.equals(target, ignoreCase = true) ||
                                p.providerId.equals(target, ignoreCase = true) ||
                                p.displayName.equals(target, ignoreCase = true) ||
                                p.displayName.contains(target, ignoreCase = true)
                        }
                        val resolvedPkg = matched?.packageName?.ifBlank { matched.providerId } ?: target
                        val resolvedId = matched?.providerId ?: when {
                            resolvedPkg.contains("boomplay", ignoreCase = true) -> "boomplay"
                            resolvedPkg.contains("youtube", ignoreCase = true) -> "youtube_music"
                            resolvedPkg.contains("audiomack", ignoreCase = true) -> "audiomack"
                            resolvedPkg.contains("spotify", ignoreCase = true) -> "spotify"
                            resolvedPkg.contains("local", ignoreCase = true) -> "local_files"
                            else -> "tunemind_inapp"
                        }
                        selectedTargetPlayer = resolvedPkg
                        if (rememberDefault) {
                            playerPrefs.edit().putString("target_player_pkg", resolvedPkg).apply()
                            registry.selectPlayer(resolvedId, true)
                        }
                    }

                    var playAnyTrackCallback: ((String, String, String?, String) -> Unit)? = null

                    fun executeSingleAppToolAction(
                        tool: com.tunemind.app.domain.execution.AgentToolCallPayload,
                        isPrimaryTurnAction: Boolean = false
                    ) {
                        if (tool.remember_default_player == true && !tool.target_player.isNullOrBlank() && tool.action != "SELECT_MUSIC_PLAYER") {
                            applyPlayerPreferenceUpdate(tool.target_player, rememberDefault = true)
                        }
                        when (tool.action) {
                            "SELECT_MUSIC_PLAYER" -> {
                                applyPlayerPreferenceUpdate(
                                    rawPlayer = tool.target_player,
                                    rememberDefault = tool.remember_default_player != false
                                )
                            }
                            "OPEN_MUSIC_PLAYER_APP" -> {
                                val targetPkg = tool.target_player?.takeIf { it.isNotBlank() }
                                    ?: selectedTargetPlayer.ifBlank { playerPrefs.getString("target_player_pkg", "") ?: "" }
                                if (targetPkg.isNotBlank()) {
                                    runCatching {
                                        val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
                                            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        if (launchIntent != null) {
                                            context.startActivity(launchIntent)
                                        }
                                    }
                                }
                            }
                            "PAUSE_PLAYBACK" -> {
                                vm.setAgentPlaybackPaused()
                                if (!isPrimaryTurnAction) {
                                    vm.pausePlayback()
                                }
                            }
                            "RESUME_PLAYBACK" -> {
                                vm.setAgentPlaybackResumed()
                                if (!isPrimaryTurnAction) {
                                    vm.resumePlayback()
                                }
                            }
                            "SKIP_NEXT" -> {
                                if (!isPrimaryTurnAction) {
                                    vm.skipNext()
                                }
                            }
                            "SKIP_PREVIOUS" -> {
                                if (!isPrimaryTurnAction) {
                                    vm.skipPrev()
                                }
                            }
                            "LIKE_TRACK" -> {
                                val np = vm.homeState.value.nowPlaying
                                val lTitle = tool.track_title?.takeIf { it.isNotBlank() } ?: np?.title
                                val lArtist = tool.track_artist?.takeIf { it.isNotBlank() } ?: np?.artist ?: ""
                                if (!lTitle.isNullOrBlank() && lTitle != "Standing By") {
                                    vm.saveTrackToCrossAppLiked(
                                        title = lTitle,
                                        artist = lArtist,
                                        sourcePkg = tool.target_player ?: np?.sourcePackage,
                                        trackId = tool.track_id ?: np?.trackId
                                    )
                                    vm.addToFresh(
                                        title = lTitle,
                                        artist = lArtist,
                                        sourcePackage = tool.target_player ?: np?.sourcePackage,
                                        spotifyUri = tool.spotify_uri ?: np?.spotifyUri,
                                        trackId = tool.track_id ?: np?.trackId
                                    )
                                    if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnected(context)) {
                                        val likeUri = tool.spotify_uri ?: np?.spotifyUri
                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            com.tunemind.app.provider.SpotifyPremiumConnectManager.likeTrackOnSpotify(
                                                context = context,
                                                title = lTitle,
                                                artist = lArtist,
                                                spotifyUri = likeUri
                                            )
                                        }
                                    }
                                }
                            }
                            "UNLIKE_TRACK" -> {
                                val np = vm.homeState.value.nowPlaying
                                val uTitle = tool.track_title?.takeIf { it.isNotBlank() } ?: np?.title
                                val uArtist = tool.track_artist?.takeIf { it.isNotBlank() } ?: np?.artist ?: ""
                                if (!uTitle.isNullOrBlank() && uTitle != "Standing By") {
                                    val canonicalKey = com.tunemind.app.domain.model.TrackIdentityResolver
                                        .buildNormalizedMatchKey(uTitle, uArtist)
                                    vm.toggleLibraryTrackLike(canonicalKey, liked = false)
                                    if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnected(context)) {
                                        val unlikeUri = tool.spotify_uri ?: np?.spotifyUri
                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            com.tunemind.app.provider.SpotifyPremiumConnectManager.unlikeTrackOnSpotify(
                                                context = context,
                                                title = uTitle,
                                                artist = uArtist,
                                                spotifyUri = unlikeUri
                                            )
                                        }
                                    }
                                }
                            }
                            "PLAY_SPOTIFY_CONTEXT" -> {
                                if (!isPrimaryTurnAction) {
                                    val ctxUri = tool.context_uri?.takeIf { it.isNotBlank() }
                                        ?: tool.spotify_uri?.takeIf { it.isNotBlank() }
                                    if (!ctxUri.isNullOrBlank()) {
                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            com.tunemind.app.provider.SpotifyPremiumConnectManager.playSpotifyContextSilently(
                                                context = context,
                                                contextUri = ctxUri,
                                                shuffleEnabled = tool.shuffle_enabled
                                            )
                                        }
                                    }
                                }
                            }
                            "SET_SHUFFLE" -> {
                                val shuffleTarget = tool.shuffle_enabled ?: true
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    com.tunemind.app.provider.SpotifyPremiumConnectManager.setShuffle(
                                        context = context,
                                        enabled = shuffleTarget
                                    )
                                }
                            }
                            "SET_REPEAT_MODE" -> {
                                val mode = tool.repeat_mode?.takeIf { it.isNotBlank() } ?: "off"
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    com.tunemind.app.provider.SpotifyPremiumConnectManager.setRepeatMode(
                                        context = context,
                                        mode = mode
                                    )
                                }
                            }
                            "SEEK_TO_POSITION" -> {
                                if (!isPrimaryTurnAction) {
                                    val posMs = tool.seek_position_ms
                                    val deltaMs = tool.seek_delta_ms
                                    val np = vm.homeState.value.nowPlaying
                                    if (com.tunemind.app.provider.TuneMindInAppPlayer.hasActiveTrack() ||
                                        com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideo()
                                    ) {
                                        val cur = np?.positionMs ?: 0L
                                        val dest = (posMs ?: (cur + (deltaMs ?: 0L))).coerceAtLeast(0L)
                                        vm.seekTo(dest)
                                    } else if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)) {
                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            com.tunemind.app.provider.SpotifyPremiumConnectManager.seekPlayback(
                                                context = context,
                                                positionMs = posMs,
                                                deltaMs = deltaMs
                                            )
                                        }
                                    } else {
                                        val cur = np?.positionMs ?: 0L
                                        val dest = (posMs ?: (cur + (deltaMs ?: 0L))).coerceAtLeast(0L)
                                        vm.seekTo(dest)
                                    }
                                }
                            }
                            "TRANSFER_SPOTIFY_DEVICE" -> {
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    com.tunemind.app.provider.SpotifyPremiumConnectManager.transferPlaybackDevice(
                                        context = context,
                                        deviceId = tool.device_id,
                                        deviceName = tool.device_name
                                    )
                                }
                            }
                            "LAUNCH_DOPAMINE_CAPSULE" -> {
                                vm.compileDopamineCapsule()
                                requestedScreenPage = 0
                                requestedDopamineSubTab = 0
                            }
                            "REFRESH_FOR_YOU_PICKS" -> {
                                vm.triggerChronoDiscovery(forceRefresh = true)
                                requestedScreenPage = 0
                                requestedDopamineSubTab = 2
                            }
                            "QUARANTINE_TO_VAULT" -> {
                                val np = vm.homeState.value.nowPlaying
                                val qTitle = tool.track_title ?: np?.title
                                val qArtist = tool.track_artist ?: np?.artist ?: ""
                                if (!qTitle.isNullOrBlank()) {
                                    vm.quarantineTrack(
                                        title = qTitle,
                                        artist = qArtist,
                                        sourcePackage = np?.sourcePackage,
                                        spotifyUri = np?.spotifyUri,
                                        trackId = np?.trackId
                                    )
                                }
                            }
                            "RESTORE_FROM_VAULT" -> {
                                val rTitle = tool.track_title ?: ""
                                vm.restoreTrackFromVaultByTitle(rTitle)
                            }
                            "SHIFT_ATMOSPHERE" -> {
                                val pid = tool.palette_id
                                if (!pid.isNullOrBlank()) {
                                    vm.setAtmospherePaletteById(pid)
                                } else {
                                    vm.cycleAtmospherePalette()
                                }
                            }
                            "SET_THEME_COLOR" -> {
                                val hex = tool.custom_accent_hex
                                val behavior = when (tool.theme_behavior?.uppercase()) {
                                    "FIXED" -> com.tunemind.app.ui.theme.ThemeBehavior.FIXED
                                    "ADAPTIVE" -> com.tunemind.app.ui.theme.ThemeBehavior.ADAPTIVE
                                    "TIME_BASED" -> com.tunemind.app.ui.theme.ThemeBehavior.TIME_BASED
                                    else -> null
                                }
                                vm.setCustomThemeAccentHex(hex, behavior)
                            }
                            "TOGGLE_AUTONOMOUS_DJ" -> {
                                val target = tool.autonomous_dj_enabled
                                    ?: !com.tunemind.app.domain.voice.AgentSettingsStore.isAutonomousDjEnabled(context)
                                com.tunemind.app.domain.voice.AgentSettingsStore.setAutonomousDjEnabled(context, target)
                            }
                            "TOGGLE_STAY_IN_TUNEMIND" -> {
                                val target = tool.stay_in_tunemind_enabled
                                    ?: !com.tunemind.app.domain.voice.AgentSettingsStore.isStayInTuneMindEnabled(context)
                                com.tunemind.app.domain.voice.AgentSettingsStore.setStayInTuneMindEnabled(context, target)
                            }
                            "TOGGLE_STRICT_NO_APP_SWITCH" -> {
                                val target = tool.strict_no_app_switch_enabled
                                    ?: !com.tunemind.app.domain.voice.AgentSettingsStore.isStrictNoAppSwitchEnabled(context)
                                com.tunemind.app.domain.voice.AgentSettingsStore.setStrictNoAppSwitchEnabled(context, target)
                            }
                            "CREATE_TUNEMIND_PLAYLIST", "ADD_TO_TUNEMIND_PLAYLIST" -> {
                                val targetName = tool.playlist_name?.takeIf { it.isNotBlank() } ?: "TuneMind Mix"
                                val resolvedTracks = when {
                                    tool.playlist_tracks.isNotEmpty() -> tool.playlist_tracks
                                    !tool.track_title.isNullOrBlank() -> listOf(
                                        com.tunemind.app.domain.execution.PlaylistTrackInput(
                                            title = tool.track_title,
                                            artist = tool.track_artist.orEmpty(),
                                            spotify_uri = tool.spotify_uri,
                                            track_id = tool.track_id
                                        )
                                    )
                                    else -> {
                                        val np = vm.homeState.value.nowPlaying
                                        val npTitle = np?.title
                                        if (!npTitle.isNullOrBlank() && npTitle != "Standing By") {
                                            listOf(
                                                com.tunemind.app.domain.execution.PlaylistTrackInput(
                                                    title = npTitle,
                                                    artist = np.artist.orEmpty(),
                                                    spotify_uri = np.spotifyUri,
                                                    track_id = np.trackId,
                                                    source_player = np.sourcePackage
                                                )
                                            )
                                        } else {
                                            emptyList()
                                        }
                                    }
                                }
                                vm.createOrAddToTuneMindPlaylistAsync(
                                    name = targetName,
                                    description = tool.playlist_description.orEmpty(),
                                    moodOrAudience = tool.mood_or_audience.orEmpty(),
                                    tracks = resolvedTracks,
                                    appendIfExists = true
                                ) {
                                    if (tool.open_playlist_screen != false) {
                                        coroutineScope.launch {
                                            selectedPlaylistNavName = targetName
                                            showPlaylistsScreen = true
                                        }
                                    }
                                }
                            }
                            "REMOVE_FROM_TUNEMIND_PLAYLIST" -> {
                                val query = tool.playlist_name?.trim().orEmpty()
                                val match = vm.tuneMindPlaylists.value.firstOrNull {
                                    it.playlist.name.equals(query, ignoreCase = true) ||
                                        (query.isNotBlank() && it.playlist.name.contains(query, ignoreCase = true))
                                } ?: vm.tuneMindPlaylists.value.firstOrNull()
                                val targetTrackTitle = tool.track_title?.takeIf { it.isNotBlank() }
                                    ?: vm.homeState.value.nowPlaying?.title.orEmpty()
                                if (match != null && targetTrackTitle.isNotBlank()) {
                                    val entry = match.tracks.firstOrNull {
                                        it.title.equals(targetTrackTitle, ignoreCase = true) ||
                                            it.title.contains(targetTrackTitle, ignoreCase = true)
                                    }
                                    if (entry != null) {
                                        vm.removeTrackFromTuneMindPlaylist(match.playlist.playlistId, entry.entryId)
                                    }
                                }
                            }
                            "RENAME_TUNEMIND_PLAYLIST" -> {
                                val query = tool.playlist_name?.trim().orEmpty()
                                val newName = tool.new_playlist_name?.trim().orEmpty()
                                val match = vm.tuneMindPlaylists.value.firstOrNull {
                                    it.playlist.name.equals(query, ignoreCase = true) ||
                                        (query.isNotBlank() && it.playlist.name.contains(query, ignoreCase = true))
                                }
                                if (match != null && newName.isNotBlank()) {
                                    vm.renameTuneMindPlaylist(match.playlist.playlistId, newName)
                                }
                            }
                            "DELETE_TUNEMIND_PLAYLIST" -> {
                                val query = tool.playlist_name?.trim().orEmpty()
                                val match = vm.tuneMindPlaylists.value.firstOrNull {
                                    it.playlist.name.equals(query, ignoreCase = true) ||
                                        (query.isNotBlank() && it.playlist.name.contains(query, ignoreCase = true))
                                }
                                if (match != null) {
                                    vm.deleteTuneMindPlaylist(match.playlist.playlistId)
                                }
                            }
                            "PLAY_TUNEMIND_PLAYLIST" -> {
                                val query = tool.playlist_name?.trim().orEmpty()
                                val match = vm.tuneMindPlaylists.value.firstOrNull {
                                    it.playlist.name.equals(query, ignoreCase = true) ||
                                        (query.isNotBlank() && it.playlist.name.contains(query, ignoreCase = true))
                                } ?: vm.tuneMindPlaylists.value.firstOrNull()
                                if (match != null) {
                                    val chosenPkg = tool.target_player?.takeIf { it.isNotBlank() }
                                        ?: playerPrefs.getString("target_player_pkg", "tunemind_inapp")
                                        ?: "tunemind_inapp"
                                    vm.playTuneMindPlaylist(match, chosenPkg) { title, artist, uri, pkg ->
                                        coroutineScope.launch {
                                            // Wait briefly if primary voice turn is speaking so we don't cut off the agent's playlist announcement
                                            if (isPrimaryTurnAction) {
                                                kotlinx.coroutines.delay(2200L)
                                            }
                                            playAnyTrackCallback?.invoke(title, artist, uri, pkg)
                                        }
                                    }
                                }
                            }
                            "QUEUE_TRACKS" -> {
                                val tracksToQueue = when {
                                    tool.queue_tracks.isNotEmpty() -> tool.queue_tracks
                                    !tool.track_title.isNullOrBlank() -> listOf(
                                        com.tunemind.app.domain.execution.PlaylistTrackInput(
                                            title = tool.track_title,
                                            artist = tool.track_artist.orEmpty(),
                                            spotify_uri = tool.spotify_uri
                                        )
                                    )
                                    else -> emptyList()
                                }
                                tracksToQueue.reversed().forEach { qt ->
                                    vm.enqueueUpNextTrack(
                                        title = qt.title,
                                        artist = qt.artist,
                                        spotifyUri = qt.spotify_uri,
                                        reason = "Queued by TuneMind",
                                        freshnessPercent = 96,
                                        playNext = true
                                    )
                                }
                                if (tracksToQueue.isNotEmpty() &&
                                    com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)
                                ) {
                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        tracksToQueue.forEach { qt ->
                                            com.tunemind.app.provider.SpotifyPremiumConnectManager.queueTrack(
                                                context = context,
                                                title = qt.title,
                                                artist = qt.artist,
                                                spotifyUri = qt.spotify_uri
                                            )
                                        }
                                    }
                                }
                            }
                            "PLAY_NEXT_IN_QUEUE" -> {
                                val chosenPkg = tool.target_player?.takeIf { it.isNotBlank() }
                                    ?: selectedTargetPlayer.ifBlank { null }
                                vm.playNextFromQueue(chosenPkg)
                            }
                            "CLEAR_UP_NEXT_QUEUE" -> {
                                val snapshot = vm.compileDopamineCapsule().tracks
                                snapshot.forEach { t ->
                                    vm.removeFromUpNextQueue(t)
                                }
                            }
                            "SET_SLEEP_TIMER" -> {
                                val mins = tool.sleep_timer_minutes ?: 0
                                if (mins > 0) {
                                    vm.startSleepTimerMinutes(mins)
                                } else {
                                    vm.cancelSleepTimer()
                                }
                            }
                            "TOGGLE_KIDS_MODE" -> {
                                val target = tool.kids_mode_enabled
                                    ?: !AgentSettingsStore.isKidsModeEnabled(context)
                                AgentSettingsStore.setKidsModeEnabled(context, target)
                            }
                            "OPEN_SETTINGS" -> {
                                showProfileScreen = true
                                showSettingsScreen = true
                            }
                            "OPEN_SPOTIFY_CONNECT_GUIDE" -> {
                                openSpotifySetupInSettings = true
                                showProfileScreen = true
                                showSettingsScreen = true
                            }
                            "SYNC_SPOTIFY_LIBRARY" -> {
                                if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnected(context)) {
                                    vm.syncSpotifyLibraryNow(forceRefresh = true)
                                    if (tool.open_playlist_screen == true) {
                                        showSettingsScreen = false
                                        showProfileScreen = false
                                        showPlaylistsScreen = true
                                    }
                                } else {
                                    openSpotifySetupInSettings = true
                                    showProfileScreen = true
                                    showSettingsScreen = true
                                }
                            }
                            "EXPORT_PLAYLIST_TO_SPOTIFY" -> {
                                if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnected(context)) {
                                    vm.exportTuneMindPlaylistToSpotify(tool.playlist_name.orEmpty())
                                } else {
                                    openSpotifySetupInSettings = true
                                    showProfileScreen = true
                                    showSettingsScreen = true
                                }
                            }
                            "NAVIGATE_SCREEN" -> {
                                val screen = tool.target_screen?.uppercase() ?: ""
                                when {
                                    screen.contains("SETTING") -> {
                                        showProfileScreen = true
                                        showSettingsScreen = true
                                    }
                                    screen.contains("SEARCH") || screen.contains("FIND") -> {
                                        showSettingsScreen = false
                                        showSearchScreen = true
                                    }
                                    screen.contains("NOW_PLAYING") || screen.contains("MINI_PLAYER") || screen.contains("QUEUE") -> {
                                        showNowPlayingScreen = true
                                    }
                                    screen.contains("NOTIF") || screen.contains("ALERT") -> {
                                        showSettingsScreen = false
                                        showProfileScreen = true
                                        showNotificationsScreen = true
                                    }
                                    screen.contains("SUPPORT") || screen.contains("HELP") || screen.contains("FEEDBACK") -> {
                                        showSettingsScreen = false
                                        showProfileScreen = true
                                        showSupportScreen = true
                                    }
                                    screen.contains("LEGAL") || screen.contains("PRIVACY") || screen.contains("TERM") -> {
                                        legalInitialTab = if (screen.contains("TERM")) 1 else 0
                                        showLegalScreen = true
                                    }
                                    screen.contains("TUNEMIND_PLAYLIST") || screen.contains("MY_PLAYLIST") || screen == "PLAYLISTS" || screen == "PLAYLIST" -> {
                                        showSettingsScreen = false
                                        showProfileScreen = false
                                        showStatsScreen = false
                                        selectedPlaylistNavName = tool.playlist_name?.takeIf { it.isNotBlank() }
                                        showPlaylistsScreen = true
                                    }
                                    screen.contains("STAT") || screen.contains("LISTENING_SUMMARY") -> {
                                        showSettingsScreen = false
                                        showProfileScreen = false
                                        showWeeklyReportScreen = false
                                        showHearingScreen = false
                                        showMusicProfileScreen = false
                                        showStatsScreen = true
                                    }
                                    screen.contains("WEEKLY") || screen.contains("RECAP") -> {
                                        showSettingsScreen = false
                                        showWeeklyReportScreen = true
                                    }
                                    screen.contains("HEARING") || screen.contains("EAR") -> {
                                        showSettingsScreen = false
                                        showHearingScreen = true
                                    }
                                    screen.contains("TASTE") -> {
                                        showSettingsScreen = false
                                        showMusicProfileScreen = true
                                    }
                                    screen.contains("PROFILE") || screen.contains("ACCOUNT") -> {
                                        showSettingsScreen = false
                                        showProfileScreen = true
                                    }
                                    else -> {
                                        showSettingsScreen = false
                                        showProfileScreen = false
                                        showStatsScreen = false
                                        showWeeklyReportScreen = false
                                        showHearingScreen = false
                                        showMusicProfileScreen = false
                                        showSearchScreen = false
                                        showNowPlayingScreen = false
                                        showPlaylistsScreen = false
                                        showNotificationsScreen = false
                                        showSupportScreen = false
                                        showLegalScreen = false
                                        when {
                                            screen.contains("FOR_YOU") || screen.contains("SUGGEST") || screen.contains("DISCOVER") -> {
                                                requestedScreenPage = 0
                                                requestedDopamineSubTab = 2
                                            }
                                            screen.contains("RESTING") || screen.contains("VAULT") || screen.contains("COOL") -> {
                                                requestedScreenPage = 0
                                                requestedDopamineSubTab = 1
                                            }
                                            screen.contains("FRESH") || screen.contains("DOPAMINE") -> {
                                                requestedScreenPage = 0
                                                requestedDopamineSubTab = 0
                                            }
                                            screen.contains("SPOTIFY") || screen.contains("LIBRARY") || screen.contains("PLAYLIST") -> {
                                                requestedScreenPage = 2
                                                requestedRhythmSubDeck = 1
                                            }
                                            screen.contains("HISTORY") -> {
                                                requestedScreenPage = 2
                                                requestedRhythmSubDeck = 2
                                            }
                                            screen.contains("RHYTHM") || screen.contains("DNA") -> {
                                                requestedScreenPage = 2
                                                requestedRhythmSubDeck = 0
                                            }
                                            else -> {
                                                requestedScreenPage = 1
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Foreground Voice Agent & Orchestrator Lifecycle
                    val agentVoiceManager = remember(context) {
                        com.tunemind.app.domain.voice.AgentVoiceManager(
                            context = context,
                            getNowPlaying = { vm.homeState.value.nowPlaying },
                            getRecentTracks = { vm.historyState.value.tracks.ifEmpty { vm.homeState.value.recentTracks } },
                            getHearingInsights = { vm.getFreshHearingInsightsNow() },
                            getVaultTracks = { vm.vaultState.value },
                            getDopamineCapsule = { vm.compileDopamineCapsule() },
                            getForYouPicks = { vm.aiDiscoveryCrate.value },
                            getCrossAppLibraryTracks = { vm.crossAppLibraryTracks.value },
                            getDetectedPlayerTabs = { vm.detectedPlayerTabs.value },
                            getListeningStats = {
                                vm.refreshLiveListeningStatsNow()
                                vm.listeningStats.value
                            },
                            getCleanPlayHistory = { vm.cleanPlayHistory.value },
                            getTuneMindPlaylists = { vm.tuneMindPlaylists.value },
                            getSleepTimerRemainingMinutes = {
                                vm.sleepTimerEndsAtMs.value?.let { endMs ->
                                    ((endMs - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(1)
                                }
                            },
                            getAppState = {
                                val prefs = vm.themePreferences.value
                                val screenName = when {
                                    showLegalScreen -> "LEGAL"
                                    showSupportScreen -> "SUPPORT"
                                    showNotificationsScreen -> "NOTIFICATIONS"
                                    showSettingsScreen -> "SETTINGS"
                                    showProfileScreen -> "PROFILE"
                                    showPlaylistsScreen -> "TUNEMIND_PLAYLISTS"
                                    showSearchScreen -> "SEARCH"
                                    showNowPlayingScreen -> "NOW_PLAYING"
                                    showWeeklyReportScreen -> "WEEKLY_REPORT"
                                    showHearingScreen -> "HEARING_HEALTH"
                                    showMusicProfileScreen -> "MUSIC_PROFILE"
                                    showStatsScreen -> "STATS"
                                    currentPagerPage == 0 -> "DOPAMINE_VAULT"
                                    currentPagerPage == 2 -> "RHYTHM_DNA"
                                    else -> "PULSE_AGENT"
                                }
                                org.json.JSONObject().apply {
                                    put("current_screen", screenName)
                                    put("theme_behavior", prefs.behavior.name)
                                    put("palette_id", vm.adaptiveThemeState.value.activePalette.id)
                                    put("custom_accent_hex", prefs.customAccentHex ?: "")
                                    put("autonomous_dj_enabled", AgentSettingsStore.isAutonomousDjEnabled(context))
                                    put("stay_in_tunemind_enabled", AgentSettingsStore.isStayInTuneMindEnabled(context))
                                    put("strict_no_app_switch_enabled", AgentSettingsStore.isStrictNoAppSwitchEnabled(context))
                                    put("prefer_in_app_player_enabled", AgentSettingsStore.isPreferInAppPlayerEnabled(context))
                                    put("kids_mode_enabled", AgentSettingsStore.isKidsModeEnabled(context))
                                    put("tunemind_playlists_count", vm.tuneMindPlaylists.value.size)
                                    put(
                                        "inapp_player_active",
                                        com.tunemind.app.provider.TuneMindInAppPlayer.isPlaying() ||
                                            com.tunemind.app.provider.TuneMindYouTubeEmbedController.isPlaying()
                                    )
                                    val spotifyConnectStatus = com.tunemind.app.provider.SpotifyPremiumConnectManager.ensureLoaded(context)
                                    put("spotify_connected", spotifyConnectStatus.isConnected)
                                    put("spotify_premium_connected", spotifyConnectStatus.isConnected && spotifyConnectStatus.isPremium)
                                    put("spotify_account_tier", spotifyConnectStatus.accountTierLabel)
                                    put("spotify_user_name", spotifyConnectStatus.displayName)
                                    put("spotify_last_device_name", spotifyConnectStatus.lastDeviceName)
                                    put("spotify_cloud_backup_synced", spotifyConnectStatus.cloudBackedUp)
                                    put("spotify_last_error", spotifyConnectStatus.lastError ?: "")
                                    put("spotify_free_open_in_spotify", AgentSettingsStore.isSpotifyFreeOpenInSpotifyEnabled(context))
                                    put("inapp_youtube_active", com.tunemind.app.provider.TuneMindYouTubeEmbedController.isPlaying())
                                    put("detected_players_count", vm.detectedPlayerTabs.value.size)
                                }
                            },
                            onTrackDispatched = { title, artist, sourcePlayer ->
                                vm.setAgentActiveTrack(title, artist, sourcePlayer)
                            },
                            onToolActionExecuted = { toolCall ->
                                executeSingleAppToolAction(toolCall, isPrimaryTurnAction = true)
                                toolCall.extra_actions.forEach { extra ->
                                    executeSingleAppToolAction(extra, isPrimaryTurnAction = false)
                                }
                            },
                            onPlaybackLaunchResult = { result ->
                                vm.reportPlaybackLaunchResult(result)
                            }
                        )
                    }
                    val orchestrator = remember(agentVoiceManager) {
                        com.tunemind.app.domain.voice.AgentOrchestrator(
                            context = context,
                            agentVoiceManager = agentVoiceManager,
                            getNowPlaying = { vm.homeState.value.nowPlaying }
                        )
                    }
                    val agentState by agentVoiceManager.agentState.collectAsState()
                    val flowAnalysis by agentVoiceManager.flowAnalysisFlow.collectAsState()
                    val agentSatiety by agentVoiceManager.agentSatietyFlow.collectAsState()
                    val pendingProposedTrack by agentVoiceManager.pendingProposedTrackFlow.collectAsState()
                    val liveTranscript by agentVoiceManager.transcriptionFlow.collectAsState(initial = "Say \"Hi TuneMind\" or tap the orb")
                    var hasCompletedLaunchStartFlow by remember { mutableStateOf(authSession.hasCompletedStartFlow) }
                    val hasCompletedAccountGate = authSession.hasCompletedAccountOnboarding

                    val isMainAppActive = hasCompletedLaunchStartFlow &&
                        authSession.hasCompletedStartFlow &&
                        hasCompletedAccountGate &&
                        hasAllPermissions

                    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                    ) {
                        playerPrefs.edit().putBoolean("prompted_post_notifications", true).apply()
                    }

                    var stalledRecoveryAttempt by remember {
                        mutableStateOf<com.tunemind.app.provider.MusicPlayerAutoLauncher.StalledPlaybackAttempt?>(null)
                    }
                    val pendingRecoveryBundle by pendingRecoveryBundleFlow.collectAsState()

                    LaunchedEffect(isMainAppActive) {
                        if (!isMainAppActive) {
                            showSettingsScreen = false
                            showProfileScreen = false
                            showMusicProfileScreen = false
                            showWeeklyReportScreen = false
                            showHearingScreen = false
                            showStatsScreen = false
                            showSearchScreen = false
                            showNowPlayingScreen = false
                            showPlaylistsScreen = false
                            showNotificationsScreen = false
                            showSupportScreen = false
                            showAgentGuideSheet = false
                            orchestrator.stop()
                        } else {
                            orchestrator.start()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !PermissionUtils.hasPostNotificationsPermission(context) &&
                                !playerPrefs.getBoolean("prompted_post_notifications", false)
                            ) {
                                playerPrefs.edit().putBoolean("prompted_post_notifications", true).apply()
                                runCatching {
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        }
                        agentVoiceManager.setForegroundActive(isMainAppActive)
                        vm.setUiForegroundActive(isMainAppActive)
                    }

                    LaunchedEffect(isMainAppActive, pendingRecoveryBundle) {
                        val bundle = pendingRecoveryBundle ?: return@LaunchedEffect
                        if (!isMainAppActive) return@LaunchedEffect
                        pendingRecoveryBundleFlow.value = null
                        stalledRecoveryAttempt = null
                        com.tunemind.app.provider.MusicPlayerAutoLauncher.cancelReturnNotification(context)
                        val rTitle = bundle.getString(com.tunemind.app.provider.MusicPlayerAutoLauncher.EXTRA_RECOVER_TITLE).orEmpty()
                        val rArtist = bundle.getString(com.tunemind.app.provider.MusicPlayerAutoLauncher.EXTRA_RECOVER_ARTIST).orEmpty()
                        val rPlayerName = bundle.getString(com.tunemind.app.provider.MusicPlayerAutoLauncher.EXTRA_RECOVER_PLAYER_NAME).orEmpty().ifBlank { "External player" }
                        if (rTitle.isNotBlank()) {
                            val recoverTargetPkg = if (com.tunemind.app.provider.YouTubeMusicProvider().isInstalled(context)) {
                                com.tunemind.app.provider.YouTubeMusicProvider.YTM_PACKAGE
                            } else {
                                com.tunemind.app.provider.TuneMindInAppProvider.PROVIDER_ID
                            }
                            agentVoiceManager.playTrackImmediatelyWithVoice(
                                title = rTitle,
                                artist = rArtist,
                                targetPlayer = recoverTargetPkg,
                                customSpokenLine = "$rPlayerName stayed paused, so I'm playing $rTitle right here inside TuneMind.",
                                neverOpenExternalApp = true
                            )
                        }
                    }

                    DisposableEffect(lifecycleOwner, agentVoiceManager, orchestrator, isMainAppActive) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            when (event) {
                                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                                    if (isMainAppActive) {
                                        orchestrator.start()
                                        agentVoiceManager.setForegroundActive(true)
                                        vm.setUiForegroundActive(true)
                                        val stalled = com.tunemind.app.provider.MusicPlayerAutoLauncher
                                            .consumeFreshStalledPlaybackAttempt()
                                        val isPlayingTarget = stalled?.let {
                                            com.tunemind.app.service.TuneMindListenerService.instance
                                                ?.isPlayingOrBuffering(it.targetPackage) == true
                                        } ?: false
                                        if (com.tunemind.app.provider.MusicPlayerAutoLauncher.shouldShowStalledRecoveryForAttempt(stalled, isPlayingTarget)) {
                                            stalledRecoveryAttempt = stalled
                                        } else {
                                            stalledRecoveryAttempt = null
                                        }
                                    }
                                }
                                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                                    agentVoiceManager.setForegroundActive(false)
                                    vm.setUiForegroundActive(false)
                                }
                                else -> {}
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            orchestrator.stop()
                            agentVoiceManager.destroy()
                        }
                    }

                    // Live Cross-App / Audio Telemetry
                    val nowPlaying = homeState.nowPlaying
                    val trackTitle = nowPlaying?.title?.takeIf { it.isNotBlank() }
                    val artistName = nowPlaying?.artist?.takeIf { it.isNotBlank() }
                    val isMusicPlaying = nowPlaying?.isPlaying == true
                    val energy = if (isMusicPlaying) 0.85f else 0.55f

                    val combinedRecentTracks = remember(historyState.tracks, homeState.recentTracks) {
                        historyState.tracks.ifEmpty { homeState.recentTracks }
                    }
                    val recentlyGraduatedVault by vm.recentlyGraduatedVaultTracks.collectAsState()

                    LaunchedEffect(isMainAppActive) {
                        while (isMainAppActive) {
                            kotlinx.coroutines.delay(60_000L)
                            vm.refreshVaultLifecycleNow(syncWithBackend = false)
                        }
                    }

                    val dopamineCapsule by vm.upNextQueueState.collectAsState()
                    LaunchedEffect(combinedRecentTracks, vaultState, nowPlaying?.title) {
                        vm.compileDopamineCapsule()
                    }

                    TuneMindTheme(adaptiveState = adaptiveState) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(adaptiveState.primaryBackground)
                        ) {
                            if (!isMainAppActive) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    OnboardingScreen(
                                        onComplete = {
                                            hasCompletedLaunchStartFlow = true
                                            authSessionManager.markAccountOnboardingCompleted()
                                            authSessionManager.markStartFlowCompleted()
                                            hasAllPermissions = true
                                        },
                                        onOpenLegal = { tab ->
                                            legalInitialTab = tab
                                            showLegalScreen = true
                                        }
                                    )
                                    if (showLegalScreen) {
                                        androidx.activity.compose.BackHandler {
                                            showLegalScreen = false
                                        }
                                        com.tunemind.app.ui.screens.LegalAboutScreen(
                                            initialTab = legalInitialTab,
                                            onBack = { showLegalScreen = false }
                                        )
                                    }
                                }
                            } else {
                                // 3-Screen Horizontal Pager: 0 = DOPAMINE, 1 = TUNEMIND (Center), 2 = RHYTHM
                                val sharedYouTubeWebView = com.tunemind.app.ui.components.rememberSharedYouTubeWebView(context)
                                val ytHasActiveVideo by com.tunemind.app.provider.TuneMindYouTubeEmbedController.hasActiveVideoFlow.collectAsState()
                                val ytPlaying by com.tunemind.app.provider.TuneMindYouTubeEmbedController.isPlayingFlow.collectAsState()
                                val ytTitle by com.tunemind.app.provider.TuneMindYouTubeEmbedController.currentTitleFlow.collectAsState()
                                val ytArtist by com.tunemind.app.provider.TuneMindYouTubeEmbedController.currentArtistFlow.collectAsState()

                                val pagerState = rememberPagerState(
                                    initialPage = 1,
                                    pageCount = { 3 }
                                )

                                LaunchedEffect(pagerState.currentPage) {
                                    currentPagerPage = pagerState.currentPage
                                }

                                LaunchedEffect(requestedScreenPage) {
                                    val target = requestedScreenPage
                                    if (target != null && target in 0..2) {
                                        pagerState.animateScrollToPage(target)
                                        requestedScreenPage = null
                                    }
                                }

                                fun playAnyTrack(
                                    title: String,
                                    artist: String,
                                    spotifyUri: String?,
                                    sourcePkg: String
                                ) {
                                    val targetPkg = selectedTargetPlayer.ifBlank { sourcePkg }
                                    val canonical = com.tunemind.app.domain.model.TrackIdentityResolver.resolveCanonicalIdentity(
                                        spotifyUri = spotifyUri,
                                        title = title,
                                        artist = artist
                                    )
                                    vm.markTrackPlayedFromQueueOrExternal(title, artist, canonical.spotifyUri)
                                    vm.setAgentActiveTrack(
                                        title = title,
                                        artist = artist,
                                        sourcePackage = targetPkg,
                                        spotifyUri = canonical.spotifyUri,
                                        trackId = canonical.trackId
                                    )
                                    agentVoiceManager.playTrackImmediatelyWithVoice(
                                        title = title,
                                        artist = artist,
                                        spotifyUri = canonical.spotifyUri,
                                        targetPlayer = targetPkg
                                    )
                                }
                                playAnyTrackCallback = ::playAnyTrack

                                val hasOverlayOpen = showLegalScreen ||
                                    showSupportScreen ||
                                    showNotificationsScreen ||
                                    showSettingsScreen ||
                                    showProfileScreen ||
                                    showWeeklyReportScreen ||
                                    showHearingScreen ||
                                    showMusicProfileScreen ||
                                    showStatsScreen ||
                                    showSearchScreen ||
                                    showNowPlayingScreen ||
                                    showPlaylistsScreen ||
                                    showAgentGuideSheet

                                androidx.activity.compose.BackHandler(enabled = hasOverlayOpen) {
                                    when {
                                        showAgentGuideSheet -> showAgentGuideSheet = false
                                        showLegalScreen -> showLegalScreen = false
                                        showSupportScreen -> showSupportScreen = false
                                        showNotificationsScreen -> showNotificationsScreen = false
                                        showSettingsScreen -> showSettingsScreen = false
                                        showMusicProfileScreen -> showMusicProfileScreen = false
                                        showWeeklyReportScreen -> showWeeklyReportScreen = false
                                        showHearingScreen -> showHearingScreen = false
                                        showStatsScreen -> showStatsScreen = false
                                        showPlaylistsScreen -> showPlaylistsScreen = false
                                        showNowPlayingScreen -> showNowPlayingScreen = false
                                        showSearchScreen -> showSearchScreen = false
                                        showProfileScreen -> showProfileScreen = false
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(adaptiveState.primaryBackground)
                                ) {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    when (page) {
                                        0 -> {
                                            DopamineVaultScreen(
                                                recentTracks = combinedRecentTracks,
                                                vaultTracks = vaultState,
                                                nowPlayingTitle = nowPlaying?.title,
                                                onPlayTrack = ::playAnyTrack,
                                                onAskAgentAboutTrack = { title, artist, freshness, spotifyUri ->
                                                    coroutineScope.launch {
                                                        pagerState.animateScrollToPage(1)
                                                        agentVoiceManager.askAgentAboutTrack(
                                                            title = title,
                                                            artist = artist,
                                                            freshnessPercent = freshness,
                                                            spotifyUri = spotifyUri,
                                                            targetPlayer = selectedTargetPlayer
                                                        )
                                                    }
                                                },
                                                onSyncAtmosphereToTrack = { title, artist ->
                                                    vm.syncAtmosphereToTrack(title, artist)
                                                },
                                                onQuarantineTrack = { title, artist ->
                                                    vm.quarantineTrack(title, artist)
                                                },
                                                onRestoreVaultTrack = { vaultId ->
                                                    vm.restoreFromVault(vaultId)
                                                },
                                                onCycleAtmospherePalette = {
                                                    vm.cycleAtmospherePalette()
                                                },
                                                forYouPicks = aiDiscoveryCrate,
                                                learningState = forYouLearningState,
                                                isDiscovering = isDiscovering,
                                                resolvePlayCount = { title, artist, uri ->
                                                    vm.resolvePlayCount(title, artist, uri)
                                                },
                                                isTrackCurrentlyPlaying = { title, artist, uri ->
                                                    vm.isTrackCurrentlyPlaying(title, artist, uri)
                                                },
                                                onRefreshForYou = {
                                                    vm.triggerChronoDiscovery(forceRefresh = true)
                                                },
                                                onSaveForYou = { rec, saved ->
                                                    vm.toggleSaveRecommendation(rec.id, saved)
                                                },
                                                onDismissForYou = { rec ->
                                                    vm.dismissAiRecommendation(rec.id)
                                                },
                                                onPlayForYouTrack = { rec ->
                                                    vm.markRecommendationPlayed(rec.id)
                                                    val validSpUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(rec.spotifyUri)
                                                    playAnyTrack(rec.title, rec.artist, validSpUri, selectedTargetPlayer)
                                                },
                                                onPreviewForYouTrack = { rec ->
                                                    val validSpUri = com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(rec.spotifyUri)
                                                    if (com.tunemind.app.provider.SpotifyPremiumConnectManager.isConnectedAndPremium(context)) {
                                                        vm.markRecommendationPlayed(rec.id)
                                                        playAnyTrack(rec.title, rec.artist, validSpUri, selectedTargetPlayer)
                                                    } else {
                                                        val previewed = vm.previewAiRecommendation(context, rec)
                                                        if (!previewed) {
                                                            vm.markRecommendationPlayed(rec.id)
                                                            playAnyTrack(rec.title, rec.artist, validSpUri, selectedTargetPlayer)
                                                        }
                                                    }
                                                },
                                                onAddToQueue = { title, artist, uri ->
                                                    vm.enqueueUpNextTrack(title, artist, uri, reason = "Added to Up Next")
                                                },
                                                onSaveToLiked = { title, artist, _ ->
                                                    vm.saveTrackToCrossAppLiked(title, artist)
                                                },
                                                onExportForYouPlaylist = { recs ->
                                                    val triples = recs.map {
                                                        Triple(
                                                            it.title,
                                                            it.artist,
                                                            com.tunemind.app.domain.model.TrackIdentityResolver.normalizeSpotifyUri(it.spotifyUri)
                                                        )
                                                    }
                                                    vm.exportForYouDropAsPlaylist("TuneMind Daily Drop", triples)
                                                },
                                                requestedSubTab = requestedDopamineSubTab,
                                                onConsumeRequestedSubTab = { requestedDopamineSubTab = null },
                                                graduatedTracks = recentlyGraduatedVault,
                                                onTickVaultLifecycle = { vm.refreshVaultLifecycleNow(syncWithBackend = false) },
                                                resolveTrackVaultStatus = { t, a, uri, id ->
                                                    vm.resolveTrackVaultStatus(t, a, uri, id)
                                                },
                                                crossAppLibraryTracks = crossAppLibraryTracks,
                                                tuneMindPlaylists = tuneMindPlaylists,
                                                savedRecommendations = savedAiRecommendations,
                                                restoredAtMap = vm.getRestoredVaultTimestampsSnapshot(),
                                                manualFreshTracks = manualFreshTracks,
                                                freshExcludedKeys = freshExcludedKeys,
                                                onAddToFresh = { title, artist, pkg, uri, tid, art ->
                                                    vm.addToFresh(title, artist, pkg, uri, tid, art)
                                                },
                                                onRemoveFromFresh = { title, artist, uri, tid ->
                                                    vm.removeFromFresh(title, artist, uri, tid)
                                                }
                                            )
                                        }
                                        1 -> {
                                            PulseAgentScreen(
                                                agentState = agentState,
                                                satietyPercentage = agentSatiety,
                                                currentTrackName = trackTitle,
                                                artistName = artistName,
                                                isMusicPlaying = isMusicPlaying,
                                                energy = energy,
                                                liveTranscript = liveTranscript,
                                                flowAnalysis = flowAnalysis,
                                                recentTracks = combinedRecentTracks,
                                                dopamineCapsule = dopamineCapsule,
                                                pendingProposedTrack = pendingProposedTrack,
                                                onApproveProposedTrack = {
                                                    agentVoiceManager.approvePendingProposedTrack()
                                                },
                                                onRejectProposedTrack = {
                                                    agentVoiceManager.rejectPendingProposedTrack()
                                                },
                                                onOrbTapped = {
                                                    agentVoiceManager.simulateWakeWordDetected()
                                                },
                                                onLaunchDopamineCapsule = {
                                                    agentVoiceManager.launchDopamineCapsuleBriefing(
                                                        capsule = dopamineCapsule,
                                                        targetPlayer = selectedTargetPlayer
                                                    )
                                                },
                                                onCycleAtmospherePalette = {
                                                    vm.cycleAtmospherePalette()
                                                },
                                                onOpenProfile = {
                                                    showProfileScreen = true
                                                },
                                                onOpenStats = {
                                                    showStatsScreen = true
                                                },
                                                onOpenSearch = {
                                                    showSearchScreen = true
                                                },
                                                onOpenNowPlaying = {
                                                    showNowPlayingScreen = true
                                                },
                                                onOpenAgentGuide = {
                                                    showAgentGuideSheet = true
                                                },
                                                profileInitial = if (authSession.isGoogleConnected || authSession.email.isNotBlank()) {
                                                    authSession.displayName
                                                } else {
                                                    ""
                                                },
                                                profileAvatarUrl = authSession.avatarUrl,
                                                currentTrackSpotifyUri = nowPlaying?.spotifyUri,
                                                currentTrackPlayCount = if (trackTitle != null) {
                                                    vm.resolvePlayCount(
                                                        title = trackTitle,
                                                        artist = artistName,
                                                        spotifyUri = nowPlaying?.spotifyUri,
                                                        trackId = nowPlaying?.trackId
                                                    )
                                                } else {
                                                    0
                                                },
                                                autonomousDjEnabled = autonomousDjEnabled,
                                                tuneMindPlaylistCount = tuneMindPlaylists.size,
                                                kidsModeEnabled = kidsModeEnabled,
                                                hasExternalMiniVideoDock = ytHasActiveVideo && !showNowPlayingScreen,
                                                onOpenPlaylists = {
                                                    selectedPlaylistNavName = null
                                                    showPlaylistsScreen = true
                                                }
                                            )
                                        }
                                        2 -> {
                                            RhythmDnaScreen(
                                                recentTracks = combinedRecentTracks,
                                                hearingInsights = hearingInsights,
                                                selectedTargetPlayer = selectedTargetPlayer,
                                                themePreferences = themePreferences,
                                                onUpdateThemePreferences = { newPrefs ->
                                                    vm.updateThemePreferences(newPrefs)
                                                },
                                                onSelectTargetPlayer = { newPkg ->
                                                    selectedTargetPlayer = newPkg
                                                    playerPrefs.edit().putString("target_player_pkg", newPkg).apply()
                                                },
                                                onPlayTrack = ::playAnyTrack,
                                                onAskAgentAboutTrack = { title, artist, freshness, spotifyUri ->
                                                    coroutineScope.launch {
                                                        pagerState.animateScrollToPage(1)
                                                        agentVoiceManager.askAgentAboutTrack(
                                                            title = title,
                                                            artist = artist,
                                                            freshnessPercent = freshness,
                                                            spotifyUri = spotifyUri,
                                                            targetPlayer = selectedTargetPlayer
                                                        )
                                                    }
                                                },
                                                onSyncAtmosphereToTrack = { title, artist ->
                                                    vm.syncAtmosphereToTrack(title, artist)
                                                },
                                                onQuarantineTrack = { title, artist ->
                                                    vm.quarantineTrack(title, artist)
                                                },
                                                listeningStats = listeningStats,
                                                cleanPlayHistory = cleanPlayHistory,
                                                crossAppLibraryTracks = crossAppLibraryTracks,
                                                detectedPlayerTabs = detectedPlayerTabs,
                                                tuneMindPlaylists = tuneMindPlaylists,
                                                resolvePlayCount = { title, artist, uri ->
                                                    vm.resolvePlayCount(title, artist, uri)
                                                },
                                                isTrackCurrentlyPlaying = { title, artist, uri ->
                                                    vm.isTrackCurrentlyPlaying(title, artist, uri)
                                                },
                                                onToggleLibraryLike = { canonicalTrackKey, liked ->
                                                    vm.toggleLibraryTrackLike(canonicalTrackKey, liked)
                                                },
                                                onOpenPlaylistsManager = {
                                                    selectedPlaylistNavName = null
                                                    showPlaylistsScreen = true
                                                },
                                                onOpenPlaylistByName = { playlistName ->
                                                    selectedPlaylistNavName = playlistName
                                                    showPlaylistsScreen = true
                                                },
                                                onOpenWeeklyReport = { showWeeklyReportScreen = true },
                                                onOpenHearingHealth = { showHearingScreen = true },
                                                onOpenMusicProfile = { showMusicProfileScreen = true },
                                                onOpenStats = { showStatsScreen = true },
                                                tasteShiftSummary = tasteShiftSummary,
                                                onRefreshStats = { vm.refreshLiveListeningStatsNow() },
                                                requestedSubDeck = requestedRhythmSubDeck,
                                                onConsumeRequestedSubDeck = { requestedRhythmSubDeck = null },
                                                resolveTrackVaultStatus = { t, a, uri, id ->
                                                    vm.resolveTrackVaultStatus(t, a, uri, id)
                                                },
                                                onToggleTrackVault = { t, a, pkg, uri, id ->
                                                    vm.toggleTrackVaultStatus(t, a, pkg, uri, id)
                                                },
                                                onToggleTrackFresh = { t, a, pkg, uri, id ->
                                                    vm.toggleTrackFreshStatus(t, a, pkg, uri, id)
                                                }
                                            )
                                        }
                                    }
                                }

                                // Centered Floating Glass Pill Dock (Strictly 3 Tabs)
                                FloatingGlassDock(
                                    currentPage = pagerState.currentPage,
                                    agentState = agentState,
                                    freshTrackCount = combinedRecentTracks.count { it.distinctAppearanceCount <= 3 },
                                    onSelectPage = { targetPage ->
                                        coroutineScope.launch {
                                            if (targetPage == 1 && pagerState.currentPage == 1) {
                                                // Tapping AGENT while already on the center screen wakes the Voice Orb
                                                agentVoiceManager.simulateWakeWordDetected()
                                            } else {
                                                pagerState.animateScrollToPage(targetPage)
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                )

                                // Playback Launch / Strict Never-Leave-TuneMind Diagnostic Banner
                                val currentFeedback = playbackLaunchFeedback
                                if (!hasOverlayOpen && currentFeedback != null &&
                                    (currentFeedback is com.tunemind.app.provider.PlaybackLaunchResult.LaunchFailed ||
                                        (currentFeedback is com.tunemind.app.provider.PlaybackLaunchResult.DirectPlaybackLaunched &&
                                            currentFeedback.providerId == "tunemind_inapp"))
                                ) {
                                    val isFailure = currentFeedback is com.tunemind.app.provider.PlaybackLaunchResult.LaunchFailed
                                    LaunchedEffect(currentFeedback) {
                                        kotlinx.coroutines.delay(if (isFailure) 9500L else 4500L)
                                        vm.clearPlaybackLaunchFeedback()
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isFailure) Color(0xFF281218) else Color(0xFF102420),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isFailure) Color(0xFFFF5A79).copy(alpha = 0.7f) else adaptiveState.primaryAccent.copy(alpha = 0.65f)
                                        ),
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .statusBarsPadding()
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                            .fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = currentFeedback.message,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                if (isFailure) {
                                                    val ytmAvailable = remember(context) {
                                                        com.tunemind.app.provider.YouTubeMusicProvider().isInstalled(context)
                                                    }
                                                    val npTrack = homeState.nowPlaying
                                                    if (ytmAvailable &&
                                                        npTrack?.title?.isNotBlank() == true &&
                                                        !selectedTargetPlayer.contains("youtube.music", ignoreCase = true)
                                                    ) {
                                                        Button(
                                                            onClick = {
                                                                vm.clearPlaybackLaunchFeedback()
                                                                agentVoiceManager.playTrackImmediatelyWithVoice(
                                                                    title = npTrack.title.orEmpty(),
                                                                    artist = npTrack.artist.orEmpty(),
                                                                    targetPlayer = com.tunemind.app.provider.YouTubeMusicProvider.YTM_PACKAGE,
                                                                    neverOpenExternalApp = true
                                                                )
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text(
                                                                "Use YouTube In-App",
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color.Black
                                                            )
                                                        }
                                                    }
                                                    Button(
                                                        onClick = {
                                                            vm.clearPlaybackLaunchFeedback()
                                                            showSettingsScreen = true
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = adaptiveState.primaryAccent),
                                                        shape = RoundedCornerShape(8.dp),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            "Open Settings",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.Black
                                                        )
                                                    }
                                                }
                                                TextButton(
                                                    onClick = { vm.clearPlaybackLaunchFeedback() },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text("Dismiss", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                                                }
                                            }
                                        }
                                    }
                                }

                                val activeStalled = stalledRecoveryAttempt
                                if (!hasOverlayOpen && currentFeedback == null && activeStalled != null) {
                                    LaunchedEffect(activeStalled) {
                                        kotlinx.coroutines.delay(14_000L)
                                        if (stalledRecoveryAttempt === activeStalled) {
                                            stalledRecoveryAttempt = null
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color(0xFF20180E),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            Color(0xFFF59E0B).copy(alpha = 0.72f)
                                        ),
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .statusBarsPadding()
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                            .fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "${activeStalled.playerDisplayName} stayed paused or on Search for '${activeStalled.title}'. Play right inside TuneMind instead?",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = {
                                                        val recovered = activeStalled
                                                        stalledRecoveryAttempt = null
                                                        val recoverPkg = if (com.tunemind.app.provider.YouTubeMusicProvider().isInstalled(context)) {
                                                            com.tunemind.app.provider.YouTubeMusicProvider.YTM_PACKAGE
                                                        } else {
                                                            com.tunemind.app.provider.TuneMindInAppProvider.PROVIDER_ID
                                                        }
                                                        agentVoiceManager.playTrackImmediatelyWithVoice(
                                                            title = recovered.title,
                                                            artist = recovered.artist,
                                                            targetPlayer = recoverPkg,
                                                            customSpokenLine = "Playing ${recovered.title} right here inside TuneMind.",
                                                            neverOpenExternalApp = true
                                                        )
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        "Play Inside TuneMind",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.Black
                                                    )
                                                }
                                                TextButton(
                                                    onClick = { stalledRecoveryAttempt = null },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text("Dismiss", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                                                }
                                            }
                                        }
                                    }
                                }

                                val currentFeedbackMsg = libraryActionFeedback
                                if (!currentFeedbackMsg.isNullOrBlank()) {
                                    LaunchedEffect(currentFeedbackMsg) {
                                        kotlinx.coroutines.delay(4000L)
                                        vm.clearLibraryActionFeedback()
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color(0xFF0E1822),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            Color(0xFF00E5FF).copy(alpha = 0.55f)
                                        ),
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .statusBarsPadding()
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                            .fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = currentFeedbackMsg,
                                                color = Color(0xFFF8FAFC),
                                                fontSize = 12.5.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                onClick = { vm.clearLibraryActionFeedback() },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("Dismiss", fontSize = 11.5.sp, color = Color(0xFF00E5FF))
                                            }
                                        }
                                    }
                                }

                                // Network Connectivity Offline Banner
                                com.tunemind.app.ui.components.NetworkConnectivityBanner(
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    onRetrySync = {
                                        vm.refreshCrossAppIntelligence()
                                    }
                                )

                                // Fullscreen Search Overlay
                                if (showSearchScreen) {
                                    com.tunemind.app.ui.screens.SearchScreen(
                                        vm = vm,
                                        selectedTargetPlayer = selectedTargetPlayer,
                                        onBack = { showSearchScreen = false },
                                        onPlayTrack = ::playAnyTrack,
                                        onAskAgentAboutTrack = { title, artist, freshness, spotifyUri ->
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(1)
                                                agentVoiceManager.askAgentAboutTrack(
                                                    title = title,
                                                    artist = artist,
                                                    freshnessPercent = freshness,
                                                    spotifyUri = spotifyUri,
                                                    targetPlayer = selectedTargetPlayer
                                                )
                                            }
                                        }
                                    )
                                }

                                // Expanded Now Playing & Up Next Queue Screen
                                if (showNowPlayingScreen) {
                                    com.tunemind.app.ui.screens.NowPlayingSheet(
                                        vm = vm,
                                        selectedTargetPlayer = selectedTargetPlayer,
                                        dopamineCapsule = dopamineCapsule,
                                        pendingProposedTrack = pendingProposedTrack,
                                        onBack = { showNowPlayingScreen = false },
                                        onApproveProposedTrack = {
                                            agentVoiceManager.approvePendingProposedTrack()
                                        },
                                        onRejectProposedTrack = {
                                            agentVoiceManager.rejectPendingProposedTrack()
                                        },
                                        onPlayTrack = ::playAnyTrack,
                                        onAskAgentAboutCurrent = { title, artist ->
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(1)
                                                agentVoiceManager.askAgentAboutTrack(
                                                    title = title,
                                                    artist = artist,
                                                    freshnessPercent = 80,
                                                    spotifyUri = nowPlaying?.spotifyUri,
                                                    targetPlayer = selectedTargetPlayer,
                                                    trackId = nowPlaying?.trackId?.takeIf { it.isNotBlank() }
                                                )
                                            }
                                        },
                                        youTubeWebView = sharedYouTubeWebView,
                                        onPlayInAppYouTube = { ytPlayTitle, ytPlayArtist ->
                                            agentVoiceManager.playTrackImmediatelyWithVoice(
                                                title = ytPlayTitle,
                                                artist = ytPlayArtist,
                                                targetPlayer = com.tunemind.app.provider.YouTubeMusicProvider.YTM_PACKAGE,
                                                neverOpenExternalApp = true
                                            )
                                        }
                                    )
                                }

                                // Fullscreen TuneMind-Owned Playlists Overlay
                                if (showPlaylistsScreen) {
                                    com.tunemind.app.ui.screens.TuneMindPlaylistsScreen(
                                        vm = vm,
                                        selectedTargetPlayer = selectedTargetPlayer,
                                        initialSelectedPlaylistName = selectedPlaylistNavName,
                                        onBack = {
                                            showPlaylistsScreen = false
                                            selectedPlaylistNavName = null
                                        },
                                        onPlayTrack = ::playAnyTrack,
                                        onAskAgentForPlaylist = { prompt ->
                                            showPlaylistsScreen = false
                                            selectedPlaylistNavName = null
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(1)
                                                if (prompt.isBlank()) {
                                                    agentVoiceManager.simulateWakeWordDetected()
                                                } else {
                                                    agentVoiceManager.processFinalTranscriptWithFallback(prompt)
                                                }
                                            }
                                        }
                                    )
                                }

                                // Fullscreen Cross-App Listening Stats Overlay
                                if (showStatsScreen) {
                                    com.tunemind.app.ui.screens.StatsScreen(
                                        vm = vm,
                                        onBack = { showStatsScreen = false },
                                        onPlayTrack = ::playAnyTrack,
                                        onOpenWeeklyReport = { showWeeklyReportScreen = true },
                                        onOpenHearingHealth = { showHearingScreen = true },
                                        onOpenMusicProfile = { showMusicProfileScreen = true }
                                    )
                                }

                                // Fullscreen Profile Screen (Credentials, Switch Accounts, Privacy & Relocated Settings)
                                if (showProfileScreen) {
                                    com.tunemind.app.ui.screens.ProfileScreen(
                                        vm = vm,
                                        onBack = { showProfileScreen = false },
                                        onOpenSettings = { showSettingsScreen = true },
                                        onOpenTasteProfile = { showMusicProfileScreen = true },
                                        onOpenWeeklyReport = { showWeeklyReportScreen = true },
                                        onOpenNotifications = { showNotificationsScreen = true },
                                        onOpenSupport = { showSupportScreen = true },
                                        onOpenLegal = { tab ->
                                            legalInitialTab = tab
                                            showLegalScreen = true
                                        },
                                        onSignedOutToAuth = {
                                            showSettingsScreen = false
                                            showProfileScreen = false
                                            hasCompletedLaunchStartFlow = false
                                        }
                                    )
                                }

                                // Hub Sub-Screens (Reachable from Profile, Stats & Rhythm Overview)
                                if (showWeeklyReportScreen) {
                                    com.tunemind.app.ui.screens.WeeklyReportScreen(
                                        vm = vm,
                                        onBack = { showWeeklyReportScreen = false },
                                        onPlayTrack = ::playAnyTrack
                                    )
                                }

                                if (showHearingScreen) {
                                    com.tunemind.app.ui.screens.HearingInsightsScreen(
                                        vm = vm,
                                        onBack = { showHearingScreen = false }
                                    )
                                }

                                if (showMusicProfileScreen) {
                                    com.tunemind.app.ui.screens.MusicProfileScreen(
                                        vm = vm,
                                        onBack = { showMusicProfileScreen = false },
                                        onOpenSettings = { showSettingsScreen = true }
                                    )
                                }

                                // Notifications & Activity Center
                                if (showNotificationsScreen) {
                                    com.tunemind.app.ui.screens.NotificationsScreen(
                                        vm = vm,
                                        onBack = { showNotificationsScreen = false },
                                        onOpenVault = {
                                            showProfileScreen = false
                                            requestedScreenPage = 0
                                        },
                                        onOpenHearing = { showHearingScreen = true },
                                        onOpenWeeklyReport = { showWeeklyReportScreen = true },
                                        onOpenSettings = { showSettingsScreen = true }
                                    )
                                }

                                // Fullscreen TuneMind Settings Overlay (Opened from Profile Screen)
                                if (showSettingsScreen) {
                                    com.tunemind.app.ui.screens.SettingsScreen(
                                        vm = vm,
                                        onBack = {
                                            openSpotifySetupInSettings = false
                                            showSettingsScreen = false
                                        },
                                        onOpenCrossApp = {
                                            openSpotifySetupInSettings = false
                                            showSettingsScreen = false
                                            showProfileScreen = false
                                            showStatsScreen = true
                                        },
                                        onOpenWeeklyReport = {
                                            openSpotifySetupInSettings = false
                                            showSettingsScreen = false
                                            showWeeklyReportScreen = true
                                        },
                                        onOpenAccountAuth = {
                                            openSpotifySetupInSettings = false
                                            showSettingsScreen = false
                                            showProfileScreen = true
                                        },
                                        onOpenSupport = { showSupportScreen = true },
                                        onOpenLegal = { tab ->
                                            legalInitialTab = tab
                                            showLegalScreen = true
                                        },
                                        openSpotifySetupInitially = openSpotifySetupInSettings,
                                        onSpotifySetupSheetConsumed = { openSpotifySetupInSettings = false }
                                    )
                                }

                                // Help, Troubleshooting & Bug Report Screen
                                if (showSupportScreen) {
                                    com.tunemind.app.ui.screens.SupportFeedbackScreen(
                                        vm = vm,
                                        onBack = { showSupportScreen = false },
                                        onOpenSettings = {
                                            showSupportScreen = false
                                            showSettingsScreen = true
                                        },
                                        onOpenLegal = {
                                            legalInitialTab = 0
                                            showLegalScreen = true
                                        }
                                    )
                                }

                                // Privacy Policy, Terms & About Screen
                                if (showLegalScreen) {
                                    com.tunemind.app.ui.screens.LegalAboutScreen(
                                        initialTab = legalInitialTab,
                                        onBack = { showLegalScreen = false },
                                        onOpenSupport = {
                                            showLegalScreen = false
                                            showSupportScreen = true
                                        }
                                    )
                                }

                                // Visible In-App YouTube Mini Dock (keeps YouTube WebView visible whenever minimized)
                                if (ytHasActiveVideo && !showNowPlayingScreen) {
                                    com.tunemind.app.ui.components.TuneMindYouTubeMiniDock(
                                        webView = sharedYouTubeWebView,
                                        title = ytTitle.orEmpty(),
                                        artist = ytArtist.orEmpty(),
                                        isPlaying = ytPlaying,
                                        accentColor = adaptiveState.primaryAccent,
                                        onTogglePlay = { vm.togglePlayback() },
                                        onExpand = { showNowPlayingScreen = true },
                                        onClose = {
                                            com.tunemind.app.provider.TuneMindYouTubeEmbedController.stop(clearMetadata = true)
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .navigationBarsPadding()
                                            .padding(bottom = if (hasOverlayOpen) 16.dp else 86.dp)
                                    )
                                }

                                // Interactive Voice Commands & Capability Guide Sheet
                                if (showAgentGuideSheet) {
                                    com.tunemind.app.ui.components.AgentGuideSheet(
                                        onDismiss = { showAgentGuideSheet = false },
                                        onRunPrompt = { prompt ->
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(1)
                                                agentVoiceManager.processFinalTranscriptWithFallback(prompt)
                                            }
                                        }
                                    )
                                }
                            }
                            }
                        }

                        if (showBrandSplash) {
                            com.tunemind.app.ui.components.TuneMindSplashOverlay(
                                onFinished = { showBrandSplash = false }
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Fatal content initialization error: ${t.message}", t)
            setContent {
                CrashDiagnosticsScreen(
                    error = t,
                    onReset = {
                        try {
                            com.tunemind.app.data.local.TuneMindDatabase.resetDatabase(applicationContext)
                        } catch (_: Exception) {}
                        recreate()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureRecoveryIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        com.tunemind.app.provider.MusicPlayerAutoLauncher.cancelReturnNotification(this)
        forceRebindListener(this)
    }

    companion object {
        fun forceRebindListener(context: Context) {
            try {
                if (!NotificationAccess.isEnabled(context)) return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationListenerService.requestRebind(
                        ComponentName(context, TuneMindListenerService::class.java)
                    )
                }
            } catch (t: Throwable) {
                Log.w("MainActivity", "forceRebindListener notice: ${t.message}")
            }
        }
    }
}

@Composable
private fun CrashDiagnosticsScreen(error: Throwable, onReset: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05060A))
            .padding(24.dp),
        color = Color(0xFF05060A)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "TuneMind Diagnostics",
                color = Color(0xFFFF2A55),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "An unexpected condition occurred during startup:",
                color = Color.White,
                fontSize = 13.sp
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1E1014),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "${error::class.java.simpleName}: ${error.message}\n\n" +
                            error.stackTrace.take(12).joinToString("\n"),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(14.dp)
                )
            }
            Button(
                onClick = onReset,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Restart TuneMind", fontWeight = FontWeight.Bold)
            }
        }
    }
}
