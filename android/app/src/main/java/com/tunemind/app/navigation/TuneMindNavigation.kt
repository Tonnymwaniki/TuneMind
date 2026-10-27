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
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.tunemind.app.ui.TuneMindViewModel
import com.tunemind.app.ui.screens.*
import com.tunemind.app.ui.theme.*

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Outlined.Home)
    object CrossApp : Screen("cross_app", "Ingest", Icons.Outlined.Smartphone)
    object Fatigue : Screen("fatigue", "Fatigue", Icons.Outlined.LocalFireDepartment)
    object CooldownManagement : Screen("cooldown_mgmt", "Cooldown", Icons.Outlined.AcUnit)
    object History : Screen("history", "Sessions", Icons.Outlined.History)

    // Additional Deep-Dive and Utility Routes
    object Profile : Screen("profile", "Profile", Icons.Outlined.Person)
    object SessionDetails : Screen("session_details", "Session", Icons.Outlined.GraphicEq)
    object SevenDayStudy : Screen("seven_day_study", "7-Day Study", Icons.Outlined.DateRange)
    object Settings : Screen("settings", "Settings", Icons.Outlined.Settings)
    object WeeklyReport : Screen("weekly_report", "Receptive Horizon", Icons.Outlined.Insights)
    object ManualCooldown : Screen("manual_cooldown", "Add to Cooldown", Icons.Outlined.AddCircleOutline)
}

@Composable
fun TuneMindAppNavigation(navController: NavHostController = rememberNavController()) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        containerColor = Color(0xFF020617), // Slate-950
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
                        popUpTo(Screen.Home.route) { saveState = true }
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
                    onOpenStudy = { navController.navigate(Screen.SevenDayStudy.route) }
                )
            }
            composable(Screen.CrossApp.route) {
                CrossAppActivityScreen(vm = vm, onBack = { navController.popBackStack() })
            }
            composable(Screen.Fatigue.route) {
                SongFatigueScreen(
                    onOpenCalibration = { navController.navigate(Screen.WeeklyReport.route) },
                    onOpenCooldown = { navController.navigate(Screen.CooldownManagement.route) }
                )
            }
            composable(Screen.CooldownManagement.route) {
                CooldownManagementScreen(
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
                ListeningSessionDetailsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SevenDayStudy.route) {
                SevenDayStudyScreen(vm = vm, onBack = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCrossApp = { navController.navigate(Screen.CrossApp.route) },
                    onOpenWeeklyReport = { navController.navigate(Screen.WeeklyReport.route) }
                )
            }
            composable(Screen.WeeklyReport.route) {
                WeeklyReportScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.ManualCooldown.route) {
                ManualCooldownSelectionScreen(onBack = { navController.popBackStack() })
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
        Screen.CrossApp.route,
        Screen.Fatigue.route,
        Screen.CooldownManagement.route,
        Screen.History.route
    )

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
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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
                            .background(AccentGreen)
                    )
                }
                Text(
                    text = when (currentRoute) {
                        Screen.Home.route -> "Home Dashboard"
                        Screen.CrossApp.route -> "Phone Ingestion Hub"
                        Screen.Fatigue.route -> "Song Fatigue Lab"
                        Screen.CooldownManagement.route -> "Cooldown Vault"
                        Screen.History.route -> "Acoustic Log"
                        Screen.Profile.route -> "Sonic Genome"
                        Screen.SessionDetails.route -> "Session Breakdown"
                        Screen.SevenDayStudy.route -> "7-Day Study"
                        Screen.Settings.route -> "Settings & Sync"
                        Screen.WeeklyReport.route -> "Receptive Horizon"
                        Screen.ManualCooldown.route -> "Manual Cooldown"
                        else -> "Live Intelligence"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { onNavigate(Screen.SevenDayStudy.route) }) {
                Icon(Icons.Default.DateRange, contentDescription = "Study", tint = Color(0xFF94A3B8))
            }
            IconButton(onClick = { onNavigate(Screen.Settings.route) }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF94A3B8))
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(PrimaryViolet),
                contentAlignment = Alignment.Center
            ) {
                Text("T", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
        Screen.CrossApp,
        Screen.Fatigue,
        Screen.CooldownManagement,
        Screen.History
    )

    NavigationBar(
        containerColor = Color(0xFF090D16),
        tonalElevation = 8.dp,
        modifier = Modifier.navigationBarsPadding()
    ) {
        items.forEach { screen ->
            val selected = currentRoute == screen.route
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(screen.route) },
                icon = {
                    Icon(
                        screen.icon,
                        contentDescription = screen.title,
                        tint = if (selected) AccentCyan else Color(0xFF64748B),
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = {
                    Text(
                        screen.title,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
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
