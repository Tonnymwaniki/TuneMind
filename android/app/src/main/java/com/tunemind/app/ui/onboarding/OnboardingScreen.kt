package com.tunemind.app.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunemind.app.R
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.auth.TuneMindUserSession
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.TuneMindAiIntelligenceLoop
import com.tunemind.app.ui.components.TuneMindSprings
import com.tunemind.app.ui.components.tactilePressScale
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onOpenLegal: (Int) -> Unit = {},
    alwaysShowAuthFirst: Boolean = false,
    isSplashFinished: Boolean = true,
    viewModel: OnboardingViewModel = viewModel()
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val context = LocalContext.current
    val authManager = remember { TuneMindAuthSessionManager.getInstance(context) }
    val session by authManager.sessionFlow.collectAsState()

    var micPermanentlyDenied by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    authManager.refreshSessionState()
                    PermissionUtils.kickstartMediaObservation(context)
                }
                if (PermissionUtils.hasMicrophonePermission(context)) {
                    micPermanentlyDenied = false
                }
                viewModel.checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(alwaysShowAuthFirst) {
        viewModel.configureLaunchGate(alwaysShowAuthFirst)
    }

    LaunchedEffect(currentStep, alwaysShowAuthFirst) {
        if (currentStep == OnboardingStep.COMPLETE && viewModel.hasPassedLaunchGate(alwaysShowAuthFirst)) {
            PermissionUtils.kickstartMediaObservation(context)
            onComplete()
        }
    }

    val entranceAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isSplashFinished) 1f else 0f,
        animationSpec = tween(durationMillis = 360),
        label = "onboarding_splash_reveal_alpha"
    )
    val entranceScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isSplashFinished) 1f else 0.97f,
        animationSpec = tween(durationMillis = 420),
        label = "onboarding_splash_reveal_scale"
    )

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted || PermissionUtils.hasMicrophonePermission(context)) {
                micPermanentlyDenied = false
            } else if (PermissionUtils.isRuntimePermissionPermanentlyDeniedAfterResult(
                    context = context,
                    permission = Manifest.permission.RECORD_AUDIO,
                    granted = false
                )
            ) {
                micPermanentlyDenied = true
            }
            viewModel.checkPermissions()
        }
    )

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            if (PermissionUtils.hasMicrophonePermission(context)) {
                micPermanentlyDenied = false
            }
            PermissionUtils.kickstartMediaObservation(context)
            viewModel.checkPermissions()
        }
    )

    StudioAmbientBackground {
        AnimatedContent(
            targetState = currentStep,
            modifier = Modifier
                .fillMaxSize()
                .alpha(entranceAlpha)
                .scale(entranceScale),
            transitionSpec = {
                fadeIn(tween(240)) togetherWith fadeOut(tween(180))
            },
            label = "onboarding_step_transition"
        ) { step ->
            when (step) {
                OnboardingStep.WELCOME -> {
                    LaunchedEffect(Unit) {
                        viewModel.onSplashFinishedAutoRoute()
                    }
                }
                OnboardingStep.AUTH_LOGIN -> {
                    AuthLoginStep(
                        session = session,
                        authManager = authManager,
                        onOpenLegal = onOpenLegal,
                        onContinue = { viewModel.proceedFromAuthToPermissions() }
                    )
                }
                OnboardingStep.MICROPHONE -> {
                    PermissionSetupStep(
                        stepIndex = 1,
                        totalSteps = 3,
                        icon = Icons.Rounded.Mic,
                        accent = Color(0xFF00E5FF),
                        title = "Allow Microphone",
                        description = "Talk naturally to TuneMind by saying \"Hey TuneMind\" or tapping the sphere to ask for tracks.",
                        primaryButtonText = if (micPermanentlyDenied) "Open App Settings" else "Allow Microphone",
                        helperNote = if (micPermanentlyDenied) {
                            "Microphone permission was previously disabled in Android. Tap above → Permissions → Microphone → Allow."
                        } else null,
                        onPrimaryClick = {
                            if (micPermanentlyDenied) {
                                runCatching {
                                    settingsLauncher.launch(PermissionUtils.getAppDetailsSettingsIntent(context))
                                }.onFailure {
                                    PermissionUtils.openAppDetailsSettings(context)
                                }
                            } else {
                                runCatching {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }.onFailure {
                                    PermissionUtils.openAppDetailsSettings(context)
                                }
                            }
                        },
                        onSkipClick = { viewModel.skipCurrentPermissionStep() }
                    )
                }
                OnboardingStep.NOTIFICATION -> {
                    PermissionSetupStep(
                        stepIndex = 2,
                        totalSteps = 3,
                        icon = Icons.Rounded.NotificationsActive,
                        accent = Color(0xFFFFB000),
                        title = "Enable Music Detection",
                        description = "Lets TuneMind recognize what is playing across Spotify, YouTube Music, Boomplay, Audiomack & local players.",
                        primaryButtonText = "Allow Music Access",
                        helperNote = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            "If TuneMind is grayed out: open App Info → tap ⋮ (top right) → Allow restricted settings, then return here."
                        } else null,
                        helperActionText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            "Toggle grayed out? Open App Info"
                        } else null,
                        onHelperActionClick = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            {
                                runCatching {
                                    settingsLauncher.launch(PermissionUtils.getAppDetailsSettingsIntent(context))
                                }.onFailure {
                                    PermissionUtils.openAppDetailsSettings(context)
                                }
                            }
                        } else null,
                        onPrimaryClick = {
                            runCatching {
                                settingsLauncher.launch(PermissionUtils.getNotificationListenerIntent(context))
                            }.onFailure {
                                PermissionUtils.openNotificationListenerSettings(context)
                            }
                        },
                        onSkipClick = { viewModel.skipCurrentPermissionStep() }
                    )
                }
                OnboardingStep.BATTERY -> {
                    PermissionSetupStep(
                        stepIndex = 3,
                        totalSteps = 3,
                        icon = Icons.Rounded.BatteryChargingFull,
                        accent = Color(0xFF10B981),
                        title = "Keep Sync Running",
                        description = "Select TuneMind in Battery settings and choose Unrestricted so cross-app music detection stays live with your screen off.",
                        primaryButtonText = "Allow Background Sync",
                        helperActionText = "Open App Battery Settings directly",
                        onHelperActionClick = {
                            runCatching {
                                settingsLauncher.launch(PermissionUtils.getAppDetailsSettingsIntent(context))
                            }.onFailure {
                                PermissionUtils.openAppDetailsSettings(context)
                            }
                        },
                        onPrimaryClick = {
                            runCatching {
                                settingsLauncher.launch(PermissionUtils.getBatteryOptimizationIntent(context))
                            }.onFailure {
                                PermissionUtils.openBatteryOptimizationSettings(context)
                            }
                        },
                        onSkipClick = { viewModel.skipCurrentPermissionStep() }
                    )
                }
                OnboardingStep.COMPLETE -> {
                    // Handled by LaunchedEffect
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthLoginStep(
    session: TuneMindUserSession,
    authManager: TuneMindAuthSessionManager,
    onOpenLegal: (Int) -> Unit = {},
    onContinue: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val savedAccounts by authManager.savedAccountsFlow.collectAsState()
    val remoteAuthError by authManager.lastAuthErrorFlow.collectAsState()

    var isSignUpMode by remember { mutableStateOf(false) }
    var ageConfirmed by remember { mutableStateOf(false) }
    var termsConsentChecked by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }
    var authInfoNotice by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isSwitchingSavedAccount by remember { mutableStateOf(false) }

    var showEmailSheet by remember { mutableStateOf(false) }
    var showGoogleConfirmSheet by remember { mutableStateOf(false) }
    var awaitingGoogleOAuthCallback by remember { mutableStateOf(false) }
    var googleLaunchStartedAtMs by remember { mutableStateOf(0L) }
    var selectedGoogleEmail by remember { mutableStateOf("") }
    var selectedGoogleName by remember { mutableStateOf("") }

    LaunchedEffect(session.lastAuthCompletedAtMs, awaitingGoogleOAuthCallback) {
        if (awaitingGoogleOAuthCallback && session.lastAuthCompletedAtMs > googleLaunchStartedAtMs) {
            awaitingGoogleOAuthCallback = false
            onContinue()
        }
    }

    LaunchedEffect(remoteAuthError) {
        if (!remoteAuthError.isNullOrBlank()) {
            awaitingGoogleOAuthCallback = false
            authError = remoteAuthError
        }
    }

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

    fun launchGoogleAccountPicker() {
        authError = null
        authInfoNotice = null
        authManager.clearLastAuthError()
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
        } catch (_: Exception) {
            showGoogleConfirmSheet = true
        }
    }

    fun submitEmailAuth() {
        val cleanEmail = emailInput.trim()
        if (!cleanEmail.contains("@") || !cleanEmail.contains(".")) {
            authError = "Enter a valid email address."
            authInfoNotice = null
            return
        }
        if (passwordInput.length < 6) {
            authError = "Password must be at least 6 characters."
            authInfoNotice = null
            return
        }
        if (isSignUpMode && !ageConfirmed) {
            authError = "Please confirm you are at least 13 years old (16+ where required by law)."
            authInfoNotice = null
            return
        }
        if (isSignUpMode && !termsConsentChecked) {
            authError = "Please agree to the Privacy Policy and Terms of Service to create an account."
            authInfoNotice = null
            return
        }
        authError = null
        authInfoNotice = null
        isSubmitting = true
        focusManager.clearFocus()
        scope.launch {
            val res = authManager.signInOrSignUpWithEmail(
                email = cleanEmail,
                password = passwordInput,
                displayName = nameInput,
                isSignUp = isSignUpMode
            )
            isSubmitting = false
            res.onSuccess {
                showEmailSheet = false
                onContinue()
            }.onFailure { err ->
                val msg = err.message ?: "Could not sign in with email"
                if (msg.contains("Verification email sent", ignoreCase = true)) {
                    authInfoNotice = msg
                    isSignUpMode = false
                } else {
                    authError = msg
                }
            }
        }
    }

    fun submitForgotPassword() {
        val cleanEmail = emailInput.trim()
        if (!cleanEmail.contains("@") || !cleanEmail.contains(".")) {
            authError = "Enter your email address above first so we know where to send the reset link."
            authInfoNotice = null
            return
        }
        authError = null
        authInfoNotice = null
        isSubmitting = true
        scope.launch {
            val res = authManager.sendPasswordResetEmail(cleanEmail)
            isSubmitting = false
            res.onSuccess {
                authInfoNotice = "Password reset link sent to $cleanEmail. Check your inbox."
            }.onFailure { err ->
                authError = err.message ?: "Could not send password reset email."
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFFAFAFA),
        unfocusedTextColor = Color(0xFFFAFAFA),
        focusedContainerColor = Color(0xFF131318),
        unfocusedContainerColor = Color(0xFF131318).copy(alpha = 0.72f),
        focusedBorderColor = Color(0xFF38BDF8),
        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
        focusedLabelColor = Color(0xFF38BDF8),
        unfocusedLabelColor = Color.White.copy(alpha = 0.58f),
        cursorColor = Color(0xFF38BDF8)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
            .navigationBarsPadding()
    ) {
        // Upper 50%: Actual Product Preview (Connected Sources + Structured Claude Insight + Live Track Row)
        Column(
            modifier = Modifier
                .weight(0.50f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_tunemind_logo),
                contentDescription = "TuneMind Icon",
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Real Product Preview Container (#120A24 elevated surface, zero 1dp outer border)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF120A24))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF00F0FF),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Claude Listening Brief",
                            color = Color(0xFFF5F3FF),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "Spotify · YT Music · Audiomack",
                        color = Color(0xFFB8B2CC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Structured insight preview row
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1033))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Late-Night Focus Pattern",
                        color = Color(0xFFF5F3FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "You replay high-energy tracks 3.2x faster after 9 PM — queueing fresh rotation to prevent fatigue.",
                        color = Color(0xFFB8B2CC),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }

                // Live playback preview row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF8A2BE2).copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = Color(0xFF00F0FF),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cross-App Queue Synced",
                            color = Color(0xFFF5F3FF),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Unified history, play counts & daily drops",
                            color = Color(0xFFB8B2CC),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF00F0FF),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Lower 50%: Sharp Value Proposition & Full-Width Rounded-28dp Auth Stack
        Column(
            modifier = Modifier
                .weight(0.50f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "TUNEMIND",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFF5F3FF),
                    letterSpacing = 2.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Connect Spotify, YouTube Music & Audiomack. Analyze your listening with Claude.",
                    fontSize = 14.sp,
                    color = Color(0xFFB8B2CC),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(TuneMindSprings.snappy()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (savedAccounts.isNotEmpty()) {
                    val primarySaved = savedAccounts.first()
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = Color(0xFF120A24),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .clickable(enabled = !isSwitchingSavedAccount) {
                                isSwitchingSavedAccount = true
                                scope.launch {
                                    authManager.switchToAccount(primarySaved)
                                    isSwitchingSavedAccount = false
                                    onContinue()
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Continue as ${primarySaved.displayName}",
                                    color = Color(0xFFF5F3FF),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${primarySaved.email} · ${if (primarySaved.provider == "GOOGLE") "Google" else "Email"}",
                                    color = Color(0xFFB8B2CC),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (isSwitchingSavedAccount) "Opening…" else "Switch",
                                color = Color(0xFF00F0FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Primary CTA 1: Continue with Google (1-tap Login or Auto-Create Account)
                val googleBtnInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        authError = null
                        authInfoNotice = null
                        authManager.clearLastAuthError()
                        if (authManager.isSupabaseConfigured()) {
                            googleLaunchStartedAtMs = System.currentTimeMillis()
                            val launched = authManager.launchGoogleLogin(context)
                            if (launched) {
                                awaitingGoogleOAuthCallback = true
                            } else {
                                launchGoogleAccountPicker()
                            }
                        } else {
                            launchGoogleAccountPicker()
                        }
                    },
                    interactionSource = googleBtnInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .tactilePressScale(googleBtnInteraction),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF0D0D0D)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (awaitingGoogleOAuthCallback) {
                            CircularProgressIndicator(
                                color = Color(0xFF0D0D0D),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.ic_google_logo),
                                contentDescription = "Google Sign In",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (awaitingGoogleOAuthCallback) {
                                "Finishing Google Sign-In…"
                            } else {
                                "Continue with Google"
                            },
                            color = Color(0xFF0D0D0D),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (awaitingGoogleOAuthCallback) {
                    TextButton(
                        onClick = {
                            awaitingGoogleOAuthCallback = false
                            launchGoogleAccountPicker()
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = "Browser didn't return? Pick Google account on device",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp
                        )
                    }
                }

                if (!showEmailSheet && !authError.isNullOrBlank()) {
                    Text(
                        text = authError!!,
                        color = Color(0xFFFF6B81),
                        fontSize = 12.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp)
                    )
                }

                // Primary CTA 2: Continue with Email (defaults to Log In)
                val emailBtnInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .tactilePressScale(emailBtnInteraction)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(28.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(28.dp))
                        .clickable(
                            interactionSource = emailBtnInteraction,
                            indication = null
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isSignUpMode = false
                            authError = null
                            authInfoNotice = null
                            showEmailSheet = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_mail),
                            contentDescription = "Email Sign In",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Continue with Email",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Bottom Footer: "Don't have an account? Create one" + Subtle Legal Links
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            isSignUpMode = true
                            authError = null
                            authInfoNotice = null
                            showEmailSheet = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Don't have an account? ",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Create one",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Terms of Use",
                        color = Color.White.copy(alpha = 0.44f),
                        fontSize = 11.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenLegal(1) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Text(
                        text = " · ",
                        color = Color.White.copy(alpha = 0.36f),
                        fontSize = 11.5.sp
                    )
                    Text(
                        text = "Privacy Policy",
                        color = Color.White.copy(alpha = 0.44f),
                        fontSize = 11.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenLegal(0) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    if (showEmailSheet) {
        val emailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEmailSheet = false },
            sheetState = emailSheetState,
            containerColor = Color(0xFF140D24),
            contentColor = Color(0xFFF8FAFC),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.22f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (isSignUpMode) "Create your account" else "Log in to TuneMind",
                    color = Color(0xFFF8FAFC),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isSignUpMode) {
                        "Your listening intelligence & history stay backed up privately to this account."
                    } else {
                        "Welcome back. Sign in to restore your playlists, history, and stats."
                    },
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                OutlinedTextField(
                    value = emailInput,
                    onValueChange = {
                        emailInput = it
                        authError = null
                        authInfoNotice = null
                    },
                    label = { Text("Email address") },
                    placeholder = { Text("you@example.com", color = Color.White.copy(alpha = 0.34f)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                AnimatedVisibility(
                    visible = isSignUpMode,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Display name (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        shape = RoundedCornerShape(16.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = {
                        passwordInput = it
                        authError = null
                        authInfoNotice = null
                    },
                    label = { Text(if (isSignUpMode) "Create password" else "Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submitEmailAuth() }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!isSignUpMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "Forgot password?",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isSubmitting) { submitForgotPassword() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { ageConfirmed = !ageConfirmed }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = ageConfirmed,
                                onCheckedChange = { ageConfirmed = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF00F0FF),
                                    uncheckedColor = Color(0xFFB8B2CC),
                                    checkmarkColor = Color(0xFF07020F)
                                )
                            )
                            Text(
                                text = "I confirm I am at least 13 years old (or 16+ where required by local law).",
                                color = Color(0xFFD6D1E6),
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { termsConsentChecked = !termsConsentChecked }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = termsConsentChecked,
                                onCheckedChange = { termsConsentChecked = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF00F0FF),
                                    uncheckedColor = Color(0xFFB8B2CC),
                                    checkmarkColor = Color(0xFF07020F)
                                )
                            )
                            Text(
                                text = "I agree to TuneMind's Terms of Service and Privacy Policy (including AI insight processing).",
                                color = Color(0xFFD6D1E6),
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (!authInfoNotice.isNullOrBlank()) {
                    Text(
                        text = authInfoNotice!!,
                        color = Color(0xFF34D399),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp
                    )
                }

                if (!authError.isNullOrBlank()) {
                    Text(
                        text = authError!!,
                        color = Color(0xFFFF6B81),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp
                    )
                }

                Button(
                    onClick = { submitEmailAuth() },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFAFAFA),
                        contentColor = Color(0xFF09090B)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            color = Color(0xFF09090B),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = when {
                            isSubmitting -> "Please wait…"
                            isSignUpMode -> "Create Account"
                            else -> "Log In"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSignUpMode) "Already have an account? " else "Don't have an account? ",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.5.sp
                    )
                    Text(
                        text = if (isSignUpMode) "Log in" else "Create one",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isSubmitting) {
                                isSignUpMode = !isSignUpMode
                                authError = null
                                authInfoNotice = null
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    if (showGoogleConfirmSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showGoogleConfirmSheet = false },
            sheetState = sheetState,
            containerColor = theme.cardSurface,
            contentColor = Color(0xFFF8FAFC),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.22f)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = "Continue with Google",
                    color = Color(0xFFF8FAFC),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = selectedGoogleEmail.firstOrNull()?.uppercase() ?: "G",
                            color = Color(0xFF10B981),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedGoogleName.ifBlank { selectedGoogleEmail.substringBefore("@").ifBlank { "Google Account" } },
                            color = Color(0xFFF8FAFC),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (selectedGoogleEmail.isNotBlank()) {
                            Text(
                                text = selectedGoogleEmail,
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                if (selectedGoogleEmail.isBlank()) {
                    OutlinedTextField(
                        value = selectedGoogleEmail,
                        onValueChange = { selectedGoogleEmail = it },
                        label = { Text("Google email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done
                        ),
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                StudioHairlineDivider()

                Button(
                    onClick = {
                        val finalEmail = selectedGoogleEmail.trim().ifBlank { "listener@gmail.com" }
                        val finalName = selectedGoogleName.trim().ifBlank { finalEmail.substringBefore("@") }
                        authManager.completeOnDeviceGoogleSignIn(finalEmail, finalName)
                        showGoogleConfirmSheet = false
                        onContinue()
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
                        text = if (selectedGoogleName.isNotBlank()) "Continue as $selectedGoogleName" else "Sign in with Google",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }

                TextButton(
                    onClick = {
                        showGoogleConfirmSheet = false
                        launchGoogleAccountPicker()
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = "Use a different account",
                        color = Color.White.copy(alpha = 0.64f),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionSetupStep(
    stepIndex: Int,
    totalSteps: Int,
    icon: ImageVector,
    accent: Color,
    title: String,
    description: String,
    primaryButtonText: String,
    helperNote: String? = null,
    helperActionText: String? = null,
    onHelperActionClick: (() -> Unit)? = null,
    onPrimaryClick: () -> Unit,
    onSkipClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Minimal step progress
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Step $stepIndex of $totalSteps",
                color = Color(0xFFB8B2CC),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in 1..totalSteps) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                if (i <= stepIndex) accent else Color.White.copy(alpha = 0.12f)
                            )
                    )
                }
            }
        }

        // Tactile dimensional permission card
        SoftGlassCard(
            modifier = Modifier.fillMaxWidth(),
            accentTint = accent,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = title,
                    color = Color(0xFFF8FAFC),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = description,
                    color = Color(0xFFC8C2DC),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
                if (!helperNote.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = helperNote,
                        color = accent.copy(alpha = 0.88f),
                        fontSize = 12.5.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Bottom-pinned primary action + optional helper + quiet skip
        val haptics = LocalHapticFeedback.current
        val primaryInteraction = remember { MutableInteractionSource() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(TuneMindSprings.snappy()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPrimaryClick()
                },
                interactionSource = primaryInteraction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF8FAFC),
                    contentColor = Color(0xFF07090E)
                ),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .tactilePressScale(primaryInteraction)
            ) {
                Text(
                    text = primaryButtonText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!helperActionText.isNullOrBlank() && onHelperActionClick != null) {
                TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onHelperActionClick()
                }) {
                    Text(
                        text = helperActionText,
                        color = accent.copy(alpha = 0.90f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            TextButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSkipClick()
            }) {
                Text(
                    text = "Skip for now",
                    color = Color(0xFFB8B2CC),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
