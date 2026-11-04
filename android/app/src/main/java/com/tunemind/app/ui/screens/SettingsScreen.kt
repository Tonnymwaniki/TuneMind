package com.tunemind.app.ui.screens

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.*

@Composable
fun SettingsScreen(
    vm: com.tunemind.app.ui.TuneMindViewModel? = null,
    onBack: () -> Unit,
    onOpenCrossApp: () -> Unit,
    onOpenWeeklyReport: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val registry = remember(context) { com.tunemind.app.provider.MusicProviderRegistry.getInstance(context) }
    var selectedProviderId by remember { mutableStateOf(registry.getPreferredProviderId()) }
    val providers = remember { registry.getInstalledProviders() }
    var showResetDialog by remember { mutableStateOf(false) }

    var persistentListener by remember { mutableStateOf(true) }
    var fatigueSensitivity by remember { mutableStateOf(0.75f) }
    var chronoReceptivity by remember { mutableStateOf(true) }
    var cloakingSession by remember { mutableStateOf(false) }
    var deduplicateLogs by remember { mutableStateOf(true) }

    val scrollState = rememberScrollState()

    val colors = TuneMindTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.primaryBackground)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // User Identity Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.secondaryBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(PrimaryVioletDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("T", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tonny", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(PrimaryViolet)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("PRO", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Text("tonny@tunemind.neuro", fontSize = 12.sp, color = TextMuted)
                        Text("• Acoustic Intelligence Tier 1 • Active", fontSize = 10.sp, color = AccentCyan)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("87.4h", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                        Text("Monitored", fontSize = 10.sp, color = TextMuted)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("3 Apps", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                        Text("Synced Live", fontSize = 10.sp, color = TextMuted)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("91%", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AccentCyan)
                        Text("Confidence", fontSize = 10.sp, color = TextMuted)
                    }
                }
            }
        }

        // ADAPTIVE PERSONAL ATMOSPHERE ENGINE
        AdaptiveAtmosphereSection(vm = vm)

        // Audio Sources & Ingestion
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Audio Sources & Ingestion", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            TextButton(onClick = onOpenCrossApp) {
                Text("Manage Daemons →", fontSize = 11.sp, color = AccentCyan)
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Persistent Background Daemon", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Switch(checked = persistentListener, onCheckedChange = { persistentListener = it })
                }
            }
        }

        // Music Services & Handoff Playback
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Default Music Service", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Agent Handoff", fontSize = 11.sp, color = AccentCyan)
        }

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "When you tap 'Play this song' in TuneMind, playback starts in your preferred music service. The intelligence belongs to TuneMind — the music belongs to your services.",
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 15.sp
                )

                providers.forEach { provider ->
                    val isSelected = provider.providerId == selectedProviderId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) DeepObsidianAmber.CardElevated else DeepObsidianAmber.CardBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) DeepObsidianAmber.PrimaryAccent else DeepObsidianAmber.SurfaceBorder),
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
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isInstalled) DeepObsidianAmber.Success.copy(alpha = 0.4f) else DeepObsidianAmber.SurfaceBorder)
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

        // Intelligence & Calibration (TM-Engine 4.2)
        Text("Intelligence & Calibration (TM-Engine 4.2)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Song Fatigue Sensitivity", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text("Balanced (75%)", fontSize = 11.sp, color = DeepObsidianAmber.SecondaryAccent, fontWeight = FontWeight.Bold)
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
                    Text("Tolerant (50%)", fontSize = 9.sp, color = TextMuted)
                    Text("Adaptive", fontSize = 9.sp, color = TextMuted)
                    Text("Aggressive (90%)", fontSize = 9.sp, color = TextMuted)
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
                        Text("Chrono-Receptivity Profiling", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("Dampens treble and vocal fatigue after 22:00", fontSize = 10.sp, color = TextMuted)
                    }
                    Switch(checked = chronoReceptivity, onCheckedChange = { chronoReceptivity = it })
                }
            }
        }

        // Privacy & Local Vault
        Text("Privacy & Zero-Cloud Local Vault", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Incognito / Cloak Session", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("Pause telemetry during guest or DJ sets", fontSize = 10.sp, color = TextMuted)
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
                        Text("Deduplicate Cross-Platform Logs", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("Unify matching ISRC tags across Spotify & YT Music", fontSize = 10.sp, color = TextMuted)
                    }
                    Switch(checked = deduplicateLogs, onCheckedChange = { deduplicateLogs = it })
                }
            }
        }

        // Profile Reset
        Button(
            onClick = { showResetDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DeepObsidianAmber.RecessedBackground,
                contentColor = DeepObsidianAmber.Error
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.Error.copy(alpha = 0.5f))
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
    }
}
