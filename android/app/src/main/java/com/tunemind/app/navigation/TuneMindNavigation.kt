package com.tunemind.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    // 4 Core Streamlined Bottom Navigation Destinations
    object Home : Screen("home", "Pulse", Icons.Outlined.GraphicEq)
    object Fatigue : Screen("fatigue", "Satiety Lab", Icons.Outlined.LocalFireDepartment)
    object History : Screen("history", "Timeline", Icons.Outlined.History)
    object SevenDayStudy : Screen("seven_day_study", "Intelligence", Icons.Outlined.DateRange)

    // Secondary Drilldown & Utility Screens
    object CrossApp : Screen("cross_app", "Daemons & Ingest", Icons.Outlined.Sensors)
    object CooldownManagement : Screen("cooldown_mgmt", "Cooldown Vault", Icons.Outlined.AcUnit)
    object Profile : Screen("profile", "Sonic Genome", Icons.Outlined.Person)
    object SessionDetails : Screen("session_details", "Session", Icons.Outlined.GraphicEq)
    object Settings : Screen("settings", "Settings", Icons.Outlined.Settings)
    object WeeklyReport : Screen("weekly_report", "Receptive Horizon", Icons.Outlined.Insights)
    object ManualCooldown : Screen("manual_cooldown", "Add to Cooldown", Icons.Outlined.AddCircleOutline)
    object MusicalMoments : Screen("musical_moments", "Musical Moments", Icons.Outlined.BookmarkBorder)
    object HearingInsights : Screen("hearing_insights", "Hearing Insights", Icons.Outlined.Headphones)
}

@Composable
fun TuneMindAppNavigation(navController: NavHostController = rememberNavController()) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        containerColor = Color(0xFF020617), // Deep slate-950
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
                onTabSelected = { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    ) { innerPadding ->
        val vm: TuneMindViewModel = viewModel()

        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    vm = vm,
                    onOpenFatigue = { navController.navigate(Screen.Fatigue.route) },
                    onOpenHistory = { navController.navigate(Screen.History.route) },
                    onOpenWeeklyReport = { navController.navigate(Screen.WeeklyReport.route) },
                    onOpenStudy = { navController.navigate(Screen.SevenDayStudy.route) },
                    onOpenMoments = { navController.navigate(Screen.MusicalMoments.route) },
                    onOpenHearing = { navController.navigate(Screen.HearingInsights.route) }
                )
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
                MusicProfileScreen(vm = vm, onBack = { navController.popBackStack() })
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(Color(0xFF020617))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isRootTab) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F172A)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "TuneMind Logo",
                        tint = AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "TUNEMIND",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentCyan,
                        letterSpacing = 1.5.sp
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isDaemonConnected) AccentGreen else AccentAmber)
                    )
                }
                Text(
                    text = when (currentRoute) {
                        Screen.Home.route -> "Live Pulse"
                        Screen.Fatigue.route -> "Satiety Lab"
                        Screen.History.route -> "Playback Timeline"
                        Screen.SevenDayStudy.route -> "7-Day Intelligence"
                        Screen.CrossApp.route -> "Observer & Ingest"
                        Screen.CooldownManagement.route -> "Cooldown Vault"
                        Screen.Profile.route -> "Sonic Genome"
                        Screen.SessionDetails.route -> "Session Breakdown"
                        Screen.Settings.route -> "Settings & Sync"
                        Screen.WeeklyReport.route -> "Receptive Horizon"
                        Screen.ManualCooldown.route -> "Manual Cooldown"
                        Screen.MusicalMoments.route -> "My Musical Moments"
                        Screen.HearingInsights.route -> "Hearing Insights"
                        else -> "Live Intelligence"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Top Right Utility Controls (Moments + Hearing + Live Daemon Pill + Settings)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Musical Moments quick shortcut
            IconButton(onClick = { onNavigate(Screen.MusicalMoments.route) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Musical Moments", tint = AccentCyan, modifier = Modifier.size(19.dp))
            }

            // Hearing Insights quick shortcut
            IconButton(onClick = { onNavigate(Screen.HearingInsights.route) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Headphones, contentDescription = "Hearing Insights", tint = AccentCyanBright, modifier = Modifier.size(19.dp))
            }

            // Live Daemon Pill Button (opens Ingest & Daemon screen)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isDaemonConnected) Color(0xFF064E3B).copy(alpha = 0.35f) else Color(0xFF451A03).copy(alpha = 0.4f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDaemonConnected) Color(0xFF10B981).copy(alpha = 0.5f) else Color(0xFFF59E0B).copy(alpha = 0.5f)
                ),
                modifier = Modifier.clickable { onNavigate(Screen.CrossApp.route) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isDaemonConnected) AccentGreen else AccentAmber)
                    )
                    Text(
                        text = if (isDaemonConnected) "LIVE" else "OFFLINE",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isDaemonConnected) AccentGreen else AccentAmber
                    )
                }
            }

            IconButton(onClick = { onNavigate(Screen.Settings.route) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF94A3B8), modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
fun TuneMindBottomNavigationBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit
) {
    val items = listOf(
        Screen.Home,
        Screen.Fatigue,
        Screen.History,
        Screen.SevenDayStudy
    )

    NavigationBar(
        containerColor = Color(0xFF090D16),
        tonalElevation = 8.dp,
        modifier = Modifier.navigationBarsPadding()
    ) {
        items.forEach { screen ->
            val selected = when (screen) {
                Screen.Home -> currentRoute in listOf(Screen.Home.route, Screen.CrossApp.route, Screen.Settings.route)
                Screen.Fatigue -> currentRoute in listOf(Screen.Fatigue.route, Screen.ManualCooldown.route, Screen.CooldownManagement.route)
                Screen.History -> currentRoute in listOf(Screen.History.route, Screen.SessionDetails.route, Screen.Profile.route)
                Screen.SevenDayStudy -> currentRoute in listOf(Screen.SevenDayStudy.route, Screen.WeeklyReport.route)
                else -> currentRoute == screen.route
            }

            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(screen.route) },
                icon = {
                    Icon(
                        screen.icon,
                        contentDescription = screen.title,
                        tint = if (selected) AccentCyan else Color(0xFF64748B),
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        screen.title,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) AccentCyanBright else Color(0xFF64748B)
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color(0xFF1E293B)
                )
            )
        }
    }
}
