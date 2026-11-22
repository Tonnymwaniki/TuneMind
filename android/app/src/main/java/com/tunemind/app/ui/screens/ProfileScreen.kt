package com.tunemind.app.ui.screens

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.tunemind.app.data.local.ProfileCustomizationStore
import com.tunemind.app.data.local.UserProfileCustomData
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
import com.tunemind.app.domain.voice.AgentSettingsStore
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.components.TuneMindAvatarCircle
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
    onAccountSwitched: () -> Unit = {},
    onSignedOutToAuth: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val theme = LocalAdaptiveTheme.current

    val authManager = remember(context) { TuneMindAuthSessionManager.getInstance(context) }
    val session by authManager.sessionFlow.collectAsState()
    val savedAccounts by authManager.savedAccountsFlow.collectAsState()

    val stats by vm.listeningStats.collectAsState()
    val userProfile by vm.userProfile.collectAsState()
    val libraryTracks by vm.crossAppLibraryTracks.collectAsState()
    val detectedTabs by vm.detectedPlayerTabs.collectAsState()

    val privateSession by AgentSettingsStore.privateSessionEnabled.collectAsState()
    val mergeDuplicatePlays by AgentSettingsStore.mergeDuplicatePlaysEnabled.collectAsState()

    var hasMicPermission by remember { mutableStateOf(PermissionUtils.hasMicrophonePermission(context)) }
    var hasNotificationAccess by remember { mutableStateOf(PermissionUtils.hasNotificationListenerPermission(context)) }
    var isBatteryIgnored by remember { mutableStateOf(PermissionUtils.isIgnoringBatteryOptimizations(context)) }

    var showEditProfileScreen by remember { mutableStateOf(false) }
    var customProfileData by remember(session.userId) {
        mutableStateOf(ProfileCustomizationStore.load(context))
    }
    val effectiveAvatarSource = customProfileData.avatarFilePath.ifBlank { session.avatarUrl }
    var showSwitchAccountsSheet by remember { mutableStateOf(false) }
    var showLogoutSheet by remember { mutableStateOf(false) }
    var showClearHistorySheet by remember { mutableStateOf(false) }
    var showDeleteAccountSheet by remember { mutableStateOf(false) }

    var statusBanner by remember { mutableStateOf<String?>(null) }

    val hasExplicitAccount = session.primaryProvider == "GOOGLE" ||
        session.primaryProvider == "EMAIL" ||
        session.isGoogleConnected ||
        session.email.isNotBlank()

    val effectiveDisplayName = when {
        session.displayName.isNotBlank() && session.displayName != "TuneMind Listener" -> session.displayName
        userProfile.name.isNotBlank() -> userProfile.name
        session.email.isNotBlank() -> session.email.substringBefore("@")
        else -> "TuneMind Listener"
    }
    val effectiveEmail = session.email.ifBlank {
        if (hasExplicitAccount) userProfile.email else ""
    }
    val effectiveArchetype = session.tasteArchetype.ifBlank { userProfile.archetype }

    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFF59E0B)
    val dangerRed = Color(0xFFFF4D6D)
    val neonCyan = theme.primaryAccent
    val electricPurple = theme.secondaryAccent

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFFAFAFA),
        unfocusedTextColor = Color(0xFFFAFAFA),
        focusedContainerColor = theme.cardSurface.copy(alpha = 0.92f),
        unfocusedContainerColor = theme.cardSurface.copy(alpha = 0.68f),
        focusedBorderColor = neonCyan,
        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
        focusedLabelColor = neonCyan,
        unfocusedLabelColor = Color.White.copy(alpha = 0.56f),
        cursorColor = neonCyan
    )

    val refreshPermissions: () -> Unit = remember(context) {
        {
            hasMicPermission = PermissionUtils.hasMicrophonePermission(context)
            hasNotificationAccess = PermissionUtils.hasNotificationListenerPermission(context)
            isBatteryIgnored = PermissionUtils.isIgnoringBatteryOptimizations(context)
            if (hasNotificationAccess) {
                PermissionUtils.kickstartMediaObservation(context)
            }
            authManager.refreshSessionState()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted || PermissionUtils.hasMicrophonePermission(context)
        if (!hasMicPermission &&
            PermissionUtils.isRuntimePermissionPermanentlyDeniedAfterResult(
                context = context,
                permission = Manifest.permission.RECORD_AUDIO,
                granted = false
            )
        ) {
            PermissionUtils.openAppDetailsSettings(context)
        }
        refreshPermissions()
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
                        TuneMindAvatarCircle(
                            imageUrl = effectiveAvatarSource,
                            fallbackInitial = effectiveDisplayName.ifEmpty { "T" },
                            size = 68.dp,
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
                                    onClick = { showEditProfileScreen = true },
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
                                    "Sign in with Google or Email to sync your profile"
                                },
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            val providerText = when {
                                session.primaryProvider == "GOOGLE" || session.isGoogleConnected -> "Google Account"
                                session.primaryProvider == "EMAIL" -> "Email Account"
                                else -> "No Account"
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
                        }
                    }

                    if (customProfileData.bio.isNotBlank()) {
                        Text(
                            text = customProfileData.bio,
                            fontSize = 13.sp,
                            color = Color(0xFFD6D1E6),
                            lineHeight = 18.sp
                        )
                    }

                    if (customProfileData.favoriteGenres.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            customProfileData.favoriteGenres.take(4).forEach { genre ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(neonCyan.copy(alpha = 0.14f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = genre,
                                        color = neonCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    StudioHairlineDivider()

                    val monitoredHours = formatListeningDuration(stats.allTimeListeningMs)
                    val matchPct = if (stats.uniqueTrackCount == 0) 0 else (74 + minOf(24, stats.uniqueTrackCount)).coerceAtMost(98)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ProfileMetricColumn("Listened", monitoredHours, neonCyan)
                        ProfileMetricColumn("Tracks", "${stats.uniqueTrackCount}", electricPurple.copy(alpha = 0.95f))
                        ProfileMetricColumn("Taste Match", if (matchPct > 0) "$matchPct%" else "--", safeGreen)
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
                        Button(
                            onClick = { showEditProfileScreen = true },
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1A1033),
                                contentColor = Color(0xFFF5F3FF)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = neonCyan,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Edit Profile", color = Color(0xFFF5F3FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 2. Open-Canvas Settings & Taste Section
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioSectionHeader(title = "Settings & Taste")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()

                ProfileNavRow(
                    icon = Icons.Rounded.Settings,
                    iconTint = neonCyan,
                    title = "App Settings",
                    subtitle = "Players, Hybrid Mode, voice & theme",
                    onClick = onOpenSettings
                )
                StudioHairlineDivider()

                ProfileNavRow(
                    icon = Icons.Rounded.GraphicEq,
                    iconTint = electricPurple,
                    title = "Taste Profile & Genres",
                    subtitle = "$effectiveArchetype · Edit favorite genres & artists",
                    onClick = onOpenTasteProfile
                )
                StudioHairlineDivider()

                ProfileNavRow(
                    icon = Icons.Rounded.AutoGraph,
                    iconTint = safeGreen,
                    title = "Weekly Listening Recap",
                    subtitle = "Top shifts, discoveries & listening balance",
                    onClick = onOpenWeeklyReport
                )
                StudioHairlineDivider()
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Cross-App Detection Overview (Automatic via Notification Listener — Zero API Keys)
            SoftGlassCard(
                modifier = Modifier.fillMaxWidth(),
                accentTint = if (hasNotificationAccess) safeGreen else theme.primaryAccent
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudioSectionHeader(
                        title = "Cross-App Music Detection",
                        actionLabel = if (hasNotificationAccess) "Live" else "Enable Access",
                        onActionClick = {
                            PermissionUtils.openNotificationListenerSettings(context)
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
                                .background((if (hasNotificationAccess) safeGreen else theme.primaryAccent).copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LibraryMusic,
                                contentDescription = null,
                                tint = if (hasNotificationAccess) safeGreen else theme.primaryAccent,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (detectedTabs.isEmpty()) "All Android Music Apps Supported" else "${detectedTabs.size} Music App Tab(s) Active",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = if (libraryTracks.isNotEmpty()) {
                                    "${libraryTracks.size} permanent tracks tracked across Boomplay, YouTube Music, Spotify, Audiomack & phone audio"
                                } else {
                                    "Play songs in Boomplay, YouTube Music, Spotify, Audiomack, or phone audio — TuneMind creates permanent per-app tabs automatically."
                                },
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.62f),
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 4. Open-Canvas Privacy & Permissions
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
                    description = "Combines matching tracks played across music apps and local players.",
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
                        if (!hasMicPermission) {
                            runCatching {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }.onFailure {
                                PermissionUtils.openAppDetailsSettings(context)
                            }
                        } else {
                            PermissionUtils.openAppDetailsSettings(context)
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
                        PermissionUtils.openNotificationListenerSettings(context)
                    }
                )
                if (!hasNotificationAccess && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    Text(
                        text = "Toggle grayed out? Open App Info → ⋮ (top right) → Allow restricted settings",
                        fontSize = 11.5.sp,
                        color = warnAmber.copy(alpha = 0.88f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { PermissionUtils.openAppDetailsSettings(context) }
                            .padding(bottom = 10.dp)
                    )
                }
                StudioHairlineDivider()

                ProfilePermissionRow(
                    title = "Background Battery Sync",
                    subtitle = if (isBatteryIgnored) {
                        "Unrestricted background sync active"
                    } else {
                        "Tap to review background battery optimization settings"
                    },
                    granted = isBatteryIgnored,
                    safeGreen = safeGreen,
                    warnAmber = warnAmber,
                    onClick = {
                        vm.requestIgnoreBatteryOptimization(context)
                    }
                )
                StudioHairlineDivider()
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 4b. Open-Canvas Alerts, Help & Legal
            Column(modifier = Modifier.fillMaxWidth()) {
                StudioSectionHeader(title = "Alerts, Help & Legal")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()

                ProfileNavRow(
                    icon = Icons.Rounded.NotificationsNone,
                    iconTint = neonCyan,
                    title = "Notifications & Alerts",
                    subtitle = "Vault alerts, hearing warnings & categories",
                    onClick = onOpenNotifications
                )
                StudioHairlineDivider()

                ProfileNavRow(
                    icon = Icons.Rounded.HelpOutline,
                    iconTint = warnAmber,
                    title = "Help & Feedback",
                    subtitle = "Spotify wake steps, battery tips & bug reports",
                    onClick = onOpenSupport
                )
                StudioHairlineDivider()

                ProfileNavRow(
                    icon = Icons.Rounded.Policy,
                    iconTint = electricPurple,
                    title = "Privacy Policy, Terms & About",
                    subtitle = "Permissions transparency, legal terms & version",
                    onClick = { onOpenLegal(0) }
                )
                StudioHairlineDivider()
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 5. Open-Canvas Account & Data Controls
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                StudioSectionHeader(title = "Account & Data")
                Spacer(modifier = Modifier.height(6.dp))
                StudioHairlineDivider()

                ProfileNavRow(
                    icon = Icons.Rounded.ManageAccounts,
                    iconTint = neonCyan,
                    title = if (savedAccounts.size > 1) "Switch Accounts (${savedAccounts.size} saved)" else "Switch or Add Account",
                    subtitle = "Manage signed-in profiles on this device",
                    onClick = { showSwitchAccountsSheet = true }
                )
                StudioHairlineDivider()

                ProfileNavRow(
                    icon = Icons.Rounded.Download,
                    iconTint = Color(0xFFFAFAFA),
                    title = "Export My Data (JSON)",
                    subtitle = "Share a copy of your listening history & preferences",
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
                    }
                )
                StudioHairlineDivider()

                ProfileNavRow(
                    icon = Icons.Rounded.History,
                    iconTint = warnAmber,
                    title = "Clear Listening History",
                    subtitle = "Reset locally tracked plays and stats",
                    onClick = { showClearHistorySheet = true }
                )
                StudioHairlineDivider()

                Spacer(modifier = Modifier.height(18.dp))

                // Log Out
                Button(
                    onClick = { showLogoutSheet = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dangerRed.copy(alpha = 0.14f),
                        contentColor = dangerRed
                    ),
                    border = BorderStroke(0.8.dp, dangerRed.copy(alpha = 0.42f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (hasExplicitAccount) "Log Out" else "Sign In",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Delete Account
                if (hasExplicitAccount) {
                    TextButton(
                        onClick = { showDeleteAccountSheet = true },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 6.dp)
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

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    // =========================================================================
    // FULL-SCREEN EDIT PROFILE & ACCOUNT SHEETS
    // =========================================================================

    if (showEditProfileScreen) {
        BackHandler(enabled = true) { showEditProfileScreen = false }
        FullScreenEditProfile(
            initialName = effectiveDisplayName,
            initialArchetype = effectiveArchetype,
            initialCustom = customProfileData,
            fallbackAvatarUrl = session.avatarUrl,
            neonCyan = neonCyan,
            fieldColors = fieldColors,
            onBack = { showEditProfileScreen = false },
            onSave = { cleanName, cleanArch, updatedCustom ->
                vm.updateUserProfile(cleanName, cleanArch)
                if (updatedCustom.favoriteGenres.isNotEmpty()) {
                    vm.saveTasteAnchors(updatedCustom.favoriteGenres)
                }
                ProfileCustomizationStore.save(context, updatedCustom)
                customProfileData = updatedCustom
                scope.launch {
                    authManager.updateProfileAndPreferences(
                        displayName = cleanName,
                        tasteArchetype = cleanArch
                    )
                    showEditProfileScreen = false
                    statusBanner = "Profile updated."
                }
            }
        )
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
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            runCatching {
                                                com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
                                                    .syncTrackCardsAndPlaylistsToBackend(context)
                                            }
                                        }
                                        authManager.switchToAccount(acc)
                                        showSwitchAccountsSheet = false
                                        onAccountSwitched()
                                    }
                                },
                                onRemove = {
                                    authManager.removeSavedAccount(acc.accountKey)
                                    if (isActive) {
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
                    text = "Log Out of TuneMind?",
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
                        Text("Cancel", color = Color(0xFFF8FAFC))
                    }
                    Button(
                        onClick = {
                            isLoggingOut = true
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
                                            .syncTrackCardsAndPlaylistsToBackend(context)
                                    }
                                }
                                authManager.signOutRemoteAndLocal(
                                    removeFromSavedRoster = !keepForQuickSwitch
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
                    text = "Removes your saved profile, clears all local listening telemetry, cross-app library, and vault cooldowns, and signs you out.",
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
private fun FullScreenEditProfile(
    initialName: String,
    initialArchetype: String,
    initialCustom: UserProfileCustomData,
    fallbackAvatarUrl: String,
    neonCyan: Color,
    fieldColors: TextFieldColors,
    onBack: () -> Unit,
    onSave: (cleanName: String, cleanArchetype: String, updatedCustom: UserProfileCustomData) -> Unit
) {
    val context = LocalContext.current
    var nameDraft by remember(initialName) { mutableStateOf(initialName) }
    var bioDraft by remember(initialCustom.bio) { mutableStateOf(initialCustom.bio) }
    var archetypeDraft by remember(initialArchetype) { mutableStateOf(initialArchetype) }
    var avatarPathDraft by remember(initialCustom.avatarFilePath) { mutableStateOf(initialCustom.avatarFilePath) }
    var selectedGenres by remember(initialCustom.favoriteGenres) {
        mutableStateOf(initialCustom.favoriteGenres.ifEmpty { listOf("Afrobeats", "Alt-R&B") })
    }
    var customGenreDraft by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val savedPath = ProfileCustomizationStore.saveAvatarFromUri(context, uri)
            if (!savedPath.isNullOrBlank()) {
                avatarPathDraft = savedPath
            }
        }
    }

    val presetGenres = remember {
        listOf("Afrobeats", "Alt-R&B", "Hip-Hop", "Lo-Fi", "Synthwave", "Neo-Soul", "Amapiano", "Electronic", "Jazz", "Indie")
    }
    val presetArchetypes = remember {
        listOf("Midnight Explorer", "Sonic Purist", "Mood Architect", "High-Energy Groover")
    }

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFFF5F3FF)
                    )
                }
                Text(
                    text = "Edit Profile",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF5F3FF)
                )
                TextButton(
                    onClick = {
                        if (nameDraft.isNotBlank() && !isSaving) {
                            isSaving = true
                            onSave(
                                nameDraft.trim(),
                                archetypeDraft.trim().ifBlank { initialArchetype },
                                UserProfileCustomData(
                                    avatarFilePath = avatarPathDraft,
                                    bio = bioDraft.trim(),
                                    favoriteGenres = selectedGenres
                                )
                            )
                        }
                    },
                    enabled = nameDraft.isNotBlank() && !isSaving
                ) {
                    Text(
                        text = if (isSaving) "Saving…" else "Save",
                        color = if (nameDraft.isNotBlank()) neonCyan else Color(0xFFB8B2CC),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Avatar Upload Section
                val previewSource = avatarPathDraft.ifBlank { fallbackAvatarUrl }
                Box(contentAlignment = Alignment.BottomEnd) {
                    TuneMindAvatarCircle(
                        imageUrl = previewSource,
                        fallbackInitial = nameDraft.ifBlank { "T" },
                        size = 104.dp,
                        accentColor = neonCyan,
                        borderWidth = 2.dp,
                        borderColor = neonCyan.copy(alpha = 0.65f),
                        modifier = Modifier.clickable { photoPickerLauncher.launch("image/*") }
                    )
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(neonCyan)
                            .clickable { photoPickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoCamera,
                            contentDescription = "Upload Profile Photo",
                            tint = Color(0xFF07020F),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { photoPickerLauncher.launch("image/*") }) {
                        Text("Upload Photo", color = neonCyan, fontWeight = FontWeight.SemiBold)
                    }
                    if (avatarPathDraft.isNotBlank()) {
                        TextButton(onClick = { avatarPathDraft = "" }) {
                            Text("Remove Photo", color = Color(0xFFFF6B81), fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Display Name
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    Text(
                        text = "TuneMind AI Agent uses this name during Pro voice & chat conversations.",
                        color = Color(0xFFB8B2CC),
                        fontSize = 11.5.sp
                    )
                }

                // Bio / Musical Vibe
                OutlinedTextField(
                    value = bioDraft,
                    onValueChange = { if (it.length <= 140) bioDraft = it },
                    label = { Text("Bio / Musical Vibe (optional)") },
                    placeholder = { Text("e.g. Late-night Afrobeats & Alt-R&B curator", color = Color(0xFFB8B2CC)) },
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                // Listening Archetype
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetArchetypes.take(2).forEach { arch ->
                            val selected = archetypeDraft.equals(arch, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) neonCyan.copy(alpha = 0.18f) else Color(0xFF120A24))
                                    .clickable { archetypeDraft = arch }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = arch,
                                    color = if (selected) neonCyan else Color(0xFFD6D1E6),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Favorite Genres
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Favorite Genres",
                        color = Color(0xFFF5F3FF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetGenres.forEach { genre ->
                            val isSelected = selectedGenres.any { it.equals(genre, ignoreCase = true) }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (isSelected) neonCyan.copy(alpha = 0.20f) else Color(0xFF120A24))
                                    .clickable {
                                        selectedGenres = if (isSelected) {
                                            selectedGenres.filterNot { it.equals(genre, ignoreCase = true) }
                                        } else {
                                            (selectedGenres + genre).distinct()
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = genre,
                                    color = if (isSelected) neonCyan else Color(0xFFD6D1E6),
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customGenreDraft,
                            onValueChange = { customGenreDraft = it },
                            label = { Text("Add custom genre") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = fieldColors,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val g = customGenreDraft.trim()
                                if (g.isNotBlank() && selectedGenres.none { it.equals(g, ignoreCase = true) }) {
                                    selectedGenres = selectedGenres + g
                                    customGenreDraft = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = neonCyan, contentColor = Color(0xFF07020F)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text("Add", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (nameDraft.isNotBlank() && !isSaving) {
                            isSaving = true
                            onSave(
                                nameDraft.trim(),
                                archetypeDraft.trim().ifBlank { initialArchetype },
                                UserProfileCustomData(
                                    avatarFilePath = avatarPathDraft,
                                    bio = bioDraft.trim(),
                                    favoriteGenres = selectedGenres
                                )
                            )
                        }
                    },
                    enabled = !isSaving && nameDraft.isNotBlank(),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = neonCyan, contentColor = Color(0xFF07020F)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(if (isSaving) "Saving…" else "Save Profile", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
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
