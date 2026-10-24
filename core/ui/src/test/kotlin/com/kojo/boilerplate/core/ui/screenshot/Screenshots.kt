package com.kojo.boilerplate.core.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.kojo.boilerplate.core.common.ThemeMode
import com.kojo.boilerplate.core.ui.theme.BoilerplateTheme

/*
 * What every screenshot test in this module renders through.
 *
 * Both declarations below exist so that the goldens stay comparable with one another. A per-class
 * device, or a per-class wrapper around the theme, would produce two pictures of the same widget
 * that cannot be read side by side — and the reader of a pixel diff has no way to tell a change in
 * the component from a change in how it was mounted. One rule factory and one host is also what
 * makes a new test three lines long, which is most of whether the next widget gets a golden at all.
 *
 * docs/screenshot-testing.md is the full account: how to record, why nobody can record on a
 * developer machine in this project, and what a red diff gate means.
 */

/**
 * The device every snapshot is taken on.
 *
 * A Pixel 5 at its real 440 dpi, which is 2.75x — deliberately not one of the integer densities.
 * Every dp-to-px conversion in the layout therefore lands on a fraction and is rounded, which is
 * the arithmetic a real phone does and the arithmetic a `dp` constant chosen to look tidy at 2x
 * quietly avoids. The renders stay deterministic: layoutlib rounds the same way every time.
 *
 * It is a `val` rather than inlined so that the `.copy(...)` cases — a night-mode configuration,
 * an RTL layout direction, a doubled font scale — are visibly *this* device with one thing changed.
 */
internal val ScreenshotDevice: DeviceConfig = DeviceConfig.PIXEL_5

/**
 * The Paparazzi rule, with the three arguments this repository does not leave to a default.
 *
 * - **[RenderingMode.SHRINK]** makes the image the component rather than a phone with a component
 *   in the top corner. That is the difference between a diff a reviewer can read and a column of
 *   empty pixels, and between a 4 kB golden and a 300 kB one — every one of which lives in the
 *   repository for as long as the component it pictures does. The cost is that a composable which asks for
 *   all the space it can get (`fillMaxSize`, `fillMaxWidth`) is handed the whole screen under
 *   SHRINK, so a test for one of those has to wrap it in something sized. `LoadingIndicator` is
 *   exactly that case.
 * - **`supportsRtl = true`** overrides a default of `false`, which silently pins the render to
 *   left-to-right whatever the device's layout direction says. `LabelledValue` places its children
 *   with `placeRelative` precisely so that a right-to-left reader gets the label on the right, and
 *   without this flag the test written to hold that claim renders LTR and passes forever.
 * - **`maxPercentDifference`** is Paparazzi's default value, spelled out. It is the tolerance of
 *   the whole gate, so an upgrade that changed the default would change how strict this
 *   repository's screenshot tests are without a line of this diff moving.
 *
 * @param deviceConfig [ScreenshotDevice] unless a test is about a configuration.
 */
internal fun screenshotRule(deviceConfig: DeviceConfig = ScreenshotDevice): Paparazzi = Paparazzi(
    deviceConfig = deviceConfig,
    renderingMode = RenderingMode.SHRINK,
    maxPercentDifference = MAX_PERCENT_DIFFERENCE,
    supportsRtl = true,
)

/**
 * The app's theme around a component, which is the only state in which a widget from this module
 * is ever drawn.
 *
 * ### Why `dynamicColor` is false and why that is not a detail
 *
 * [BoilerplateTheme] takes its palette from the wallpaper on Android 12 and above, and it defaults
 * to doing so. Paparazzi reports `Build.VERSION.SDK_INT` as 34 — see the `paparazzi` entry in
 * `gradle/libs.versions.toml` for where that number comes from — so that branch is live under
 * every snapshot. Left at its default, these goldens would be pictures of layoutlib's stock
 * `system_accent*` resources: stable, plausible, and no longer a picture of `Color.kt` at all. Every
 * token in this module could then be edited without a single golden moving. The theme's own KDoc
 * names a screenshot test as the caller that needs a fixed palette; this is that caller.
 *
 * ### Why the mode is an argument rather than the device's night mode
 *
 * [ThemeMode] is what the app asks for and `isSystemInDarkTheme()` is only what the platform
 * reports; the two meet in `ThemeMode.isDark`. Passing the mode here renders the palette this app
 * *chose*, which is what a component golden is about. The deferral — `ThemeMode.System` landing on
 * the dark palette because the device is in night mode — is a different claim and has its own
 * snapshot in `ColorSchemeScreenshotTest`.
 *
 * ### Why there is a `Surface`
 *
 * Without one the image carries the platform theme's window background, which is the same near
 * white behind both palettes: a dark snapshot would be dark text on white with a dark button in
 * the middle of it. The `Surface` paints `colorScheme.background`, so the golden is the component
 * on the app's own canvas — and a contrast regression between a widget and the surface under it
 * becomes visible instead of being hidden by a backdrop no device ever shows.
 */
@Composable
internal fun ScreenshotHost(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    BoilerplateTheme(themeMode = themeMode, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.padding(HostPadding)) {
                content()
            }
        }
    }
}

/**
 * Breathing room around the component, so that a shadow, a focus ring or an outline is inside the
 * image rather than clipped by its edge — under SHRINK the image is the content's own bounds.
 */
private val HostPadding = 8.dp

/**
 * Paparazzi's own default, restated. Antialiased text at 2.75x differs by a pixel or two across
 * nothing in particular; a tolerance of zero would make the gate report noise, and a larger one
 * would let a one-shade colour change through.
 */
private const val MAX_PERCENT_DIFFERENCE = 0.1
