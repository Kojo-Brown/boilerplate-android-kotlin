package com.kojo.boilerplate.feature.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.navigation.AppDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val route: AppDestination.Profile = savedStateHandle.toRoute()
    private val userId: String = route.userId

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        observeUser()
    }

    fun retry() {
        observeUser()
    }

    private fun observeUser() {
        userRepository.getUser(userId)
            .onStart { _uiState.value = ProfileUiState.Loading }
            .onEach { user ->
                _uiState.value = if (user != null) {
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
                _uiState.value = ProfileUiState.Error(
                    message = throwable.message ?: "Failed to load profile",
                )
            }
            .launchIn(viewModelScope)
    }
}
