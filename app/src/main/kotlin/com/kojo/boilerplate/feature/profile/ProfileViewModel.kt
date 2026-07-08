package com.kojo.boilerplate.feature.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kojo.boilerplate.core.coroutines.IoDispatcher
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.navigation.AppDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val route: AppDestination.Profile = savedStateHandle.toRoute()
    private val userId: String = route.userId

    private val _retrySignal = MutableStateFlow(0)

    val uiState: StateFlow<ProfileUiState> = _retrySignal
        .flatMapLatest {
            userRepository.getUser(userId)
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
        .flowOn(ioDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ProfileUiState.Loading,
        )

    fun retry() {
        _retrySignal.update { it + 1 }
    }
}
