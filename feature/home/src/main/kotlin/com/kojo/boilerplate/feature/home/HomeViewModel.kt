package com.kojo.boilerplate.feature.home

import androidx.lifecycle.viewModelScope
import com.kojo.boilerplate.core.common.network.NetworkMonitor
import com.kojo.boilerplate.core.coroutines.DefaultDispatcher
import com.kojo.boilerplate.core.coroutines.asSearchQueries
import com.kojo.boilerplate.core.coroutines.retryWithBackoff
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.repository.UserRepository
import com.kojo.boilerplate.core.domain.usecase.RefreshVisibleUsersUseCase
import com.kojo.boilerplate.core.ui.udf.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// flatMapLatest is still @ExperimentalCoroutinesApi in coroutines 1.9.0. The
// opt-in is recorded here rather than left as a compiler warning so that the
// experimental surface this class depends on is visible at the declaration.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    // Still the repository and not a use case for the read path. `getUsers()` is observed and
    // rendered with no policy in between, and a use case that forwards one method to one
    // repository is a hop that buys nothing — the argument docs/solid.md makes for why the
    // missing layer was defensible at this size. The refresh is the opposite case: three
    // decisions with wrong answers, which is why that one moved.
    private val userRepository: UserRepository,
    private val refreshVisibleUsers: RefreshVisibleUsersUseCase,
    // Covers the filtering and item mapping in `content` below, and nothing else.
    //
    // @DefaultDispatcher and not @IoDispatcher: this was IO while the same flowOn also
    // covered the repository's row mapping, which the repository now confines itself. What
    // is left is a scan of the whole user list plus an allocation per surviving row —
    // CPU-bound, with no I/O anywhere in it. Leaving it on the IO pool would be the mistake
    // CoroutineErrorModule describes: IO is sized for threads that are parked waiting, so
    // filling it with work that actually wants a core starves the calls it exists for.
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    networkMonitor: NetworkMonitor,
) : UdfViewModel<HomeUiState, HomeUiEvent, HomeUiEffect>() {

    private val searchQuery = MutableStateFlow("")

    private val retrySignal = MutableStateFlow(0)

    /**
     * The refresh's in-flight flag *and* its lock — see the CAS in [refresh]. One atomic value
     * cannot disagree with itself about whether a refresh is running, which is why the two are
     * not separate.
     */
    private val refreshing = MutableStateFlow(false)

    /**
     * `flatMapLatest` and not `flatMapConcat`/`merge`: a manual retry must *replace* the
     * previous subscription, and only `flatMapLatest` cancels the inner flow it is switching
     * away from. With either alternative every tap on retry would leave another live collection
     * of `getUsers()` behind it, all of them writing to the same state.
     */
    private val content: Flow<HomeContent> = retrySignal
        .flatMapLatest {
            combine<List<User>, String, HomeContent>(
                // Retry first, dedupe second. Resubscribing replays whatever the source has
                // already emitted, and Room invalidates per table rather than per row, so an
                // unrelated write to `users` re-delivers a byte-identical list. The `stateIn`
                // at the end would conflate the resulting state anyway — but only after the
                // filter and the whole item mapping had run over the full list again.
                // `distinctUntilChanged` drops the duplicate before that work happens.
                userRepository.getUsers()
                    .retryWithBackoff()
                    .distinctUntilChanged(),
                searchQuery.asSearchQueries(),
            ) { users, query ->
                val filtered = if (query.isBlank()) {
                    users
                } else {
                    users.filter {
                        it.displayName.contains(query, ignoreCase = true) ||
                            it.email.contains(query, ignoreCase = true)
                    }
                }
                HomeContent.Users(
                    // Mapped straight into a persistent-list builder rather than through
                    // `map { }.toImmutableList()`. The latter fills an ArrayList and then
                    // copies all of it into the persistent trie; the builder writes the trie
                    // once and `build()` hands over its root without copying. One list-sized
                    // allocation per emission instead of two, on a path that runs on every
                    // keystroke after the debounce.
                    items = filtered.mapTo(persistentListOf<HomeItem>().builder()) { user ->
                        HomeItem(
                            id = user.id,
                            title = user.displayName,
                            description = user.email,
                        )
                    }.build(),
                    greeting = "Boilerplate Android",
                )
            }.catch { throwable ->
                emit(HomeContent.Error(message = throwable.message ?: "Failed to load users"))
            }
        }
        // Covers the combine transform only. The repository's own `flowOn` sits closer to
        // the source, and the innermost `flowOn` wins for the section it encloses — so the
        // row mapping stays on IO and this governs the filtering above it.
        .flowOn(defaultDispatcher)
        // Outside the `flowOn`, and load-bearing rather than cosmetic. `combine` produces
        // nothing until *every* input has emitted, so without a value here the whole screen —
        // including the search field the user is typing into — would sit at the initial state
        // until the first database read landed. Emitting Loading up front decouples the two,
        // and it costs nothing: it is identical to `stateIn`'s initial value, so `stateIn`
        // conflates it away.
        .onStart { emit(HomeContent.Loading) }

    /**
     * The initial `false` is "assume online", so a cold start does not flash a banner in the
     * window before the monitor has reported; the first real status arrives immediately after.
     * It is also what keeps a monitor that never emits from stalling the whole screen, for the
     * same `combine` reason as above. `distinctUntilChanged` absorbs the duplicate when the
     * first real status agrees with the assumption.
     */
    private val offline: Flow<Boolean> = networkMonitor.networkStatus
        .map { status -> !status.isOnline }
        .onStart { emit(false) }
        .distinctUntilChanged()

    /**
     * `WhileSubscribed(5_000)` is what makes all four inputs cost nothing while nobody is
     * looking: the Room query, the debounce and the connectivity callback are all registered
     * on the first collector and torn down five seconds after the last one leaves — long
     * enough to cover an Activity recreation, short enough that a backgrounded screen stops
     * holding a socket open. See `docs/state-and-events.md`.
     */
    override val state: StateFlow<HomeUiState> = combine(
        content,
        searchQuery,
        offline,
        refreshing,
    ) { content, query, isOffline, isRefreshing ->
        HomeUiState(
            content = content,
            searchQuery = query,
            isOffline = isOffline,
            isRefreshing = isRefreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    override fun onEvent(event: HomeUiEvent) {
        when (event) {
            is HomeUiEvent.SearchQueryChanged -> searchQuery.value = event.query
            HomeUiEvent.RetryClicked -> retrySignal.update { it + 1 }
            HomeUiEvent.RefreshClicked -> refresh()
        }
    }

    /**
     * Re-fetches the users currently on screen from the network, all at once.
     *
     * [HomeUiEvent.RetryClicked] and this are different operations that a user would describe
     * with the same word. Retry resubscribes to the database query, which is the fix for a
     * *read* that failed; it cannot make the data newer, because nothing in this screen ever
     * asked the network for it. This is the one that does, and until it existed
     * [UserRepository.syncUser] had no caller outside its own tests — the app could display
     * users indefinitely without ever refetching one.
     *
     * The ids come from [state], so a refresh under an active search covers what the user is
     * looking at rather than the whole table. That is both the cheaper request and the one
     * they asked for; clearing the search and refreshing again covers the rest.
     */
    private fun refresh() {
        // Claim with a CAS, not read-check-write. Two taps landing in the same frame both read
        // false, both pass a check, and both launch a fan-out — doubling the requests and
        // racing to write the result. Under the CAS the loser fails to claim and becomes a
        // no-op.
        if (!refreshing.compareAndSet(expect = false, update = true)) return

        viewModelScope.launch {
            try {
                // `state.value` is the initial value whenever nothing is collecting, which on
                // this screen cannot happen — the tap came from a composition that is
                // collecting it. The trap is real for tests, and is why they keep a collector
                // alive; see `HomeViewModelRefreshTest`.
                val ids = (state.value.content as? HomeContent.Users)
                    ?.items
                    ?.map { it.id }
                    .orEmpty()
                val outcome = refreshVisibleUsers(ids)
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
         * callback registration as well as the repository subscription.
         */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
