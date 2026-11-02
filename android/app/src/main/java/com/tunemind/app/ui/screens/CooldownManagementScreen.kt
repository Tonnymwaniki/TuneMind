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
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.SongProfileData
import com.tunemind.app.ui.components.SongProfileSheet
import com.tunemind.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CooldownManagementScreen(
    vm: TuneMindViewModel,
    onBack: () -> Unit = {},
    onAddTrack: () -> Unit = {}
) {
    val vaultTracks by vm.vaultState.collectAsState()
    var notificationText by remember { mutableStateOf<String?>(null) }
    var selectedProfileData by remember { mutableStateOf<SongProfileData?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepObsidianAmber.AppBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
            border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
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
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = DeepObsidianAmber.TextPrimary)
                        }
                        Column {
                            Text(
                                text = "CRYO VAULT",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.CryoCyan,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Cooldown Management",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepObsidianAmber.TextPrimary
                            )
                        }
                    }

                    Button(
                        onClick = onAddTrack,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepObsidianAmber.CryoCyan,
                            contentColor = DeepObsidianAmber.AppBackground
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = DeepObsidianAmber.AppBackground, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Track", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Text(
                    text = "Songs in cooldown are suppressed from high-rotation recommendation loops to prevent listener burnout. TuneMind uses an exponential decay curve to return tracks to full acoustic freshness.",
                    fontSize = 12.sp,
                    color = DeepObsidianAmber.TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        val avgResetDays = if (vaultTracks.isNotEmpty()) {
            String.format(java.util.Locale.US, "%.1f Days", vaultTracks.map { it.cooldownDaysRemaining }.average())
        } else "5.0 Days"

        // 3 Key Metrics Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.CryoCyan.copy(alpha = 0.4f)),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Total Resting", fontSize = 10.sp, color = DeepObsidianAmber.TextMuted, fontFamily = FontFamily.Monospace)
                    Text("${vaultTracks.size} Tracks", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.CryoCyan)
                    Text("Cryo Protected", fontSize = 10.sp, color = DeepObsidianAmber.GlacialBlue)
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Avg Reset", fontSize = 10.sp, color = DeepObsidianAmber.TextMuted, fontFamily = FontFamily.Monospace)
                    Text(avgResetDays, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.PrimaryAccent)
                    Text("Decay: 0.18/d", fontSize = 10.sp, color = DeepObsidianAmber.TextSecondary)
                }
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Shield", fontSize = 10.sp, color = DeepObsidianAmber.TextMuted, fontFamily = FontFamily.Monospace)
                    Text("Active", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.Success)
                    Text("Dopamine guard", fontSize = 10.sp, color = DeepObsidianAmber.TextSecondary)
                }
            }
        }

        // Notification Banner
        notificationText?.let { msg ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardElevated),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.Success),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(msg, color = DeepObsidianAmber.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { notificationText = null }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = DeepObsidianAmber.TextPrimary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // Active Resting List
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.HourglassEmpty, contentDescription = null, tint = DeepObsidianAmber.SecondaryAccent, modifier = Modifier.size(18.dp))
                Text(
                    text = "Tracks in Active Cooldown",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepObsidianAmber.TextPrimary
                )
            }

            Text(
                text = "${vaultTracks.size} Resting",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = DeepObsidianAmber.SecondaryAccent
            )
        }

        if (vaultTracks.isEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.AcUnit, contentDescription = null, tint = DeepObsidianAmber.SecondaryAccent, modifier = Modifier.size(36.dp))
                    Text("Vault is Empty", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.TextPrimary)
                    Text("Tap \"Add Track\" above to quarantine any song into the cooldown vault.", fontSize = 12.sp, color = DeepObsidianAmber.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        } else {
            vaultTracks.forEach { track ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DeepObsidianAmber.CardBackground),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.SurfaceBorder),
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
                                    .background(DeepObsidianAmber.RecessedBackground),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = null, tint = DeepObsidianAmber.Success, modifier = Modifier.size(22.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DeepObsidianAmber.TextPrimary)
                                Text(track.artist, fontSize = 12.sp, color = DeepObsidianAmber.TextSecondary)
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = DeepObsidianAmber.Success.copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DeepObsidianAmber.Success.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    "${track.cooldownDaysRemaining} days left",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepObsidianAmber.Success,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // Receptor Recovery Progress Bar
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Receptor Recovery (e^-0.18t)", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.TextMuted)
                                Text("${(track.recoveryProgress * 100).toInt()}% restored", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DeepObsidianAmber.Success)
                            }
                            LinearProgressIndicator(
                                progress = { track.recoveryProgress.coerceIn(0.05f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                color = DeepObsidianAmber.Success,
                                trackColor = DeepObsidianAmber.RecessedBackground
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    vm.restoreFromVault(track.id)
                                    notificationText = "Restored '${track.title}' from Vault early."
                                }
                            ) {
                                Text("Restore Early", fontSize = 11.sp, color = DeepObsidianAmber.TextMuted)
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
