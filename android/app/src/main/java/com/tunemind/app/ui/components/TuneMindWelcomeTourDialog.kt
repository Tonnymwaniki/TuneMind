package com.tunemind.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import kotlinx.coroutines.launch

private data class WelcomeTourSlide(
    val title: String,
    val badge: String,
    val body: String,
    val highlight: String,
    val icon: ImageVector,
    val accent: Color
)

/**
 * 4-card interactive welcome walkthrough shown once to new Play Store testers
 * right after setup so every screen, Vault cooldown, and player rule makes instant sense.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TuneMindWelcomeTourDialog(
    onFinish: () -> Unit,
    onOpenFullGuide: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val scope = rememberCoroutineScope()

    val slides = listOf(
        WelcomeTourSlide(
            title = "Every App, One Library",
            badge = "STEP 1 OF 4 · TRACKING",
            body = "TuneMind automatically logs plays across Spotify, Boomplay, Audiomack, YouTube Music, and Phone Audio into unified Song Cards with real play counts.",
            highlight = "Tap any song anywhere to open its Song Card & listening relationship.",
            icon = Icons.Rounded.LibraryMusic,
            accent = theme.primaryAccent
        ),
        WelcomeTourSlide(
            title = "Never Overplay a Favorite",
            badge = "STEP 2 OF 4 · FRESH & VAULT",
            body = "Repeating a song too often drops its Freshness %. TuneMind rests fatigued songs inside your Resting Vault until their spark returns.",
            highlight = "Visit the Fresh & Resting tab anytime to check readiness or restore a song.",
            icon = Icons.Rounded.Shield,
            accent = Color(0xFFF59E0B)
        ),
        WelcomeTourSlide(
            title = "Talk to TuneMind Hands-Free",
            badge = "STEP 3 OF 4 · VOICE AI",
            body = "Tap the orb or say \"Hi TuneMind\" to ask about your stats, Fresh songs, For You picks, or playlists. TuneMind always asks \"Should I play…?\" before playing.",
            highlight = "Say \"Yes\" to play or \"No\" to pick something else — you're always in charge.",
            icon = Icons.Rounded.GraphicEq,
            accent = Color(0xFF38BDF8)
        ),
        WelcomeTourSlide(
            title = "Smart Playback & 7-Day Trial",
            badge = "STEP 4 OF 4 · PLAYBACK",
            body = "Spotify Premium plays silently without leaving TuneMind. Spotify Free, Boomplay & Audiomack open their own app to play. YouTube Music uses TuneMind's built-in YouTube player.",
            highlight = "New accounts include a 7-day TuneMind Pro Free Trial. Tap ? anytime for full guides.",
            icon = Icons.Rounded.AutoAwesome,
            accent = Color(0xFF10B981)
        )
    )

    val pagerState = rememberPagerState(pageCount = { slides.size })
    val isLast = pagerState.currentPage == slides.lastIndex

    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFF110A20),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Welcome to TuneMind",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                    TextButton(onClick = onFinish) {
                        Text(
                            text = "Skip",
                            color = Color.White.copy(alpha = 0.64f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(310.dp)
                ) { page ->
                    val slide = slides[page]
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(74.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            slide.accent.copy(alpha = 0.30f),
                                            slide.accent.copy(alpha = 0.08f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = slide.icon,
                                contentDescription = slide.title,
                                tint = slide.accent,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = slide.badge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                            color = slide.accent
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = slide.title,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFAFAFA),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = slide.body,
                            fontSize = 13.5.sp,
                            color = Color.White.copy(alpha = 0.78f),
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = slide.accent.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, slide.accent.copy(alpha = 0.30f))
                        ) {
                            Text(
                                text = slide.highlight,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFF8FAFC),
                                lineHeight = 17.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Page dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(slides.size) { index ->
                        val active = pagerState.currentPage == index
                        val dotColor = if (active) slides[index].accent else Color.White.copy(alpha = 0.24f)
                        Box(
                            modifier = Modifier
                                .height(7.dp)
                                .width(if (active) 22.dp else 7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pagerState.currentPage > 0) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Back", color = Color.White.copy(alpha = 0.84f), fontSize = 14.sp)
                        }
                    } else {
                        TextButton(
                            onClick = {
                                onFinish()
                                onOpenFullGuide()
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Full Guide", color = theme.primaryAccent, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Button(
                        onClick = {
                            if (isLast) {
                                onFinish()
                            } else {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = slides[pagerState.currentPage].accent,
                            contentColor = Color(0xFF061018)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = if (isLast) "Start Exploring" else "Next",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
