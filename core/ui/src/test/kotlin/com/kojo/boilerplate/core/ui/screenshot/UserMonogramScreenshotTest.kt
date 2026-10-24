package com.kojo.boilerplate.core.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import com.kojo.boilerplate.core.common.ThemeMode
import com.kojo.boilerplate.core.ui.components.UserMonogram
import org.junit.Rule
import org.junit.Test

/**
 * The monogram at the two sizes it is drawn at, and the two cases its KDoc is about.
 *
 * `UserMonogram` exists because the same circle is drawn on both sides of a shared-element
 * transition, and `sharedElement` — as opposed to `sharedBounds` — is only correct for content
 * that is *visually identical* on both sides. "Visually identical at two magnifications" is a
 * claim about pixels and about nothing else: `SharedElementContractTest` can hold that there is
 * one implementation, and only a pair of images can hold that the glyph sits in the same place in
 * the circle at 40dp as it does at 80dp. A font size taken from the type scale instead of from
 * the diameter would pass every other test in this repository and show up as a transition that
 * jumps at its midpoint, on a device, months later.
 */
class UserMonogramScreenshotTest {

    @get:Rule
    val paparazzi = screenshotRule()

    /** The size the home list row draws. */
    @Test
    fun listRow() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            UserMonogram(displayName = "Kelsey Turner", size = ListRowSize)
        }
    }

    /** The size the profile screen draws — the other end of the shared element. */
    @Test
    fun profile() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            UserMonogram(displayName = "Kelsey Turner", size = ProfileSize)
        }
    }

    @Test
    fun profileDark() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Dark) {
            UserMonogram(displayName = "Kelsey Turner", size = ProfileSize)
        }
    }

    /**
     * The user whose name failed to sync. `displayName.first()` would have thrown here, and the
     * component answers an empty circle instead — a filled shape with nothing in it is a
     * deliberate drawing rather than a missing one, which is the part worth being able to see.
     */
    @Test
    fun unsyncedName() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            UserMonogram(displayName = "", size = ProfileSize)
        }
    }
}

/**
 * The monogram beside its own label at twice the system font size.
 *
 * A separate class because the font scale belongs to the device configuration, which Paparazzi
 * takes once per rule, and a rule is per test class.
 *
 * **This golden is a picture of a defect, and it is the first thing this gate found.**
 * `UserMonogram`'s KDoc claimed that its letter does not grow with the system font scale — a
 * trade-off a monogram can defend, and the opposite of what the component does. `sp` is the unit
 * that follows the font scale; `dp` is the one that does not, and the circle is the `dp` half. So
 * at 200% the letter grows, the circle does not, and the glyph drops below centre and runs over
 * the bottom edge of the circle it is supposed to sit inside. Measured against
 * [UserMonogramScreenshotTest.listRow], the same letter goes from 23x31 to 38x41 pixels and from
 * the middle of the circle to its lower edge.
 *
 * It is recorded rather than fixed, because the fix is a decision about a drawing two features
 * animate between — see that KDoc. The golden is what will make the decision visible when it
 * lands, which is the job a screenshot test has that no other test here can do.
 */
class UserMonogramFontScaleScreenshotTest {

    @get:Rule
    val paparazzi: Paparazzi = screenshotRule(ScreenshotDevice.copy(fontScale = DoubleFontScale))

    @Test
    fun listRowAtDoubleFontScale() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            NamedMonogram()
        }
    }
}

/** The pairing every instance of the monogram appears in: the circle, then the name in full. */
@Composable
private fun NamedMonogram() {
    Row(
        modifier = Modifier.width(RowWidth),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserMonogram(displayName = "Kelsey Turner", size = ListRowSize)
        Text(text = "Kelsey Turner", style = MaterialTheme.typography.bodyLarge)
    }
}

private val ListRowSize = 40.dp

private val ProfileSize = 80.dp

/** Wide enough for the name at twice the font scale, so the diff is the type size and not a wrap. */
private val RowWidth = 300.dp

private const val DoubleFontScale = 2f
