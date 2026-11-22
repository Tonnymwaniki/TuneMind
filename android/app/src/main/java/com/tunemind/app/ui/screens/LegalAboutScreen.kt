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
                                        text = "Effective November 2026 · tunemind.co/privacy",
                                        fontSize = 12.sp,
                                        color = Color(0xFFB8B2CC)
                                    )
                                }
                            }
                            StudioHairlineDivider()
                            LegalParagraph(
                                title = "1. Data We Collect",
                                body = "TuneMind collects only what is needed for your listening companion: account email and display name, encrypted OAuth tokens (Spotify), playlists, liked songs, listening history and play counts, and device/app identifiers."
                            )
                            LegalParagraph(
                                title = "2. AI Processing & Insights",
                                body = "Your listening history, track metadata, and voice/text prompts are processed by AI to generate musical insights, satiety scores, and personalized daily picks. TuneMind never sells your data."
                            )
                            LegalParagraph(
                                title = "3. Third-Party Processors",
                                body = "TuneMind integrates with: Anthropic (Claude API) for AI music reasoning; Supabase for authentication, encrypted database storage, and private user file storage; Spotify, Google / YouTube Data API, and Audiomack for music metadata and playback; Google Play Billing for subscriptions; and Namecheap Private Email for account and trial-reminder emails."
                            )
                            LegalParagraph(
                                title = "4. Device Permissions",
                                body = "• Microphone: Activated only when you say the wake word or tap the TuneMind orb for voice commands.\n• Music Detection (Notification Listener): Reads currently playing track metadata locally on-device across installed music players (such as Spotify, YouTube Music, Audiomack, Boomplay, and phone audio).\n• Local Audio Storage: Optional read-only access so the built-in player can search and play local audio files."
                            )
                            LegalParagraph(
                                title = "5. Your Data Controls & Hard Deletion",
                                body = "From Profile or Settings you can Export your data in JSON, Clear Listening History, or permanently Delete Account & Wipe Data (cascading deletion of cloud rows, OAuth tokens, and private storage files)."
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
                                title = "4. Subscriptions & Refund Policy",
                                body = "TuneMind Pro includes a 7-day free trial via Google Play Billing ($4.99/mo or $29.99/yr, localized by region). Cancel anytime in Google Play Subscriptions with one tap before Day 7. Within 48 hours of an accidental renewal, request a refund directly in Google Play Order History or email support@tunemind.co."
                            )
                            LegalParagraph(
                                title = "5. Account Termination",
                                body = "You may stop using TuneMind or permanently delete your account, OAuth tokens, and cloud data at any time from Profile or Settings -> Delete Account & Wipe Data."
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
                                title = "Official Developer Contact",
                                body = "TuneMind Labs · Support: support@tunemind.co · Legal: legal@tunemind.co · Web: https://tunemind.co"
                            )
                            LegalParagraph(
                                title = "Third-Party SDKs & Open-Source Attributions",
                                body = "• Jetpack Compose, AndroidX Room, Material Icons & Security Crypto (Apache 2.0)\n• Retrofit, OkHttp, Gson & Coil Image Loading (Apache 2.0)\n• Lottie Android & Vosk Offline Wake-Word Engine (Apache 2.0)\n• Firebase Crashlytics & Analytics, Google Play Billing v7\n• Supabase Auth/Postgres/Storage & Anthropic Claude API (backend)"
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
