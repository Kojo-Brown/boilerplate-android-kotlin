package com.kojo.boilerplate.core.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import com.android.resources.LayoutDirection
import com.kojo.boilerplate.core.common.ThemeMode
import com.kojo.boilerplate.core.ui.layout.LabelledValue
import org.junit.Rule
import org.junit.Test

/**
 * Both branches of a measure policy, as pictures of the layout they produce.
 *
 * `MeasurePolicyTest` covers the decision — the arithmetic of `fitsOnOneRow`, including the
 * `Int.MAX_VALUE` overflow — on a plain JVM, and says in its own KDoc that covering the policy
 * *in place* would mean composing. These two images are that coverage: the same label and value
 * at two widths, one laid out as a row with the value against the trailing edge and one stacked
 * with both at the leading edge. Nothing else in this repository can tell the two apart, which
 * matters because the failure mode of a custom `MeasurePolicy` is not an exception — it is
 * children placed somewhere plausible and wrong.
 */
class LabelledValueScreenshotTest {

    @get:Rule
    val paparazzi = screenshotRule()

    /** Wide enough for both children: label at the leading edge, value hard against the other. */
    @Test
    fun row() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            EmailField(width = WideWidth)
        }
    }

    /** Narrower than the value's own unwrapped width, which is what sends it to the stack. */
    @Test
    fun stack() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            EmailField(width = NarrowWidth)
        }
    }
}

/**
 * The row branch with the layout direction reversed, which is the only thing that can hold the
 * `placeRelative` in `measureAsRow` to its purpose.
 *
 * A separate class because the layout direction is part of the device configuration, which
 * Paparazzi reads once per rule. Only the direction is changed and not the locale: Paparazzi
 * substitutes an RTL locale of its own when the configuration asks for RTL and the locale does
 * not supply it, so the image stays a statement about placement rather than about a script the
 * renderer may have no font for.
 *
 * `place` instead of `placeRelative` compiles, passes `MeasurePolicyTest`, and puts the label on
 * the left for a reader whose language runs right to left. In the golden it is unmistakable: the
 * label is on the right.
 */
class LabelledValueRtlScreenshotTest {

    @get:Rule
    val paparazzi: Paparazzi =
        screenshotRule(ScreenshotDevice.copy(layoutDirection = LayoutDirection.RTL))

    @Test
    fun row() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            EmailField(width = WideWidth)
        }
    }
}

/** The pairing `ProfileScreen` draws, which is the call site this component was extracted from. */
@Composable
private fun EmailField(width: Dp) {
    LabelledValue(
        label = { Text(text = "Email", style = MaterialTheme.typography.labelMedium) },
        value = {
            Text(
                text = "ada.lovelace@example.com",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        modifier = Modifier.width(width),
    )
}

/** Comfortably more than the two children and the gap between them. */
private val WideWidth = 320.dp

/** Less than the value alone asks for at `bodyMedium`, with margin to spare. */
private val NarrowWidth = 140.dp
