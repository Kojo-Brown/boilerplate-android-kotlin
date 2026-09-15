package com.kojo.boilerplate.core.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/**
 * A caption and the value it captions, side by side when they both fit on one row and stacked when
 * they do not.
 *
 * ### Why this is measure-dependent at all
 *
 * The same two strings render at two very different widths in this app. `ProfileScreen` draws them
 * across a phone, and `ProfileDetailPane` draws them in the detail half of a list-detail layout on
 * a tablet or an unfolded device — see `useListDetailLayout()` for the switch. In the wide case
 * "Email" above `ada.lovelace@example.com` spends a line saying something a row says in one; in the
 * narrow case a row truncates the address the reader opened the screen to read.
 *
 * Neither is a decision a *size class* can make. A size class describes the window, and this
 * composable is given what is left of the window after a navigation rail, a pane split and two lots
 * of padding — for a value that may be twelve characters or sixty. The only thing that knows is the
 * measure pass, which is what a custom layout is for.
 *
 * ### Why it is a `Layout` and not a `SubcomposeLayout`
 *
 * This is the case [ExpandableText] exists to contrast with, and the rule is worth stating in one
 * line: **a `Layout` is enough whenever a measurement decides where content goes, and only a
 * measurement deciding what content *is* needs subcomposition.**
 *
 * Both children exist under either outcome here. The row and the stack place the same two nodes at
 * different coordinates, so nothing has to be composed to discover what to compose. Reaching for
 * `SubcomposeLayout` anyway would cost real things and buy none: it composes its slots during the
 * layout phase rather than with its caller, and it cannot answer a parent's intrinsic query without
 * running a whole speculative measure pass.
 *
 * The two children are separate slots rather than one `content` lambda because the measure policy
 * has to tell them apart, and `Layout(contents = …)` is how that is said without subcomposing: each
 * lambda arrives as its own list of measurables. Before that overload existed, wanting exactly this
 * was the most common honest reason to reach for `SubcomposeLayout` — which is why it is worth
 * naming here rather than left as an API detail.
 *
 * ### The intrinsic
 *
 * The fit is decided from [Measurable.maxIntrinsicWidth] — what a child would take if nothing
 * constrained it — because a `Measurable` may be measured only once per layout pass, so "measure it
 * and see" is not on offer. It is not free: an intrinsic query runs its own pass over the child's
 * subtree. Two `Text`s are the cheap end of that; `docs/custom-layout.md` records the sharp end,
 * which is that a lazy list cannot answer an intrinsic query at all.
 *
 * Each slot must emit exactly one node — [label] and [value] are a caption and a value, not lists.
 */
@Composable
fun LabelledValue(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    horizontalGap: Dp = LabelledValueDefaults.HorizontalGap,
    verticalGap: Dp = LabelledValueDefaults.VerticalGap,
) {
    Layout(contents = listOf(label, value), modifier = modifier) { measurables, constraints ->
        val labelMeasurable = measurables[LABEL_SLOT].single()
        val valueMeasurable = measurables[VALUE_SLOT].single()

        // Asked for once each and then carried, because an intrinsic query is a measure pass over
        // the child's subtree and the row branch needs the same two numbers the decision used.
        // Both are asked at an unbounded height for the reason the children are measured at one:
        // a value clipped to the parent's height is a truncated address with nothing saying so.
        val labelWidth = labelMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val valueWidth = valueMeasurable.maxIntrinsicWidth(Constraints.Infinity)

        if (fitsOnOneRow(labelWidth, valueWidth, horizontalGap.roundToPx(), constraints.maxWidth)) {
            measureAsRow(
                label = MeasuredChild(labelMeasurable, labelWidth),
                value = MeasuredChild(valueMeasurable, valueWidth),
                gap = horizontalGap.roundToPx(),
                constraints = constraints,
            )
        } else {
            measureAsStack(
                labelMeasurable = labelMeasurable,
                valueMeasurable = valueMeasurable,
                gap = verticalGap.roundToPx(),
                constraints = constraints,
            )
        }
    }
}

/** A child and the width it asked for, so the row branch does not query the same intrinsic twice. */
private class MeasuredChild(val measurable: Measurable, val intrinsicWidth: Int)

/**
 * Label at the leading edge, value at the trailing one, each centred against the taller of the two.
 *
 * The children are measured at their own intrinsic widths rather than at the space available, which
 * is what puts the value hard against the trailing edge instead of leaving a `Text` filling the row
 * and drawing its glyphs at the start of it. Both are known to fit by the time this runs, so
 * neither measurement can overflow the row.
 */
private fun MeasureScope.measureAsRow(
    label: MeasuredChild,
    value: MeasuredChild,
    gap: Int,
    constraints: Constraints,
): MeasureResult {
    val labelPlaceable = label.measureAtIntrinsicWidth(constraints)
    val valuePlaceable = value.measureAtIntrinsicWidth(constraints)

    // An unbounded parent — a horizontally scrolling one — gets the row's own width. A bounded one
    // gets the whole width it offered, which is what leaves somewhere for the value to sit against.
    val width = constraints.constrainWidth(
        if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            labelPlaceable.width + gap + valuePlaceable.width
        },
    )
    val height = constraints.constrainHeight(maxOf(labelPlaceable.height, valuePlaceable.height))

    return layout(width, height) {
        // placeRelative rather than place: in an RTL locale the label belongs on the right and the
        // value on the left, and this is the one line that decides it.
        labelPlaceable.placeRelative(x = 0, y = (height - labelPlaceable.height) / 2)
        valuePlaceable.placeRelative(
            x = width - valuePlaceable.width,
            y = (height - valuePlaceable.height) / 2,
        )
    }
}

/** Label above value, both starting at the leading edge, with [gap] between them. */
private fun MeasureScope.measureAsStack(
    labelMeasurable: Measurable,
    valueMeasurable: Measurable,
    gap: Int,
    constraints: Constraints,
): MeasureResult {
    val childConstraints = Constraints(maxWidth = constraints.maxWidth)
    val labelPlaceable = labelMeasurable.measure(childConstraints)
    val valuePlaceable = valueMeasurable.measure(childConstraints)

    val width = constraints.constrainWidth(maxOf(labelPlaceable.width, valuePlaceable.width))
    val height = constraints.constrainHeight(labelPlaceable.height + gap + valuePlaceable.height)

    return layout(width, height) {
        labelPlaceable.placeRelative(x = 0, y = 0)
        valuePlaceable.placeRelative(x = 0, y = labelPlaceable.height + gap)
    }
}

/**
 * Measures the child at the width it asked for, capped at what the parent offers.
 *
 * The cap is not defensive padding. `Constraints` packs its four bounds into one `Long` and refuses
 * a finite width it cannot represent, so an intrinsic taken from a very long unwrapped string —
 * or an unbounded parent's `Infinity` passed straight through — throws out of the constructor
 * rather than producing a wide child.
 */
private fun MeasuredChild.measureAtIntrinsicWidth(constraints: Constraints) =
    measurable.measure(Constraints(maxWidth = minOf(intrinsicWidth, constraints.maxWidth)))

/** The spacing this component uses when the caller does not say. */
object LabelledValueDefaults {

    /** Between a label and a value sharing a row — wide enough to read as two fields, not one. */
    val HorizontalGap: Dp = 16.dp

    /** Between a label and the value under it, which is a tighter relationship than the row's. */
    val VerticalGap: Dp = 4.dp
}

private const val LABEL_SLOT = 0

private const val VALUE_SLOT = 1
