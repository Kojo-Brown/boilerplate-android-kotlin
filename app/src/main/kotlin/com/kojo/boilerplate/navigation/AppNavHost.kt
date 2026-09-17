package com.kojo.boilerplate.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.kojo.boilerplate.core.ui.transition.SharedElementTransition
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
 * ### Shared elements
 *
 * The `SharedTransitionLayout` below wraps the whole graph rather than sitting inside a
 * destination, because it owns the overlay a travelling element is drawn into and that overlay has
 * to outlive both the screen being left and the screen being entered. It is also what makes this
 * file the only place a [SharedElementTransition] is constructed: doing so needs the layout's own
 * scope *and* the per-destination `AnimatedContentScope`, and this is the one function that has
 * both. `docs/shared-elements.md` records why that is a feature rather than an inconvenience —
 * whether a screen takes part in a transition is a fact about where it is drawn, and `Home` is
 * drawn two ways.
 *
 * ### Predictive back
 *
 * There is no back handler here, and that is the point. `NavHost` seeks its own pop transition
 * from the system's back gesture — so the pop tracks the finger and reverses if the gesture is
 * abandoned, and a shared element follows the same seek for free — but only once
 * `android:enableOnBackInvokedCallback` is set on the manifest's `application` node, which it now
 * is. Intercepting back on a destination would take the gesture away from that and replace it with
 * an animation played after the fact. The one place this app does intercept is the list-detail
 * layout, where back is not a navigation at all: see `HomeTwoPaneScreen`.
 *
 * @param appEvents the app-wide broadcast, collected here for the reactions that are
 *   navigation's to make. It is a parameter rather than something read from a `@Singleton`
 *   inside the graph so this function stays a function of its inputs — see `MainActivity`.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
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

    SharedTransitionLayout {
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
                // Remembered against the two scopes it wraps rather than rebuilt per
                // recomposition: a composable taking one of these is compared by identity under
                // strong skipping — the class makes no stability promise and cannot — so a fresh
                // instance every pass would make `HomeScreen` unskippable, on a screen whose
                // state is replaced on every keystroke. Both scopes are stable for the life of
                // this entry, so the keys are only there to say what the identity depends on.
                val transition = remember(this@SharedTransitionLayout, this@composable) {
                    SharedElementTransition(this@SharedTransitionLayout, this@composable)
                }

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
                            // The source half of the transition, and only on this branch: the
                            // two-pane layout draws the row and the profile at the same time, so
                            // there both halves of every key would be visible at once. That
                            // decision belongs here, where both branches are in view.
                            transition = transition,
                        )
                    }
                }
            }

            composable<AppDestination.Profile> {
                val transition = remember(this@SharedTransitionLayout, this@composable) {
                    SharedElementTransition(this@SharedTransitionLayout, this@composable)
                }

                ProfileScreen(
                    onNavigateUp = navController::navigateUp,
                    transition = transition,
                )
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
}

private enum class TopLevelDestination {
    HOME, SCANNER, TEXT_RECOGNITION
}

/**
 * The navigation surface every top-level screen sits inside.
 *
 * The item list is built from exactly two things — which destination is current, and the
 * controller the three callbacks navigate with — so it is remembered against exactly those two.
 * Unkeyed, it would be three [AdaptiveNavItem] allocations and a fresh `persistentListOf` on
 * every recomposition of this function, and the cost is not the allocations: `items` is the
 * parameter [AdaptiveNavigationScaffold] skips on, `AdaptiveNavItem` is a data class, and
 * `ImageVector` compares its whole path tree — so a structurally identical rebuild is a deep
 * comparison of two Material icons per item, every pass, to conclude nothing changed. Keyed, it
 * is one identity check.
 *
 * How often that happens is not up to this function. `useListDetailLayout()` returns a value, so
 * it is not restartable, so the window-metrics state it reads is recorded against its *caller* —
 * the `Home` entry below. Every posture or window-size update therefore recomposes that entry
 * and re-invokes this, which during a drag-resize in split-screen is per frame.
 */
@Composable
private fun MainNavScaffold(
    navController: NavHostController,
    currentTopLevel: TopLevelDestination,
    content: @Composable () -> Unit,
) {
    val navItems = remember(navController, currentTopLevel) {
        persistentListOf(
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
    }

    AdaptiveNavigationScaffold(items = navItems, content = content)
}
