package com.kojo.boilerplate.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * A user's initial in a filled circle: the stand-in for an avatar this app does not fetch.
 *
 * ### Why it is in the design system rather than in a feature
 *
 * It is drawn on both sides of a shared-element transition — in the home list row and on the
 * profile it expands into — and `sharedElement` (as opposed to `sharedBounds`) is only correct for
 * content that is *visually identical* on both sides, because it animates one drawing from one
 * rectangle to another with nothing cross-fading. Two copies of this circle, one per feature,
 * would satisfy that on the day they were written and stop satisfying it the first time a colour
 * or a shape changed on one side only — and the symptom would be a transition that visibly jumps
 * at its midpoint, on a device, months later. Features are siblings and neither may import the
 * other, so the only place one drawing can live is here. See `docs/shared-elements.md`.
 *
 * It also replaces the copy of this block that `ProfileScreen` and `ProfileDetailPane` each had.
 *
 * ### The font size
 *
 * Derived from [size] rather than taken from the type scale, because the same circle is drawn at
 * 40dp in a list row and 80dp on a profile and a fixed `headlineLarge` would be a glyph that does
 * not scale with its container — which is exactly the mismatch the paragraph above is about. The
 * ratio is what keeps the two a single drawing at two magnifications.
 *
 * The consequence is that this one glyph does not grow with the system font scale: it is sized in
 * `sp` off a `dp` container, so a reader at 200% text size gets the same letter in the same
 * circle. That is deliberate for a monogram — the alternative is a letter that overflows a fixed
 * circle — and it is a decision the accessibility pass should revisit as a whole, alongside making
 * the circle itself scalable. [displayName] is announced in full by the label beside every
 * instance of this, so nothing is lost to a screen reader.
 *
 * ### Why it is invisible to a screen reader
 *
 * It is a decoration, and the thing it decorates is already named. Every instance sits beside a
 * `Text` carrying [displayName] in full, so left as it is the reader hears "K" and then
 * "Kelsey Turner" — the initial twice, once as a letter. `clearAndSetSemantics {}` with nothing
 * in it drops this subtree from the semantics tree entirely, which is the documented way to say
 * "decorative" for a composable that is not an `Icon` or an `Image` with a `contentDescription`
 * to null out. Cleared rather than `invisibleToUser`, because the point is that there is nothing
 * here to describe rather than that it is hidden.
 *
 * @param displayName the name to take the initial from. An empty name draws an empty circle rather
 *   than throwing, which is what the two copies this replaces did: `displayName.first()` on a user
 *   whose name failed to sync is an exception on the profile screen.
 */
@Composable
fun UserMonogram(
    displayName: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics {},
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = (size.value * MONOGRAM_FONT_RATIO).sp,
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The glyph's height as a fraction of the circle's diameter. 0.4 puts a cap-height letter
 * comfortably inside the circle with room for a descender on a lowercase fallback.
 */
private const val MONOGRAM_FONT_RATIO = 0.4f
