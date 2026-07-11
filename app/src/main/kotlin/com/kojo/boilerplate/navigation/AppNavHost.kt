package com.kojo.boilerplate.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kojo.boilerplate.feature.home.HomeScreen
import com.kojo.boilerplate.feature.profile.ProfileScreen
import com.kojo.boilerplate.feature.scanner.BarcodeScannerScreen
import com.kojo.boilerplate.feature.signin.GoogleSignInScreen

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: AppDestination = AppDestination.SignIn,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<AppDestination.SignIn> {
            GoogleSignInScreen(
                onSignedIn = {
                    navController.navigate(AppDestination.Home) {
                        popUpTo(AppDestination.SignIn) { inclusive = true }
                    }
                },
            )
        }

        composable<AppDestination.Home> {
            HomeScreen(
                onNavigateToProfile = { userId ->
                    navController.navigate(AppDestination.Profile(userId = userId))
                },
                onNavigateToBarcodeScanner = {
                    navController.navigate(AppDestination.BarcodeScanner)
                },
            )
        }

        composable<AppDestination.Profile> {
            ProfileScreen(
                onNavigateUp = navController::navigateUp,
            )
        }

        composable<AppDestination.BarcodeScanner> {
            BarcodeScannerScreen(
                onNavigateUp = navController::navigateUp,
            )
        }
    }
}
