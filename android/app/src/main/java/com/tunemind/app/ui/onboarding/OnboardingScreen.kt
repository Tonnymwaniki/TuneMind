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
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.auth.TuneMindUserSession
import com.tunemind.app.ui.components.MagicalSegmentedTabs
import com.tunemind.app.ui.components.MagicalTabItem
import com.tunemind.app.ui.components.SoftGlassCard
import com.tunemind.app.ui.components.StudioAmbientBackground
import com.tunemind.app.ui.components.StudioHairlineDivider
import com.tunemind.app.ui.components.TuneMindLogoMark
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
                onContinue()
            }.onFailure { err ->
                authError = err.message ?: "Could not sign in with email"
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFF8FAFC),
        unfocusedTextColor = Color(0xFFF8FAFC),
        focusedContainerColor = Color(0xFF080C15).copy(alpha = 0.72f),
        unfocusedContainerColor = Color(0xFF080C15).copy(alpha = 0.56f),
        focusedBorderColor = theme.primaryAccent,
        unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
        focusedLabelColor = theme.primaryAccent,
        unfocusedLabelColor = Color.White.copy(alpha = 0.58f),
        cursorColor = theme.primaryAccent
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TuneMindLogoMark(
                size = 48.dp,
                showContainer = false,
                animatedWave = true
            )

            TextButton(onClick = onContinue) {
                Text(
                    text = "Continue as Guest",
                    color = theme.primaryAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isSignUpMode) "Create your TuneMind" else "Welcome back",
            color = Color(0xFFF8FAFC),
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Keep your favorite songs fresh, build daily mixes, and sync across your music players.",
            color = Color.White.copy(alpha = 0.66f),
            fontSize = 14.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Main Auth Card with Magical Switch between Register & Sign In
        SoftGlassCard(
            modifier = Modifier.fillMaxWidth(),
            accentTint = theme.primaryAccent
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MagicalSegmentedTabs(
                    tabs = listOf(
                        MagicalTabItem("Create Account", theme.primaryAccent),
                        MagicalTabItem("Sign In", theme.secondaryAccent)
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
                    shape = RoundedCornerShape(14.dp),
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
                        shape = RoundedCornerShape(14.dp),
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
                    shape = RoundedCornerShape(14.dp),
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
                        containerColor = Color(0xFFF8FAFC),
                        contentColor = Color(0xFF07090E)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = when {
                            isSubmitting -> "Please wait…"
                            isSignUpMode -> "Create Account"
                            else -> "Sign In"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Hairline divider
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StudioHairlineDivider(modifier = Modifier.weight(1f))
            Text(
                text = "or continue quickly with",
                color = Color.White.copy(alpha = 0.46f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            StudioHairlineDivider(modifier = Modifier.weight(1f))
        }

        // Quick Social & Player Card
        SoftGlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { launchGoogleAccountPicker() },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF0B101B).copy(alpha = 0.8f),
                        contentColor = Color(0xFFF8FAFC)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (session.isGoogleConnected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Text(
                            text = "G",
                            color = Color(0xFFF8FAFC),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = if (session.isGoogleConnected) {
                            "Google · ${session.email.ifBlank { session.displayName }}"
                        } else {
                            "Continue with Google"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = {
                        authError = null
                        authManager.launchSpotifyLogin(context)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1DB954),
                        contentColor = Color(0xFF040605)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(
                        imageVector = if (session.isSpotifyConnected) Icons.Rounded.Check else Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (session.isSpotifyConnected) "Spotify Connected" else "Continue with Spotify",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (session.isLoggedIn || session.isSpotifyConnected || session.isGoogleConnected) {
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = theme.primaryAccent,
                    contentColor = Color(0xFF040605)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = "Enter TuneMind",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
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
                    shape = RoundedCornerShape(14.dp),
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
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
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
