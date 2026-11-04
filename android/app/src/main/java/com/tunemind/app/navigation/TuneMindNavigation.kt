package com.tunemind.app.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.tunemind.app.service.TuneMindListenerService
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.screens.*
import com.tunemind.app.ui.theme.*

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    // 5 Core Streamlined Bottom Navigation Destinations (Hardware Switchboard Style)
    object Home : Screen("home", "Pulse", Icons.Outlined.GraphicEq)
    object AiDiscovery : Screen("ai_discovery", "Crate", Icons.Outlined.AutoAwesome)
    object Fatigue : Screen("fatigue", "Lab", Icons.Outlined.LocalFireDepartment)
    object History : Screen("history", "Timeline", Icons.Outlined.Reorder)
    object SevenDayStudy : Screen("seven_day_study", "Intel", Icons.Outlined.QueryStats)

    // Secondary Drilldown & Utility Screens
    object CrossApp : Screen("cross_app", "Daemons & Ingest", Icons.Outlined.Sensors)
    object CooldownManagement : Screen("cooldown_mgmt", "Cooldown Vault", Icons.Outlined.AcUnit)
    object Profile : Screen("profile", "Sonic Genome", Icons.Outlined.Person)
    object SessionDetails : Screen("session_details", "Session", Icons.Outlined.GraphicEq)
    object Settings : Screen("settings", "Switchboard", Icons.Outlined.Settings)
    object WeeklyReport : Screen("weekly_report", "Receptive Horizon", Icons.Outlined.Insights)
    object ManualCooldown : Screen("manual_cooldown", "Add to Cooldown", Icons.Outlined.AddCircleOutline)
    object MusicalMoments : Screen("musical_moments", "Moments", Icons.Outlined.BookmarkBorder)
    object HearingInsights : Screen("hearing_insights", "Hearing", Icons.Outlined.Headphones)
}

