package com.kojo.boilerplate.feature.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kojo.boilerplate.core.domain.usecase.ObserveUserProfileUseCase
import com.kojo.boilerplate.navigation.AppDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

// flatMapLatest is still @ExperimentalCoroutinesApi in coroutines 1.9.0. The
// opt-in is recorded here rather than left as a compiler warning so that the
// experimental surface this class depends on is visible at the declaration.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeUserProfile: ObserveUserProfileUseCase,
) : ViewModel() {

    private val route: AppDestination.Profile = savedStateHandle.toRoute()
    private val userId: String = route.userId

    private val _retrySignal = MutableStateFlow(0)

    /**
     * Retry, dedupe and the missing-row decision all sit in [ObserveUserProfileUseCase] now;
     * what is left here is the route's id, the retry signal, and turning the outcome into
     * strings. `ProfileDetailPaneViewModel` held a verbatim copy of the part that moved —
     * `docs/solid.md` finding 1.
     *
     * No `flowOn` and no injected dispatcher, deliberately: the repository confines its own
     * I/O and row mapping, so what runs here is one `when` and one [ProfileData] allocation
     * per emission. That is cheaper than the thread hand-off a `flowOn` would add to pay for
     * it — see `docs/dispatchers.md`.
     */
    val uiState: StateFlow<ProfileUiState> = _retrySignal
        .flatMapLatest { observeUserProfile(userId).map { profile -> profile.toUiState() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = ProfileUiState.Loading,
        )

    fun retry() {
        _retrySignal.update { it + 1 }
    }

    private companion object {
        /**
         * Long enough to cover a configuration change, short enough that a backgrounded
         * screen stops costing anything. The standard Android value, and the same one
         * `HomeViewModel` uses.
         */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
