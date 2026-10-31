package com.tunemind.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    // 4 Core Streamlined Bottom Navigation Destinations (Hardware Switchboard Style)
    object Home : Screen("home", "Pulse", Icons.Outlined.GraphicEq)
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
    val colors = TuneMindTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(colors.secondaryBackground)
            .border(
                width = 1.dp,
                color = colors.border.copy(alpha = 0.4f),
                shape = RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp)
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isRootTab) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.cardSurface)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = colors.primaryText, modifier = Modifier.size(18.dp))
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.primaryAccent)
                )
            }

            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "TM // DSP-01",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText,
                        letterSpacing = 1.2.sp
                    )
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
                        else -> "TuneMind"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primaryText,
                    letterSpacing = (-0.3).sp
                )
            }
        }

        // Top Right Utility Controls (DSP Link, Graphic EQ, Switchboard)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Musical Moments quick shortcut
            IconButton(
                onClick = { onNavigate(Screen.MusicalMoments.route) },
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.cardSurface)
            ) {
                Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Musical Moments", tint = colors.primaryAccent, modifier = Modifier.size(18.dp))
            }

            // Hearing Insights quick shortcut
            IconButton(
                onClick = { onNavigate(Screen.HearingInsights.route) },
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.cardSurface)
            ) {
                Icon(Icons.Outlined.Headphones, contentDescription = "Hearing Insights", tint = colors.secondaryAccent, modifier = Modifier.size(18.dp))
            }

            // Live Daemon Pill Button
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors.elevatedSurface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDaemonConnected) colors.primaryAccent.copy(alpha = 0.5f) else Color(0xFFF59E0B).copy(alpha = 0.5f)
                ),
                modifier = Modifier.clickable { onNavigate(Screen.CrossApp.route) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isDaemonConnected) Color(0xFF10B981) else Color(0xFFF59E0B))
                    )
                    Text(
                        text = if (isDaemonConnected) "LIVE" else "OFFLINE",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isDaemonConnected) Color(0xFF10B981) else Color(0xFFF59E0B),
                        letterSpacing = 0.8.sp
                    )
                }
            }

            // Studio Switchboard Settings
            IconButton(
                onClick = { onNavigate(Screen.Settings.route) },
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.cardSurface)
            ) {
                Icon(Icons.Default.Tune, contentDescription = "Switchboard", tint = colors.primaryAccent, modifier = Modifier.size(18.dp))
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
    val colors = TuneMindTheme.colors

    Surface(
        color = colors.secondaryBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.35f)),
        modifier = Modifier.navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { screen ->
                val selected = when (screen) {
                    Screen.Home -> currentRoute in listOf(Screen.Home.route, Screen.CrossApp.route)
                    Screen.Fatigue -> currentRoute in listOf(Screen.Fatigue.route, Screen.ManualCooldown.route, Screen.CooldownManagement.route)
                    Screen.History -> currentRoute in listOf(Screen.History.route, Screen.SessionDetails.route, Screen.Profile.route)
                    Screen.SevenDayStudy -> currentRoute in listOf(Screen.SevenDayStudy.route, Screen.WeeklyReport.route)
                    else -> currentRoute == screen.route
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) colors.elevatedSurface else Color.Transparent)
                        .border(
                            width = if (selected) 1.dp else 0.dp,
                            color = if (selected) colors.border else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onTabSelected(screen.route) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = screen.title,
                            tint = if (selected) colors.primaryAccent else colors.secondaryText,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = screen.title.uppercase(),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) colors.primaryText else colors.secondaryText,
                            letterSpacing = 0.6.sp
                        )
                    }
                }
            }
        }
    }
}
