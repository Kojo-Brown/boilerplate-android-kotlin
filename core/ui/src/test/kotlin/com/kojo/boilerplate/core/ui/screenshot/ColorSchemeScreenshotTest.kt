package com.kojo.boilerplate.core.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.kojo.boilerplate.core.common.ThemeMode
import org.junit.Rule
import org.junit.Test

/**
 * The palette itself, as a sheet of every colour the theme sets.
 *
 * ### Why the tokens need a golden of their own
 *
 * `Color.kt` is sixty hand-written hex literals and nothing in this repository reads them. A
 * swapped pair, a light value in the dark scheme, a token that drifts a shade on a redesign: none
 * of it fails a test, and none of it is visible in a diff that shows `0xFF6650A4` becoming
 * `0xFF6750A4`. The component goldens above catch the handful of roles they happen to draw —
 * primary, onPrimary, primaryContainer, error, outline — and say nothing about the rest.
 *
 * Each role is drawn as a band of the container colour carrying its own `on` colour as the label,
 * so one image holds both halves of the pair *and* the contrast between them: an `onSecondary`
 * that stops being legible against `secondary` becomes an unreadable word rather than a number in
 * a file. The roles with no content colour of their own follow as swatches. Between them the two
 * lists name all twenty-nine tokens `BoilerplateTheme` passes to `lightColorScheme` and
 * `darkColorScheme`, which is what makes "no edit to `Color.kt` can be invisible" true rather than
 * approximately true. A role added to the theme has to be added here too — that is the known
 * limit, and it is why both lists are exhaustive by construction rather than a selection.
 */
class ColorSchemeScreenshotTest {

    @get:Rule
    val paparazzi = screenshotRule()

    @Test
    fun light() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            ColorSchemeSheet()
        }
    }

    @Test
    fun dark() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Dark) {
            ColorSchemeSheet()
        }
    }
}

/**
 * The same sheet asked for as [ThemeMode.System] on a device in night mode, which has to come
 * back as the dark palette — the same colours [ColorSchemeScreenshotTest.dark] records.
 *
 * `ThemeMode.System` is a deferral, and `ThemeModeTest` holds `isDark` to the right answer as a
 * function of a boolean. What is untested either side of that is the wiring: that the boolean
 * reaching it is `isSystemInDarkTheme()`, which reads the configuration rather than, say, the
 * system's own night mode after `enableEdgeToEdge` has had an opinion about it. The device
 * configuration is per rule, so this is a class rather than a third test above.
 */
class ColorSchemeSystemNightScreenshotTest {

    @get:Rule
    val paparazzi: Paparazzi =
        screenshotRule(ScreenshotDevice.copy(nightMode = NightMode.NIGHT))

    @Test
    fun systemDefersToNightMode() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.System) {
            ColorSchemeSheet()
        }
    }
}

@Composable
private fun ColorSchemeSheet() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.width(SheetWidth),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        pairedRoles(scheme).forEach { (name, colors) ->
            RoleBand(name = name, container = colors.first, content = colors.second)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            unpairedRoles(scheme).forEach { color -> Swatch(color) }
        }
    }
}

/** A role and its content colour: the fill, with the colour meant to be legible on it written on it. */
@Composable
private fun RoleBand(
    name: String,
    container: Color,
    content: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(text = name, color = content, style = MaterialTheme.typography.labelLarge)
    }
}

/** A role with nothing written on it, because nothing is drawn on it. */
@Composable
private fun Swatch(color: Color) {
    Box(modifier = Modifier.size(SwatchSize).background(color))
}

/**
 * Every (role, on-role) pair the theme sets, in the order `lightColorScheme` takes them so that
 * this list and `BoilerplateTheme` can be read against each other.
 */
private fun pairedRoles(scheme: ColorScheme): List<Pair<String, Pair<Color, Color>>> = listOf(
    "primary" to (scheme.primary to scheme.onPrimary),
    "primaryContainer" to (scheme.primaryContainer to scheme.onPrimaryContainer),
    "secondary" to (scheme.secondary to scheme.onSecondary),
    "secondaryContainer" to (scheme.secondaryContainer to scheme.onSecondaryContainer),
    "tertiary" to (scheme.tertiary to scheme.onTertiary),
    "tertiaryContainer" to (scheme.tertiaryContainer to scheme.onTertiaryContainer),
    "error" to (scheme.error to scheme.onError),
    "errorContainer" to (scheme.errorContainer to scheme.onErrorContainer),
    "background" to (scheme.background to scheme.onBackground),
    "surface" to (scheme.surface to scheme.onSurface),
    "surfaceVariant" to (scheme.surfaceVariant to scheme.onSurfaceVariant),
    "inverseSurface" to (scheme.inverseSurface to scheme.inverseOnSurface),
)

/**
 * The five remaining tokens: a hairline, a divider, a scrim, the inverse accent an inverse surface
 * carries, and the tint elevation applies. None of them has a content colour, so none of them can
 * carry its own name.
 */
private fun unpairedRoles(scheme: ColorScheme): List<Color> = listOf(
    scheme.outline,
    scheme.outlineVariant,
    scheme.scrim,
    scheme.inversePrimary,
    scheme.surfaceTint,
)

/** Wide enough for the longest role name on one line, narrow enough to keep the golden small. */
private val SheetWidth = 200.dp

private val SwatchSize = 32.dp
