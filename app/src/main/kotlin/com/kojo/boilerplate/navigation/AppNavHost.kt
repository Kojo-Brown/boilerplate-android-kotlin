package com.kojo.boilerplate.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kojo.boilerplate.feature.home.HomeScreen
import com.kojo.boilerplate.feature.profile.ProfileScreen

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: AppDestination = AppDestination.Home,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<AppDestination.Home> {
            HomeScreen(
                onNavigateToProfile = { userId ->
                    navController.navigate(AppDestination.Profile(userId = userId))
                },
            )
        }

        composable<AppDestination.Profile> {
            ProfileScreen(
                onNavigateUp = navController::navigateUp,
            )
        }
    }
}
