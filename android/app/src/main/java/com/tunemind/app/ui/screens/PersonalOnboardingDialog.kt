package com.tunemind.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.StudioAmbientBackground
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

    StudioAmbientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TuneMind Setup",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primaryAccent
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0C1018)
                ) {
                    Text(
                        text = "Step 1 of 1",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                colors.primaryAccent.copy(alpha = 0.24f),
                                Color(0xFF0C1018),
                                Color(0xFF05060A)
                            )
                        )
                    )
                    .border(1.5.dp, colors.primaryAccent.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(colors.primaryAccent.copy(alpha = 0.16f))
                        .border(1.dp, colors.primaryAccent.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = colors.primaryAccent,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Choose Your Music Player",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "TuneMind tracks freshness across your music apps and helps keep every playlist feeling new.",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    lineHeight = 19.sp
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Primary Music App",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.72f)
                )

                installedProviders.forEach { provider ->
                    val isSelected = provider.providerId == selectedProviderId
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) colors.primaryAccent.copy(alpha = 0.14f) else Color(0xFF0C1018),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) colors.primaryAccent else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedProviderId = provider.providerId
                                vm.setPreferredProviderId(context, provider.providerId)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = provider.displayName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            if (isSelected) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = colors.primaryAccent.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "Selected",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.primaryAccent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!hasNotificationAccess) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0C1018),
                    border = BorderStroke(1.dp, colors.secondaryAccent.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Notification Access Needed",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.secondaryAccent
                        )
                        Text(
                            text = "Enable Notification Access so TuneMind can see what song is playing without leaving the app.",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.68f)
                        )
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.secondaryAccent),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Enable Notification Access",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF05060A)
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    vm.completeOnboarding(context, selectedProviderId)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.primaryAccent),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = "Get Started",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF05060A)
                )
            }
        }
    }
}
