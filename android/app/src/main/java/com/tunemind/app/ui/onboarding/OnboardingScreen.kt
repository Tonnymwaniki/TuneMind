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
                    onGetStarted = { viewModel.proceedFromWelcomeToAuth() }
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
    onGetStarted: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "start_orb")
    val orbScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Center Living Orb + Brand Identity
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(170.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .scale(orbScale)
                        .blur(36.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF).copy(alpha = 0.55f),
                                    Color(0xFF7000FF).copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .scale(orbScale)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF),
                                    Color(0xFF7000FF),
                                    Color(0xFF1DB954)
                                )
                            )
                        )
                        .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "TUNEMIND",
                color = Color(0xFF00E5FF),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 6.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Autonomous Audio\nIntelligence Agent",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                lineHeight = 36.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Direct Spotify Web API ingestion, real-time dopamine satiety detection, and OS-level voice execution powered by Claude.",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }

        // Feature Pillars + CTA
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FeatureHighlightRow(
                dotColor = Color(0xFF1DB954),
                title = "Direct Spotify OAuth 2.0 Sync",
                subtitle = "Pulls recently-played history every 15m into local Room SQLite"
            )
            FeatureHighlightRow(
                dotColor = Color(0xFF00E5FF),
                title = "Satiety Reasoning Engine",
                subtitle = "Detects listener fatigue across your last 50 tracks automatically"
            )
            FeatureHighlightRow(
                dotColor = Color(0xFFA855F7),
                title = "Hands-Free OS Execution",
                subtitle = "Auto-plays tracks, skips overplayed loops & speaks live insights"
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onGetStarted,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color(0xFF05050A)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Get Started →",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
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

    var showGoogleQuickSheet by remember { mutableStateOf(false) }
    var googleEmailInput by remember { mutableStateOf(session.email.ifBlank { "" }) }
    var googleNameInput by remember { mutableStateOf(session.displayName) }

    var showSupabaseConfigDialog by remember { mutableStateOf(false) }
    var supabaseUrlInput by remember { mutableStateOf(authManager.getSupabaseUrl()) }
    var supabaseAnonKeyInput by remember { mutableStateOf(authManager.getSupabaseAnonKey()) }

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
                text = "Connect your Spotify account for automated 15-minute Web API history ingestion and sign in with Google or Email.",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 1. ONE-TAP LOGIN WITH SPOTIFY BUTTON
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

            // 2. ONE-TAP CONTINUE WITH GOOGLE BUTTON
            Button(
                onClick = {
                    authError = null
                    val launchedBrowser = authManager.launchGoogleLogin(context)
                    if (!launchedBrowser) {
                        showGoogleQuickSheet = true
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
                        "✓ Signed in with Google (${session.email.ifBlank { session.displayName }})"
                    } else {
                        "G   Continue with Google"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
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

    // On-Device Google Account Quick Sign-In Dialog (when Supabase URL isn't configured yet)
    if (showGoogleQuickSheet) {
        AlertDialog(
            onDismissRequest = { showGoogleQuickSheet = false },
            containerColor = Color(0xFF12101E),
            title = {
                Text("Sign in with Google", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter your Google Account to bind your TuneMind profile (or configure your Supabase Project URL in ⚙️ SUPABASE SETUP for browser OAuth):",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = googleNameInput,
                        onValueChange = { googleNameInput = it },
                        label = { Text("Your Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = googleEmailInput,
                        onValueChange = { googleEmailInput = it },
                        label = { Text("Google Email (e.g. you@gmail.com)") },
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
                        val finalEmail = googleEmailInput.trim().ifBlank { "listener@gmail.com" }
                        val finalName = googleNameInput.trim().ifBlank { finalEmail.substringBefore("@") }
                        authManager.completeOnDeviceGoogleSignIn(finalEmail, finalName)
                        showGoogleQuickSheet = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                ) {
                    Text("Continue with Google", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGoogleQuickSheet = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
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
