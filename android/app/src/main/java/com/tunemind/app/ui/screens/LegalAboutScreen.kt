package com.tunemind.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.BuildConfig
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.StudioSectionHeader
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

@Composable
fun LegalAboutScreen(
    initialTab: Int = 0,
    onBack: () -> Unit,
    onOpenSupport: (() -> Unit)? = null
) {
    val theme = LocalAdaptiveTheme.current
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab.coerceIn(0, 2)) }
    val neonCyan = theme.primaryAccent
    val electricPurple = theme.secondaryAccent
    val safeGreen = Color(0xFF10B981)

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
                    text = "Privacy, Terms & About",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            MagicalSegmentedTabs(
                tabs = listOf(
                    MagicalTabItem("Privacy Policy", neonCyan),
                    MagicalTabItem("Terms of Use", electricPurple),
                    MagicalTabItem("About & Licenses", safeGreen)
                ),
                selectedIndex = selectedTab,
                onSelectTab = { selectedTab = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    SoftGlassCard(modifier = Modifier.fillMaxWidth(), accentTint = neonCyan) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Rounded.Policy, contentDescription = null, tint = neonCyan)
                                Column {
                                    Text(
                                        text = "TuneMind Privacy Policy",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF8FAFC)
                                    )
                                    Text(
                                        text = "Effective November 2026 · Local-First Architecture",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.56f)
                                    )
                                }
                            }
                            StudioHairlineDivider()
                            LegalParagraph(
                                title = "1. Account Identity & Credentials",
                                body = "When you sign in with Google or Email & Password, TuneMind stores your profile name, email, and taste archetype in our encrypted database to sync your preferences across sessions."
                            )
                            LegalParagraph(
                                title = "2. Listening Telemetry & Local Storage",
                                body = "Playback events, cross-app player tabs, freshness scores, and Resting Vault tracks are processed and stored locally on your device inside an encrypted SQLite (Room) database. You can pause all tracking anytime using Private Session in Profile or Settings."
                            )
                            LegalParagraph(
                                title = "3. Device Permissions",
                                body = "• Microphone: Activated only when you say the wake word or tap the TuneMind orb for voice commands.\n• Music Detection (Notification Listener): Reads currently playing track metadata locally on-device across installed music players (such as Boomplay, YouTube Music, Spotify, Audiomack, and phone audio).\n• Local Audio Storage: Optional read-only access so the built-in player can search and play local audio files."
                            )
                            LegalParagraph(
                                title = "4. Your Data Controls",
                                body = "From the Profile screen you can Export your complete data in JSON format, Clear Listening History, or permanently Delete Account & Wipe Data."
                            )
                        }
                    }
                }
                1 -> {
                    SoftGlassCard(modifier = Modifier.fillMaxWidth(), accentTint = electricPurple) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = electricPurple)
                                Column {
                                    Text(
                                        text = "Terms of Service",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF8FAFC)
                                    )
                                    Text(
                                        text = "TuneMind Personal Listening Companion",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.56f)
                                    )
                                }
                            }
                            StudioHairlineDivider()
                            LegalParagraph(
                                title = "1. Personal Listening Companion",
                                body = "TuneMind helps you track repetition fatigue, protect song freshness, monitor safe volume headroom, and control playback across supported music services for personal, non-commercial use."
                            )
                            LegalParagraph(
                                title = "2. Third-Party Players & Catalogs",
                                body = "TuneMind interacts with music players installed on your device via standard Android media controls and public catalog APIs under their respective terms. Audio playback rights remain with your chosen player; TuneMind does not bypass DRM or redistribute copyrighted audio."
                            )
                            LegalParagraph(
                                title = "3. Safe Listening Disclaimer",
                                body = "Decibel headroom and hearing health estimates are informational heuristics derived from device volume steps and audio route characteristics. They are not medical advice—always adjust volume comfortably to protect your hearing."
                            )
                            LegalParagraph(
                                title = "4. Account Termination",
                                body = "You may stop using TuneMind or delete your account and cloud profile at any time from Profile -> Delete Account & Wipe Data."
                            )
                        }
                    }
                }
                else -> {
                    SoftGlassCard(modifier = Modifier.fillMaxWidth(), accentTint = safeGreen) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Rounded.Info, contentDescription = null, tint = safeGreen)
                                Column {
                                    Text(
                                        text = "TuneMind Android",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF8FAFC)
                                    )
                                    Text(
                                        text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                        fontSize = 12.sp,
                                        color = safeGreen,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            StudioHairlineDivider()
                            LegalParagraph(
                                title = "Open-Source Libraries & Attributions",
                                body = "• Jetpack Compose & AndroidX Room (Apache 2.0)\n• Kotlin Coroutines & Material 3 (Apache 2.0)\n• OkHttp Networking & Security Crypto (Apache 2.0)\n• Lottie Android Vector Animations (Apache 2.0)\n• Vosk Offline Wake-Word Engine (Apache 2.0)\n• Open Catalog APIs (Deezer, iTunes, Audius)"
                            )
                            if (onOpenSupport != null) {
                                StudioHairlineDivider()
                                Button(
                                    onClick = onOpenSupport,
                                    shape = RoundedCornerShape(28.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = theme.primaryAccent,
                                        contentColor = Color(0xFF0D0D0D)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Text("Open Help & Send Feedback", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun LegalParagraph(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFF8FAFC)
        )
        Text(
            text = body,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.70f),
            lineHeight = 19.sp
        )
    }
}
