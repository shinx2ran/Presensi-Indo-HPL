package com.indohpl.presensi.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.ui.checkin.CheckInScreen
import com.indohpl.presensi.ui.home.HomeScreen
import com.indohpl.presensi.ui.recap.RecapScreen
import com.indohpl.presensi.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val RECAP = "recap"
    const val SETTINGS = "settings"
    const val CHECK_IN = "checkin/{employeeId}"
    fun checkIn(employeeId: Long) = "checkin/$employeeId"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Beranda", Icons.Filled.Home),
    Tab(Routes.RECAP, "Rekap", Icons.Filled.Summarize),
    Tab(Routes.SETTINGS, "Pengaturan", Icons.Filled.Settings),
)

@Composable
fun PresensiNavHost(repository: Repository) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    repository = repository,
                    onCheckIn = { id -> navController.navigate(Routes.checkIn(id)) },
                    onMessage = ::toast,
                )
            }
            composable(
                Routes.CHECK_IN,
                arguments = listOf(navArgument("employeeId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("employeeId") ?: 0L
                CheckInScreen(
                    repository = repository,
                    employeeId = id,
                    onFinished = { msg ->
                        toast(msg)
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(Routes.RECAP) {
                RecapScreen(repository = repository, onMessage = ::toast)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(repository = repository, onMessage = ::toast)
            }
        }
    }
}
