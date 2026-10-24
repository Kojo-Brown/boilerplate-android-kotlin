package com.kojo.boilerplate.core.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kojo.boilerplate.core.common.ThemeMode
import com.kojo.boilerplate.core.ui.components.LoadingIndicator
import org.junit.Rule
import org.junit.Test

/**
 * The full-screen spinner, in a box the size of the space it is given.
 *
 * `LoadingIndicator` is `fillMaxSize` by design — it centres itself in whatever it is handed —
 * which under SHRINK rendering means "the whole device" unless something sizes it. The wrapper is
 * that something, and it is also what the image is about: the indicator is centred in its
 * container rather than drawn at its origin, which is the one thing about this component that a
 * `onNodeWithTag(...).assertIsDisplayed()` cannot see.
 *
 * The frame is deterministic. An indeterminate `CircularProgressIndicator` animates, and
 * Paparazzi renders a single frame with the platform clock held at zero, so what is recorded is
 * the sweep's starting position every time.
 */
class LoadingIndicatorScreenshotTest {

    @get:Rule
    val paparazzi = screenshotRule()

    @Test
    fun light() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            Box(modifier = Modifier.size(ContainerSize)) {
                LoadingIndicator()
            }
        }
    }

    @Test
    fun dark() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Dark) {
            Box(modifier = Modifier.size(ContainerSize)) {
                LoadingIndicator()
            }
        }
    }
}

/** Twice the indicator's own 48dp, so that "centred" is visible rather than implied. */
private val ContainerSize = 96.dp
