package com.tunemind.app.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunemind.app.util.PermissionUtils

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.checkPermissions()
    }

    LaunchedEffect(currentStep) {
        if (currentStep == OnboardingStep.COMPLETE) {
            onComplete()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) viewModel.checkPermissions()
        }
    )

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            viewModel.checkPermissions()
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0A09)) // Obsidian
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (currentStep) {
            OnboardingStep.WELCOME -> {
                Text("TUNE MIND", color = Color(0xFF00E5FF), fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Neuro-Acoustic Intelligence", color = Color.Gray, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(48.dp))
                Button(
                    onClick = { viewModel.nextStep() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7000FF)) // Neural Violet
                ) {
                    Text("Initialize Entity")
                }
            }
            OnboardingStep.MICROPHONE -> {
                PermissionCard(
                    title = "The Ears",
                    description = "TuneMind requires microphone access to listen for 'Hi TuneMind' and process your vocal commands via AssemblyAI.",
                    buttonText = "Grant Microphone Access",
                    onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }
            OnboardingStep.NOTIFICATION -> {
                PermissionCard(
                    title = "The Synapse",
                    description = "To monitor acoustic fatigue, TuneMind needs Notification Access to read what track is currently playing on Spotify.",
                    buttonText = "Enable Notification Listener",
                    onClick = { settingsLauncher.launch(PermissionUtils.getNotificationListenerIntent()) }
                )
            }
            OnboardingStep.BATTERY -> {
                PermissionCard(
                    title = "The Core",
                    description = "To run autonomously in the background, Android requires you to disable battery optimization for TuneMind.",
                    buttonText = "Disable Battery Limits",
                    onClick = { settingsLauncher.launch(PermissionUtils.getBatteryOptimizationIntent(context)) }
                )
            }
            OnboardingStep.COMPLETE -> {
                // Handled by LaunchedEffect
            }
        }
    }
}

@Composable
fun PermissionCard(title: String, description: String, buttonText: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text(description, color = Color.Gray, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)) // Acoustic Cyan
        ) {
            Text(buttonText, color = Color.Black)
        }
    }
}
