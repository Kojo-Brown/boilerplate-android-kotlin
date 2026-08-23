package com.kojo.boilerplate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        enableEdgeToEdge()
        setContent {
            val themeMode by themePreferencesRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.System)

            BoilerplateTheme(themeMode = themeMode) {
                AppNavHost(appEvents = appEventBus.events)
            }
        }
    }
}
