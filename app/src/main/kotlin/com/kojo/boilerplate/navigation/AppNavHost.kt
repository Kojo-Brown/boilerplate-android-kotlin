package com.kojo.boilerplate.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kojo.boilerplate.core.ui.adaptive.AdaptiveNavItem
import com.kojo.boilerplate.core.ui.adaptive.AdaptiveNavigationScaffold
import com.kojo.boilerplate.core.ui.adaptive.useListDetailLayout
import com.kojo.boilerplate.feature.home.HomeTwoPaneScreen
import com.kojo.boilerplate.feature.home.HomeScreen
import com.kojo.boilerplate.feature.profile.ProfileScreen
import com.kojo.boilerplate.feature.scanner.BarcodeScannerScreen
import com.kojo.boilerplate.feature.signin.GoogleSignInScreen
import com.kojo.boilerplate.feature.textrecognition.TextRecognitionScreen

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
                        popUpTo<AppDestination.SignIn> { inclusive = true }
                    }
                },
            )
        }

        composable<AppDestination.Home> {
            val useListDetail = useListDetailLayout()
            var selectedUserId by rememberSaveable { mutableStateOf<String?>(null) }

            MainNavScaffold(
                navController = navController,
                currentTopLevel = TopLevelDestination.HOME,
            ) {
                if (useListDetail) {
                    HomeTwoPaneScreen(
                        selectedUserId = selectedUserId,
                        onUserSelected = { userId -> selectedUserId = userId },
                        onNavigateToBarcodeScanner = {
                            navController.navigate(AppDestination.BarcodeScanner) {
                                popUpTo<AppDestination.Home> { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToTextRecognition = {
                            navController.navigate(AppDestination.TextRecognition) {
                                popUpTo<AppDestination.Home> { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                    )
                } else {
                    HomeScreen(
                        onNavigateToProfile = { userId ->
                            navController.navigate(AppDestination.Profile(userId = userId))
                        },
                        onNavigateToBarcodeScanner = {
                            navController.navigate(AppDestination.BarcodeScanner) {
                                popUpTo<AppDestination.Home> { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToTextRecognition = {
                            navController.navigate(AppDestination.TextRecognition) {
                                popUpTo<AppDestination.Home> { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        }

        composable<AppDestination.Profile> {
            ProfileScreen(onNavigateUp = navController::navigateUp)
        }

        composable<AppDestination.BarcodeScanner> {
            MainNavScaffold(
                navController = navController,
                currentTopLevel = TopLevelDestination.SCANNER,
            ) {
                BarcodeScannerScreen(onNavigateUp = navController::navigateUp)
            }
        }

        composable<AppDestination.TextRecognition> {
            MainNavScaffold(
                navController = navController,
                currentTopLevel = TopLevelDestination.TEXT_RECOGNITION,
            ) {
                TextRecognitionScreen(onNavigateUp = navController::navigateUp)
            }
        }
    }
}

private enum class TopLevelDestination {
    HOME, SCANNER, TEXT_RECOGNITION
}

@Composable
private fun MainNavScaffold(
    navController: NavHostController,
    currentTopLevel: TopLevelDestination,
    content: @Composable () -> Unit,
) {
    val navItems = listOf(
        AdaptiveNavItem(
            label = "Home",
            icon = Icons.Default.Home,
            selected = currentTopLevel == TopLevelDestination.HOME,
            onClick = {
                navController.navigate(AppDestination.Home) {
                    popUpTo<AppDestination.Home> { inclusive = true }
                    launchSingleTop = true
                }
            },
        ),
        AdaptiveNavItem(
            label = "Scanner",
            icon = Icons.Default.QrCodeScanner,
            selected = currentTopLevel == TopLevelDestination.SCANNER,
            onClick = {
                navController.navigate(AppDestination.BarcodeScanner) {
                    popUpTo<AppDestination.Home> { inclusive = false }
                    launchSingleTop = true
                }
            },
        ),
        AdaptiveNavItem(
            label = "Text",
            icon = Icons.Default.DocumentScanner,
            selected = currentTopLevel == TopLevelDestination.TEXT_RECOGNITION,
            onClick = {
                navController.navigate(AppDestination.TextRecognition) {
                    popUpTo<AppDestination.Home> { inclusive = false }
                    launchSingleTop = true
                }
            },
        ),
    )

    AdaptiveNavigationScaffold(items = navItems, content = content)
}
