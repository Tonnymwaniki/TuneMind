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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

    var geminiApiKeyInput by remember {
        mutableStateOf(com.tunemind.app.domain.ai.GeminiReasoningEngine.getApiKey(context))
    }
    var isKeyObscured by remember { mutableStateOf(true) }
    var testStatusText by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }
    val isGeminiConfigured by vm?.isGeminiConfigured?.collectAsState()
        ?: remember { mutableStateOf(com.tunemind.app.domain.ai.GeminiReasoningEngine.isConfigured(context)) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // User Identity Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
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

        // GOOGLE GEMINI REASONING INTELLIGENCE ENGINE
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isGeminiConfigured) AccentCyan.copy(alpha = 0.5f) else Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentCyanBright, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(
                                text = "GEMINI REASONING ENGINE",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Cognitive AI Integration",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isGeminiConfigured) Color(0x3310B981) else Color(0x33F59E0B),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isGeminiConfigured) AccentGreen else AccentAmber)
                    ) {
                        Text(
                            text = if (isGeminiConfigured) "● ACTIVE" else "○ AWAITING KEY",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isGeminiConfigured) AccentGreen else AccentAmber,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                Text(
                    text = "Runs real-time cognitive reasoning over your playback logs to prevent song burnout, synthesize satiety dynamics, and curate AI-guided recommendations.",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 16.sp
                )

                // Key Input
                OutlinedTextField(
                    value = geminiApiKeyInput,
                    onValueChange = {
                        geminiApiKeyInput = it
                        testStatusText = null
                    },
                    label = { Text("Gemini API Key", fontSize = 12.sp) },
                    placeholder = { Text("AIzaSy...", fontSize = 11.sp, color = Color(0xFF64748B)) },
                    visualTransformation = if (isKeyObscured) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = {
                        IconButton(onClick = { isKeyObscured = !isKeyObscured }) {
                            Icon(
                                if (isKeyObscured) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Visibility",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF020617),
                        unfocusedContainerColor = Color(0xFF020617),
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Action buttons: Save & Test
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (vm != null) {
                                vm.saveGeminiApiKey(geminiApiKeyInput)
                            } else {
                                com.tunemind.app.domain.ai.GeminiReasoningEngine.saveApiKey(context, geminiApiKeyInput)
                            }
                            testStatusText = "API Key saved successfully ✓"
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF020617)),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save & Activate", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            isTestingConnection = true
                            testStatusText = "Pinging Google Gemini API..."
                            if (vm != null) {
                                vm.testGeminiApiKey(geminiApiKeyInput) { success, msg ->
                                    isTestingConnection = false
                                    testStatusText = if (success) "✓ $msg" else "✗ Error: $msg"
                                }
                            } else {
                                kotlinx.coroutines.GlobalScope.let {
                                    // Fallback test
                                    isTestingConnection = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(if (isTestingConnection) "Testing..." else "Test Connection", fontSize = 11.sp, color = Color.White)
                    }
                }

                testStatusText?.let { status ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF020617),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = status,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (status.startsWith("✓")) AccentGreen else if (status.startsWith("✗")) Color(0xFFEF4444) else AccentCyanBright,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }

                Text(
                    text = "Tip: You can also set GEMINI_API_KEY in local.properties or your system environment variables to bake it into the Gradle build.",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B)
                )
            }
        }

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
                        color = if (isSelected) Color(0xFF1E293B) else SurfaceContainerLow,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AccentCyan else BorderSubtle),
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
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = provider.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else TextPrimary
                                    )
                                    val isInstalled = provider.isInstalled(context)
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isInstalled) AccentGreen.copy(alpha = 0.15f) else Color(0xFF334155),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isInstalled) AccentGreen.copy(alpha = 0.4f) else Color(0xFF475569))
                                    ) {
                                        Text(
                                            text = if (isInstalled) "Installed" else "Web Handoff",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isInstalled) AccentGreen else Color(0xFF94A3B8),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }

                                val capDesc = if (provider.supportedCapabilities.contains(com.tunemind.app.provider.ProviderCapability.CAN_DEEP_LINK)) {
                                    "Supports direct deep links & track playback"
                                } else {
                                    "Supports search intent handoff"
                                }
                                Text(text = capDesc, fontSize = 10.sp, color = TextMuted)
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
                    Text("Balanced (75%)", fontSize = 11.sp, color = AccentCyan, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = fatigueSensitivity,
                    onValueChange = { fatigueSensitivity = it },
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryViolet,
                        activeTrackColor = PrimaryViolet,
                        inactiveTrackColor = SurfaceContainerHighest
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
                Divider(color = BorderSubtle)
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

                Divider(color = BorderSubtle)

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

        // System & Diagnostics
        OutlinedButton(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Text("Export Listening Ledger (.JSON / .CSV)", fontSize = 12.sp, color = TextSecondary)
        }

        // Profile Reset
        Button(
            onClick = { showResetDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF451A03),
                contentColor = AccentAmber
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
        ) {
            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Reset & Delete Listening Profile", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset Listening Profile?", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "This will permanently erase all local Room database playback history, observed listening patterns, satiety models, and quarantined cooldowns. TuneMind will begin a fresh observation cycle.",
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showResetDialog = false
                            vm?.resetListeningProfile()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White)
                    ) {
                        Text("Erase Profile")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF0F172A),
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}
