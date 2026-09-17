package com.kojo.boilerplate.core.ui.transition

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp

/**
 * How far through a back gesture the user is, and which edge they started from.
 *
 * Read by [predictiveBackDismiss] and by nothing else, which is the point: [progress] moves with
 * the user's finger, so anything reading it *during composition* is recomposed every frame of the
 * gesture. It is exposed as a holder rather than as a returned `Float` for exactly that reason —
 * a `@Composable` function returning a value is not restartable, so its state reads are recorded
 * against its caller, and the caller here is a whole two-pane layout. `useListDetailLayout()` is
 * the same shape and `docs/derived-state.md` has the argument.
 */
@Stable
class PredictiveBackDismissState internal constructor() {

    private val progressState = mutableFloatStateOf(0f)
    private val swipeEdgeState = mutableIntStateOf(BackEventCompat.EDGE_LEFT)

    /**
     * 0 when no gesture is in flight, rising to 1 as the user commits to it. Never read during
     * composition — see the class KDoc.
     */
    val progress: Float get() = progressState.floatValue

    /** [BackEventCompat.EDGE_LEFT] or [BackEventCompat.EDGE_RIGHT]. */
    val swipeEdge: Int get() = swipeEdgeState.intValue

    internal fun onEvent(event: BackEventCompat) {
        progressState.floatValue = event.progress
        swipeEdgeState.intValue = event.swipeEdge
    }

    internal fun reset() {
        progressState.floatValue = 0f
    }
}

/**
 * Takes over the back gesture while [enabled], reports its progress, and calls [onDismissRequest]
 * if the user commits to it.
 *
 * ### Why this exists rather than a `BackHandler`
 *
 * There is one piece of back navigation in this app that `NavHost` does not own. In the
 * list-detail layout both panes are on screen inside a single navigation entry, so selecting a
 * user is not a navigation — it is state — and back has to clear that selection before it means
 * "leave the screen". Without something here, back with a profile on screen leaves Home
 * altogether, which is not what the user asked for.
 *
 * The obvious tool is `BackHandler`, and it is the wrong one. `BackHandler` consumes the gesture
 * without taking part in it: the system's predictive-back animation is suppressed for the whole
 * time it is enabled, so the user gets no preview of what back will do — on this screen or on the
 * one underneath — and the dismissal happens in a single frame at the end. `PredictiveBackHandler`
 * hands over the gesture's progress instead, which is what lets the pane visibly shrink under the
 * finger and, crucially, *come back* when the gesture is abandoned. A back gesture that cannot be
 * cancelled is the thing predictive back was introduced to fix.
 *
 * Everything else in this app stays with `NavHost`, which seeks its own pop transition from the
 * same gesture once `android:enableOnBackInvokedCallback` is set on the manifest's `application`
 * node. Adding a handler on a destination would take that gesture away from it and replace a
 * seeked transition — one that tracks the finger and reverses — with a hand-rolled animation
 * played after the fact. `docs/shared-elements.md` records the division.
 *
 * ### The commit/cancel split
 *
 * The flow completing means the user let go past the threshold; the flow being cancelled means
 * they let go before it. Those are the only two outcomes, and they are distinguished here without
 * catching anything: a cancellation propagates out of the handler, as a cancellation should, and
 * the `finally` is what guarantees the pane is wound back on the way past. [onDismissRequest] sits
 * after the `try`, so it is unreachable on the cancelled path.
 *
 * @param enabled whether there is anything to dismiss. `false` hands the gesture straight back to
 *   the system, which is what keeps the back-to-home animation intact on a screen with no
 *   selection.
 */
@Composable
fun rememberPredictiveBackDismiss(
    enabled: Boolean,
    onDismissRequest: () -> Unit,
): PredictiveBackDismissState {
    val state = remember { PredictiveBackDismissState() }
    // The gesture outlives the recomposition that started it, so the handler's lambda captures
    // this holder rather than the callback itself: a selection changing mid-gesture must not
    // leave the finished gesture calling the callback the screen had three frames ago.
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)

    PredictiveBackHandler(enabled = enabled) { events ->
        var committed = false
        try {
            events.collect(state::onEvent)
            committed = true
        } finally {
            // Cancelled: the pane has to return to full size, and this is the only place that
            // runs on that path. Committed: the progress is deliberately *left* where the
            // gesture ended, because winding it back before the content is removed shows one
            // frame of a full-size pane on its way out.
            if (!committed) state.reset()
        }
        currentOnDismissRequest()
        state.reset()
    }

    return state
}

/**
 * Shrinks and fades this node in step with a back gesture tracked by [state].
 *
 * The transform is written into a `graphicsLayer` block rather than applied with `Modifier.scale`
 * and `Modifier.alpha`, and that is the whole reason this is a modifier and not three lines at the
 * call site. A value passed to `scale()` is read in *composition*, so every frame of the gesture
 * would recompose the subtree the modifier is on — a detail pane, its view model's state and a
 * profile's worth of cards, sixty times a second, to move a scale factor. The block form is
 * evaluated in the draw phase and its state reads are recorded there, so a progress change
 * invalidates drawing and nothing else. No recomposition, no relayout.
 *
 * The pivot follows the edge the gesture came from, so the pane leans towards the finger the way
 * the system's own back preview does, rather than collapsing towards its middle.
 */
fun Modifier.predictiveBackDismiss(state: PredictiveBackDismissState): Modifier =
    graphicsLayer {
        val progress = state.progress
        val scale = lerp(1f, DISMISS_MIN_SCALE, progress)
        scaleX = scale
        scaleY = scale
        alpha = lerp(1f, DISMISS_MIN_ALPHA, progress)
        transformOrigin = TransformOrigin(
            pivotFractionX = if (state.swipeEdge == BackEventCompat.EDGE_LEFT) 0f else 1f,
            pivotFractionY = 0.5f,
        )
    }

/**
 * How far the pane shrinks at full progress. The system's own predictive-back preview takes the
 * window to roughly this, and matching it is what stops the in-app dismissal reading as a
 * different gesture from the one that leaves the app.
 */
private const val DISMISS_MIN_SCALE = 0.9f

/** Enough fade to read as leaving, not enough to make the pane unreadable mid-gesture. */
private const val DISMISS_MIN_ALPHA = 0.5f
