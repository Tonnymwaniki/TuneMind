package com.tunemind.app.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val context = LocalContext.current
    val authManager = remember { TuneMindAuthSessionManager.getInstance(context) }
    val session by authManager.sessionFlow.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                authManager.refreshSessionState()
                viewModel.checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.resetToWelcome()
    }

    LaunchedEffect(currentStep) {
        if (currentStep == OnboardingStep.COMPLETE) {
            onComplete()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { viewModel.checkPermissions() }
    )

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { viewModel.checkPermissions() }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF07060E),
                        Color(0xFF0E0B1C),
                        Color(0xFF06050A)
                    )
                )
            )
    ) {
        // Ambient background glow orbs
        Box(
            modifier = Modifier
                .offset(x = (-60).dp, y = (-40).dp)
                .size(260.dp)
                .blur(90.dp)
                .background(Color(0xFF7000FF).copy(alpha = 0.22f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 50.dp, y = 40.dp)
                .size(240.dp)
                .blur(85.dp)
                .background(Color(0xFF00E5FF).copy(alpha = 0.16f), CircleShape)
        )

        when (currentStep) {
            OnboardingStep.WELCOME -> {
                StartSplashWelcomeStep(
                    isReturningUser = session.isLoggedIn || session.isSpotifyConnected || session.hasCompletedStartFlow,
                    userDisplayName = session.displayName.ifBlank { session.email.substringBefore("@") },
                    onSplashFinished = { viewModel.onSplashFinishedAutoRoute() }
                )
            }
            OnboardingStep.AUTH_LOGIN -> {
                AuthLoginStep(
                    session = session,
                    authManager = authManager,
                    onContinue = { viewModel.proceedFromAuthToPermissions() }
                )
            }
            OnboardingStep.MICROPHONE -> {
                PermissionSetupStep(
                    stepIndex = 1,
                    totalSteps = 3,
                    badge = "NEURAL VOICE INPUT",
                    title = "Enable Microphone Access",
                    description = "TuneMind listens for 'Hi TuneMind' or orb taps so you can ask Claude Sonnet to play songs, shift genres, or analyze your listening satiety.",
                    primaryButtonText = "Grant Microphone Access",
                    onPrimaryClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onSkipClick = { viewModel.skipCurrentPermissionStep() }
                )
            }
            OnboardingStep.NOTIFICATION -> {
                PermissionSetupStep(
                    stepIndex = 2,
                    totalSteps = 3,
                    badge = "REAL-TIME MEDIA SYNAPSE",
                    title = "Enable Notification Listener",
                    description = "Allows TuneMind to observe active media sessions alongside the 15-minute Spotify Web API sync engine for instant satiety protection.",
                    primaryButtonText = "Open Notification Access",
                    onPrimaryClick = { settingsLauncher.launch(PermissionUtils.getNotificationListenerIntent()) },
                    onSkipClick = { viewModel.skipCurrentPermissionStep() }
                )
            }
            OnboardingStep.BATTERY -> {
                PermissionSetupStep(
                    stepIndex = 3,
                    totalSteps = 3,
                    badge = "AUTONOMOUS BACKGROUND CORE",
                    title = "Unrestricted Background Sync",
                    description = "Ensures the 15-minute Spotify Web API WorkManager sync and autonomous satiety foreground service never get throttled while your screen is off.",
                    primaryButtonText = "Allow Background Execution",
                    onPrimaryClick = { settingsLauncher.launch(PermissionUtils.getBatteryOptimizationIntent(context)) },
                    onSkipClick = { viewModel.skipCurrentPermissionStep() }
                )
            }
            OnboardingStep.COMPLETE -> {
                // Handled by LaunchedEffect
            }
        }
    }
}

