package com.tunemind.app.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.model.TuneMindDemoData
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@Composable
fun HomeScreen(
    vm: TuneMindViewModel,
    onOpenFatigue: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenWeeklyReport: () -> Unit,
    onOpenStudy: () -> Unit = {}
) {
    val state by vm.homeState.collectAsState()
    val scrollState = rememberScrollState()

    var isPlaying by remember { mutableStateOf(true) }
    var playbackProgress by remember { mutableStateOf(0.45f) }

    // Live playback ticker
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1000)
            playbackProgress = if (playbackProgress >= 1f) 0f else playbackProgress + 0.005f
        }
    }

    // Determine current track: prefer real observed track from phone, or fallback to demo
    val latestRealTrack = state.recentTracks.firstOrNull()
    val nowPlayingTitle = latestRealTrack?.title ?: TuneMindDemoData.currentNowPlaying.title
    val nowPlayingArtist = latestRealTrack?.artist ?: TuneMindDemoData.currentNowPlaying.artist
    val nowPlayingSource = latestRealTrack?.sourcePackage?.substringAfterLast(".")?.replaceFirstChar { it.uppercase() } ?: "Spotify"
    val isLiveObserved = latestRealTrack != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Live Audio Session Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AccentGreen)
                )
                Text(
                    text = if (isLiveObserved) "LIVE PHONE MEDIA SESSION" else "AUDIO SESSION OBSERVER",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyanBright,
                    letterSpacing = 1.sp
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Text(
                    text = nowPlayingSource,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Main Now Playing Card (Glowing Slate Deck)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Artwork / Audio Icon Box
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF0891B2), Color(0xFF4F46E5), Color(0xFF9333EA))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xCC020617))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "84 BPM",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AccentCyanBright,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Track Title & Artist
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nowPlayingTitle,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1
                        )
                        Text(
                            text = nowPlayingArtist,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF94A3B8),
                            maxLines = 1
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (isLiveObserved) "Real-time Captured Playback" else "Lo-Fi Atmosphere • Spotify",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                // Satiety and Fatigue Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0x26F59E0B),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4DF59E0B))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(13.dp))
                            Text("Vocal Fatigue", fontSize = 11.sp, color = AccentAmber, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0x2606B6D4),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4D06B6D4))
                    ) {
                        Text(
                            "Satiety: 58%",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AccentCyanBright,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            "${state.identifiableTrackCount} Tracks",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFCBD5E1),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Playback Seek Bar
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { playbackProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = AccentCyan,
                        trackColor = Color(0xFF1E293B)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1:38", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                        Text("3:36", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                    }
                }

                // Controls: Play, Skip, Cooldown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { isPlaying = !isPlaying },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF020617)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPlaying) "Pause" else "Play", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    FilledTonalButton(
                        onClick = { playbackProgress = 0f },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF1E293B), contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                    }

                    FilledTonalButton(
                        onClick = { onOpenFatigue() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF1E293B), contentColor = AccentCyanBright)
                    ) {
                        Icon(Icons.Outlined.AcUnit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cooldown", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Satiety Velocity & Vault Stats (2 Column Grid)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Critical Loops", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Spacer(Modifier.height(4.dp))
                    Text("2", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, color = AccentAmber)
                    Text("High saturation", fontSize = 10.sp, color = Color(0xFFF59E0B).copy(alpha = 0.8f))
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Resting in Vault", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Spacer(Modifier.height(4.dp))
                    Text("3 Tracks", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, color = AccentCyanBright)
                    Text("Cooldown active", fontSize = 10.sp, color = AccentCyan.copy(alpha = 0.8f))
                }
            }
        }

        // Auditory Anchor Snapshot Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Auditory Anchor Snapshot", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Continuously updated behavioral weights", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                    Text("7-Day Drift", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentCyanBright)
                }

                // Anchor 1: Groove & Rhythm
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Groove & Rhythm Anchor", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0))
                        Text("38%  (+6%)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentCyanBright, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { 0.38f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = Color(0xFF06B6D4),
                        trackColor = Color(0xFF1E293B)
                    )
                    Text("Rhythm, bass, tempo lock", fontSize = 10.sp, color = Color(0xFF64748B))
                }

                // Anchor 2: Vocal & Melody
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Vocal & Melody Anchor", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0))
                        Text("34%  (-7% reset)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = PrimaryVioletBright, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { 0.34f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = Color(0xFF8B5CF6),
                        trackColor = Color(0xFF1E293B)
                    )
                    Text("Vocals, harmony, linguistic load", fontSize = 10.sp, color = Color(0xFF64748B))
                }

                // Anchor 3: Dynamic & Structure
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Dynamic & Structure Anchor", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE2E8F0))
                        Text("28%  (+1% stable)", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AccentGreen, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { 0.28f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = Color(0xFF10B981),
                        trackColor = Color(0xFF1E293B)
                    )
                    Text("Build-ups, drops, frequency balance", fontSize = 10.sp, color = Color(0xFF64748B))
                }
            }
        }

        // Circadian Receptivity Windows Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Circadian Receptivity Windows", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)

                // Window 1
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF020617),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Morning Window (08:00 – 12:00)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Sweet Spot: 10:30 AM – 1:00 PM", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("92%", fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = AccentGreen)
                            Text("High Harmonic Tolerance", fontSize = 9.sp, color = AccentGreen)
                        }
                    }
                }

                // Window 2
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF020617),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Midday Drive (13:00 – 18:00)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("41% Habitual Loops Zone", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("68%", fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = AccentCyanBright)
                            Text("Groove & Dynamic", fontSize = 9.sp, color = AccentCyanBright)
                        }
                    }
                }

                // Window 3 (Fatigue Warning)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x1AF59E0B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33F59E0B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Late Night (> 22:00)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Low BPM Ambient Advised", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("24%", fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = AccentAmber)
                            Text("Treble Clashing Alert", fontSize = 9.sp, color = AccentAmber)
                        }
                    }
                }
            }
        }

        // Recent Real Music Activity from Room Database
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Observed Playback History",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    "${state.recentTracks.size} captured",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AccentCyanBright
                )
            }

            if (state.recentTracks.isNotEmpty()) {
                state.recentTracks.take(4).forEach { track ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0F172A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1E293B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title ?: "Observed Track", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text(track.artist ?: "Unknown artist", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF020617),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                            ) {
                                Text(
                                    track.sourcePackage?.substringAfterLast(".") ?: "media",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF38BDF8),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0F172A),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Radio, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(28.dp))
                        Text("Awaiting Media Callbacks", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "Play any track in Spotify, YouTube Music, or Apple Music. TuneMind will observe it automatically.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        // Quick Portal Cards (Fatigue, Study, Cooldown)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF0F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f).clickable { onOpenFatigue() }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Icon(Icons.Outlined.Speed, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("Song Fatigue", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Satiety Index", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF0F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f).clickable { onOpenStudy() }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Icon(Icons.Outlined.DateRange, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("7-Day Study", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Protocol v1.0", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }
        }
    }
}
