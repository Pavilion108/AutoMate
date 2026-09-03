package com.automate.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.automate.ui.dashboard.DashboardScreen
import com.automate.ui.taskeditor.TaskEditorScreen
import com.automate.ui.geofencemanager.GeofenceManagerScreen
import com.automate.ui.accounts.AccountsScreen
import com.automate.ui.settings.SettingsScreen
import com.automate.ui.setup.SetupWizardScreen

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object TaskEditor : Screen("task_editor/{taskId}") {
        fun createRoute(taskId: Long = -1) = "task_editor/$taskId"
    }
    data object GeofenceManager : Screen("geofence_manager")
    data object Accounts : Screen("accounts")
    data object Settings : Screen("settings")
    data object Setup : Screen("setup")
}

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToTaskEditor = { taskId ->
                    navController.navigate(Screen.TaskEditor.createRoute(taskId))
                },
                onNavigateToGeofenceManager = {
                    navController.navigate(Screen.GeofenceManager.route)
                },
                onNavigateToAccounts = {
                    navController.navigate(Screen.Accounts.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.TaskEditor.route) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId")?.toLongOrNull() ?: -1
            TaskEditorScreen(
                taskId = taskId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.GeofenceManager.route) {
            GeofenceManagerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Accounts.route) {
            AccountsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Setup.route) {
            SetupWizardScreen(
                onSetupComplete = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
