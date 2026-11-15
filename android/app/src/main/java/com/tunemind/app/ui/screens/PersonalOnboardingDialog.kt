package com.tunemind.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunemind.app.service.NotificationAccess
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.TuneMindLogoMark
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
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.Start
        ) {
            TuneMindLogoMark(size = 48.dp, showContainer = false, animatedWave = false)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Choose Your Music App",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
                Text(
                    text = "TuneMind tracks what plays across your music apps and keeps playlists fresh.",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.64f),
                    lineHeight = 20.sp
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Primary music app",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.56f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                StudioHairlineDivider()
                installedProviders.forEach { provider ->
                    val isSelected = provider.providerId == selectedProviderId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectedProviderId = provider.providerId
                                vm.setPreferredProviderId(context, provider.providerId)
                            }
                            .padding(vertical = 15.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = provider.displayName,
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) colors.primaryAccent else Color(0xFFF8FAFC)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Selected",
                                tint = colors.primaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    StudioHairlineDivider()
                }
            }

            if (!hasNotificationAccess) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Music Detection",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "Allow Notification Access so TuneMind can follow along with your playing tracks.",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.64f),
                        lineHeight = 19.sp
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Allow Notification Access",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primaryAccent
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = true))

            Button(
                onClick = {
                    vm.completeOnboarding(context, selectedProviderId)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF8FAFC),
                    contentColor = Color(0xFF07090E)
                ),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "Done",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
