package com.tunemind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.VaultTrack
import com.tunemind.app.ui.components.SongProfileData
import com.tunemind.app.ui.components.SongProfileSheet
import com.tunemind.app.ui.theme.*

@Composable
fun SongFatigueScreen(
    vm: TuneMindViewModel,
    onOpenCalibration: () -> Unit = {},
    onOpenCooldown: () -> Unit = {}
) {
    val historyState by vm.historyState.collectAsState()
    val vaultTracks by vm.vaultState.collectAsState()
    val colors = TuneMindTheme.colors
    var selectedProfileData by remember { mutableStateOf<SongProfileData?>(null) }
    var isAutoReviveActive by remember { mutableStateOf(true) }

    // Real captured tracks from Room database
    val realCapturedTracks = historyState.tracks.filter { !it.title.isNullOrBlank() }
    val avgSatiety = if (realCapturedTracks.isNotEmpty()) {
        (realCapturedTracks.map { minOf(95, it.distinctAppearanceCount * 22) }.average()).toInt()
    } else 64

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.primaryBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. TOP TELEMETRY STATUS RAIL
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.secondaryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF83B9BD))
                    )
                    Text(
                        text = "CRYOGENIC COOLDOWN PROTOCOL",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF83B9BD),
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = "VAULT // 48kHz ISO",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText
                )
            }
        }

        // Section Title & Intent
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "SATIETY LAB // AUDITORY REST",
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "Preventing melodic burnout by quarantining overplayed tracks into cryogenic cooldown.",
                fontSize = 12.sp,
                color = colors.secondaryText,
                lineHeight = 17.sp
            )
        }

        // 2. CENTERPIECE: TACHOMETER GAUGE MODULE (Integrated Recessed Well)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.secondaryBackground)
                .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Simulated Precision Tachometer Gauge Arc
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer Track Ring Arc Simulation
                Box(
                    modifier = Modifier
                        .size(200.dp, 100.dp)
                        .clip(RoundedCornerShape(topStart = 100.dp, topEnd = 100.dp))
                        .background(
                            Brush.sweepGradient(
                                listOf(
                                    Color(0xFF83B9BD),
                                    colors.primaryAccent,
                                    Color(0xFFCF7778),
                                    Color(0xFF83B9BD)
                                )
                            )
                        )
                        .padding(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 92.dp, topEnd = 92.dp))
                            .background(colors.cardSurface)
                    )
                }

                // Needle & Center Hub Anchor
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF83B9BD))
                            .border(2.dp, colors.primaryBackground, CircleShape)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "BURNOUT VELOCITY: LOW",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "STABLE EQUILIBRIUM",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF83B9BD)
                    )
                }
            }

            // Telemetry Data Strip
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors.primaryBackground,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RECOVERY QUEUE",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondaryText
                        )
                        Text(
                            text = "${vaultTracks.size} Tracks • 4.2d Avg",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .width(1.dp)
                            .background(colors.border)
                    )

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "DELTA FATIGUE",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondaryText
                        )
                        Text(
                            text = "-14.2% [1.1x]",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF83B9BD)
                        )
                    }
                }
            }
        }

        // 3. QUARANTINED VAULT CARTRIDGES RAIL
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "QUARANTINED CARTRIDGES",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF83B9BD),
                        letterSpacing = 1.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = colors.cardSurface
                    ) {
                        Text(
                            text = "${vaultTracks.size} ACTIVE",
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onOpenCooldown() }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = colors.primaryAccent, modifier = Modifier.size(14.dp))
                    Text(
                        text = "ADD TRACK",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryAccent
                    )
                }
            }

            if (vaultTracks.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.secondaryBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Outlined.AcUnit, contentDescription = null, tint = Color(0xFF83B9BD), modifier = Modifier.size(24.dp))
                        Text(
                            text = "CRYO-CHAMBER EMPTY",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.primaryText
                        )
                        Text(
                            text = "Add songs that you have been replaying too frequently to let dopamine receptors reset.",
                            fontSize = 11.sp,
                            color = colors.secondaryText
                        )
                    }
                }
            } else {
                vaultTracks.forEach { track ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.secondaryBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.primaryBackground)
                                        .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Outlined.AcUnit,
                                        contentDescription = null,
                                        tint = Color(0xFF83B9BD),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = track.title,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.primaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        fontSize = 11.sp,
                                        color = colors.secondaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = colors.cardSurface
                                        ) {
                                            Text(
                                                text = "${track.daysRemaining}d RECOVERY REMAINING",
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.secondaryAccent,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            IconButton(
                                onClick = { vm.releaseFromVault(track.title) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.cardSurface)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Release", tint = colors.secondaryText, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
