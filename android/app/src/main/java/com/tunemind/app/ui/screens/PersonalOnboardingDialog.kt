package com.tunemind.app.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@Composable
fun PersonalOnboardingDialog(
    vm: TuneMindViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val installedProviders = remember {
        val detected = vm.getInstalledProviders(context)
        if (detected.isNotEmpty()) detected else com.tunemind.app.provider.MusicProviderRegistry.getInstance(context).getAllProviders()
    }
    var selectedProviderId by remember { mutableStateOf(vm.getPreferredProviderId(context)) }
    val hasNotificationAccess = NotificationAccess.isEnabled(context)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)), // Deep slate-950
        color = Color(0xFF020617)
    ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Header Brand Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0891B2).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "TUNEMIND AGENT",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Personal Music Intelligence",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                // Core Philosophy Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "THE CENTRAL PRINCIPLE",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryVioletBright
                        )
                        Text(
                            text = "\"The intelligence belongs to TuneMind. The music belongs to any service you choose.\"",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            lineHeight = 22.sp
                        )
                        Text(
                            text = "TuneMind does not own a music catalog. It passively observes your playback habits across your installed music apps, learns your acoustic preferences, and launches recommendations directly inside the music service of your choice.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8),
                            lineHeight = 18.sp
                        )
                    }
                }

                // What TuneMind Observes (Privacy-First)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "WHAT WE OBSERVE • 100% LOCAL",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan
                        )

                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            Column {
                                Text("Track metadata & source app", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Titles, artists, and durations exposed by active playback sessions.", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }

                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            Column {
                                Text("Repetition & Skip frequency", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Identifies when you repeat favorites or abandon songs to spot fatigue.", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }

                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            Column {
                                Text("Zero Raw Data Uploads", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("All event telemetry stays in your phone's Room SQLite database.", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }
                    }
                }

                // Preferred Music Provider Selection
                Text(
                    text = "Select Preferred Music Service",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                installedProviders.forEach { provider ->
                    val isSelected = selectedProviderId == provider.providerId

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF0891B2).copy(alpha = 0.15f) else Color(0xFF0F172A)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) AccentCyan else Color(0xFF1E293B)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedProviderId = provider.providerId }
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
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1E293B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = if (provider.providerId == "spotify") Color(0xFF1DB954) else AccentCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(provider.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(
                                    if (provider.isInstalled(context)) "Installed on this phone • Full playback handoff"
                                    else "Available via web fallback",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedProviderId = provider.providerId },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCyan, unselectedColor = Color(0xFF64748B))
                            )
                        }
                    }
                }

                // Permission Warning if Notification Access not yet enabled
                if (!hasNotificationAccess) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03).copy(alpha = 0.6f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Notification Access Required", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentAmber)
                                Text("TuneMind needs access to observe active media session metadata.", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                            }
                            Button(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color(0xFF020617)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Complete Onboarding Action Button
                Button(
                    onClick = {
                        vm.completeOnboarding(context, selectedProviderId)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color(0xFF020617))
                ) {
                    Text(
                        text = "Begin Personal Music Intelligence",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
}
