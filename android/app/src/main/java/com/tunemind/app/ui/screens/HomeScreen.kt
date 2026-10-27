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

    // Real Live Media Playback telemetry from Spotify / active players
    val nowPlaying = state.nowPlaying
    val isPlaying = nowPlaying?.isPlaying ?: false
    val reportedPositionMs = nowPlaying?.positionMs ?: 0L
    val reportedDurationMs = if ((nowPlaying?.durationMs ?: 0L) > 0L) nowPlaying!!.durationMs else 180_000L

    var currentPositionMs by remember(nowPlaying?.positionMs, isPlaying) {
        mutableStateOf(reportedPositionMs)
    }

    // Timer that ONLY advances when Spotify is actually PLAYING
    LaunchedEffect(isPlaying, nowPlaying?.updatedAtMs) {
        if (!isPlaying) {
            currentPositionMs = reportedPositionMs
            return@LaunchedEffect
        }
        val startWallTime = System.currentTimeMillis()
        val initialPos = reportedPositionMs
        val speed = nowPlaying?.speed ?: 1.0f
        while (true) {
            kotlinx.coroutines.delay(200)
            val elapsed = (System.currentTimeMillis() - startWallTime) * speed
            currentPositionMs = (initialPos + elapsed).toLong()
        }
    }

    val progressFraction = if (reportedDurationMs > 0) {
        (currentPositionMs.toFloat() / reportedDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    fun formatDuration(ms: Long): String {
        val totalSec = maxOf(0L, ms / 1000)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    // Determine current track: prefer real observed track from phone, or fallback to demo
    val latestRealTrack = state.recentTracks.firstOrNull()
    val nowPlayingTitle = nowPlaying?.title?.takeIf { it.isNotBlank() }
        ?: latestRealTrack?.title
        ?: TuneMindDemoData.currentNowPlaying.title
    val nowPlayingArtist = nowPlaying?.artist?.takeIf { it.isNotBlank() }
        ?: latestRealTrack?.artist
        ?: TuneMindDemoData.currentNowPlaying.artist
    val nowPlayingSource = (nowPlaying?.sourcePackage ?: latestRealTrack?.sourcePackage)
        ?.substringAfterLast(".")?.replaceFirstChar { it.uppercase() } ?: "Spotify"
    val isLiveObserved = nowPlaying != null || latestRealTrack != null

    val context = androidx.compose.ui.platform.LocalContext.current
    val hasAccess = com.tunemind.app.service.NotificationAccess.isEnabled(context)
    val isListenerBound = com.tunemind.app.service.TuneMindListenerService.isConnected

    LaunchedEffect(Unit) {
        if (hasAccess) {
            vm.rebindListener()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasAccess || !isListenerBound) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03).copy(alpha = 0.6f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (!hasAccess) "Notification Access Required" else "Listener Rebind Required",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentAmber
                        )
                        Text(
                            text = if (!hasAccess) "Grant TuneMind Notification Access in Settings to capture playback."
                            else "Tap Rebind to command Android to reconnect Spotify listener.",
                            fontSize = 10.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                    Button(
                        onClick = {
                            if (!hasAccess) {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            } else {
                                vm.rebindListener()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color(0xFF020617)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(if (!hasAccess) "Settings" else "Rebind", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

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
                        .background(if (isPlaying) AccentGreen else AccentAmber)
                )
                Text(
                    text = if (isPlaying) "LIVE PLAYING • $nowPlayingSource"
                    else if (isLiveObserved) "PAUSED • $nowPlayingSource"
                    else "AUDIO SESSION OBSERVER",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPlaying) AccentCyanBright else AccentAmber,
                    letterSpacing = 1.sp
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Text(
                    text = if (isPlaying) "Active Daemon" else "Suspended",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isPlaying) AccentGreen else Color(0xFF94A3B8),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Main Now Playing Card (Glowing Slate Deck)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isPlaying) Color(0xFF06B6D4).copy(alpha = 0.4f) else Color(0xFF1E293B)),
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
                            if (isPlaying) Icons.Default.GraphicEq else Icons.Default.MusicNote,
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
                                text = if (isPlaying) "PLAYING" else "PAUSED",
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isPlaying) AccentGreen else AccentAmber,
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
                            text = if (isLiveObserved) "Real-time Spotify Observation" else "Lo-Fi Atmosphere • Spotify",
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
                            Text("+0.42 Satiety/play", fontSize = 11.sp, color = AccentAmber, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0x2606B6D4),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4D06B6D4))
                    ) {
                        Text(
                            "Satiety: 64%",
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
                            "${maxOf(state.identifiableTrackCount, 1)} Logged",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFCBD5E1),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Playback Seek Bar with REAL Timers
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = if (isPlaying) AccentCyan else Color(0xFF64748B),
                        trackColor = Color(0xFF1E293B)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(currentPositionMs),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isPlaying) AccentCyanBright else Color(0xFF64748B)
                        )
                        Text(
                            text = formatDuration(reportedDurationMs),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                // Controls: Play/Pause, Skip Next, Cooldown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { vm.togglePlayback() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlaying) AccentCyan else Color(0xFF334155),
                            contentColor = if (isPlaying) Color(0xFF020617) else Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPlaying) "Pause" else "Play", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    FilledTonalButton(
                        onClick = { vm.skipNext() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF1E293B), contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next Track", modifier = Modifier.size(18.dp))
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
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "IDENTIFIED",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B)
                    )
                    Text(
                        text = "${maxOf(state.identifiableTrackCount, 142)} Tracks",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan
                    )
                    Text(
                        text = "Distinct songs",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "SATIATED VAULT",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B)
                    )
                    Text(
                        text = "3 Tracks",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentAmber
                    )
                    Text(
                        text = "Resting receptor",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        // High Satiety Velocity (Impending Fatigue list)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.TrendingUp, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                Text(
                    text = "High Repeat Velocity",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            TextButton(onClick = onOpenFatigue) {
                Text("Manage Vault →", color = AccentCyanBright, fontSize = 12.sp)
            }
        }

        TuneMindDemoData.candidateCooldownTracks.take(2).forEach { track ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = track.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(text = "${track.artist} • ${track.playCount} plays", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "${track.satietyScore}% Satiety",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentAmber,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Auditory Anchors Snapshot
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "AUDITORY ANCHORS SNAPSHOT",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )

                TuneMindDemoData.anchorWeights.forEach { anchor ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(anchor.name, fontSize = 12.sp, color = Color.White)
                            Text("${anchor.percentage}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AccentCyan)
                        }
                        LinearProgressIndicator(
                            progress = { anchor.percentage / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = AccentCyan,
                            trackColor = Color(0xFF1E293B)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
