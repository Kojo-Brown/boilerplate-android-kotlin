package com.kojo.boilerplate.feature.home

import androidx.compose.runtime.Immutable
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

@Immutable
data class HomeItem(
    val id: String,
    val title: String,
    val description: String,
)

/**
 * The heading above the list. A constant rather than a resource for the same reason the rest of
 * this screen's copy is one — this is a boilerplate, and localisation is its own spec item.
 */
private const val DEFAULT_GREETING = "Boilerplate Android"

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
 * independent fields keeps every distinction and still hands the composable one object: the
 * screen collects once, renders once, and cannot observe half an update — which four flows,
 * conflated independently, allow.
 *
 * ### Why the list is a `Flow` and not a list
 *
 * [users] used to be a `HomeContent` — a sealed `Loading` / `Users(ImmutableList)` / `Error`,
 * rebuilt in the view model on every database emission. Paging cannot be expressed that way and
 * should not be: a `PagingData` is a stream of load *events*, and the presenter that turns it
 * into rows ([androidx.paging.compose.LazyPagingItems]) is a composition-scoped object, because
 * which pages are live depends on where the reader has scrolled. Collapsing it back into a list
 * in the view model would mean holding every loaded page in memory to hand the screen a snapshot
 * that is stale the moment a page loads — which is the whole of what Paging exists to avoid.
 *
 * `Loading` and `Error` went with it, and that is a deletion rather than a move. They are now
 * `LazyPagingItems.loadState`, which already distinguishes the refresh from the append: a failed
 * *append* must leave the loaded pages on screen with a retry under them, and a sealed
 * `HomeContent.Error` could only replace the whole list. Keeping both would be two answers to
 * "did the last load work" that nothing keeps in step.
 *
 * The field is a `Flow` rather than the `LazyPagingItems` itself because a presenter belongs to a
 * composition and a view model outlives several of them. What the view model owns is the stream,
 * built once and `cachedIn(viewModelScope)`; what each composition owns is the presenter it
 * collects from it.
 *
 * ### Why `@Immutable` is still true
 *
 * A `Flow` is an interface, so the Compose compiler cannot infer anything about it and treats
 * this class as unstable without the annotation — which would cost the screen its skipping on
 * every keystroke, since the search field is bound to [searchQuery] undebounced. The annotation
 * is an unchecked promise and the promise here is kept: [HomeViewModel] builds exactly one
 * stream and copies the same reference into every state it emits, so the property never changes
 * and `equals` — reference equality, which is what `Flow` has — is stable across emissions.
 *
 * That is why the field has no default. A defaulted `emptyFlow()` would be a *second* instance,
 * handed to the screen for as long as the initial value stood, and swapping it out would rebuild
 * the presenter and restart paging from page one on every cold start. The view model passes its
 * own stream as `stateIn`'s initial value instead, and `StabilityContractTest` pins this as the
 * one `Flow`-typed state property in the app so that the next one has to make the same argument.
 */
@Immutable
data class HomeUiState(
    /**
     * The paged users, already mapped to what the list renders. Collected with
     * `collectAsLazyPagingItems()` by the composable and by nothing else — a view model reading
     * it would be re-deriving the presenter's scroll-dependent state without the scroll.
     */
    val users: Flow<PagingData<HomeItem>>,
    /**
     * What the text field shows. Undebounced on purpose: the field is bound to this, and a
     * character that appears 300ms after it is typed reads as a broken keyboard. The debounce
     * goes on the derived query inside the view model, where it saves work instead of costing
     * responsiveness — and under paging it saves more than it used to, since each distinct query
     * builds a new `PagingSource` rather than re-running a filter.
     */
    val searchQuery: String = "",
    /**
     * Whether to tell the user the list they are looking at may be stale. A different question
     * from the list's own load state: that says whether the last load worked, this says whether a
     * load could work *now*. A cached list plus "you are offline" is a truthful screen.
     */
    val isOffline: Boolean = false,
    /** A network refresh is in flight. Also the refresh's own in-flight lock — see the CAS. */
    val isRefreshing: Boolean = false,
    /**
     * The heading above the list. Here rather than in the composable so the screen still renders
     * from one value; it is the last survivor of `HomeContent.Users`.
     */
    val greeting: String = DEFAULT_GREETING,
)
