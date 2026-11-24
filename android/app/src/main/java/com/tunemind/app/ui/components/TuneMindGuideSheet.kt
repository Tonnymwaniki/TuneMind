package com.tunemind.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.theme.LocalAdaptiveTheme

enum class TuneMindGuideSection(val label: String) {
    SETUP("Quick Setup"),
    PLAYBACK("Music Apps"),
    VOICE("Voice AI"),
    VAULT_FRESH("Fresh & Vault"),
    LIBRARY_CARDS("Song Cards"),
    HEARING_TRIAL("Hearing & Trial")
}

private data class AppGuideTopic(
    val section: TuneMindGuideSection,
    val title: String,
    val summary: String,
    val bullets: List<String>,
    val icon: ImageVector,
    val accent: Color,
    val samplePrompts: List<String> = emptyList()
)

/**
 * Unified interactive App Guide Hub covering every TuneMind screen & feature,
 * with contextual deep-links (`initialSection`) and tappable Voice Companion prompts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuneMindGuideSheet(
    initialSection: TuneMindGuideSection? = null,
    onDismiss: () -> Unit,
    onRunPrompt: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onReplayWelcomeTour: (() -> Unit)? = null
) {
    val theme = LocalAdaptiveTheme.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedSection by remember(initialSection) {
        mutableStateOf(initialSection ?: TuneMindGuideSection.VOICE)
    }

    val neonCyan = theme.primaryAccent
    val electricPurple = theme.secondaryAccent
    val safeGreen = Color(0xFF10B981)
    val warmGold = Color(0xFFF59E0B)

    val topics = listOf(
        AppGuideTopic(
            section = TuneMindGuideSection.SETUP,
            title = "Setup in 30 Seconds",
            summary = "Allow these 2 permissions once so TuneMind captures every play and hears your voice cleanly.",
            bullets = listOf(
                "Notification Access: Lets TuneMind automatically log what you play on Spotify, Boomplay, Audiomack, and YouTube Music — no manual entry.",
                "Microphone Permission: Used only while your voice session is active so you can speak naturally to TuneMind.",
                "Battery Unrestricted (Optional): Prevents Android from pausing background play tracking on Xiaomi/Redmi, Tecno, Infinix, and Samsung phones."
            ),
            icon = Icons.Rounded.Tune,
            accent = neonCyan
        ),
        AppGuideTopic(
            section = TuneMindGuideSection.PLAYBACK,
            title = "How Playback Works Per Music App",
            summary = "TuneMind adapts to whichever music app you use so playback stays reliable.",
            bullets = listOf(
                "Spotify Premium: Plays silently in the background without ever leaving TuneMind.",
                "Spotify Free: Spotify blocks silent remote playback on Free accounts, so TuneMind opens Spotify directly to play the track.",
                "YouTube Music: Plays right inside TuneMind's built-in YouTube player when selected as your player.",
                "Boomplay, Audiomack & Other Apps: TuneMind detects your installed apps and opens the selected app directly to play your song."
            ),
            icon = Icons.Rounded.PlayArrow,
            accent = safeGreen
        ),
        AppGuideTopic(
            section = TuneMindGuideSection.VOICE,
            title = "Talking to TuneMind (Voice AI)",
            summary = "Say \"Hi TuneMind\" or tap the glowing orb. TuneMind always asks \"Should I play…?\" before launching music.",
            bullets = listOf(
                "Approval Gate: Ask for any song, mood, or playlist — TuneMind tells you the match and waits for \"Yes\" or \"No\" before playing.",
                "Reads All Screens: Ask about your Fresh songs, For You crate, TuneMind playlists, Song Cards, stats, or live playback.",
                "Controls Everything: Adjust volume, set sleep timers, quarantine songs to the Vault, switch themes, or save songs to playlists by voice."
            ),
            icon = Icons.Rounded.GraphicEq,
            accent = Color(0xFF38BDF8),
            samplePrompts = listOf(
                "Play something fresh I haven't heard lately",
                "How many times have I played this song?",
                "What's in my For You picks today?",
                "Set a 20-minute sleep timer"
            )
        ),
        AppGuideTopic(
            section = TuneMindGuideSection.VAULT_FRESH,
            title = "Freshness %, Resting Vault & For You",
            summary = "Stop killing your favorite songs through loop fatigue.",
            bullets = listOf(
                "Freshness %: Starts at 100% and drops when you replay a track heavily in a short window.",
                "Resting Vault: Overplayed songs enter the Vault on a cooldown timer so you can miss them and restore their thrill.",
                "Manual Fresh & For You: Pin songs you want ready in Fresh, or explore daily personalized picks in For You (Taste Match grows as you listen).",
                "Restore Anytime: You can bring any track out of the Vault back to Fresh with one tap — or ask TuneMind by voice."
            ),
            icon = Icons.Rounded.Shield,
            accent = warmGold,
            samplePrompts = listOf(
                "Which songs are getting overplayed this week?",
                "Quarantine this track to the Vault",
                "Play a rested song from my Vault"
            )
        ),
        AppGuideTopic(
            section = TuneMindGuideSection.LIBRARY_CARDS,
            title = "Song Cards, Cross-App Library & Playlists",
            summary = "Every app you listen on merges cleanly into one permanent personal music memory.",
            bullets = listOf(
                "Unified Song Cards: Tap any song to see verified play count, skip count, relationship stage (e.g. Heavy Repeat vs. Fresh Discovery), first/last played dates, and sonic traits.",
                "Player Tabs: Browse All Music, Spotify, Boomplay, Audiomack, YouTube Music, Phone Audio, Liked tracks, and TuneMind Playlists.",
                "In-App Playlists: Build playlists manually or ask TuneMind by voice (\"Add this song to my workout playlist\")."
            ),
            icon = Icons.Rounded.LibraryMusic,
            accent = electricPurple,
            samplePrompts = listOf(
                "Add this song to a new playlist",
                "Open my Cross-App Library screen",
                "Show my TuneMind playlists"
            )
        ),
        AppGuideTopic(
            section = TuneMindGuideSection.HEARING_TRIAL,
            title = "Hearing Health & 7-Day Free Trial",
            summary = "Monitor safe headphone volume and understand your TuneMind Pro trial.",
            bullets = listOf(
                "Safe Listening Headroom: Estimates live output dBA for Bluetooth earbuds, wired headphones, or speakers and alerts you when approaching daily limits.",
                "7-Day Free Trial: New listeners get 7 days of full TuneMind Pro access (Voice Companion, Song Cards, Vault & Discovery). Note that your TuneMind trial is separate from Spotify Premium.",
                "Need Help?: Visit Settings -> Help & Send Feedback anytime to send diagnostics or suggestions directly."
            ),
            icon = Icons.Rounded.Hearing,
            accent = safeGreen,
            samplePrompts = listOf(
                "Check my safe listening headroom right now",
                "Give me my weekly listening recap"
            )
        )
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF130B22),
        contentColor = Color(0xFFF8FAFC),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.24f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 22.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TuneMind Guide",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "Quick guide to every feature — tap any prompt to try it live.",
                        fontSize = 12.5.sp,
                        color = Color.White.copy(alpha = 0.64f)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close Guide",
                        tint = Color.White.copy(alpha = 0.72f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick section jump strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TuneMindGuideSection.entries.forEach { sec ->
                    val active = sec == selectedSection
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (active) neonCyan.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, if (active) neonCyan else Color.White.copy(alpha = 0.14f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { selectedSection = sec }
                    ) {
                        Text(
                            text = sec.label,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) neonCyan else Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                topics.forEach { topic ->
                    val expanded = topic.section == selectedSection
                    SoftGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        accentTint = topic.accent,
                        cornerRadius = 18.dp,
                        contentPadding = PaddingValues(15.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSection = topic.section },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(topic.accent.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = topic.icon,
                                        contentDescription = null,
                                        tint = topic.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = topic.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF8FAFC)
                                    )
                                    Text(
                                        text = topic.summary,
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.64f),
                                        lineHeight = 16.sp
                                    )
                                }
                                Icon(
                                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.62f)
                                )
                            }

                            AnimatedVisibility(visible = expanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StudioHairlineDivider()
                                    topic.bullets.forEach { bullet ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 6.dp)
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(topic.accent)
                                            )
                                            Text(
                                                text = bullet,
                                                fontSize = 12.5.sp,
                                                color = Color.White.copy(alpha = 0.84f),
                                                lineHeight = 18.sp
                                            )
                                        }
                                    }

                                    if (topic.samplePrompts.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "TRY SAYING",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            color = topic.accent
                                        )
                                        topic.samplePrompts.forEach { prompt ->
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = topic.accent.copy(alpha = 0.11f),
                                                border = BorderStroke(1.dp, topic.accent.copy(alpha = 0.28f)),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        onDismiss()
                                                        onRunPrompt(prompt)
                                                    }
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "\"$prompt\"",
                                                        fontSize = 12.5.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = Color(0xFFF8FAFC),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Icon(
                                                        imageVector = Icons.Rounded.ChevronRight,
                                                        contentDescription = "Try prompt",
                                                        tint = topic.accent,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Quick actions footer for Settings & Replay Welcome Tour
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (onReplayWelcomeTour != null) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onReplayWelcomeTour()
                            },
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, neonCyan.copy(alpha = 0.45f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Replay Welcome Tour", color = neonCyan, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenSettings()
                        },
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Open Settings", color = Color.White.copy(alpha = 0.88f), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
