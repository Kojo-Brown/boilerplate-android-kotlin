package com.kojo.boilerplate.core.ui.screenshot

import com.kojo.boilerplate.core.common.ThemeMode
import com.kojo.boilerplate.core.ui.components.AppButton
import com.kojo.boilerplate.core.ui.components.AppButtonVariant
import org.junit.Rule
import org.junit.Test

/**
 * Every visual state `AppButton` has, in both palettes.
 *
 * `AppButtonTest` in `androidTest` already holds the behaviour — the label is shown, the click
 * arrives, a disabled button does not fire, the loading indicator is present — and all four of
 * those pass against a button rendered in the wrong colour, at the wrong size, or with its label
 * invisible against its own fill. These are the assertions that are only available as pixels:
 * that `isLoading` swaps the label for an indicator of the right size *and* keeps the button's
 * footprint, that a disabled button is legible rather than merely greyed, and that the outlined
 * variant's border survives the dark palette.
 *
 * Method names are short because each one *is* a filename: Paparazzi writes the golden as
 * `<package>_<class>_<method>.png`.
 */
class AppButtonScreenshotTest {

    @get:Rule
    val paparazzi = screenshotRule()

    @Test
    fun primaryEnabled() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            AppButton(text = "Continue", onClick = {})
        }
    }

    /**
     * The state with the most to go wrong and the least to see in a code review: the content is
     * swapped for a 20dp indicator inside a `Box`, so the button keeps the width of whichever of
     * the two is wider — which is the label. A regression here is a button that visibly resizes
     * the moment it is pressed.
     */
    @Test
    fun primaryLoading() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            AppButton(text = "Continue", onClick = {}, isLoading = true)
        }
    }

    @Test
    fun primaryDisabled() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            AppButton(text = "Continue", onClick = {}, enabled = false)
        }
    }

    @Test
    fun outlinedEnabled() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            AppButton(text = "Continue", onClick = {}, variant = AppButtonVariant.Outlined)
        }
    }

    /**
     * The outlined variant's indicator is drawn in `colorScheme.primary` rather than
     * `onPrimary`, because there is no fill under it. In the dark palette those two are a light
     * lilac and a dark purple, so getting it the wrong way round is an indicator that disappears
     * — on the one screen state where the user is waiting and looking at it.
     */
    @Test
    fun outlinedLoadingDark() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Dark) {
            AppButton(text = "Continue", onClick = {}, variant = AppButtonVariant.Outlined, isLoading = true)
        }
    }

    @Test
    fun primaryEnabledDark() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Dark) {
            AppButton(text = "Continue", onClick = {})
        }
    }
}
