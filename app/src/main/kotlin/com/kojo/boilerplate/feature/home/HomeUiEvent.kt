package com.kojo.boilerplate.feature.home

import com.kojo.boilerplate.core.ui.udf.UiEvent

/**
 * Everything the home screen can be told.
 *
 * [RetryClicked] and [RefreshClicked] are two members and not one on purpose. A user would
 * describe both as "try again", and they are different operations: retry resubscribes to the
 * database query, which is the fix for a *read* that failed and cannot make the data any
 * newer; refresh is the one that asks the network. Naming them for the button that was pressed
 * rather than for what they do keeps that distinction where it belongs — in the view model,
 * which is the only place that knows there is one.
 */
sealed interface HomeUiEvent : UiEvent {

    /** A keystroke in the search field. */
    data class SearchQueryChanged(val query: String) : HomeUiEvent

    /** The retry button on the error state. Resubscribes to the user query. */
    data object RetryClicked : HomeUiEvent

    /** The refresh action in the app bar. Re-fetches the visible users from the network. */
    data object RefreshClicked : HomeUiEvent
}
