package com.tunemind.app.ui.screens

import android.os.Build
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
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@Composable
fun SettingsScreen(
    vm: TuneMindViewModel? = null,
    onBack: () -> Unit,
    onOpenCrossApp: () -> Unit,
    onOpenWeeklyReport: () -> Unit
) {
    val context = LocalContext.current
    val registry = remember(context) { com.tunemind.app.provider.MusicProviderRegistry.getInstance(context) }
    var selectedProviderId by remember { mutableStateOf(registry.getPreferredProviderId()) }
    var alwaysAsk by remember { mutableStateOf(registry.isAlwaysAskEnabled()) }
    val providers = remember { registry.getInstalledProviders() }
    var showResetDialog by remember { mutableStateOf(false) }

    val isBatteryIgnored by (vm?.isBatteryOptimizationIgnored ?: remember { mutableStateOf(true) }).collectAsState()
    val audioFocusBehavior by (vm?.audioFocusBehavior ?: remember { mutableStateOf(com.tunemind.app.ui.AudioFocusBehavior.DUCK) }).collectAsState()
    val isDecibelSafetyEnabled by (vm?.isDecibelSafetyAlertEnabled ?: remember { mutableStateOf(true) }).collectAsState()

    var persistentListener by remember { mutableStateOf(true) }
    var fatigueSensitivity by remember { mutableStateOf(0.75f) }
    var chronoReceptivity by remember { mutableStateOf(true) }
    var cloakingSession by remember { mutableStateOf(false) }
    var deduplicateLogs by remember { mutableStateOf(true) }

    val scrollState = rememberScrollState()
    val colors = TuneMindTheme.colors

    LaunchedEffect(Unit) {
        vm?.checkBatteryOptimization(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.primaryBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                        text = "STUDIO SWITCHBOARD // HARDWARE ENGINE",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = "DSP OS BUS",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.SecondaryAccent
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            text = if (isBatteryIgnored) "SHIELD ACTIVE" else "RESTRICTED",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isBatteryIgnored) DeepObsidianAmber.DecibelSafe else DeepObsidianAmber.Warning,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (!isBatteryIgnored) {
                    Button(
                        onClick = { vm?.requestIgnoreBatteryOptimization(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepObsidianAmber.Warning),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Grant Battery Exemption (Allow Background)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepObsidianAmber.AppBackground
                        )
                    }
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
                        onCheckedChange = { vm?.setDecibelSafetyAlertEnabled(it) }
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
                        .clickable { vm?.setAudioFocusBehavior(com.tunemind.app.ui.AudioFocusBehavior.DUCK) }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.DUCK,
                            onClick = { vm?.setAudioFocusBehavior(com.tunemind.app.ui.AudioFocusBehavior.DUCK) },
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
                        .clickable { vm?.setAudioFocusBehavior(com.tunemind.app.ui.AudioFocusBehavior.PAUSE) }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = audioFocusBehavior == com.tunemind.app.ui.AudioFocusBehavior.PAUSE,
                            onClick = { vm?.setAudioFocusBehavior(com.tunemind.app.ui.AudioFocusBehavior.PAUSE) },
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
                                vm?.setAlwaysAskEnabled(context, it)
                                    ?: registry.setAlwaysAskEnabled(it)
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
                            vm?.setPreferredProviderId(context, provider.providerId)
                                ?: registry.setPreferredProviderId(provider.providerId)
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
                                    vm?.setPreferredProviderId(context, provider.providerId)
                                        ?: registry.setPreferredProviderId(provider.providerId)
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
                            vm?.resetListeningProfile()
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
