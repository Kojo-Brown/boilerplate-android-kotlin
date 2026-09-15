package com.kojo.boilerplate.core.ui.layout

/**
 * The measure-time decisions the two layouts in this package make, as ordinary functions over
 * pixels.
 *
 * They are here, in a file that imports nothing from Compose, because of what a layout decision
 * is and where it can otherwise be tested. A `MeasurePolicy` runs inside the layout phase, so the
 * only way to exercise one in place is a composition on a device or in Robolectric — which is the
 * `androidTest` source set, and `androidTest` runs nowhere in this repository yet (Phase 12's
 * emulator matrix is still unchecked, and `docs/room-migrations.md` records what that cost the
 * migration suite). A decision left inside a measure lambda is therefore a decision nothing
 * executes until someone opens the app.
 *
 * Pulled out here, each is a total function from integers to an answer, running under
 * `testDebugUnitTest` on every push. What stays in the measure lambda is the part that genuinely
 * needs a `Measurable`: asking for an intrinsic, measuring, and placing.
 *
 * Every dimension is a pixel count, because that is the unit a `MeasureScope` works in — a `Dp` is
 * resolved against the density before it reaches any of this.
 */

/**
 * Whether a label and a value fit beside each other in [available] pixels with [gap] between them.
 *
 * ### Why the arithmetic is in `Long`
 *
 * The obvious `labelWidth + gap + valueWidth <= available` is wrong twice over, and both failures
 * need a wide value to show up. `Constraints.Infinity` **is** `Int.MAX_VALUE`, so an unbounded
 * parent hands this the largest `Int` there is as [available]; and a `Text`'s maximum intrinsic
 * width is the width it would take with no wrapping at all, which for a paragraph of recognised
 * text is far larger than any screen.
 *
 * Add three of those in `Int` and the sum wraps negative, a negative sum is `<=` anything, and the
 * answer comes back `true` — the one answer that makes the caller then measure a child against
 * `available - labelWidth - gap`, which is itself negative, which is an `IllegalArgumentException`
 * out of the `Constraints` constructor. A crash in the layout phase, from a string being long.
 *
 * In `Long` the sum cannot overflow: three `Int`s add to at most about 3 × 2^31, which is six
 * orders of magnitude inside `Long`'s range.
 */
internal fun fitsOnOneRow(labelWidth: Int, valueWidth: Int, gap: Int, available: Int): Boolean =
    labelWidth.toLong() + gap.toLong() + valueWidth.toLong() <= available.toLong()

/** Which of an expandable text's two bodies is placed. */
internal enum class ExpandableTextBody {
    /** Clipped to the collapsed line limit. */
    Collapsed,

    /** Every line, however many there are. */
    Expanded,
}

/**
 * What an expandable text places, and whether it offers a control to change it.
 */
internal data class ExpandableTextPlan(
    val body: ExpandableTextBody,
    val showsToggle: Boolean,
)

/**
 * Decides what an expandable text shows, from two measured heights and the caller's [expanded]
 * flag.
 *
 * [collapsedHeight] is the text measured at its collapsed line limit; [probeHeight] is the same
 * text measured at one line more. The text has more to show precisely when the second is taller
 * than the first — one extra line got somewhere to go, so there was something waiting for it.
 *
 * ### Why [expanded] is not simply obeyed
 *
 * `showsToggle` and `body` are both derived from the overflow rather than one from the other,
 * and that is the rule this function exists to hold: **a text that does not overflow is never
 * expanded, whatever the caller says.**
 *
 * The version that reads better — `body = if (expanded) Expanded else Collapsed` — is wrong on a
 * case this screen actually reaches. `expanded` is screen state and outlives the string it was
 * set for: the reader expands a page of recognised text, points the camera at a door sign, and
 * the next scan replaces `fullText` with four words. Obeying the flag there places an expanded
 * body that is identical to the collapsed one, under a "Show less" control that collapses to
 * exactly what is already on screen. Deriving both from the measurement means the short text is
 * simply a short text, and the flag becomes true again the moment it has something to hide.
 *
 * The mirror image is why `showsToggle` does not consult [expanded] at all: an expanded text must
 * keep its control, or there is no way back.
 */
internal fun planExpandableText(
    collapsedHeight: Int,
    probeHeight: Int,
    expanded: Boolean,
): ExpandableTextPlan {
    val overflows = probeHeight > collapsedHeight
    return ExpandableTextPlan(
        body = if (overflows && expanded) ExpandableTextBody.Expanded else ExpandableTextBody.Collapsed,
        showsToggle = overflows,
    )
}

/**
 * The line limit the overflow probe is measured at: one more than the collapsed limit, and never
 * an overflowed `Int`.
 *
 * Measuring the probe at [Int.MAX_VALUE] — laying the whole text out to see whether it is taller —
 * is the reading that comes first and it is the expensive one: text layout is linear in the text,
 * and this runs in the measure pass of an item a `LazyColumn` re-measures on every scroll. One
 * line more is all the question needs, and it bounds the probe's own layout to the collapsed
 * limit plus one however long the string is.
 *
 * At `collapsedMaxLines == Int.MAX_VALUE` the probe is the collapsed limit itself, which makes
 * `planExpandableText` report no overflow. That is the right answer rather than a fallback: a text
 * with no line limit has nothing left to reveal.
 */
internal fun overflowProbeMaxLines(collapsedMaxLines: Int): Int =
    if (collapsedMaxLines == Int.MAX_VALUE) collapsedMaxLines else collapsedMaxLines + 1
