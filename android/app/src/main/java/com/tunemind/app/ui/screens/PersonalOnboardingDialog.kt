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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.theme.*

@Composable
fun PersonalOnboardingDialog(
    vm: TuneMindViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = TuneMindTheme.colors
    val installedProviders = remember {
        val detected = vm.getInstalledProviders(context)
        if (detected.isNotEmpty()) detected else com.tunemind.app.provider.MusicProviderRegistry.getInstance(context).getAllProviders()
    }
    var selectedProviderId by remember { mutableStateOf(vm.getPreferredProviderId(context)) }
    val hasNotificationAccess = NotificationAccess.isEnabled(context)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.primaryBackground),
        color = colors.primaryBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. TOP TELEMETRY STRIP
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981))
                    )
                    Text(
                        text = "TUNEMIND // CALIBRATION PROTOCOL 1.0",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = colors.cardSurface
                ) {
                    Text(
                        text = "SYNC: READY 48kHz / 24-BIT",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF83B9BD),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Recessed Chassis Groove
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.border.copy(alpha = 0.4f))
            )

            // 2. ACOUSTIC SENSOR DIAL MODULE (Knurled Bezel Well & Harmonic Waveform)
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                colors.elevatedSurface,
                                colors.cardSurface,
                                colors.primaryBackground
                            )
                        )
                    )
                    .border(2.dp, colors.border, CircleShape)
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                // Inner Aluminum Cavity
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(colors.secondaryBackground)
                        .border(1.dp, colors.border.copy(alpha = 0.5f), CircleShape),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Lissajous / Soundwave Vector Visualization Icon
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(colors.primaryAccent.copy(alpha = 0.15f))
                            .border(1.dp, colors.primaryAccent.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.GraphicEq,
                            contentDescription = null,
                            tint = colors.primaryAccent,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "FLOOR: -42.8 dBFS",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryText
                    )
                    Text(
                        text = "RES: 99.4% OPTIMAL",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF83B9BD)
                    )
                }
            }

            // Sensor Bus Tag
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(colors.primaryAccent)
                )
                Text(
                    text = "AWA-28 ACOUSTIC SENSOR BUS",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText,
                    letterSpacing = 1.sp
                )
            }

            // Recessed Chassis Groove
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.border.copy(alpha = 0.4f))
            )

            // 3. HARDWARE NARRATIVE
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Calibrate Your Auditory Anchor",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primaryText,
                    letterSpacing = (-0.3).sp
                )
                Text(
                    text = "TuneMind monitors song fatigue, peak spectral density, and acoustic saturation across your music players in real-time.",
                    fontSize = 12.sp,
                    color = colors.secondaryText,
                    lineHeight = 18.sp
                )
            }

            // 4. SOURCE SELECTOR MODULE
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "SELECT PRIMARY OBSERVED AUDIO BUS",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.secondaryText,
                    letterSpacing = 1.sp
                )

                installedProviders.forEach { provider ->
                    val isSelected = provider.id == selectedProviderId
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) colors.cardSurface else colors.secondaryBackground,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) colors.primaryAccent else colors.border.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedProviderId = provider.id
                                vm.setPreferredProviderId(context, provider.id)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = provider.displayName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryText
                            )
                            if (isSelected) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = colors.primaryAccent.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "ACTIVE BUS",
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.primaryAccent,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. NOTIFICATION INGEST PERMISSION
            if (!hasNotificationAccess) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.secondaryBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.secondaryAccent.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "MEDIA LISTENER PERMISSION REQUIRED",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondaryAccent,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "TuneMind requires Android Notification Access to observe now-playing metadata locally without external servers.",
                            fontSize = 11.sp,
                            color = colors.secondaryText
                        )
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.secondaryAccent),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "GRANT MEDIA INGEST ACCESS",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = colors.primaryBackground
                            )
                        }
                    }
                }
            }

            // 6. INITIALIZE CALIBRATION BUTTON
            Button(
                onClick = {
                    vm.completeOnboarding()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.primaryAccent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "INITIALIZE ACOUSTIC CALIBRATION",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colors.primaryBackground,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
