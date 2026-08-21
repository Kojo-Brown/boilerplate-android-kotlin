package com.kojo.boilerplate.feature.home

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class HomeItem(
    val id: String,
    val title: String,
    val description: String,
)

/**
 * Everything the home screen renders, in one value.
 *
 * This screen is what the single-state rule is worth arguing about, because it is the one
 * where four separate flows were each defensible on their own. The list, the search text, the
 * offline banner and the refresh spinner really do answer different questions, and
 * `docs/state-and-events.md` records why the answers must not be collapsed into each other —
 * an offline device must not turn a readable list into an error page, and a refresh that fails
 * must not replace the rows the user is reading.
 *
 * None of that argues for four `StateFlow`s. It argues against one *sealed* state, where
 * `Error` would exclude the list and `Loading` would exclude the search text. A data class of
 * independent fields with the mutually-exclusive part in [content] keeps every distinction and
 * still hands the composable one object: the screen collects once, renders once, and cannot
 * observe half an update — which four flows, conflated independently, allow.
 */
@Immutable
data class HomeUiState(
    val content: HomeContent = HomeContent.Loading,
    /**
     * What the text field shows. Undebounced on purpose: the field is bound to this, and a
     * character that appears 300ms after it is typed reads as a broken keyboard. The debounce
     * goes on the derived query inside the view model, where it saves work instead of costing
     * responsiveness.
     */
    val searchQuery: String = "",
    /**
     * Whether to tell the user the list they are looking at may be stale. A different question
     * from [content]: that says whether the last load worked, this says whether a load could
     * work *now*. A cached list plus "you are offline" is a truthful screen.
     */
    val isOffline: Boolean = false,
    /** A network refresh is in flight. Also the refresh's own in-flight lock — see the CAS. */
    val isRefreshing: Boolean = false,
)

/** The mutually-exclusive part of the home screen: what sits where the list goes. */
@Immutable
sealed interface HomeContent {

    data object Loading : HomeContent

    /**
     * [items] is an [ImmutableList] rather than a `List` because `List` is an interface and
     * `HomeContent` is rendered on every frame of a scroll.
     *
     * A `List`-typed property makes the whole class unstable to the Compose compiler: it
     * cannot see the implementation, and `ArrayList` behind that interface is mutable. Under
     * strong skipping the composable is still skippable, but only by *instance* comparison —
     * so the list this view model rebuilds on every upstream emission is a new object every
     * time and never compares equal, even when the users are unchanged. `ImmutableList` is
     * in the Compose compiler's known-stable set, which puts the class back on structural
     * equality and lets an identical refresh skip the recomposition entirely.
     */
    @Immutable
    data class Users(
        val items: ImmutableList<HomeItem>,
        val greeting: String,
    ) : HomeContent

    @Immutable
    data class Error(val message: String) : HomeContent
}
