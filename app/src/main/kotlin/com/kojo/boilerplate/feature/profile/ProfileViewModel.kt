package com.kojo.boilerplate.feature.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kojo.boilerplate.core.coroutines.retryWithBackoff
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.navigation.AppDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val userRepository: UserRepository,
) : ViewModel() {

    private val route: AppDestination.Profile = savedStateHandle.toRoute()
    private val userId: String = route.userId

    private val _retrySignal = MutableStateFlow(0)

    /**
     * No `flowOn` and no injected dispatcher, deliberately.
     *
     * The repository confines its own I/O and row mapping, so everything left here is one
     * null check and one [ProfileData] allocation per emission. That is cheaper than the
     * thread hand-off a `flowOn` would add to pay for it, and running it on the main thread
     * is the correct answer rather than a tolerated one. A dispatcher belongs here only if
     * this transform grows real work — see `docs/dispatchers.md`.
     */
    val uiState: StateFlow<ProfileUiState> = _retrySignal
        .flatMapLatest {
            // Retry first, dedupe second — see HomeViewModel for why this pair belongs together.
            userRepository.getUser(userId)
                .retryWithBackoff()
                .distinctUntilChanged()
                .map { user ->
                    if (user != null) {
                        ProfileUiState.Success(
                            profile = ProfileData(
                                userId = user.id,
                                displayName = user.displayName,
                                email = user.email,
                                avatarUrl = user.avatarUrl,
                            ),
                        )
                    } else {
                        ProfileUiState.Error(message = "User $userId not found")
                    }
                }
                .catch { throwable ->
                    emit(
                        ProfileUiState.Error(
                            message = throwable.message ?: "Failed to load profile",
                        ),
                    )
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ProfileUiState.Loading,
        )

    fun retry() {
        _retrySignal.update { it + 1 }
    }
}
