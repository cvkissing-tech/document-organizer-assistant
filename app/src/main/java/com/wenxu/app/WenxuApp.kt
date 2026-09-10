package com.wenxu.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wenxu.app.navigation.WenxuDestinations
import com.wenxu.app.navigation.WenxuNavGraph
import com.wenxu.app.ui.components.DocumentAssistantBottomBar

@Composable
fun WenxuApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomNavigation = WenxuDestinations.bottomNavigation.any { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomNavigation) {
                DocumentAssistantBottomBar(
                    selectedRoute = currentRoute,
                    onDestination = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(WenxuDestinations.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        WenxuNavGraph(
            navController = navController,
            container = container,
            contentPadding = innerPadding,
        )
    }
}
