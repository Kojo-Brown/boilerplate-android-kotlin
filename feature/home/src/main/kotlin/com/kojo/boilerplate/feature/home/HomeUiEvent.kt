package com.kojo.boilerplate.feature.home

import com.kojo.boilerplate.core.ui.udf.UiEvent

/**
 * Everything the home screen can be told.
 *
 * ### Where `RetryClicked` went
 *
 * It used to sit beside [RefreshClicked] because the two were genuinely different: retry
 * resubscribed to the database query, which is the fix for a *read* that failed and cannot make
 * the data any newer, and refresh is the one that asks the network.
 *
 * Under paging there is no subscription for a view model to replace. A failed load is
 * `LoadState.Error` on whichever end of the list failed, and the fix for it is
 * `LazyPagingItems.retry()` — which retries *that* load, leaving the pages that did arrive where
 * they are. A `RetryClicked` event would have to travel up to the view model and back down to a
 * presenter that lives in the composition, to do what the presenter already exposes. So the
 * distinction the two members recorded is now made by Paging, and the member that restated it is
 * gone rather than kept as a forwarding hop.
 */
sealed interface HomeUiEvent : UiEvent {

    /** A keystroke in the search field. */
    data class SearchQueryChanged(val query: String) : HomeUiEvent

    /**
     * The refresh action in the app bar. Re-fetches the users the reader can currently see.
     *
     * ### Why this one carries a payload
     *
     * Because after the move to paging the view model no longer knows which users those are. It
     * used to read them out of `state.value.content`, which held every row the screen showed;
     * under paging the loaded pages live in the composition's `LazyPagingItems` and *which of
     * them are on screen* lives in the `LazyListState` — both presenter state, both by
     * construction unavailable to a `ViewModel`, and rightly so: neither survives the
     * composition, and a view model holding a scroll position would be holding a second copy of
     * something the layout already owns.
     *
     * The alternative was to redefine the refresh as "every page loaded so far", which the view
     * model could derive. That is a different and worse operation: the fan-out makes one request
     * per id, so it would grow with the scroll and a reader deep in the list would fire hundreds
     * of requests to update the dozen rows in front of them. `docs/fan-out.md` is about bounding
     * that work, not about finding new ways to spend it.
     *
     * So the ids travel with the event. This is not state in the composable — nothing is held
     * across a recomposition, and nothing has to be cleared after it is read, which are the two
     * questions `docs/state-and-events.md` asks. It is a reading of the layout taken at the
     * instant the button was pressed, which is exactly when "what I am looking at" is defined.
     *
     * @param visibleUserIds the ids of the rows laid out in the viewport, in view order. Empty is
     *   legitimate — an empty or still-loading list — and the use case treats it as a no-op.
     */
    data class RefreshClicked(val visibleUserIds: List<String>) : HomeUiEvent
}
