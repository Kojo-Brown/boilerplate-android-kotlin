package com.kojo.boilerplate.core.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measure-time decisions of [LabelledValue] and [ExpandableText], exercised on a plain JVM.
 *
 * This is the whole reason they are functions over pixels rather than lines inside a measure
 * lambda. A `MeasurePolicy` runs in the layout phase, so covering one in place means composing —
 * which in this repository means `androidTest`, and `androidTest` currently runs nowhere (see
 * `docs/room-migrations.md` for what that cost the migration suite, and Phase 12 for the emulator
 * matrix that would fix it). Everything below runs under `testDebugUnitTest` on every push.
 *
 * Two of these cases are the ones that would be found on a device, months later, by a user:
 * `Int.MAX_VALUE` reaching the fit arithmetic, and an `expanded` flag outliving the text it was set
 * for.
 */
class MeasurePolicyTest {

    @Test
    fun `a label and value exactly filling the row fit on it`() {
        assertTrue(fitsOnOneRow(labelWidth = 100, valueWidth = 380, gap = 20, available = 500))
    }

    @Test
    fun `one pixel more than the row does not fit`() {
        assertFalse(fitsOnOneRow(labelWidth = 100, valueWidth = 381, gap = 20, available = 500))
    }

    @Test
    fun `a zero gap is the whole row`() {
        assertTrue(fitsOnOneRow(labelWidth = 250, valueWidth = 250, gap = 0, available = 500))
        assertFalse(fitsOnOneRow(labelWidth = 250, valueWidth = 251, gap = 0, available = 500))
    }

    /**
     * The overflow case, and the one this function exists for.
     *
     * `Constraints.Infinity` is `Int.MAX_VALUE`, and a `Text`'s maximum intrinsic width is its
     * width with no wrapping at all — so a long unwrapped string under an unbounded parent puts
     * numbers of this size on both sides. Added as `Int`s they wrap negative, a negative sum is
     * `<=` anything, and the true answer inverts: the caller then measures a child against a
     * negative width, which is an exception out of `Constraints` rather than a layout.
     */
    @Test
    fun `widths near Int MAX_VALUE do not wrap around into a false fit`() {
        assertFalse(
            fitsOnOneRow(
                labelWidth = Int.MAX_VALUE - 1,
                valueWidth = Int.MAX_VALUE - 1,
                gap = 16,
                available = 1080,
            ),
        )
    }

    @Test
    fun `an unbounded row fits anything that is not itself unbounded`() {
        assertTrue(
            fitsOnOneRow(
                labelWidth = 100_000,
                valueWidth = 100_000,
                gap = 16,
                available = Int.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `text that does not overflow is collapsed and offers no control`() {
        val plan = planExpandableText(collapsedHeight = 120, probeHeight = 120, expanded = false)

        assertEquals(ExpandableTextBody.Collapsed, plan.body)
        assertFalse(plan.showsToggle)
    }

    /**
     * The rule the obvious implementation gets wrong. `expanded` is screen state and outlives the
     * string it was set for: expand a page of recognised text, point the camera at a door sign, and
     * the next scan replaces the text with four words. Obeying the flag there would place an
     * expanded body identical to the collapsed one under a control that collapses to what is
     * already on screen.
     */
    @Test
    fun `text that does not overflow is collapsed even when the caller says expanded`() {
        val plan = planExpandableText(collapsedHeight = 120, probeHeight = 120, expanded = true)

        assertEquals(ExpandableTextBody.Collapsed, plan.body)
        assertFalse(plan.showsToggle)
    }

    @Test
    fun `text that overflows offers a control and stays collapsed until it is used`() {
        val plan = planExpandableText(collapsedHeight = 120, probeHeight = 140, expanded = false)

        assertEquals(ExpandableTextBody.Collapsed, plan.body)
        assertTrue(plan.showsToggle)
    }

    /** An expanded text keeps its control, or there is no way back. */
    @Test
    fun `text that overflows and was expanded shows everything and keeps its control`() {
        val plan = planExpandableText(collapsedHeight = 120, probeHeight = 140, expanded = true)

        assertEquals(ExpandableTextBody.Expanded, plan.body)
        assertTrue(plan.showsToggle)
    }

    @Test
    fun `the overflow probe asks for one line more than the collapsed limit`() {
        assertEquals(2, overflowProbeMaxLines(1))
        assertEquals(7, overflowProbeMaxLines(6))
    }

    /**
     * A text with no line limit has nothing left to reveal, so the probe is the limit itself and
     * [planExpandableText] reports no overflow. The arithmetic must not be what says so — one more
     * than `Int.MAX_VALUE` is `Int.MIN_VALUE`, which as a `maxLines` is an exception from `Text`.
     */
    @Test
    fun `an unlimited collapsed line count does not overflow into a negative probe`() {
        assertEquals(Int.MAX_VALUE, overflowProbeMaxLines(Int.MAX_VALUE))
    }
}
