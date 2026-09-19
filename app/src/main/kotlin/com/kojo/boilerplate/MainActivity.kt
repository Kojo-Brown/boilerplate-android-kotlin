package com.kojo.boilerplate

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kojo.boilerplate.core.common.ThemeMode
import com.kojo.boilerplate.core.datastore.ThemePreferencesRepository
import com.kojo.boilerplate.core.event.AppEventBus
import com.kojo.boilerplate.core.ui.theme.BoilerplateTheme
import com.kojo.boilerplate.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themePreferencesRepository: ThemePreferencesRepository

    /**
     * Injected here and handed down rather than read from inside the navigation graph.
     *
     * Reaching a `@Singleton` from a composable means an `EntryPoint` or a ViewModel that
     * exists only to forward a flow; a parameter keeps [AppNavHost] a function of its inputs,
     * which is what makes it callable from a test or a preview with a flow of the test's
     * choosing.
     */
    @Inject
    lateinit var appEventBus: AppEventBus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bootstrap. The styled call below is inside the composition and so cannot run until
        // after the first one, and the window has to be laid out edge-to-edge before that or
        // the first frame is drawn inside the system bars. This one takes no arguments because
        // nothing here knows the theme yet — reading the preference is the composition's job —
        // and the styled call replaces the window decoration outright a moment later.
        enableEdgeToEdge()
        setContent {
            val themeMode by themePreferencesRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val darkTheme = themeMode.isDark(systemInDarkTheme = isSystemInDarkTheme())

            // The system bar icons follow the *app's* theme, not the device's.
            //
            // `enableEdgeToEdge()` with no arguments defaults both bars to `SystemBarStyle.auto`
            // with a `detectDarkMode` that reads `Configuration.UI_MODE_NIGHT_MASK` — the
            // device's night mode. That is the wrong question whenever the two disagree: a
            // reader who picks Light on a device in dark mode gets white status-bar icons over
            // a white app bar and cannot see the clock or the battery. Passing `darkTheme`
            // as the detector is what ties the glyphs to what is actually drawn beneath them.
            //
            // In a `DisposableEffect` keyed on `darkTheme` rather than at `onCreate`, because
            // the theme is a preference that can change while the activity is resumed — from
            // the app's own setting, or from the device's while the mode is System. `onDispose`
            // is empty because there is nothing to undo: the next value replaces the window
            // decoration outright, and the window dies with the activity.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = NAVIGATION_BAR_LIGHT_SCRIM,
                        darkScrim = NAVIGATION_BAR_DARK_SCRIM,
                    ) { darkTheme },
                )
                onDispose {}
            }

            BoilerplateTheme(themeMode = themeMode) {
                AppNavHost(appEvents = appEventBus.events)
            }
        }
    }

    private companion object {

        /**
         * What the platform paints behind the navigation bar on API 28 and below, where it
         * cannot guarantee the buttons stay legible over arbitrary content. From API 29
         * `enableEdgeToEdge` makes the bar genuinely transparent and turns the system's own
         * contrast enforcement off, and neither of these is drawn.
         *
         * The status bar above needs no equivalent: `SystemBarStyle`'s status-bar scrim is only
         * used below API 23, and this app's `minSdk` is 26.
         *
         * The values are the ones AndroidX's own samples use — a near-opaque white and a 50%
         * black — chosen to keep the buttons readable without the bar reading as solid.
         */
        val NAVIGATION_BAR_LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)

        val NAVIGATION_BAR_DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
