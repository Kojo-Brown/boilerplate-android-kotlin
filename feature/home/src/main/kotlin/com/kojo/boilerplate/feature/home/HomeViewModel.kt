package com.kojo.boilerplate.feature.home

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.kojo.boilerplate.core.common.network.NetworkMonitor
import com.kojo.boilerplate.core.coroutines.asSearchQueries
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.usecase.RefreshVisibleUsersUseCase
import com.kojo.boilerplate.core.paging.PagedUserRepository
import com.kojo.boilerplate.core.ui.udf.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// flatMapLatest is still @ExperimentalCoroutinesApi in coroutines 1.9.0. The
// opt-in is recorded here rather than left as a compiler warning so that the
// experimental surface this class depends on is visible at the declaration.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    // The paged contract, not `UserRepository`. `getUsers()` is every row Room holds, in one
    // list, re-emitted whole for a single edited row — the right shape for a handful of users
    // and the wrong one for a list that grows without a bound. Still a repository rather than a
    // use case, for the reason `docs/solid.md` gives: the read has no policy in between, and a
    // use case forwarding one method to one repository is a hop that buys nothing. The refresh
    // is the opposite case — three decisions with wrong answers — which is why that one moved.
    private val pagedUsers: PagedUserRepository,
    private val refreshVisibleUsers: RefreshVisibleUsersUseCase,
    networkMonitor: NetworkMonitor,
) : UdfViewModel<HomeUiState, HomeUiEvent, HomeUiEffect>() {

    private val searchQuery = MutableStateFlow("")

    /**
     * The refresh's in-flight flag *and* its lock — see the CAS in [refresh]. One atomic value
     * cannot disagree with itself about whether a refresh is running, which is why the two are
     * not separate.
     */
    private val refreshing = MutableStateFlow(false)

    /**
     * The one paged stream this view model owns, built once and handed to every state it emits.
     *
     * ### `flatMapLatest`, and what a new query costs
     *
     * A query is not a filter over this stream — it is a parameter of the query the stream is
     * made of, so each distinct one is a different `Pager` and `flatMapLatest` is what cancels
     * the previous one. `merge` or `flatMapConcat` would leave every query the user typed
     * loading pages in parallel, all of them writing to the same Room table.
     *
     * That makes the debounce in [asSearchQueries] load-bearing rather than a nicety. Typing
     * `alice` undebounced would build five `Pager`s, four of which exist only long enough to run
     * an initial load of three pages against the database. It is also why the trimming and the
     * `distinctUntilChanged` inside `asSearchQueries` matter here more than they did over a
     * list: `"alice "` and `"alice"` are the same search, and restarting paging is a visibly
     * more expensive way to discover that than re-running a filter.
     *
     * ### `cachedIn`, which is not optional
     *
     * A `PagingData` is a one-shot stream of load events. Without `cachedIn` every collector
     * gets its own generation and starts at page one — so a rotation would drop the reader back
     * to the top of the list, and the two-pane layout would page the same list twice. Scoped to
     * `viewModelScope` because that is the scope that outlives the composition and not the
     * screen.
     *
     * `cachedIn` also makes this flow *hot* for as long as the view model lives, which is the
     * one place this class departs from the `WhileSubscribed(5_000)` shape below. It is what the
     * operator is for — holding the loaded pages across a configuration change — and it holds
     * pages rather than a subscription: the database query behind them is the `PagingSource`'s,
     * and Paging closes that when the last collector goes away.
     */
    private val users: Flow<PagingData<HomeItem>> = searchQuery
        .asSearchQueries()
        .flatMapLatest { query -> pagedUsers.users(query) }
        .map { pagingData -> pagingData.map { user -> user.toHomeItem() } }
        .cachedIn(viewModelScope)

    /**
     * The initial `false` is "assume online", so a cold start does not flash a banner in the
     * window before the monitor has reported; the first real status arrives immediately after.
     * It is also what keeps a monitor that never emits from stalling the whole screen, for the
     * same `combine` reason as always. `distinctUntilChanged` absorbs the duplicate when the
     * first real status agrees with the assumption.
     */
    private val offline: Flow<Boolean> = networkMonitor.networkStatus
        .map { status -> !status.isOnline }
        .onStart { emit(false) }
        .distinctUntilChanged()

    /**
     * `WhileSubscribed(5_000)` is what makes the connectivity callback cost nothing while
     * nobody is looking: it is registered on the first collector and torn down five seconds
     * after the last one leaves — long enough to cover an Activity recreation, short enough
     * that a backgrounded screen stops holding a callback open. See `docs/state-and-events.md`.
     *
     * [users] is passed as part of the initial value and not only inside the transform, and that
     * is what keeps `HomeUiState`'s `@Immutable` promise honest: every state this flow ever
     * emits carries the *same* stream instance, so the screen's `collectAsLazyPagingItems()`
     * keeps one presenter for the life of the composition instead of rebuilding it — and paging
     * does not restart — when an unrelated field changes.
     */
    override val state: StateFlow<HomeUiState> = combine(
        searchQuery,
        offline,
        refreshing,
    ) { query, isOffline, isRefreshing ->
        HomeUiState(
            users = users,
            searchQuery = query,
            isOffline = isOffline,
            isRefreshing = isRefreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = HomeUiState(users = users),
    )

    override fun onEvent(event: HomeUiEvent) {
        when (event) {
            is HomeUiEvent.SearchQueryChanged -> searchQuery.value = event.query
            is HomeUiEvent.RefreshClicked -> refresh(event.visibleUserIds)
        }
    }

    /**
     * Re-fetches the users the reader can see from the network, all at once.
     *
     * This is the only thing on the screen that asks the network for a *user* rather than for a
     * page. The paged list has its own network path — the `RemoteMediator` behind
     * [PagedUserRepository] — and the two do different jobs: the mediator extends the list
     * forwards, this one makes the rows already in it current. Neither can stand in for the
     * other, and until this existed `UserRepository.syncUser` had no caller outside its tests.
     *
     * [visibleUserIds] arrives on the event because the view model cannot see the viewport —
     * see [HomeUiEvent.RefreshClicked] for why that is the right place for it rather than a
     * shortcoming. An empty list is a legitimate value and the use case makes no request for it.
     */
    private fun refresh(visibleUserIds: List<String>) {
        // Claim with a CAS, not read-check-write. Two taps landing in the same frame both read
        // false, both pass a check, and both launch a fan-out — doubling the requests and
        // racing to write the result. Under the CAS the loser fails to claim and becomes a
        // no-op.
        if (!refreshing.compareAndSet(expect = false, update = true)) return

        viewModelScope.launch {
            try {
                val outcome = refreshVisibleUsers(visibleUserIds)
                if (outcome.failed > 0) {
                    emitEffect(
                        HomeUiEffect.RefreshIncomplete(
                            refreshed = outcome.refreshed,
                            failed = outcome.failed,
                        ),
                    )
                }
            } finally {
                // In a `finally` because anything else leaves the flag stuck on after a
                // throw, and a stuck flag is a refresh button that never works again — the
                // CAS above would reject every later tap. The previous shape had that bug.
                //
                // Defensive rather than exercised: nothing below here throws today, because
                // the fan-out reports its failures as values. It is deliberately not unit
                // tested — an exception escaping `viewModelScope` goes to the application's
                // CoroutineExceptionHandler, and `runTest` treats that as the test failing,
                // so the test would be about the framework rather than about this flag.
                refreshing.value = false
            }
        }
    }

    private companion object {
        /**
         * Long enough to cover a configuration change, short enough that a backgrounded screen
         * stops costing anything. The standard Android value, and it governs a platform
         * callback registration.
         */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

/**
 * What the list renders for one user.
 *
 * A free function rather than a `map` inside the state builder, because under paging it is
 * applied per item by the presenter as the reader reaches it — `PagingData.map` is lazy — rather
 * than over a whole list on every emission. That is also why no dispatcher is injected for it any
 * more: there is no longer a list-sized transform to confine, and a `flowOn` here would move the
 * construction of the `PagingData` rather than the mapping of its items.
 *
 * `internal` so that `HomeViewModelTest` can assert the mapping directly. That is not a
 * convenience: the transform is applied lazily *inside* a `PagingData`, and reading items back
 * out of one needs either `androidx.paging:paging-testing` or an instrumented test, so a test
 * that went through the view model would be asserting the framework rather than these three
 * lines. See the PR that introduced this for what is consequently untested here.
 */
internal fun User.toHomeItem(): HomeItem = HomeItem(
    id = id,
    title = displayName,
    description = email,
)
