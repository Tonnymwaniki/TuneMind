package com.tunemind.app.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunemind.app.R
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.auth.TuneMindUserSession
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.TuneMindAiIntelligenceLoop
import com.tunemind.app.ui.theme.LocalAdaptiveTheme
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
        AnimatedContent(
            targetState = currentStep,
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
                        primaryButtonText = "Allow Microphone",
                        onPrimaryClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
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
                        description = "Lets TuneMind recognize what is playing across your music apps and protect favorites from overplay.",
                        primaryButtonText = "Allow Music Access",
                        onPrimaryClick = { settingsLauncher.launch(PermissionUtils.getNotificationListenerIntent()) },
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
                        description = "Keeps your Spotify listening history up to date and playback responsive even with your screen off.",
                        primaryButtonText = "Allow Background Sync",
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthLoginStep(
    session: TuneMindUserSession,
    authManager: TuneMindAuthSessionManager,
    onContinue: () -> Unit
) {
    val theme = LocalAdaptiveTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var isSignUpMode by remember { mutableStateOf(true) }
    var emailInput by remember { mutableStateOf(session.email) }
    var passwordInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf(session.displayName) }
    var authError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    var showEmailSheet by remember { mutableStateOf(false) }
    var showGoogleConfirmSheet by remember { mutableStateOf(false) }
    var selectedGoogleEmail by remember { mutableStateOf(session.email.ifBlank { "" }) }
    var selectedGoogleName by remember { mutableStateOf(session.displayName) }

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
            return
        }
        if (passwordInput.length < 4) {
            authError = "Password must be at least 4 characters."
            return
        }
        authError = null
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
                authError = err.message ?: "Could not sign in with email"
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFF8FAFC),
        unfocusedTextColor = Color(0xFFF8FAFC),
        focusedContainerColor = Color(0xFF0D0D0D).copy(alpha = 0.78f),
        unfocusedContainerColor = Color(0xFF0D0D0D).copy(alpha = 0.56f),
        focusedBorderColor = Color(0xFF00F0FF),
        unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
        focusedLabelColor = Color(0xFF00F0FF),
        unfocusedLabelColor = Color.White.copy(alpha = 0.58f),
        cursorColor = Color(0xFF00F0FF)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07020D))
            .navigationBarsPadding()
    ) {
        // Upper 50%: Lottie Brain-to-Soundwave Neural Loop Hero + Emblem
        Box(
            modifier = Modifier
                .weight(0.50f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Subtle dark indigo / violet ambient glow behind Lottie loop
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF8A2BE2).copy(alpha = 0.24f),
                                Color(0xFF00F0FF).copy(alpha = 0.10f),
                                Color.Transparent
                            )
                        )
                    )
                    .blur(40.dp)
            )

            TuneMindAiIntelligenceLoop(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 24.dp),
                speed = 1.05f,
                alpha = 0.94f
            )

            Image(
                painter = painterResource(id = R.drawable.ic_tunemind_logo),
                contentDescription = "TuneMind Icon",
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Fit
            )

            // Subtle Guest Pass-through in top-right safe area
            TextButton(
                onClick = onContinue,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 6.dp, end = 12.dp)
            ) {
                Text(
                    text = "Continue as Guest",
                    color = Color(0xFF00F0FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Soft bottom fade transitioning neural graphic seamlessly into dark action container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xFF07020D))
                        )
                    )
            )
        }

        // Lower 50%: Brand Pitch & Full-Width Rounded-28dp Auth Stack
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_tunemind_logo),
                        contentDescription = "App Icon",
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TUNEMIND",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 2.5.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sync your music mind with personal listening intelligence.",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Button 1: Continue with Google
                Button(
                    onClick = { launchGoogleAccountPicker() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
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
                        if (session.isGoogleConnected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(20.dp)
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
                            text = if (session.isGoogleConnected) {
                                "Google · ${session.email.ifBlank { session.displayName }}"
                            } else {
                                "Continue with Google"
                            },
                            color = Color(0xFF0D0D0D),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Button 2: Continue with Spotify
                Button(
                    onClick = {
                        authError = null
                        authManager.launchSpotifyLogin(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1DB954),
                        contentColor = Color(0xFF0D0D0D)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (session.isSpotifyConnected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color(0xFF0D0D0D),
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.ic_spotify_logo),
                                contentDescription = "Spotify Sign In",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (session.isSpotifyConnected) "Spotify Connected" else "Continue with Spotify",
                            color = Color(0xFF0D0D0D),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Button 3: Continue with Email (Translucent, Secondary)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(28.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(28.dp))
                        .clickable {
                            isSignUpMode = true
                            authError = null
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

                if (session.isLoggedIn || session.isSpotifyConnected || session.isGoogleConnected) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00F0FF),
                            contentColor = Color(0xFF0D0D0D)
                        )
                    ) {
                        Text(
                            text = "Enter TuneMind",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Bottom-most Log In Action
            Row(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 18.dp)
                    .clickable {
                        isSignUpMode = false
                        authError = null
                        showEmailSheet = true
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Already have an account? ",
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 14.sp
                )
                Text(
                    text = "Log In",
                    color = Color(0xFF00F0FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
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
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (isSignUpMode) "Create your TuneMind" else "Welcome back",
                    color = Color(0xFFF8FAFC),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                MagicalSegmentedTabs(
                    tabs = listOf(
                        MagicalTabItem("Create Account", Color(0xFF00F0FF)),
                        MagicalTabItem("Log In", Color(0xFF8A2BE2))
                    ),
                    selectedIndex = if (isSignUpMode) 0 else 1,
                    onSelectTab = { idx ->
                        isSignUpMode = idx == 0
                        authError = null
                    }
                )

                OutlinedTextField(
                    value = emailInput,
                    onValueChange = {
                        emailInput = it
                        authError = null
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
                    },
                    label = { Text(if (isSignUpMode) "Create password" else "Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
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

                if (!authError.isNullOrBlank()) {
                    Text(
                        text = authError!!,
                        color = Color(0xFFFF6B81),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = { submitEmailAuth() },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00F0FF),
                        contentColor = Color(0xFF0D0D0D)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
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
                color = Color.White.copy(alpha = 0.56f),
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
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }
        }

        // Bottom-pinned primary action + quiet skip
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onPrimaryClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF8FAFC),
                    contentColor = Color(0xFF07090E)
                ),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = primaryButtonText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            TextButton(onClick = onSkipClick) {
                Text(
                    text = "Skip for now",
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
