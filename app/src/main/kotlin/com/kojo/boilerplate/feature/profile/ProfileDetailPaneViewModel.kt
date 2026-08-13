package com.kojo.boilerplate.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kojo.boilerplate.core.domain.usecase.ObserveUserProfileUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

// flatMapLatest is still @ExperimentalCoroutinesApi in coroutines 1.9.0. The
// opt-in is recorded here rather than left as a compiler warning so that the
// experimental surface this class depends on is visible at the declaration.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ProfileDetailPaneViewModel.Factory::class)
class ProfileDetailPaneViewModel @AssistedInject constructor(
    @Assisted private val userId: String,
    private val observeUserProfile: ObserveUserProfileUseCase,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(userId: String): ProfileDetailPaneViewModel
    }

    private val _retrySignal = MutableStateFlow(0)

    /**
     * Identical to `ProfileViewModel.uiState`, and that is now the whole story rather than a
     * problem: both are one `flatMapLatest` over the same use case, and the policy they used
     * to hold two copies of lives in [ObserveUserProfileUseCase]. The only difference between
     * the two screens is where [userId] comes from — an `@Assisted` parameter here, a
     * `SavedStateHandle` route there — which is the difference that is actually real.
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
        /** Matches `ProfileViewModel` and `HomeViewModel`; see the note there. */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