@Composable
fun TuneMindAppNavigation(navController: NavHostController = rememberNavController()) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        containerColor = TuneMindTheme.colors.primaryBackground,
        topBar = {
            TuneMindTopAppBar(
                currentRoute = currentRoute,
                onNavigate = { route -> navController.navigate(route) },
                onBack = { navController.popBackStack() }
            )
        },
        bottomBar = {
            TuneMindBottomNavigationBar(
                currentRoute = currentRoute,
                onTabSelected = { targetRoute ->
                    if (currentRoute == targetRoute) return@TuneMindBottomNavigationBar
                    try {
                        if (targetRoute == Screen.Home.route) {
                            val popped = navController.popBackStack(Screen.Home.route, inclusive = false)
                            if (!popped) {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        inclusive = false
                                    }
                                    launchSingleTop = true
                                }
                            }
                        } else {
                            navController.navigate(targetRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    } catch (_: Exception) {
                        navController.navigate(targetRoute)
                    }
                }
            )
        }
    ) { innerPadding ->
        val vm: TuneMindViewModel = viewModel()

        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                fadeIn(animationSpec = tween(320)) + scaleIn(initialScale = 0.94f, animationSpec = tween(320, easing = FastOutSlowInEasing))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(220)) + scaleOut(targetScale = 1.03f, animationSpec = tween(220, easing = FastOutSlowInEasing))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(320)) + scaleIn(initialScale = 1.03f, animationSpec = tween(320, easing = FastOutSlowInEasing))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(220)) + scaleOut(targetScale = 0.94f, animationSpec = tween(220, easing = FastOutSlowInEasing))
            }
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    vm = vm,
                    onOpenFatigue = { navController.navigate(Screen.Fatigue.route) },
                    onOpenHistory = { navController.navigate(Screen.History.route) },
                    onOpenWeeklyReport = { navController.navigate(Screen.WeeklyReport.route) },
                    onOpenStudy = { navController.navigate(Screen.SevenDayStudy.route) },
                    onOpenMoments = { navController.navigate(Screen.MusicalMoments.route) },
                    onOpenHearing = { navController.navigate(Screen.HearingInsights.route) },
                    onOpenAiDiscovery = { navController.navigate(Screen.AiDiscovery.route) },
                    onOpenProfile = { navController.navigate(Screen.Profile.route) },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable(Screen.AiDiscovery.route) {
                AiDiscoveryScreen(vm = vm, onBack = { navController.popBackStack() })
            }
            composable(Screen.MusicalMoments.route) {
                MusicalMomentsScreen(vm = vm, onBack = { navController.popBackStack() })
            }
            composable(Screen.HearingInsights.route) {
                HearingInsightsScreen(vm = vm, onBack = { navController.popBackStack() })
            }
            composable(Screen.CrossApp.route) {
                CrossAppActivityScreen(vm = vm, onBack = { navController.popBackStack() })
            }
            composable(Screen.Fatigue.route) {
                SongFatigueScreen(
                    vm = vm,
                    onOpenCalibration = { navController.navigate(Screen.WeeklyReport.route) },
                    onOpenCooldown = { navController.navigate(Screen.ManualCooldown.route) }
                )
            }
            composable(Screen.CooldownManagement.route) {
                CooldownManagementScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onAddTrack = { navController.navigate(Screen.ManualCooldown.route) }
                )
            }
            composable(Screen.History.route) {
                ListeningHistoryScreen(vm = vm)
            }
            composable(Screen.Profile.route) {
                MusicProfileScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable(Screen.SessionDetails.route) {
                ListeningSessionDetailsScreen(vm = vm, onBack = { navController.popBackStack() })
            }
            composable(Screen.SevenDayStudy.route) {
                SevenDayStudyScreen(vm = vm, onBack = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onOpenCrossApp = { navController.navigate(Screen.CrossApp.route) },
                    onOpenWeeklyReport = { navController.navigate(Screen.WeeklyReport.route) }
                )
            }
            composable(Screen.WeeklyReport.route) {
                WeeklyReportScreen(vm = vm, onBack = { navController.popBackStack() })
            }
            composable(Screen.ManualCooldown.route) {
                ManualCooldownSelectionScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
fun TuneMindTopAppBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit
) {
    val isRootTab = currentRoute in listOf(
        Screen.Home.route,
        Screen.Fatigue.route,
        Screen.History.route,
        Screen.SevenDayStudy.route
    )

    val isDaemonConnected = TuneMindListenerService.isConnected
    val colors = TuneMindTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepObsidianAmber.SecondaryBackground,
                        DeepObsidianAmber.AppBackground
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        DeepObsidianAmber.CopperAccent.copy(alpha = 0.5f),
                        DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.3f),
                        DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.2f)
                    )
                ),
                shape = RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isRootTab) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DeepObsidianAmber.CardElevated)
                        .border(1.dp, DeepObsidianAmber.SurfaceBorder, RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = DeepObsidianAmber.TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                // Section-specific dynamic accent and animated soundwave
                val activeSectionColor = when (currentRoute) {
                    Screen.Home.route -> DeepObsidianAmber.EQBassCyan
                    Screen.Fatigue.route -> DeepObsidianAmber.SatietyEmber
                    Screen.History.route -> DeepObsidianAmber.NeuralViolet
                    Screen.SevenDayStudy.route -> DeepObsidianAmber.CircadianMorning
                    Screen.CooldownManagement.route -> DeepObsidianAmber.CryoCyan
                    Screen.HearingInsights.route -> DeepObsidianAmber.DecibelSafe
                    else -> DeepObsidianAmber.PrimaryAccent
                }

                val waveAnim = rememberInfiniteTransition(label = "headerSoundwave")
                val wavePulse by waveAnim.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 550, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "wavePulse"
                )

                // Radiant Soundwave Brand Mark with live pulsing motion
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DeepObsidianAmber.RecessedBackground)
                        .border(1.dp, activeSectionColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 6.dp)
                ) {
                    val barHeights = listOf(6, 12, 16, 10, 5)
                    barHeights.forEachIndexed { idx, h ->
                        val animatedH = (h * (0.55f + 0.5f * kotlin.math.abs(kotlin.math.sin(idx * 0.7f + wavePulse * 3.14f)))).coerceIn(3f, 18f)
                        Box(
                            modifier = Modifier
                                .width(2.5.dp)
                                .height(animatedH.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(activeSectionColor)
                        )
                    }
                }
            }

            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "TM // DSP-01",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.2.sp
                    )

                    // Sleek, refined Daemon Status indicator (stored with telemetry header)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isDaemonConnected) DeepObsidianAmber.Success.copy(alpha = 0.15f) else DeepObsidianAmber.Warning.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, if (isDaemonConnected) DeepObsidianAmber.Success.copy(alpha = 0.45f) else DeepObsidianAmber.Warning.copy(alpha = 0.45f)),
                        modifier = Modifier.clickable { onNavigate(Screen.CrossApp.route) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(if (isDaemonConnected) DeepObsidianAmber.Success else DeepObsidianAmber.Warning)
                            )
                            Text(
                                text = if (isDaemonConnected) "LIVE" else "OFFLINE",
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isDaemonConnected) DeepObsidianAmber.Success else DeepObsidianAmber.Warning,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                }
                Text(
                    text = when (currentRoute) {
                        Screen.Home.route -> "Pulse"
                        Screen.Fatigue.route -> "Satiety Lab"
                        Screen.History.route -> "Timeline"
                        Screen.SevenDayStudy.route -> "Circadian Intel"
                        Screen.CrossApp.route -> "Observer & Ingest"
                        Screen.CooldownManagement.route -> "Cooldown Vault"
                        Screen.Profile.route -> "Sonic Genome"
                        Screen.SessionDetails.route -> "Session Breakdown"
                        Screen.Settings.route -> "Studio Switchboard"
                        Screen.WeeklyReport.route -> "Receptive Horizon"
                        Screen.ManualCooldown.route -> "Manual Cooldown"
                        Screen.MusicalMoments.route -> "Musical Moments"
                        Screen.HearingInsights.route -> "Decibel Headroom"
                        Screen.AiDiscovery.route -> "Neuro AI Crate"
                        else -> "TuneMind"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primaryText,
                    letterSpacing = (-0.3).sp
                )
            }
        }

        // Top Right Utility Controls: Exactly 3 well-placed, uncongested buttons
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 1. Decibel Headroom / Hearing Insights
            IconButton(
                onClick = { onNavigate(Screen.HearingInsights.route) },
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.cardSurface)
                    .border(1.dp, DeepObsidianAmber.DecibelSafe.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    Icons.Outlined.Headphones,
                    contentDescription = "Decibel Headroom",
                    tint = DeepObsidianAmber.DecibelSafe,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 2. Musical Moments Marker
            IconButton(
                onClick = { onNavigate(Screen.MusicalMoments.route) },
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.cardSurface)
                    .border(1.dp, DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    Icons.Outlined.GraphicEq,
                    contentDescription = "Musical Moments",
                    tint = colors.primaryAccent,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 3. User Profile Avatar Capsule (leads to Profile Screen, where Settings resides)
            IconButton(
                onClick = { onNavigate(Screen.Profile.route) },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                DeepObsidianAmber.NeuralViolet,
                                DeepObsidianAmber.ElectricViolet
                            )
                        )
                    )
                    .border(1.2.dp, DeepObsidianAmber.NeuralViolet, CircleShape)
            ) {
                Text(
                    text = "T",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun TuneMindBottomNavigationBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit
) {
    // 4 Streamlined, Uncongested Core Tabs: Pulse, Crate, Timeline, Intel
    val items = listOf(
        Screen.Home,
        Screen.AiDiscovery,
        Screen.History,
        Screen.SevenDayStudy
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xF511141D),
            shape = RoundedCornerShape(32.dp),
            shadowElevation = 18.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.2.dp,
                Brush.horizontalGradient(
                    listOf(
                        DeepObsidianAmber.CopperAccent.copy(alpha = 0.55f),
                        DeepObsidianAmber.PrimaryAccent.copy(alpha = 0.35f),
                        DeepObsidianAmber.SurfaceBorder.copy(alpha = 0.5f)
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { screen ->
                    val selected = currentRoute == screen.route

                    val sectionAccent = when (screen) {
                        Screen.Home -> DeepObsidianAmber.EQBassCyan
                        Screen.AiDiscovery -> DeepObsidianAmber.PrimaryAccent
                        Screen.History -> DeepObsidianAmber.NeuralViolet
                        Screen.SevenDayStudy -> DeepObsidianAmber.CircadianMorning
                        else -> DeepObsidianAmber.PrimaryAccent
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onTabSelected(screen.route) },
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = selected,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.9f))
                                    .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.9f))
                            },
                            label = "tabMorph"
                        ) { isTargetSelected ->
                            if (isTargetSelected) {
                                // UNIQUE CLICKED STATE: Glowing illuminated capsule pill
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    sectionAccent.copy(alpha = 0.22f),
                                                    sectionAccent.copy(alpha = 0.08f)
                                                )
                                            )
                                        )
                                        .border(
                                            width = 1.2.dp,
                                            brush = Brush.horizontalGradient(
                                                listOf(
                                                    sectionAccent.copy(alpha = 0.8f),
                                                    sectionAccent.copy(alpha = 0.4f)
                                                )
                                            ),
                                            shape = RoundedCornerShape(22.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Icon(
                                        imageVector = screen.icon,
                                        contentDescription = screen.title,
                                        tint = sectionAccent,
                                        modifier = Modifier.size(19.dp)
                                    )
                                    Text(
                                        text = screen.title.uppercase(),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = sectionAccent,
                                        letterSpacing = 0.6.sp
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(sectionAccent)
                                    )
                                }
                            } else {
                                // CASUAL RESTING STATE: Clean, effortless floating icon
                                Box(
                                    modifier = Modifier.size(44.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = screen.icon,
                                        contentDescription = screen.title,
                                        tint = DeepObsidianAmber.TextSecondary.copy(alpha = 0.5f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
