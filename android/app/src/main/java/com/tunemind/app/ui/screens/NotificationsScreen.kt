package com.tunemind.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.domain.memory.ListeningPlayDeduplicator
import com.tunemind.app.domain.memory.StatsTimeWindow
import com.tunemind.app.domain.voice.AgentSettingsStore
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.util.PermissionUtils

private data class TuneMindAlertItem(
    val id: String,
    val title: String,
    val body: String,
    val icon: ImageVector,
    val accent: Color,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

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
    val authSession by TuneMindAuthSessionManager.getInstance(context).sessionFlow.collectAsState()

    val vaultTracks by vm.vaultState.collectAsState()
    val hearingInsights by vm.hearingInsightsState.collectAsState()
    val listeningStats by vm.listeningStats.collectAsState()
    val cleanPlayHistory by vm.cleanPlayHistory.collectAsState()
    val needsScopeUpgrade by vm.needsLibraryScopeUpgrade.collectAsState()
    val weeklyTrackCount = remember(cleanPlayHistory, listeningStats) {
        ListeningPlayDeduplicator.buildWindowedStats(
            cleanPlayHistory = cleanPlayHistory,
            overallStats = listeningStats,
            window = StatsTimeWindow.SEVEN_DAYS
        ).uniqueTrackCount
    }

    val vaultAlertsEnabled by AgentSettingsStore.vaultRecoveryAlertsEnabled.collectAsState()
    val weeklyRecapEnabled by AgentSettingsStore.weeklyRecapAlertsEnabled.collectAsState()
    val hearingAlertsEnabled by AgentSettingsStore.hearingAlertsEnabled.collectAsState()
    val syncAlertsEnabled by AgentSettingsStore.syncStatusAlertsEnabled.collectAsState()

    var dismissedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        AgentSettingsStore.ensureLoaded(context)
    }

    val neonCyan = theme.primaryAccent
    val safeGreen = Color(0xFF10B981)
    val warnAmber = Color(0xFFF59E0B)
    val electricPurple = theme.secondaryAccent

    val liveAlerts = remember(
        vaultTracks,
        hearingInsights,
        weeklyTrackCount,
        needsScopeUpgrade,
        authSession.isSpotifyConnected,
        vaultAlertsEnabled,
        weeklyRecapEnabled,
        hearingAlertsEnabled,
        syncAlertsEnabled,
        dismissedIds
    ) {
        buildList {
            if (syncAlertsEnabled && !PermissionUtils.hasNotificationListenerPermission(context)) {
                add(
                    TuneMindAlertItem(
                        id = "perm_listener",
                        title = "Music detection paused",
                        body = "Notification access is off, so TuneMind cannot detect active tracks from your players.",
                        icon = Icons.Rounded.NotificationsActive,
                        accent = warnAmber,
                        actionLabel = "Enable Access",
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    PermissionUtils.getNotificationListenerIntent().apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                    )
                )
            }

            if (syncAlertsEnabled && needsScopeUpgrade) {
                add(
                    TuneMindAlertItem(
                        id = "spotify_scope_upgrade",
                        title = "Spotify library access update needed",
                        body = "Re-link Spotify in Settings to include Liked Songs, Playlists & Podcasts.",
                        icon = Icons.Rounded.LibraryMusic,
                        accent = Color(0xFF1DB954),
                        actionLabel = "Open Settings",
                        onAction = {
                            onBack()
                            onOpenSettings()
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
                            actionLabel = "Open Vault",
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
                        title = "Your listening balance recap is ready",
                        body = "$weeklyTrackCount unique ${if (weeklyTrackCount == 1) "track" else "tracks"} tracked across your sessions this week.",
                        icon = Icons.Rounded.AutoGraph,
                        accent = electricPurple,
                        actionLabel = "View Recap",
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
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
                        text = "Notifications & Alerts",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                }
                if (liveAlerts.isNotEmpty()) {
                    TextButton(
                        onClick = { dismissedIds = dismissedIds + liveAlerts.map { it.id } }
                    ) {
                        Text("Clear All", color = neonCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Live Activity Feed
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

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Notification Category Preferences
            SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    StudioSectionHeader(title = "Alert Preferences")
                    Spacer(modifier = Modifier.height(6.dp))
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
                        title = "Weekly Recap Summaries",
                        description = "Surface weekly listening shifts and discovery balance updates.",
                        checked = weeklyRecapEnabled,
                        accent = electricPurple,
                        onCheckedChange = { AgentSettingsStore.setWeeklyRecapAlertsEnabled(context, it) }
                    )
                    StudioHairlineDivider()

                    AlertPrefSwitchRow(
                        title = "Sync & Permission Health",
                        description = "Alert if Spotify access or music detection needs attention.",
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
