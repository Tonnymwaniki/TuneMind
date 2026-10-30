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
import androidx.compose.material.icons.outlined.*
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
import com.tunemind.app.model.TuneMindDemoData
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.VaultTrack
import com.tunemind.app.ui.components.SongProfileData
import com.tunemind.app.ui.components.SongProfileSheet
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongFatigueScreen(
    vm: TuneMindViewModel,
    onOpenCalibration: () -> Unit = {},
    onOpenCooldown: () -> Unit = {}
) {
    val historyState by vm.historyState.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) } // 0 = Watchlist, 1 = Cooldown Vault, 2 = Palate Cleansers
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var selectedProfileData by remember { mutableStateOf<SongProfileData?>(null) }

    // Real identified tracks from phone Room database
    val realCapturedTracks = historyState.tracks.filter { !it.title.isNullOrBlank() }
    val avgSatiety = if (realCapturedTracks.isNotEmpty()) {
        (realCapturedTracks.map { minOf(95, it.distinctAppearanceCount * 22) }.average()).toInt()
    } else 68

    val satietyDescriptor = when {
        avgSatiety >= 80 -> "High Satiety Saturation"
        avgSatiety >= 50 -> "Balanced Receptivity"
        else -> "High Auditory Potency"
    }

    val watchlistDisplayCount = realCapturedTracks.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)) // Deep slate-950
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF78350F).copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF78350F).copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = AccentAmber,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "SATIETY LAB",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = AccentAmber,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Fatigue & Cooldown Vault",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Button(
                        onClick = onOpenCooldown,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF020617), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add to Vault", color = Color(0xFF020617), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Text(
                    text = "Protects your favorite music from dopamine burnout. Mathematical decay formulas predict when repeated exposure desensitizes auditory receptors, guiding songs into recovery.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
            }
        }

        // Satiety Gauge Hero Card (Derived from real Room captured tracks)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "OVERALL LISTENER SATIETY",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B)
                    )
                    Text(
                        text = "$avgSatiety% • $satietyDescriptor",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (avgSatiety >= 80) AccentAmber else AccentCyan
                    )
                    Text(
                        text = "${vaultTracks.size} tracks resting in vault • Decay λ = 0.18/day",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { (avgSatiety / 100f).coerceIn(0.1f, 1f) },
                        modifier = Modifier.size(46.dp),
                        color = if (avgSatiety >= 80) AccentAmber else AccentCyan,
                        strokeWidth = 4.dp,
                        trackColor = Color(0xFF334155)
                    )
                    Text(
                        text = "$avgSatiety%",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Interactive Segmented Controls
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0F172A),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    0 to "Watchlist ($watchlistDisplayCount)",
                    1 to "In Vault (${vaultTracks.size})",
                    2 to "Cleansers (3)"
                ).forEach { (index, label) ->
                    val isSelected = selectedTab == index
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(0xFF1E293B) else Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) AccentCyan.copy(alpha = 0.4f) else Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = index }
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) AccentCyanBright else Color(0xFF94A3B8),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Notification Snackbar
        snackbarMessage?.let { msg ->
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(msg, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { snackbarMessage = null }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // Tab Content
        when (selectedTab) {
            0 -> {
                // High Velocity Watchlist (Combines Real Captured Plays + Candidates)
                if (realCapturedTracks.isNotEmpty()) {
                    realCapturedTracks.forEach { track ->
                        val satietyScore = minOf(95, track.distinctAppearanceCount * 22)
                        val isHighFatigue = track.distinctAppearanceCount >= 2
                        val velocityLabel = if (track.distinctAppearanceCount >= 3) "+0.84 Vel" else "+0.42 Vel"

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isHighFatigue) AccentAmber.copy(alpha = 0.5f) else Color(0xFF1E293B)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedProfileData = SongProfileData.fromTrackMemory(track)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isHighFatigue) Color(0xFF78350F).copy(alpha = 0.4f) else Color(0xFF1E293B)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isHighFatigue) Icons.Default.Speed else Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = if (isHighFatigue) AccentAmber else AccentCyan,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.title ?: "Unknown Title", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(
                                        "${track.artist ?: "Unknown Artist"} • ${track.distinctAppearanceCount} play(s)",
                                        fontSize = 12.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("$satietyScore% Satiety", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (isHighFatigue) AccentAmber else AccentCyan, fontWeight = FontWeight.Bold)
                                        Text("•", color = Color(0xFF475569))
                                        Text(velocityLabel, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (isHighFatigue) Color(0xFFF87171) else Color(0xFF38BDF8))
                                    }
                                }

                                Button(
                                    onClick = {
                                        vm.quarantineTrack(track.title ?: "Track", track.artist ?: "Artist", 5f)
                                        snackbarMessage = "Quarantined '${track.title}' to Cooldown Vault for 5 days."
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentAmber.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Outlined.AcUnit, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Quarantine", color = AccentAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(36.dp))
                            Text("No Satiety Fatigue Detected", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("As you listen to songs repeatedly on Spotify, TuneMind will automatically detect repeat loops and surface them here.", fontSize = 12.sp, color = Color(0xFF94A3B8), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }

            1 -> {
                // Persistent Cooldown Vault Tracks
                if (vaultTracks.isEmpty()) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.AcUnit, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(36.dp))
                            Text("Vault is Empty", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("No tracks currently resting. Quarantine tracks from the Watchlist to restore receptivity.", fontSize = 12.sp, color = Color(0xFF94A3B8), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                } else {
                    vaultTracks.forEach { track ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedProfileData = SongProfileData.fromVaultTrack(track)
                                }
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF064E3B).copy(alpha = 0.4f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Restore, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(22.dp))
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(track.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text(track.artist, fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF064E3B).copy(alpha = 0.4f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            "${track.cooldownDaysRemaining} days left",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentGreen,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                // Receptor Recovery Progress
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Receptor Recovery (e^-0.18t)", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                                        Text("${(track.recoveryProgress * 100).toInt()}% restored", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = AccentGreen)
                                    }
                                    LinearProgressIndicator(
                                        progress = { track.recoveryProgress.coerceIn(0.05f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                        color = AccentGreen,
                                        trackColor = Color(0xFF1E293B)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            vm.restoreFromVault(track.id)
                                            snackbarMessage = "Restored '${track.title}' from Vault early."
                                        }
                                    ) {
                                        Text("Restore Early", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                // Curated Palate Cleansers
                TuneMindDemoData.palateCleansers.forEach { cleanser ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedProfileData = SongProfileData(
                                    title = cleanser.title,
                                    artist = cleanser.artist,
                                    album = cleanser.genre,
                                    satietyScore = 20,
                                    estimatedPlaybackMs = 195_000L
                                )
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0891B2).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Spa, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(cleanser.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("${cleanser.artist} • ${cleanser.genre}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                Text(cleanser.fatigueCategory, fontSize = 11.sp, color = AccentCyanBright)
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF1E293B)
                            ) {
                                Text(
                                    "-0.45 Vel",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentCyan,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    selectedProfileData?.let { profile ->
        SongProfileSheet(
            profile = profile,
            vm = vm,
            onDismiss = { selectedProfileData = null }
        )
    }
}
