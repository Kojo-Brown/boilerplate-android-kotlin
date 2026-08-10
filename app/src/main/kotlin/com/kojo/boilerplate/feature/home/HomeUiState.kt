package com.kojo.boilerplate.feature.home

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class HomeItem(
    val id: String,
    val title: String,
    val description: String,
)

@Immutable
sealed class HomeUiState {
    data object Loading : HomeUiState()

    /**
     * [items] is an [ImmutableList] rather than a `List` because `List` is an interface and
     * `HomeContent` is called on every frame of a scroll.
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
    data class Success(
        val items: ImmutableList<HomeItem>,
        val greeting: String,
    ) : HomeUiState()

    @Immutable
    data class Error(val message: String) : HomeUiState()
}

/**
 * Where the network refresh has got to, kept separate from [HomeUiState].
 *
 * The list on screen is served from the database and stays valid throughout a refresh, so
 * folding this into [HomeUiState] would mean tapping refresh replaced a readable list with
 * a spinner and a failure replaced it with an error page — losing data the user could still
 * use in order to report on a request they made *about* that data. The same argument the
 * offline banner is built on.
 *
 * Successful refreshes need no state of their own to be visible: the writes land in Room and
 * the list observing it re-renders. What [Finished] carries is the part the list cannot
 * show, which is what did *not* arrive.
 */
@Immutable
sealed interface RefreshState {

    /** No refresh has run, or the last result has been dismissed. */
    data object Idle : RefreshState

    /** A fan-out is in flight. */
    data object InProgress : RefreshState

    /**
     * A fan-out completed. [refreshed] and [failed] together account for every user the
     * refresh attempted, so `refreshed + failed == 0` means there was nothing to refresh.
     */
    @Immutable
    data class Finished(
        val refreshed: Int,
        val failed: Int,
    ) : RefreshState
}
