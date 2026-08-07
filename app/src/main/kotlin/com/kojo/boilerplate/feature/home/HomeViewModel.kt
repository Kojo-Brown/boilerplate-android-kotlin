package com.kojo.boilerplate.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kojo.boilerplate.core.coroutines.IoDispatcher
import com.kojo.boilerplate.core.coroutines.asSearchQueries
import com.kojo.boilerplate.core.coroutines.retryWithBackoff
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.network.connectivity.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

// flatMapLatest is still @ExperimentalCoroutinesApi in coroutines 1.9.0. The
// opt-in is recorded here rather than left as a compiler warning so that the
// experimental surface this class depends on is visible at the declaration.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userRepository: UserRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    /**
     * Whether to tell the user the list they are looking at may be stale.
     *
     * Separate from [uiState] rather than folded into it, because it answers a different
     * question. `uiState` says whether the *last load* worked; this says whether a load could
     * work *now*. A cached list plus "you are offline" is a truthful screen, and turning the
     * whole thing into an error state because the network dropped would throw away data the
     * user can still read.
     *
     * `WhileSubscribed(5_000)` matches `uiState`: the same rotation that keeps the loaded
     * list also keeps the network callback registered, instead of tearing it down and putting
     * it back up again a frame later. Nothing is registered at all while no one is looking.
     *
     * The initial value is "online" so a cold start does not flash a banner in the window
     * before the monitor has reported; the first real status arrives immediately after.
     */
    val isOffline: StateFlow<Boolean> = networkMonitor.networkStatus
        .map { status -> !status.isOnline }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = false,
        )

    /**
     * What the text field shows. Undebounced on purpose: the field is bound to this, and a
     * character that appears 300ms after it is typed reads as a broken keyboard. The debounce
     * goes on the derived query below, where it saves work instead of costing responsiveness.
     */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _retrySignal = MutableStateFlow(0)

    /**
     * `flatMapLatest` and not `flatMapConcat`/`merge`: a manual retry must *replace* the
     * previous subscription, and only `flatMapLatest` cancels the inner flow it is switching
     * away from. With either alternative every tap on retry would leave another live collection
     * of `getUsers()` behind it, all of them writing to the same state.
     */
    val uiState: StateFlow<HomeUiState> = _retrySignal
        .flatMapLatest {
            combine<List<User>, String, HomeUiState>(
                // Retry first, dedupe second. Resubscribing replays whatever the source has
                // already emitted, and Room invalidates per table rather than per row, so an
                // unrelated write to `users` re-delivers a byte-identical list. The `stateIn`
                // at the end would conflate the resulting state anyway — but only after the
                // filter and the whole item mapping had run over the full list again.
                // `distinctUntilChanged` drops the duplicate before that work happens.
                userRepository.getUsers()
                    .retryWithBackoff()
                    .distinctUntilChanged(),
                _searchQuery.asSearchQueries(),
            ) { users, query ->
                val filtered = if (query.isBlank()) {
                    users
                } else {
                    users.filter {
                        it.displayName.contains(query, ignoreCase = true) ||
                            it.email.contains(query, ignoreCase = true)
                    }
                }
                HomeUiState.Success(
                    items = filtered.map { user ->
                        HomeItem(
                            id = user.id,
                            title = user.displayName,
                            description = user.email,
                        )
                    },
                    greeting = "Boilerplate Android",
                )
            }.catch { throwable ->
                emit(HomeUiState.Error(message = throwable.message ?: "Failed to load users"))
            }
        }
        .flowOn(ioDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = HomeUiState.Loading,
        )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun retry() {
        _retrySignal.update { it + 1 }
    }

    private companion object {
        /**
         * Long enough to cover a configuration change, short enough that a backgrounded screen
         * stops costing anything. The standard Android value, and it now governs a platform
         * callback registration as well as the repository subscription.
         */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
