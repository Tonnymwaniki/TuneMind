package com.tunemind.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunemind.app.data.auth.SavedAccountProfile
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.ingestion.SpotifyAuthManager
import com.tunemind.app.domain.voice.AgentSettingsStore
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.SpotifyAvatarCircle
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasteProfile: () -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenNotifications: () -> Unit = {},
    onOpenSupport: () -> Unit = {},
    onOpenLegal: (Int) -> Unit = {},
    onSignedOutToAuth: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val theme = LocalAdaptiveTheme.current

    val authManager = remember(context) { TuneMindAuthSessionManager.getInstance(context) }
    val spotifyAuthManager = remember(context) { SpotifyAuthManager(context) }
    val session by authManager.sessionFlow.collectAsState()
    val savedAccounts by authManager.savedAccountsFlow.collectAsState()

    val stats by vm.listeningStats.collectAsState()
    val userProfile by vm.userProfile.collectAsState()
    val spotifyProfile by vm.spotifyUserProfile.collectAsState()
    val likedTracks by vm.spotifyLikedTracks.collectAsState()
    val playlists by vm.spotifyPlaylists.collectAsState()
    val isLibrarySyncing by vm.isLibrarySyncing.collectAsState()

    val privateSession by AgentSettingsStore.privateSessionEnabled.collectAsState()
    val mergeDuplicatePlays by AgentSettingsStore.mergeDuplicatePlaysEnabled.collectAsState()

    var hasMicPermission by remember { mutableStateOf(PermissionUtils.hasMicrophonePermission(context)) }
    var hasNotificationAccess by remember { mutableStateOf(PermissionUtils.hasNotificationListenerPermission(context)) }
    var hasOverlayPermission by remember { mutableStateOf(PermissionUtils.hasOverlayPermission(context)) }
    var isBatteryIgnored by remember { mutableStateOf(PermissionUtils.isIgnoringBatteryOptimizations(context)) }

    var showEditProfileSheet by remember { mutableStateOf(false) }
    var showCredentialsSheet by remember { mutableStateOf(false) }
    var showSwitchAccountsSheet by remember { mutableStateOf(false) }
    var showLogoutSheet by remember { mutableStateOf(false) }
    var showClearHistorySheet by remember { mutableStateOf(false) }
    var showDeleteAccountSheet by remember { mutableStateOf(false) }
    var showDisconnectSpotifySheet by remember { mutableStateOf(false) }

    var statusBanner by remember { mutableStateOf<String?>(null) }

    val hasExplicitAccount = session.primaryProvider == "GOOGLE" ||
        session.primaryProvider == "EMAIL" ||
        session.isGoogleConnected ||
        session.email.isNotBlank()

    val effectiveDisplayName = when {
        session.displayName.isNotBlank() && session.displayName != "TuneMind Listener" -> session.displayName
        userProfile.name.isNotBlank() -> userProfile.name
        session.email.isNotBlank() -> session.email.substringBefore("@")
        else -> "Guest Listener"
    }
    val effectiveEmail = session.email.ifBlank {
        if (hasExplicitAccount) userProfile.email else ""
    }
    val effectiveArchetype = session.tasteArchetype.ifBlank { userProfile.archetype }

    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFFFB000)
    val dangerRed = Color(0xFFFF4D6D)
    val neonCyan = Color(0xFF00F0FF)
    val electricPurple = Color(0xFF8A2BE2)

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFF8FAFC),
        unfocusedTextColor = Color(0xFFF8FAFC),
        focusedContainerColor = Color(0xFF0D0D0D).copy(alpha = 0.85f),
        unfocusedContainerColor = Color(0xFF0D0D0D).copy(alpha = 0.60f),
        focusedBorderColor = neonCyan,
        unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
        focusedLabelColor = neonCyan,
        unfocusedLabelColor = Color.White.copy(alpha = 0.56f),
        cursorColor = neonCyan
    )

    val refreshPermissions: () -> Unit = remember(context) {
        {
            hasMicPermission = PermissionUtils.hasMicrophonePermission(context)
            hasNotificationAccess = PermissionUtils.hasNotificationListenerPermission(context)
            hasOverlayPermission = PermissionUtils.hasOverlayPermission(context)
            isBatteryIgnored = PermissionUtils.isIgnoringBatteryOptimizations(context)
            authManager.refreshSessionState()
        }
    }

    LaunchedEffect(Unit) {
        AgentSettingsStore.ensureLoaded(context)
        refreshPermissions()
        if (session.isSupabaseAuthenticated) {
            authManager.syncProfileFromSupabase()
        }
    }

    LaunchedEffect(session.displayName, session.email, session.tasteArchetype) {
        if (hasExplicitAccount) {
            vm.syncIdentityFromAuth(
                name = session.displayName,
                email = session.email,
                archetype = session.tasteArchetype.ifBlank { null }
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
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
                    Text(
                        text = "Profile",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                }

                TextButton(
                    onClick = { showSwitchAccountsSheet = true },
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SwapHoriz,
                        contentDescription = null,
                        tint = neonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Accounts",
                        color = neonCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!statusBanner.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = neonCyan.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusBanner!!,
                            color = Color(0xFFF8FAFC),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { statusBanner = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Account & Credentials Hero Card
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = theme.primaryAccent
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpotifyAvatarCircle(
                            imageUrl = session.avatarUrl.ifBlank { spotifyProfile?.avatarUrl.orEmpty() },
                            fallbackInitial = effectiveDisplayName.ifEmpty { "T" },
                            size = 62.dp,
                            accentColor = neonCyan,
                            borderWidth = 2.dp,
                            borderColor = Color.White.copy(alpha = 0.25f)
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = effectiveDisplayName,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF8FAFC),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { showEditProfileSheet = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "Edit Profile",
                                        tint = neonCyan,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }

                            Text(
                                text = if (effectiveEmail.isNotBlank()) {
                                    effectiveEmail
                                } else {
                                    "Guest session · Sign in with Google or Email"
                                },
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            val providerText = when {
                                session.primaryProvider == "GOOGLE" || session.isGoogleConnected -> "Google Account"
                                session.primaryProvider == "EMAIL" -> "Email Account"
                                else -> "Guest Mode"
                            }
                            val syncText = when {
                                session.isSupabaseAuthenticated -> "Cloud Synced"
                                hasExplicitAccount -> "Local Encrypted Vault"
                                else -> "Device Only"
                            }
                            Text(
                                text = "$providerText · $syncText",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (session.isSupabaseAuthenticated) safeGreen else theme.primaryAccent
                            )
                            val spProfile = spotifyProfile
                            if (spProfile != null && spProfile.displayName.isNotBlank()) {
                                val spPlan = listOfNotNull(
                                    spProfile.product.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
                                    spProfile.country.takeIf { it.isNotBlank() }
                                ).joinToString(" · ")
                                Text(
                                    text = if (spPlan.isNotBlank()) "Spotify: ${spProfile.displayName} ($spPlan)" else "Spotify: ${spProfile.displayName}",
                                    fontSize = 11.5.sp,
                                    color = safeGreen.copy(alpha = 0.9f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    StudioHairlineDivider()

                    val monitoredHours = formatListeningDuration(stats.allTimeListeningMs)
                    val matchPct = (74 + minOf(24, stats.uniqueTrackCount)).coerceAtMost(98)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ProfileMetricColumn("Listened", monitoredHours, neonCyan)
                        ProfileMetricColumn("Tracks", "${stats.uniqueTrackCount}", electricPurple.copy(alpha = 0.95f))
                        ProfileMetricColumn("Taste Match", "$matchPct%", safeGreen)
                    }

                    if (!hasExplicitAccount) {
                        Button(
                            onClick = {
                                authManager.reopenAccountOnboarding()
                                onSignedOutToAuth()
                            },
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = neonCyan,
                                contentColor = Color(0xFF0D0D0D)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                text = "Sign In or Create Account",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showEditProfileSheet = true },
                                shape = RoundedCornerShape(28.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = null,
                                    tint = Color(0xFFF8FAFC),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Edit Profile", color = Color(0xFFF8FAFC), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = { showCredentialsSheet = true },
                                shape = RoundedCornerShape(28.dp),
                                border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.48f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VpnKey,
                                    contentDescription = null,
                                    tint = neonCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Credentials", color = neonCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Relocated App Settings & Taste Profile Card
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StudioSectionHeader(title = "Settings & Taste")
                    Spacer(modifier = Modifier.height(6.dp))
                    StudioHairlineDivider()

                    ProfileNavRow(
                        icon = Icons.Rounded.Settings,
                        iconTint = neonCyan,
                        title = "App Settings",
                        subtitle = "Playback players, Spotify Remote, voice companion & theme",
                        onClick = onOpenSettings
                    )
                    StudioHairlineDivider()

                    ProfileNavRow(
                        icon = Icons.Rounded.GraphicEq,
                        iconTint = Color(0xFFA855F7),
                        title = "Taste Profile & Genres",
                        subtitle = "$effectiveArchetype · Tap to edit favorite genres & artists",
                        onClick = onOpenTasteProfile
                    )
                    StudioHairlineDivider()

                    ProfileNavRow(
                        icon = Icons.Rounded.AutoGraph,
                        iconTint = safeGreen,
                        title = "Weekly Listening Recap",
                        subtitle = "Your top shifts, discoveries & listening balance",
                        onClick = onOpenWeeklyReport
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Connected Music Data Source (Spotify is strictly for pulling user data)
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = if (session.isSpotifyConnected) Color(0xFF1DB954) else theme.primaryAccent
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudioSectionHeader(
                        title = "Music Data Connection",
                        actionLabel = if (session.isSpotifyConnected) "Connected" else "Not Linked",
                        onActionClick = {
                            if (session.isSpotifyConnected) {
                                vm.refreshSpotifyLibrary(forcePlaylistTracks = true)
                                statusBanner = "Refreshing your Spotify library..."
                            } else {
                                spotifyAuthManager.launchSpotifyAuth(context)
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1DB954).copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LibraryMusic,
                                contentDescription = null,
                                tint = Color(0xFF1DB954),
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Spotify Music Sync",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = if (session.isSpotifyConnected) {
                                    "${likedTracks.size} liked tracks · ${playlists.size} playlists synced for data analysis"
                                } else {
                                    "Link Spotify to pull your liked tracks, playlists & recent listening history"
                                },
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.62f),
                                lineHeight = 17.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (session.isSpotifyConnected) {
                                    vm.refreshSpotifyLibrary(forcePlaylistTracks = true)
                                    statusBanner = "Syncing latest tracks from Spotify..."
                                } else {
                                    spotifyAuthManager.launchSpotifyAuth(context)
                                }
                            },
                            enabled = !isLibrarySyncing,
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1DB954),
                                contentColor = Color(0xFF0D0D0D)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Text(
                                text = when {
                                    isLibrarySyncing -> "Syncing…"
                                    session.isSpotifyConnected -> "Sync Music Data"
                                    else -> "Connect Spotify"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (session.isSpotifyConnected) {
                            OutlinedButton(
                                onClick = { showDisconnectSpotifySheet = true },
                                shape = RoundedCornerShape(28.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                            ) {
                                Text(
                                    text = "Disconnect Data",
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Privacy & Permissions Health Card
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StudioSectionHeader(title = "Privacy & Permissions")
                    Spacer(modifier = Modifier.height(6.dp))
                    StudioHairlineDivider()

                    ProfileSwitchRow(
                        title = "Private Session",
                        description = "Pauses listening tracking, history logging & cloud sync while active.",
                        checked = privateSession,
                        accent = neonCyan,
                        onCheckedChange = { enabled ->
                            AgentSettingsStore.setPrivateSessionEnabled(context, enabled)
                            scope.launch {
                                authManager.updateProfileAndPreferences(
                                    displayName = effectiveDisplayName,
                                    tasteArchetype = effectiveArchetype,
                                    privateSessionEnabled = enabled,
                                    mergeDuplicatePlays = mergeDuplicatePlays
                                )
                            }
                        }
                    )
                    StudioHairlineDivider()

                    ProfileSwitchRow(
                        title = "Merge Duplicate Plays",
                        description = "Combines matching tracks played across Spotify and local players.",
                        checked = mergeDuplicatePlays,
                        accent = neonCyan,
                        onCheckedChange = { enabled ->
                            AgentSettingsStore.setMergeDuplicatePlaysEnabled(context, enabled)
                            scope.launch {
                                authManager.updateProfileAndPreferences(
                                    displayName = effectiveDisplayName,
                                    tasteArchetype = effectiveArchetype,
                                    privateSessionEnabled = privateSession,
                                    mergeDuplicatePlays = enabled
                                )
                            }
                        }
                    )
                    StudioHairlineDivider()

                    ProfilePermissionRow(
                        title = "Microphone Access",
                        subtitle = if (hasMicPermission) "Ready for \"Hey TuneMind\" voice commands" else "Tap to allow voice interactions",
                        granted = hasMicPermission,
                        safeGreen = safeGreen,
                        warnAmber = warnAmber,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                )
                            }
                        }
                    )
                    StudioHairlineDivider()

                    ProfilePermissionRow(
                        title = "Music Playback Detection",
                        subtitle = if (hasNotificationAccess) "Detects active tracks across music players" else "Tap to grant Notification Listener access",
                        granted = hasNotificationAccess,
                        safeGreen = safeGreen,
                        warnAmber = warnAmber,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    PermissionUtils.getNotificationListenerIntent().apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                    )
                    StudioHairlineDivider()

                    ProfilePermissionRow(
                        title = "Background Sync & Return",
                        subtitle = if (isBatteryIgnored && hasOverlayPermission) {
                            "Unrestricted background sync & auto-return active"
                        } else {
                            "Tap to review background battery & overlay permissions"
                        },
                        granted = isBatteryIgnored && hasOverlayPermission,
                        safeGreen = safeGreen,
                        warnAmber = warnAmber,
                        onClick = {
                            if (!isBatteryIgnored) {
                                vm.requestIgnoreBatteryOptimization(context)
                            } else {
                                runCatching {
                                    context.startActivity(PermissionUtils.getOverlayPermissionIntent(context))
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4b. Notifications, Support & Legal
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StudioSectionHeader(title = "Alerts, Help & Legal")
                    Spacer(modifier = Modifier.height(6.dp))
                    StudioHairlineDivider()

                    ProfileNavRow(
                        icon = Icons.Rounded.NotificationsNone,
                        iconTint = neonCyan,
                        title = "Notifications & Alerts",
                        subtitle = "Vault comeback alerts, hearing warnings & alert categories",
                        onClick = onOpenNotifications
                    )
                    StudioHairlineDivider()

                    ProfileNavRow(
                        icon = Icons.Rounded.HelpOutline,
                        iconTint = warnAmber,
                        title = "Help, Troubleshooting & Feedback",
                        subtitle = "Spotify wake steps, battery tips & bug reports",
                        onClick = onOpenSupport
                    )
                    StudioHairlineDivider()

                    ProfileNavRow(
                        icon = Icons.Rounded.Policy,
                        iconTint = Color(0xFFA855F7),
                        title = "Privacy Policy, Terms & About",
                        subtitle = "Permissions transparency, legal terms & app version",
                        onClick = { onOpenLegal(0) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Account Controls: Switch Accounts, Log Out & Data Management
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StudioSectionHeader(title = "Account & Data Controls")
                    Spacer(modifier = Modifier.height(2.dp))

                    // Switch Account
                    Button(
                        onClick = { showSwitchAccountsSheet = true },
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            contentColor = Color(0xFFF8FAFC)
                        ),
                        border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.42f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Rounded.ManageAccounts, contentDescription = null, tint = neonCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (savedAccounts.size > 1) {
                                "Switch Accounts (${savedAccounts.size} saved)"
                            } else {
                                "Switch or Add Account"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Export My Data (JSON)
                    OutlinedButton(
                        onClick = {
                            val exportJson = vm.buildUserDataExportJson(context)
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_SUBJECT, "TuneMind Data Export")
                                putExtra(Intent.EXTRA_TEXT, exportJson)
                            }
                            val chooser = Intent.createChooser(sendIntent, "Export TuneMind Data").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(chooser) }
                        },
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, tint = Color(0xFFF8FAFC), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Export My Data (JSON)", color = Color(0xFFF8FAFC), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    // Clear Listening History
                    OutlinedButton(
                        onClick = { showClearHistorySheet = true },
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, warnAmber.copy(alpha = 0.42f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = null, tint = warnAmber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Clear Listening History", color = warnAmber, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    // Log Out
                    Button(
                        onClick = { showLogoutSheet = true },
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = dangerRed.copy(alpha = 0.14f),
                            contentColor = dangerRed
                        ),
                        border = BorderStroke(1.dp, dangerRed.copy(alpha = 0.48f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (hasExplicitAccount) "Log Out" else "Exit Guest Session",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Delete Account
                    if (hasExplicitAccount) {
                        TextButton(
                            onClick = { showDeleteAccountSheet = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(
                                text = "Delete Account & Wipe Data",
                                color = dangerRed.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    // =========================================================================
    // MODAL BOTTOM SHEETS (EDIT PROFILE, CREDENTIALS, SWITCH ACCOUNTS, LOG OUT)
    // =========================================================================

    if (showEditProfileSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var nameDraft by remember(effectiveDisplayName) { mutableStateOf(effectiveDisplayName) }
        var archetypeDraft by remember(effectiveArchetype) { mutableStateOf(effectiveArchetype) }
        var isSaving by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { showEditProfileSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF140D24),
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
                Text("Edit Profile", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF8FAFC))

                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = archetypeDraft,
                    onValueChange = { archetypeDraft = it },
                    label = { Text("Listening Archetype") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (nameDraft.isNotBlank()) {
                            isSaving = true
                            val cleanName = nameDraft.trim()
                            val cleanArch = archetypeDraft.trim().ifBlank { effectiveArchetype }
                            vm.updateUserProfile(cleanName, cleanArch)
                            scope.launch {
                                authManager.updateProfileAndPreferences(
                                    displayName = cleanName,
                                    tasteArchetype = cleanArch
                                )
                                isSaving = false
                                showEditProfileSheet = false
                                statusBanner = "Profile updated."
                            }
                        }
                    },
                    enabled = !isSaving && nameDraft.isNotBlank(),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = neonCyan, contentColor = Color(0xFF0D0D0D)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(if (isSaving) "Saving…" else "Save Profile", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showCredentialsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var newPassword by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }
        var credMessage by remember { mutableStateOf<String?>(null) }
        var credIsError by remember { mutableStateOf(false) }
        var isSubmittingCred by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { showCredentialsSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF140D24),
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
                Text("Credentials & Security", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF8FAFC))

                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CredentialInfoLine("Email", effectiveEmail.ifBlank { "Not set" })
                        CredentialInfoLine(
                            "Sign-In Method",
                            if (session.primaryProvider == "GOOGLE" || session.isGoogleConnected) "Google" else "Email & Password"
                        )
                        CredentialInfoLine("User ID", session.userId.ifBlank { "local-device" })
                        CredentialInfoLine(
                            "Cloud Backend",
                            when {
                                session.isSupabaseAuthenticated -> "Supabase Authenticated"
                                authManager.isSupabaseConfigured() -> "Supabase Ready (Local Sign-In)"
                                else -> "Encrypted Local Vault"
                            }
                        )
                    }
                }

                if (session.primaryProvider != "GOOGLE") {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            credMessage = null
                        },
                        label = { Text("New Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        shape = RoundedCornerShape(16.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            credMessage = null
                        },
                        label = { Text("Confirm New Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        shape = RoundedCornerShape(16.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!credMessage.isNullOrBlank()) {
                        Text(
                            text = credMessage!!,
                            color = if (credIsError) dangerRed else safeGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            if (newPassword.length < 6) {
                                credIsError = true
                                credMessage = "Password must be at least 6 characters."
                                return@Button
                            }
                            if (newPassword != confirmPassword) {
                                credIsError = true
                                credMessage = "Passwords do not match."
                                return@Button
                            }
                            isSubmittingCred = true
                            scope.launch {
                                val res = authManager.updatePassword(newPassword)
                                isSubmittingCred = false
                                res.onSuccess {
                                    credIsError = false
                                    credMessage = "Password updated on your TuneMind cloud account."
                                    newPassword = ""
                                    confirmPassword = ""
                                }.onFailure { err ->
                                    credIsError = true
                                    credMessage = err.message ?: "Could not update password."
                                }
                            }
                        },
                        enabled = !isSubmittingCred,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = neonCyan, contentColor = Color(0xFF0D0D0D)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(if (isSubmittingCred) "Updating…" else "Update Password", fontWeight = FontWeight.Bold)
                    }

                    if (effectiveEmail.contains("@") && authManager.isSupabaseConfigured()) {
                        TextButton(
                            onClick = {
                                isSubmittingCred = true
                                scope.launch {
                                    val res = authManager.sendPasswordResetEmail(effectiveEmail)
                                    isSubmittingCred = false
                                    res.onSuccess {
                                        credIsError = false
                                        credMessage = "Password reset link sent to $effectiveEmail."
                                    }.onFailure { err ->
                                        credIsError = true
                                        credMessage = err.message ?: "Could not send reset link."
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Send Password Reset Email", color = neonCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Text(
                        text = "Your account uses Google authentication. Manage your password inside your Google Account security settings.",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.65f),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }

    if (showSwitchAccountsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSwitchAccountsSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF140D24),
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
                Text("Switch Accounts", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF8FAFC))
                Text(
                    text = "Switch between your saved Google or Email TuneMind accounts. Your Spotify music data connection stays linked.",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.62f),
                    lineHeight = 18.sp
                )

                if (savedAccounts.isEmpty()) {
                    SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No saved accounts on this device yet. Add a Google or Email account to switch between profiles quickly.",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.72f),
                            lineHeight = 18.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        savedAccounts.forEach { acc ->
                            val isActive = acc.email.equals(session.email, ignoreCase = true) &&
                                acc.provider.equals(session.primaryProvider, ignoreCase = true)
                            SavedAccountRow(
                                account = acc,
                                isActive = isActive,
                                neonCyan = neonCyan,
                                safeGreen = safeGreen,
                                onSelect = {
                                    scope.launch {
                                        authManager.switchToAccount(acc)
                                        vm.syncIdentityFromAuth(acc.displayName, acc.email)
                                        showSwitchAccountsSheet = false
                                        statusBanner = "Switched to ${acc.displayName} (${acc.email})"
                                    }
                                },
                                onRemove = {
                                    val wasOnlyActive = isActive && savedAccounts.size <= 1
                                    authManager.removeSavedAccount(acc.accountKey)
                                    if (wasOnlyActive) {
                                        showSwitchAccountsSheet = false
                                        onSignedOutToAuth()
                                    }
                                }
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        showSwitchAccountsSheet = false
                        authManager.reopenAccountOnboarding()
                        onSignedOutToAuth()
                    },
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = neonCyan, contentColor = Color(0xFF0D0D0D)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add or Sign In to Another Account", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showLogoutSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var keepForQuickSwitch by remember { mutableStateOf(true) }
        var alsoDisconnectSpotify by remember { mutableStateOf(false) }
        var isLoggingOut by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { showLogoutSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF140D24),
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
                    text = if (hasExplicitAccount) "Log Out of TuneMind?" else "Exit Guest Session?",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = "You will return to the sign-in screen so you can log into any Google or Email account.",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    lineHeight = 19.sp
                )

                if (hasExplicitAccount) {
                    ProfileSwitchRow(
                        title = "Keep account for 1-tap switching",
                        description = "Remembers this profile in Switch Accounts for faster sign-in.",
                        checked = keepForQuickSwitch,
                        accent = neonCyan,
                        onCheckedChange = { keepForQuickSwitch = it }
                    )
                }

                if (session.isSpotifyConnected) {
                    ProfileSwitchRow(
                        title = "Also disconnect Spotify music data",
                        description = "Leave off to keep your Spotify library sync ready.",
                        checked = alsoDisconnectSpotify,
                        accent = Color(0xFF1DB954),
                        onCheckedChange = { alsoDisconnectSpotify = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showLogoutSheet = false },
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Text("Cancel", color = Color(0xFFF8FAFC), fontWeight = FontWeight.Medium)
                    }

                    Button(
                        onClick = {
                            isLoggingOut = true
                            scope.launch {
                                authManager.signOutRemoteAndLocal(
                                    removeFromSavedRoster = !keepForQuickSwitch,
                                    disconnectSpotify = alsoDisconnectSpotify
                                )
                                isLoggingOut = false
                                showLogoutSheet = false
                                onSignedOutToAuth()
                            }
                        },
                        enabled = !isLoggingOut,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = dangerRed, contentColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Text(if (isLoggingOut) "Signing Out…" else "Log Out", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDisconnectSpotifySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showDisconnectSpotifySheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF140D24),
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
                Text("Disconnect Spotify Data?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF8FAFC))
                Text(
                    text = "Stops pulling new liked songs and playlists from Spotify. Your TuneMind account stays signed in.",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    lineHeight = 19.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showDisconnectSpotifySheet = false },
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Cancel", color = Color(0xFFF8FAFC))
                    }
                    Button(
                        onClick = {
                            authManager.disconnectSpotifyData()
                            showDisconnectSpotifySheet = false
                            statusBanner = "Spotify music data disconnected."
                        },
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = dangerRed, contentColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Disconnect", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showClearHistorySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showClearHistorySheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF140D24),
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
                Text("Clear Listening History?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF8FAFC))
                Text(
                    text = "Clears your tracked listening counts, song freshness, and Dopamine Vault cooldowns on this device. Your TuneMind account stays signed in.",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    lineHeight = 19.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showClearHistorySheet = false },
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Cancel", color = Color(0xFFF8FAFC))
                    }
                    Button(
                        onClick = {
                            vm.resetListeningProfile()
                            showClearHistorySheet = false
                            statusBanner = "Listening history cleared."
                        },
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = warnAmber, contentColor = Color(0xFF0D0D0D)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Clear History", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDeleteAccountSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var isDeleting by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { showDeleteAccountSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF140D24),
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
                Text("Delete Account & Wipe Data?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = dangerRed)
                Text(
                    text = "Removes your saved profile, clears all local listening telemetry and vault cooldowns, disconnects Spotify data, and signs you out.",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    lineHeight = 19.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showDeleteAccountSheet = false },
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Text("Cancel", color = Color(0xFFF8FAFC))
                    }
                    Button(
                        onClick = {
                            isDeleting = true
                            scope.launch {
                                authManager.deleteProfileAndSignOut {
                                    vm.wipeAllLocalUserData(context)
                                }
                                isDeleting = false
                                showDeleteAccountSheet = false
                                onSignedOutToAuth()
                            }
                        },
                        enabled = !isDeleting,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = dangerRed, contentColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Text(if (isDeleting) "Deleting…" else "Delete & Wipe", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileMetricColumn(
    label: String,
    value: String,
    accent: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent)
        Text(text = label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.56f))
    }
}

@Composable
private fun ProfileNavRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF8FAFC))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.56f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.42f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ProfileSwitchRow(
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

@Composable
private fun ProfilePermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    safeGreen: Color,
    warnAmber: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF8FAFC))
            Text(text = subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.56f), lineHeight = 16.sp)
        }
        Text(
            text = if (granted) "Allowed" else "Fix",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (granted) safeGreen else warnAmber
        )
    }
}

@Composable
private fun CredentialInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.56f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFF8FAFC),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SavedAccountRow(
    account: SavedAccountProfile,
    isActive: Boolean,
    neonCyan: Color,
    safeGreen: Color,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isActive) neonCyan.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f),
        border = BorderStroke(
            1.dp,
            if (isActive) neonCyan.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isActive) neonCyan.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = account.displayName.take(1).uppercase().ifEmpty { "T" },
                    color = if (isActive) neonCyan else Color(0xFFF8FAFC),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = account.displayName,
                    color = Color(0xFFF8FAFC),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${account.email} · ${if (account.provider == "GOOGLE") "Google" else "Email"}",
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isActive) {
                Text(
                    text = "Active",
                    color = safeGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Remove account",
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
