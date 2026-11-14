package com.tunemind.app.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.tunemind.app.ui.components.StudioAmbientBackground
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

    StudioAmbientBackground {
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
                    badge = "Voice Control",
                    title = "Allow Microphone",
                    description = "Talk naturally to TuneMind with \"Hey TuneMind\" or by tapping the sphere to ask for tracks and playlists.",
                    primaryButtonText = "Allow Microphone",
                    onPrimaryClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onSkipClick = { viewModel.skipCurrentPermissionStep() }
                )
            }
            OnboardingStep.NOTIFICATION -> {
                PermissionSetupStep(
                    stepIndex = 2,
                    totalSteps = 3,
                    badge = "Now Playing",
                    title = "Enable Music Detection",
                    description = "Lets TuneMind see what song is playing across your music apps and protect tracks from getting overplayed.",
                    primaryButtonText = "Enable Notification Access",
                    onPrimaryClick = { settingsLauncher.launch(PermissionUtils.getNotificationListenerIntent()) },
                    onSkipClick = { viewModel.skipCurrentPermissionStep() }
                )
            }
            OnboardingStep.BATTERY -> {
                PermissionSetupStep(
                    stepIndex = 3,
                    totalSteps = 3,
                    badge = "Background Sync",
                    title = "Keep Sync Running",
                    description = "Keeps your Spotify history synced and background playback smooth even when your screen is off.",
                    primaryButtonText = "Allow Unrestricted Battery",
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
    var progressTarget by remember { mutableFloatStateOf(0.15f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "splash_progress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "splash_pulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring_scale"
    )

    LaunchedEffect(Unit) {
        progressTarget = 1.0f
        kotlinx.coroutines.delay(700L)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05060A))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 36.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(144.dp)
                    .scale(ringScale)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val ringRadius = size.minDimension * 0.36f
                    val cyanColor = Color(0xFF00E5FF)
                    val amberColor = Color(0xFFFFB000)

                    val arcTopLeft = Offset(cx - ringRadius, cy - ringRadius)
                    val arcSize = Size(ringRadius * 2f, ringRadius * 2f)

                    drawArc(
                        color = cyanColor,
                        startAngle = 155f,
                        sweepAngle = 155f,
                        useCenter = false,
                        topLeft = Offset(arcTopLeft.x - 1.5.dp.toPx(), arcTopLeft.y - 1.5.dp.toPx()),
                        size = arcSize,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = amberColor,
                        startAngle = -25f,
                        sweepAngle = 155f,
                        useCenter = false,
                        topLeft = Offset(arcTopLeft.x + 1.5.dp.toPx(), arcTopLeft.y + 1.5.dp.toPx()),
                        size = arcSize,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )

                    val topBarHalf = ringRadius * 0.45f
                    val topY = cy - ringRadius * 0.34f
                    val bottomY = cy + ringRadius * 0.44f
                    val strokeW = 6.5.dp.toPx()

                    val tPath = Path().apply {
                        moveTo(cx - topBarHalf, topY)
                        lineTo(cx + topBarHalf, topY)
                        moveTo(cx, topY)
                        lineTo(cx, bottomY)
                    }
                    drawPath(
                        path = tPath,
                        color = Color.White,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "TuneMind",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "LISTEN IN FLOW",
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.68f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isReturningUser) {
                    if (!userDisplayName.isNullOrBlank()) "Welcome back, $userDisplayName" else "Signing you in…"
                } else {
                    "Preparing your space…"
                },
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
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
                                    Color(0xFFFFB000)
                                )
                            )
                        )
                )
            }
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
        // Top Header + Cloud Sync Config Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Account & Music",
                color = Color(0xFF00E5FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                    .clickable {
                        supabaseUrlInput = authManager.getSupabaseUrl()
                        supabaseAnonKeyInput = authManager.getSupabaseAnonKey()
                        showSupabaseConfigDialog = true
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = if (authManager.isSupabaseConfigured()) Icons.Rounded.Bolt else Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = if (authManager.isSupabaseConfigured()) Color(0xFF10B981) else Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (authManager.isSupabaseConfigured()) "Cloud Sync Ready" else "Server Setup",
                    color = if (authManager.isSupabaseConfigured()) Color(0xFF10B981) else Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Sign in to TuneMind",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Link Spotify to keep your listening history synced and sign in with your Google or email account.",
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

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
                    .height(54.dp)
            ) {
                if (session.isGoogleConnected) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = if (session.isGoogleConnected) {
                        "Google Connected (${session.email.ifBlank { session.displayName }})"
                    } else {
                        "Continue with Google"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

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
                    .height(54.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (session.isSpotifyConnected) Icons.Rounded.Check else Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (session.isSpotifyConnected) {
                            "Spotify Connected"
                        } else {
                            "Continue with Spotify"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedButton(
                onClick = { showEmailForm = !showEmailForm },
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Rounded.MailOutline, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (showEmailForm) "Hide Email Sign-In" else "Continue with Email",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            AnimatedVisibility(visible = showEmailForm) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0C1018))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSignUpMode) "Create Account" else "Sign In",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isSignUpMode) "Have an account? Sign In" else "New here? Sign Up",
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
                            label = { Text("Name") },
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
                        label = { Text("Email") },
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
                            text = if (isSubmitting) "Signing in…" else if (isSignUpMode) "Create Account" else "Sign In",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (session.isLoggedIn || session.isSpotifyConnected || session.isGoogleConnected) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Signed In",
                        color = Color(0xFF10B981),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = buildString {
                            append(session.displayName)
                            if (session.email.isNotBlank()) append(" (${session.email})")
                            if (session.isSpotifyConnected) append(" • Spotify Linked")
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "Continue",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showGoogleConfirmSheet) {
        AlertDialog(
            onDismissRequest = { showGoogleConfirmSheet = false },
            containerColor = Color(0xFF10131C),
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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF00E5FF).copy(alpha = 0.12f))
                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
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
                                text = selectedGoogleEmail.ifBlank { "Enter your email below" },
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
                    Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

    if (showSupabaseConfigDialog) {
        AlertDialog(
            onDismissRequest = { showSupabaseConfigDialog = false },
            containerColor = Color(0xFF10131C),
            title = {
                Text("Cloud Sync Configuration", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter your Supabase Project URL and Anon Key. Redirect URI:\ntunemind://supabase-auth-callback",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = supabaseUrlInput,
                        onValueChange = { supabaseUrlInput = it },
                        label = { Text("Project URL") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = supabaseAnonKeyInput,
                        onValueChange = { supabaseAnonKeyInput = it },
                        label = { Text("Anon Key") },
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
                    Text("Save", fontWeight = FontWeight.Bold)
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
                .background(Color(0xFF0C1018).copy(alpha = 0.90f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
                .padding(26.dp)
        ) {
            Text(
                text = "Step $stepIndex of $totalSteps • $badge",
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.70f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )
            Spacer(modifier = Modifier.height(26.dp))
            Button(
                onClick = onPrimaryClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = primaryButtonText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        TextButton(onClick = onSkipClick) {
            Text(
                text = "Skip for now",
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 13.sp
            )
        }
    }
}
