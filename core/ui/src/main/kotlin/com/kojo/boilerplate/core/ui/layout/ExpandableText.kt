package com.kojo.boilerplate.core.ui.layout

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/**
 * A block of text clipped to [collapsedMaxLines], with a control to open it — and *only* when
 * there is something behind the clip.
 *
 * ### Why this one needs `SubcomposeLayout`
 *
 * Whether the control exists is not a property of the string. A hundred characters is four lines
 * on a phone and two in a dialog, one line at `bodySmall` and three at `bodyLarge`, and more again
 * once the reader turns the system font size up. The only thing that knows whether this text
 * overflows is a text layout at the width and style this instance was actually given — which
 * happens in the measure pass, after composition has already decided what exists.
 *
 * That is the circle [LabelledValue] does not have to break and this one does: **what to compose
 * depends on a measurement, and a measurement is only available after composing.**
 * `SubcomposeLayout` is the runtime's answer — it can compose a slot *from inside* its own measure
 * pass, so the answer to "is there a control?" and the control itself can belong to the same frame.
 *
 * ### The alternative, and what it costs
 *
 * The usual way to write this needs no custom layout at all:
 *
 * ```
 * var overflows by remember { mutableStateOf(false) }
 * Text(text, maxLines = 4, onTextLayout = { overflows = it.hasVisualOverflow })
 * if (overflows) TextButton(…)
 * ```
 *
 * It reads better and it is wrong in a way that is easy to miss. `onTextLayout` runs in the layout
 * phase, so that assignment is a snapshot write *from* layout, which invalidates the composition
 * that layout was produced from: the first frame is drawn without the control, and the control
 * appears on the second. One frame is nothing on a screen that is composed once — and this text
 * lives in a `LazyColumn` item, which is re-composed and re-measured every time it is scrolled back
 * into view, so the pop repeats for the life of the screen.
 *
 * It is also the shape that produces "Reading a state that was modified during composition" and
 * infinite invalidation loops when the flag feeds back into what is measured, which here it does:
 * the control takes vertical space.
 *
 * The cost of doing it this way instead is one extra text layout, and [overflowProbeMaxLines] is
 * what keeps that extra layout bounded rather than proportional to the text.
 *
 * ### What the slots are
 *
 * Four, and each is subcomposed under a distinct entry of [ExpandableTextSlot]:
 *
 * - `Collapsed` — the text at [collapsedMaxLines]. Always measured; placed unless [expanded].
 * - `OverflowProbe` — the same text at one line more. **Measured and never placed**, which is what
 *   makes it invisible rather than hidden: an unplaced node is not drawn, and it is not walked by
 *   semantics either, so it does not reach TalkBack or a UI test's node tree.
 * - `Expanded` — the whole text. Subcomposed only when it is going to be shown.
 * - `Toggle` — the control. Subcomposed only when there is something to toggle.
 *
 * Distinct ids are not cosmetic: a slot id is the identity `SubcomposeLayout` reuses a
 * subcomposition under, so two `subcompose` calls sharing one id in a pass make the second replace
 * the first's composition — silently, with both still measuring. `CustomLayoutContractTest`
 * enforces it, because the broken form compiles and reads fine.
 *
 * @param expanded what the reader last asked for. Honoured only while the text actually overflows;
 *  see [planExpandableText] for the case where it must not be.
 * @param onExpandedChange called with the state the control would move to.
 */
@Composable
fun ExpandableText(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = ExpandableTextDefaults.CollapsedMaxLines,
    style: TextStyle = LocalTextStyle.current,
    gap: Dp = ExpandableTextDefaults.Gap,
) {
    require(collapsedMaxLines >= 1) {
        "collapsedMaxLines must be at least 1, was $collapsedMaxLines"
    }

    SubcomposeLayout(modifier = modifier) { constraints ->
        // Height left unbounded and re-constrained on the way out: the measurement this layout is
        // built around is "how tall would this text be", and a maxHeight from the parent would
        // answer it with the parent's number.
        val childConstraints = Constraints(maxWidth = constraints.maxWidth)

        // Named rather than inlined three times, and not `text` — that is the parameter it
        // renders, and shadowing it here is the sort of thing that compiles and reads wrong.
        fun measureText(slot: ExpandableTextSlot, maxLines: Int): Placeable =
            subcompose(slot) {
                Text(
                    text = text,
                    style = style,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }.single().measure(childConstraints)

        val collapsed = measureText(ExpandableTextSlot.Collapsed, collapsedMaxLines)
        val probe =
            measureText(ExpandableTextSlot.OverflowProbe, overflowProbeMaxLines(collapsedMaxLines))

        val plan = planExpandableText(
            collapsedHeight = collapsed.height,
            probeHeight = probe.height,
            expanded = expanded,
        )
        val showingEverything = plan.body == ExpandableTextBody.Expanded

        val body = when (plan.body) {
            ExpandableTextBody.Collapsed -> collapsed
            ExpandableTextBody.Expanded -> measureText(ExpandableTextSlot.Expanded, Int.MAX_VALUE)
        }
        val toggle = if (plan.showsToggle) {
            subcompose(ExpandableTextSlot.Toggle) {
                // Driven by the plan rather than by the `expanded` parameter, so that the label and
                // the body can never disagree: they are now two readings of one decision.
                TextButton(onClick = { onExpandedChange(!showingEverything) }) {
                    Text(
                        text = if (showingEverything) {
                            ExpandableTextDefaults.CollapseLabel
                        } else {
                            ExpandableTextDefaults.ExpandLabel
                        },
                    )
                }
            }.single().measure(childConstraints)
        } else {
            null
        }

        val gapPx = if (toggle == null) 0 else gap.roundToPx()
        val width = constraints.constrainWidth(maxOf(body.width, toggle?.width ?: 0))
        val height = constraints.constrainHeight(body.height + gapPx + (toggle?.height ?: 0))

        layout(width, height) {
            body.placeRelative(x = 0, y = 0)
            toggle?.placeRelative(x = 0, y = body.height + gapPx)
        }
    }
}

/**
 * The slots [ExpandableText] subcomposes, and the identities it reuses their compositions under.
 *
 * An `enum` for the reason the lazy lists in this app key on one: it is the narrowest type that
 * makes every id distinct by construction, and a `SubcomposeLayout` given two identical ids does
 * not fail — it quietly composes the second over the first.
 */
private enum class ExpandableTextSlot {
    Collapsed,
    OverflowProbe,
    Expanded,
    Toggle,
}

/** The copy and spacing [ExpandableText] uses when the caller does not say. */
object ExpandableTextDefaults {

    /**
     * Enough to tell a paragraph from a line of a receipt, and short enough that a page of
     * recognised text does not push everything below it off the screen.
     */
    const val CollapsedMaxLines: Int = 6

    /** Between the text and its control. */
    val Gap: Dp = 4.dp

    internal const val ExpandLabel = "Show all"

    internal const val CollapseLabel = "Show less"
}
