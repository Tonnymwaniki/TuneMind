package com.tunemind.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.domain.model.TrackMemoryEntry
import com.tunemind.app.ui.HearingInsightsState
import com.tunemind.app.ui.components.NeuralSonicActionButton
import com.tunemind.app.ui.components.NeuralTrackActionDialog
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.bouncyClickable
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.ui.theme.ThemeBehavior
import com.tunemind.app.ui.theme.ThemePreferences
import java.util.Calendar

private data class CircadianPhase(
    val id: Int,
    val paletteId: String,
    val label: String,
    val hoursLabel: String,
    val targetBpm: Int,
    val color: Color,
    val startAngle: Float,
    val sweepAngle: Float
)

@Composable
fun RhythmDnaScreen(
    recentTracks: List<TrackMemoryEntry>,
    hearingInsights: HearingInsightsState,
    selectedTargetPlayer: String,
    themePreferences: ThemePreferences,
    onUpdateThemePreferences: (ThemePreferences) -> Unit,
    onSelectTargetPlayer: (String) -> Unit,
    onPlayTrack: (title: String, artist: String, spotifyUri: String?, sourcePackage: String) -> Unit,
    onAskAgentAboutTrack: (title: String, artist: String, freshnessPercent: Int, spotifyUri: String?) -> Unit,
    onSyncAtmosphereToTrack: (title: String, artist: String) -> Unit,
    onQuarantineTrack: (title: String, artist: String) -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val context = LocalContext.current
    val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }

    val phases = remember {
        listOf(
            CircadianPhase(0, "morning", "MORNING FOCUS", "06:00 – 12:00", 112, Color(0xFF00E5FF), -90f, 82f),
            CircadianPhase(1, "afternoon", "MIDDAY DRIVE", "12:00 – 17:00", 118, Color(0xFF10B981), 0f, 82f),
            CircadianPhase(2, "evening", "EVENING UNWIND", "17:00 – 22:00", 98, Color(0xFFFFB000), 90f, 82f),
            CircadianPhase(3, "night", "LATE-NIGHT FLOW", "22:00 – 06:00", 86, Color(0xFFA855F7), 180f, 82f)
        )
    }

    val livePhaseIndex = when (currentHour) {
        in 6..11 -> 0
        in 12..16 -> 1
        in 17..21 -> 2
        else -> 3
    }

    var selectedPhaseIndex by remember { mutableStateOf(livePhaseIndex) }
    val activePhase = phases[selectedPhaseIndex]

    var activeSyncTrack by remember { mutableStateOf<TrackMemoryEntry?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    var serverUrlInput by remember {
        mutableStateOf(com.tunemind.app.domain.network.BackendEndpointManager.getCustomBaseUrl(context))
    }
    var connectionLabel by remember {
        mutableStateOf(com.tunemind.app.domain.network.BackendEndpointManager.getDisplayConnectionLabel(context))
    }

    val powerTracks = remember(recentTracks, selectedPhaseIndex) {
        val unique = recentTracks
            .filter { !it.title.isNullOrBlank() }
            .distinctBy { "${it.title?.trim()?.lowercase()}|${it.artist?.trim()?.lowercase()}" }
        if (unique.size <= 5) unique
        else {
            val offset = (selectedPhaseIndex * 2) % maxOf(1, unique.size)
            (unique.drop(offset) + unique.take(offset)).take(5)
        }
    }

    val topArtists = remember(recentTracks) {
        recentTracks
            .mapNotNull { it.artist?.trim()?.takeIf { a -> a.isNotBlank() && !a.equals("Unknown Artist", ignoreCase = true) } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(4)
    }

    val dbValue = hearingInsights.estimatedAverageDb.toInt().coerceIn(45, 95)
    val audioRouteLabel = hearingInsights.activeAudioDevice?.deviceName?.takeIf { it.isNotBlank() }
        ?: "Phone / Wireless Audio"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        theme.primaryBackground,
                        theme.secondaryBackground,
                        theme.recessedBackground
                    )
                )
            )
    ) {
        // Ambient Adaptive Glow Orb
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-30).dp, y = (-20).dp)
                .size(220.dp)
                .blur(75.dp)
                .background(theme.primaryAccent.copy(alpha = 0.20f), CircleShape)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 108.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RHYTHM DNA",
                            color = theme.primaryAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Circadian Sonic Flow",
                            color = theme.primaryText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(theme.primaryAccent.copy(alpha = 0.14f))
                            .border(1.dp, theme.primaryAccent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            .bouncyClickable {
                                serverUrlInput = com.tunemind.app.domain.network.BackendEndpointManager.getCustomBaseUrl(context)
                                showServerDialog = true
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "☁️ CLOUD BRAIN",
                            color = theme.primaryAccent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            // Spotify Web API OAuth, Account Identity & 15-Minute Local-First Room Sync Card
            item {
                val spotifyRepo = remember {
                    com.tunemind.app.data.ingestion.SpotifyOAuthRepository.getInstance(context)
                }
                val isSpotifyConnected by spotifyRepo.isConnectedFlow.collectAsState()
                val spotifyAuthManager = remember {
                    com.tunemind.app.data.ingestion.SpotifyAuthManager(context)
                }
                val authSessionManager = remember {
                    com.tunemind.app.data.auth.TuneMindAuthSessionManager.getInstance(context)
                }
                val authSession by authSessionManager.sessionFlow.collectAsState()

                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "SPOTIFY WEB API & ACCOUNT",
                                    color = Color(0xFF1DB954),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isSpotifyConnected || authSession.isSpotifyConnected) {
                                        "Spotify Connected • 15m WorkManager + Room Sync"
                                    } else {
                                        "Bypass Scrobbling • Direct OAuth 2.0 Web API Sync"
                                    },
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (authSession.email.isNotBlank() || authSession.displayName.isNotBlank()) {
                                    Text(
                                        text = "${authSession.displayName}${if (authSession.email.isNotBlank()) " • ${authSession.email}" else ""}",
                                        color = Color.White.copy(alpha = 0.65f),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSpotifyConnected || authSession.isSpotifyConnected) Color(0xFF1DB954).copy(alpha = 0.22f)
                                            else Color(0xFF1DB954)
                                        )
                                        .border(
                                            1.dp,
                                            Color(0xFF1DB954).copy(alpha = 0.7f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .bouncyClickable {
                                            if (isSpotifyConnected || authSession.isSpotifyConnected) {
                                                com.tunemind.app.data.ingestion.SpotifyRecentlyPlayedSyncWorker
                                                    .enqueueImmediateOnAudioFocusChange(
                                                        context = context,
                                                        reason = "MANUAL_UI_SYNC",
                                                        bypassDebounce = true
                                                    )
                                            } else {
                                                spotifyAuthManager.launchSpotifyAuth(context)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = if (isSpotifyConnected || authSession.isSpotifyConnected) "SYNC NOW" else "LOGIN SPOTIFY",
                                        color = if (isSpotifyConnected || authSession.isSpotifyConnected) Color(0xFF1DB954) else Color.Black,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .border(
                                            1.dp,
                                            Color.White.copy(alpha = 0.2f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .bouncyClickable {
                                            authSessionManager.signOut()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "SIGN OUT",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Live Adaptive Atmosphere & Mood Switcher Card
            item {
                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "✦ ADAPTIVE ATMOSPHERE ENGINE",
                                    color = theme.primaryAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp
                                )
                                Text(
                                    text = "${theme.activePalette.name} • ${theme.musicMood.label}",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            val isAuto = themePreferences.behavior == ThemeBehavior.ADAPTIVE
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isAuto) Color(0xFF10B981).copy(alpha = 0.20f)
                                        else Color.White.copy(alpha = 0.06f)
                                    )
                                    .border(
                                        1.dp,
                                        if (isAuto) Color(0xFF10B981).copy(alpha = 0.6f)
                                        else Color.White.copy(alpha = 0.15f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .bouncyClickable {
                                        onUpdateThemePreferences(
                                            themePreferences.copy(
                                                behavior = ThemeBehavior.ADAPTIVE,
                                                enableMoodAdaptation = true,
                                                enableEnergyAdaptation = true
                                            )
                                        )
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isAuto) "AUTO ON" else "SET AUTO",
                                    color = if (isAuto) Color(0xFF10B981) else Color.White.copy(alpha = 0.75f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 4 Distinct Atmosphere Theme Presets (Tap any to see the entire app transform!)
                        val atmospherePresets = listOf(
                            Triple("morning", "Dawn Cyan", Color(0xFF00E5FF)),
                            Triple("afternoon", "Emerald", Color(0xFF10B981)),
                            Triple("evening", "Solar Amber", Color(0xFFFFB000)),
                            Triple("night", "Violet Night", Color(0xFFA855F7))
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            atmospherePresets.forEach { (id, label, color) ->
                                val isSelected = theme.activePalette.id == id && themePreferences.behavior == ThemeBehavior.FIXED
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 40.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isSelected) color.copy(alpha = 0.24f)
                                            else color.copy(alpha = 0.08f)
                                        )
                                        .border(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            color = if (isSelected) color else color.copy(alpha = 0.30f),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .bouncyClickable {
                                            onUpdateThemePreferences(
                                                themePreferences.copy(
                                                    behavior = ThemeBehavior.FIXED,
                                                    fixedPaletteId = id
                                                )
                                            )
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.White else color,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 24-Hour Circadian Sonic Ring Card
            item {
                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(164.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                phases.forEachIndexed { index, phase ->
                                    val isSelected = index == selectedPhaseIndex
                                    drawArc(
                                        color = phase.color.copy(alpha = if (isSelected) 0.95f else 0.22f),
                                        startAngle = phase.startAngle,
                                        sweepAngle = phase.sweepAngle,
                                        useCenter = false,
                                        style = Stroke(
                                            width = if (isSelected) 12.dp.toPx() else 7.dp.toPx(),
                                            cap = StrokeCap.Round
                                        )
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = activePhase.label,
                                    color = activePhase.color,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${activePhase.targetBpm} BPM",
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${activePhase.hoursLabel} • $dbValue dB",
                                    color = theme.secondaryText,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 4 Interactive Time-Window Selector Pills (Also shifts atmosphere if desired)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            phases.forEachIndexed { index, phase ->
                                val isSelected = index == selectedPhaseIndex
                                val shortTitle = when (index) {
                                    0 -> "MORNING"
                                    1 -> "MIDDAY"
                                    2 -> "EVENING"
                                    else -> "NIGHT"
                                }
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 40.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isSelected) phase.color.copy(alpha = 0.20f)
                                            else Color.White.copy(alpha = 0.04f)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) phase.color.copy(alpha = 0.55f)
                                                    else Color.White.copy(alpha = 0.08f),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .bouncyClickable {
                                            selectedPhaseIndex = index
                                            onUpdateThemePreferences(
                                                themePreferences.copy(
                                                    behavior = ThemeBehavior.FIXED,
                                                    fixedPaletteId = phase.paletteId
                                                )
                                            )
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = shortTitle,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp
                                    )
                                }
                            }
                        }

                        if (topArtists.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "DNA:",
                                    color = theme.secondaryText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                topArtists.take(3).forEach { artist ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(theme.primaryAccent.copy(alpha = 0.12f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = artist,
                                            color = Color.White.copy(alpha = 0.90f),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section Header: Power Rotation
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔥 POWER ROTATION • ${activePhase.label}",
                        color = theme.primaryText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Text(
                        text = "TAP ▶ TO PLAY",
                        color = activePhase.color,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Scroll-Safe Power Rotation Song Rows (Only ▶ Play button plays; SYNC opens Neural Sheet)
            itemsIndexed(powerTracks) { _, track ->
                val title = track.title?.trim() ?: "Unknown Track"
                val artist = track.artist?.trim() ?: "Unknown Artist"
                val uri = track.providerTrackId?.takeIf { it.startsWith("spotify:track:") }
                val pkg = selectedTargetPlayer.ifBlank {
                    track.sourcePackage?.ifBlank { "com.spotify.music" } ?: "com.spotify.music"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(theme.cardSurface.copy(alpha = 0.62f))
                        .border(1.dp, theme.border.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Dedicated 46dp Play Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(activePhase.color.copy(alpha = 0.18f))
                            .border(1.dp, activePhase.color.copy(alpha = 0.55f), CircleShape)
                            .bouncyClickable {
                                onPlayTrack(title, artist, uri, pkg)
                            }
                    ) {
                        Text(
                            text = "▶",
                            color = activePhase.color,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = artist,
                            color = theme.secondaryText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Upgraded Neural Sonic Action Button
                    NeuralSonicActionButton(
                        accentColor = activePhase.color,
                        secondaryColor = theme.secondaryAccent,
                        onClick = { activeSyncTrack = track }
                    )
                }
            }

            // Output & Target Player Selector Card
            item {
                SoftGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🎧 TARGET PLAYER & AUDIO",
                                color = theme.primaryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "$audioRouteLabel • $dbValue dB Safe",
                                color = Color(0xFF10B981),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val playerOptions = listOf(
                            "com.spotify.music" to "Spotify",
                            "com.google.android.apps.youtube.music" to "YouTube Music",
                            "com.apple.android.music" to "Apple Music"
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            playerOptions.forEach { (pkg, label) ->
                                val isSelected = selectedTargetPlayer == pkg
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 44.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isSelected) theme.primaryAccent.copy(alpha = 0.20f)
                                            else Color.White.copy(alpha = 0.04f)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) theme.primaryAccent.copy(alpha = 0.65f)
                                                    else Color.White.copy(alpha = 0.08f),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .bouncyClickable { onSelectTargetPlayer(pkg) }
                                        .padding(vertical = 10.dp, horizontal = 6.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.58f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Text(
                            text = connectionLabel,
                            color = theme.primaryAccent.copy(alpha = 0.8f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }

    // Neural Track Action Sheet when tapping SYNC on a Power Rotation song
    activeSyncTrack?.let { selected ->
        val title = selected.title?.trim() ?: "Unknown Track"
        val artist = selected.artist?.trim() ?: "Unknown Artist"
        val plays = maxOf(1, selected.distinctAppearanceCount)
        val freshness = (100 - minOf(94, plays * 19)).coerceIn(15, 98)
        val uri = selected.providerTrackId?.takeIf { it.startsWith("spotify:track:") }
        val pkg = selectedTargetPlayer.ifBlank {
            selected.sourcePackage?.ifBlank { "com.spotify.music" } ?: "com.spotify.music"
        }

        NeuralTrackActionDialog(
            title = title,
            artist = artist,
            freshnessPercent = freshness,
            playCount = plays,
            isCoolingDown = plays >= 4,
            onDismiss = { activeSyncTrack = null },
            onPlayNow = {
                onPlayTrack(title, artist, uri, pkg)
            },
            onAgentNeuralBreakdown = {
                onAskAgentAboutTrack(title, artist, freshness, uri)
            },
            onSyncAtmosphereToTrack = {
                onSyncAtmosphereToTrack(title, artist)
            },
            onToggleCooldownVault = {
                onQuarantineTrack(title, artist)
            }
        )
    }

    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            containerColor = theme.elevatedSurface,
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.85f),
            title = {
                Text("Wireless / Cloud Brain URL", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Connected to your 24/7 Cloud Brain by default. Override only if testing a custom server:",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        placeholder = {
                            Text(
                                "https://tunemind-backend-iudp.onrender.com",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = theme.primaryAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        com.tunemind.app.domain.network.BackendEndpointManager.setCustomBaseUrl(context, serverUrlInput)
                        connectionLabel = com.tunemind.app.domain.network.BackendEndpointManager.getDisplayConnectionLabel(context)
                        showServerDialog = false
                    }
                ) {
                    Text("Save URL", color = theme.primaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }
}
