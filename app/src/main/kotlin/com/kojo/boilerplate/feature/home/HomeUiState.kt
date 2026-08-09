package com.kojo.boilerplate.feature.home

data class HomeItem(
    val id: String,
    val title: String,
    val description: String,
)

sealed class HomeUiState {
    data object Loading : HomeUiState()

    data class Success(
        val items: List<HomeItem>,
        val greeting: String,
    ) : HomeUiState()

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
sealed interface RefreshState {

    /** No refresh has run, or the last result has been dismissed. */
    data object Idle : RefreshState

    /** A fan-out is in flight. */
    data object InProgress : RefreshState

    /**
     * A fan-out completed. [refreshed] and [failed] together account for every user the
     * refresh attempted, so `refreshed + failed == 0` means there was nothing to refresh.
     */
    data class Finished(
        val refreshed: Int,
        val failed: Int,
    ) : RefreshState
}
