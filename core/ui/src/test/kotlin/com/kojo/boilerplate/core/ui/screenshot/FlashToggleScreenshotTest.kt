package com.kojo.boilerplate.core.ui.screenshot

import com.kojo.boilerplate.core.common.ThemeMode
import com.kojo.boilerplate.core.ui.components.FlashToggle
import org.junit.Rule
import org.junit.Test

/**
 * Both states of the torch control, which is a component whose whole argument is visual.
 *
 * `FlashToggle` replaced two `IconButton` copies that drew the *action* — a struck-through flash
 * while the flash was on — with a toggle that draws the *state*. `FlashToggleTest` holds the
 * semantics half of that (a role, a checked state, a state description). The other half is the
 * claim its KDoc makes about colour: that the checked tint from
 * `IconButtonDefaults.iconToggleButtonColors` is a second cue, so the state is not carried by the
 * icon alone. Two images are the only thing that can hold it, and the pair is also the only thing
 * that would catch the icons being swapped back — which is not a test failure anywhere else,
 * because `FlashOn` and `FlashOff` are both valid icons with valid content descriptions.
 */
class FlashToggleScreenshotTest {

    @get:Rule
    val paparazzi = screenshotRule()

    @Test
    fun off() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            FlashToggle(flashOn = false, onToggle = {})
        }
    }

    @Test
    fun on() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            FlashToggle(flashOn = true, onToggle = {})
        }
    }

    /**
     * The camera screens are the two this app draws over a live preview, so the dark palette is
     * the one they are seen in most.
     */
    @Test
    fun onDark() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Dark) {
            FlashToggle(flashOn = true, onToggle = {})
        }
    }
}
