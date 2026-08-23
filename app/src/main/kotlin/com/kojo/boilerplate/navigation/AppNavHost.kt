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
import com.kojo.boilerplate.core.event.AppEvent
import com.kojo.boilerplate.core.navigation.AppDestination
import com.kojo.boilerplate.core.ui.adaptive.AdaptiveNavItem
import com.kojo.boilerplate.core.ui.adaptive.AdaptiveNavigationScaffold
import com.kojo.boilerplate.core.ui.adaptive.useListDetailLayout
import com.kojo.boilerplate.core.ui.event.ObserveAsEvents
import com.kojo.boilerplate.feature.home.HomeScreen
import com.kojo.boilerplate.feature.home.HomeTwoPaneScreen
import com.kojo.boilerplate.feature.profile.ProfileDetailPane
import com.kojo.boilerplate.feature.profile.ProfileScreen
import com.kojo.boilerplate.feature.scanner.BarcodeScannerScreen
import com.kojo.boilerplate.feature.signin.GoogleSignInScreen
import com.kojo.boilerplate.feature.textrecognition.TextRecognitionScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow

/**
 * @param appEvents the app-wide broadcast, collected here for the reactions that are
 *   navigation's to make. It is a parameter rather than something read from a `@Singleton`
 *   inside the graph so this function stays a function of its inputs — see `MainActivity`.
 */
@Composable
fun AppNavHost(
    appEvents: Flow<AppEvent>,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: AppDestination = AppDestination.SignIn,
) {
    // The UI half of the session-expiry reaction. It is a *reaction to* the event and not the
    // handling of it: the credential state is cleared by SessionExpiryCredentialListener, which
    // is subscribed for the life of the process, because a session usually dies with the app in
    // the background and this collector only runs while a screen is started. A SharedFlow
    // delivers to both; a Channel would have given the event to whichever asked first.
    ObserveAsEvents(appEvents) { event ->
        when (event) {
            AppEvent.SessionExpired -> navController.navigate(AppDestination.SignIn) {
                // Everything on the stack was reached as a signed-in user, so none of it
                // should be behind the back button now. `startDestinationId` is SignIn itself,
                // and inclusive pops that too — Home's own navigation removes it from the
                // stack, so popping "up to SignIn" without inclusive would find nothing.
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

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
                        // `:feature:home` cannot see `:feature:profile` — features are siblings
                        // and neither may import the other. Knowing about both is navigation's
                        // job, so the detail pane is supplied from here.
                        detailPane = { userId -> ProfileDetailPane(userId = userId) },
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
    val navItems = persistentListOf(
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