@Composable
private fun StartSplashWelcomeStep(
    isReturningUser: Boolean,
    userDisplayName: String?,
    onSplashFinished: () -> Unit
) {
    var progressTarget by remember { mutableFloatStateOf(0.08f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "splash_progress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "splash_pulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring_scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    LaunchedEffect(Unit) {
        progressTarget = 1.0f
        kotlinx.coroutines.delay(1650L)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF030207),
                        Color(0xFF070510),
                        Color(0xFF020205)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 36.dp)
    ) {
        // Center Branded Emblem & Wordmark
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(164.dp)
            ) {
                // Outer pulse ring
                Box(
                    modifier = Modifier
                        .size(156.dp)
                        .scale(ringScale)
                        .border(
                            width = 1.2.dp,
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF).copy(alpha = glowAlpha),
                                    Color(0xFF7000FF).copy(alpha = 0.3f),
                                    Color(0xFF1DB954).copy(alpha = glowAlpha),
                                    Color(0xFF00E5FF).copy(alpha = glowAlpha)
                                )
                            ),
                            shape = CircleShape
                        )
                )
                // Soft ambient radial glow
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .scale(ringScale)
                        .blur(32.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF).copy(alpha = 0.45f),
                                    Color(0xFF7000FF).copy(alpha = 0.28f),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        )
                )
                // Core Obsidian Emblem Badge
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF111526),
                                    Color(0xFF080A14)
                                )
                            )
                        )
                        .border(
                            width = 1.5.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF),
                                    Color(0xFF7000FF),
                                    Color(0xFF1DB954)
                                )
                            ),
                            shape = RoundedCornerShape(28.dp)
                        )
                ) {
                    // Acoustic Waveform Bars inside Emblem
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val barHeights = listOf(18.dp, 32.dp, 44.dp, 26.dp, 36.dp)
                        val barColors = listOf(
                            Color(0xFF00E5FF),
                            Color(0xFF38BDF8),
                            Color(0xFF1DB954),
                            Color(0xFFA855F7),
                            Color(0xFF00E5FF)
                        )
                        barHeights.forEachIndexed { idx, h ->
                            Box(
                                modifier = Modifier
                                    .width(5.dp)
                                    .height(h)
                                    .clip(RoundedCornerShape(50))
                                    .background(barColors[idx])
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "TUNEMIND",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 7.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "NEURO-ACOUSTIC AUDIO INTELLIGENCE",
                color = Color(0xFF00E5FF).copy(alpha = 0.82f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.8.sp
            )
        }

        // Bottom Minimalist Progress Bar & Auto-Login Status
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.68f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isReturningUser) {
                    if (!userDisplayName.isNullOrBlank()) "Welcome back, $userDisplayName" else "Signing you in automatically..."
                } else {
                    "Initializing Studio Core..."
                },
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.10f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress.coerceIn(0.05f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF),
                                    Color(0xFF7000FF),
                                    Color(0xFF1DB954)
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun FeatureHighlightRow(
    dotColor: Color,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun AuthLoginStep(
    session: com.tunemind.app.data.auth.TuneMindUserSession,
    authManager: TuneMindAuthSessionManager,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showEmailForm by remember { mutableStateOf(false) }
    var isSignUpMode by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf(session.email) }
    var passwordInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf(session.displayName) }
    var authError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    var showGoogleConfirmSheet by remember { mutableStateOf(false) }
    var selectedGoogleEmail by remember { mutableStateOf(session.email.ifBlank { "" }) }
    var selectedGoogleName by remember { mutableStateOf(session.displayName) }

    var showSupabaseConfigDialog by remember { mutableStateOf(false) }
    var supabaseUrlInput by remember { mutableStateOf(authManager.getSupabaseUrl()) }
    var supabaseAnonKeyInput by remember { mutableStateOf(authManager.getSupabaseAnonKey()) }

    // Native Android System Google Account Picker (shows the user's real @gmail.com accounts on the phone)
    val googleAccountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pickedEmail = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)?.trim()
        if (!pickedEmail.isNullOrBlank()) {
            selectedGoogleEmail = pickedEmail
            val derivedName = pickedEmail.substringBefore("@")
                .replace(".", " ")
                .replace("_", " ")
                .split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
            selectedGoogleName = derivedName.ifBlank { "TuneMind Listener" }
            showGoogleConfirmSheet = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header + Supabase Config Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "IDENTITY & MUSIC LINK",
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .clickable {
                        supabaseUrlInput = authManager.getSupabaseUrl()
                        supabaseAnonKeyInput = authManager.getSupabaseAnonKey()
                        showSupabaseConfigDialog = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (authManager.isSupabaseConfigured()) "⚡ SUPABASE ACTIVE" else "⚙️ SUPABASE SETUP",
                    color = if (authManager.isSupabaseConfigured()) Color(0xFF10B981) else Color.White.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Main Auth Card
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Sign in to TuneMind",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Connect your Spotify account for automated 15-minute Web API history ingestion and sign in with your Google account.",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 1. ONE-TAP LOGIN WITH GOOGLE BUTTON (Launches Native System Google Account Picker)
            Button(
                onClick = {
                    authError = null
                    try {
                        @Suppress("DEPRECATION")
                        val chooseIntent = android.accounts.AccountManager.newChooseAccountIntent(
                            null,
                            null,
                            arrayOf("com.google"),
                            null,
                            null,
                            null,
                            null
                        )
                        googleAccountPickerLauncher.launch(chooseIntent)
                    } catch (e: Exception) {
                        showGoogleConfirmSheet = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF111827)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = if (session.isGoogleConnected) {
                        "✓ Google Connected (${session.email.ifBlank { session.displayName }})"
                    } else {
                        "G   Continue with Google"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // 2. ONE-TAP LOGIN WITH SPOTIFY BUTTON
            Button(
                onClick = {
                    authError = null
                    authManager.launchSpotifyLogin(context)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1DB954),
                    contentColor = Color(0xFF040404)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (session.isSpotifyConnected) {
                            "✓ Spotify Connected (Tap to Re-Link)"
                        } else {
                            "♫  Continue with Spotify"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // 3. EMAIL / PASSWORD SIGN IN TOGGLE
            OutlinedButton(
                onClick = { showEmailForm = !showEmailForm },
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = if (showEmailForm) "Hide Email Sign-In" else "✉️  Continue with Email & Password",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            AnimatedVisibility(visible = showEmailForm) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSignUpMode) "Create TuneMind Account" else "Sign In with Email",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isSignUpMode) "Have an account? Sign In" else "New user? Sign Up",
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { isSignUpMode = !isSignUpMode }
                        )
                    }

                    if (isSignUpMode) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("Display Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                            )
                        )
                    }

                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Email Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                        )
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                        )
                    )

                    if (!authError.isNullOrBlank()) {
                        Text(
                            text = authError!!,
                            color = Color(0xFFFF4D6D),
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = {
                            if (emailInput.isBlank() || passwordInput.length < 4) {
                                authError = "Please enter a valid email and password."
                                return@Button
                            }
                            authError = null
                            isSubmitting = true
                            scope.launch {
                                val res = authManager.signInOrSignUpWithEmail(
                                    email = emailInput,
                                    password = passwordInput,
                                    displayName = nameInput,
                                    isSignUp = isSignUpMode
                                )
                                isSubmitting = false
                                res.onSuccess {
                                    showEmailForm = false
                                }.onFailure { err ->
                                    authError = err.message ?: "Authentication error"
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isSubmitting) "Authenticating..." else if (isSignUpMode) "Create Account" else "Sign In",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Live Connection Summary Card
            if (session.isLoggedIn || session.isSpotifyConnected || session.isGoogleConnected) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "✓ SESSION READY",
                        color = Color(0xFF10B981),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.4.sp
                    )
                    Text(
                        text = buildString {
                            append("User: ${session.displayName}")
                            if (session.email.isNotBlank()) append(" (${session.email})")
                            if (session.isSpotifyConnected) append(" • Spotify Web API Linked")
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Bottom Continue / Skip Actions
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (session.isLoggedIn || session.isSpotifyConnected) Color(0xFF00E5FF) else Color(0xFF7000FF),
                    contentColor = if (session.isLoggedIn || session.isSpotifyConnected) Color.Black else Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = if (session.isLoggedIn || session.isSpotifyConnected) {
                        "Continue to TuneMind →"
                    } else {
                        "Continue to OS Setup →"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }

    // Google Account Selected Confirmation Modal ("Show me my email then I click Login")
    if (showGoogleConfirmSheet) {
        AlertDialog(
            onDismissRequest = { showGoogleConfirmSheet = false },
            containerColor = Color(0xFF12101E),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("G", color = Color(0xFF111827), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                    Text("Sign in with Google", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Continue to TuneMind with your selected Google account:",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp
                    )

                    // Highlighted Selected Google Account Card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF00E5FF).copy(alpha = 0.12f))
                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (selectedGoogleEmail.firstOrNull()?.uppercase() ?: "G"),
                                color = Color.Black,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedGoogleName.ifBlank { selectedGoogleEmail.substringBefore("@") },
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = selectedGoogleEmail.ifBlank { "Tap below to enter email" },
                                color = Color(0xFF00E5FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (selectedGoogleEmail.isBlank()) {
                        OutlinedTextField(
                            value = selectedGoogleEmail,
                            onValueChange = { selectedGoogleEmail = it },
                            label = { Text("Google Email") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalEmail = selectedGoogleEmail.trim().ifBlank { "listener@gmail.com" }
                        val finalName = selectedGoogleName.trim().ifBlank { finalEmail.substringBefore("@") }
                        authManager.completeOnDeviceGoogleSignIn(finalEmail, finalName)
                        showGoogleConfirmSheet = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Login", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showGoogleConfirmSheet = false
                        try {
                            @Suppress("DEPRECATION")
                            val chooseIntent = android.accounts.AccountManager.newChooseAccountIntent(
                                null,
                                null,
                                arrayOf("com.google"),
                                null,
                                null,
                                null,
                                null
                            )
                            googleAccountPickerLauncher.launch(chooseIntent)
                        } catch (_: Exception) {}
                    }
                ) {
                    Text("Switch Account", color = Color.White.copy(alpha = 0.75f))
                }
            }
        )
    }

    // Supabase Project URL & Anon Key Configuration Dialog
    if (showSupabaseConfigDialog) {
        AlertDialog(
            onDismissRequest = { showSupabaseConfigDialog = false },
            containerColor = Color(0xFF12101E),
            title = {
                Text("Supabase Auth Configuration", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Paste your Supabase Project URL and Anon Key. Redirect URI to add in Supabase Dashboard -> Authentication -> URL Configuration:\ntunemind://supabase-auth-callback",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = supabaseUrlInput,
                        onValueChange = { supabaseUrlInput = it },
                        label = { Text("https://xyzcompany.supabase.co") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = supabaseAnonKeyInput,
                        onValueChange = { supabaseAnonKeyInput = it },
                        label = { Text("Supabase Anon Public Key") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        authManager.saveSupabaseConfig(supabaseUrlInput, supabaseAnonKeyInput)
                        showSupabaseConfigDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.Black)
                ) {
                    Text("Save Supabase Config", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSupabaseConfigDialog = false }) {
                    Text("Close", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}

@Composable
private fun PermissionSetupStep(
    stepIndex: Int,
    totalSteps: Int,
    badge: String,
    title: String,
    description: String,
    primaryButtonText: String,
    onPrimaryClick: () -> Unit,
    onSkipClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Step Progress Indicator
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 1..totalSteps) {
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .width(if (i == stepIndex) 36.dp else 14.dp)
                        .clip(CircleShape)
                        .background(
                            if (i <= stepIndex) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.18f)
                        )
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .padding(26.dp)
        ) {
            Text(
                text = "STEP $stepIndex OF $totalSteps • $badge",
                color = Color(0xFF00E5FF),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.6.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onPrimaryClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = primaryButtonText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        TextButton(onClick = onSkipClick) {
            Text(
                text = "Skip this step for now →",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp
            )
        }
    }
}
