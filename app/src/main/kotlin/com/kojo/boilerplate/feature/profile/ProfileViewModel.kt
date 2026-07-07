package com.kojo.boilerplate.feature.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.kojo.boilerplate.navigation.AppDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route: AppDestination.Profile = savedStateHandle.toRoute()
    private val userId: String = route.userId

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    fun retry() {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            _uiState.value = ProfileUiState.Loading
            runCatching {
                // Replace with real repository call in Phase 2 (Repository pattern)
                delay(300)
                ProfileData(
                    userId = userId,
                    displayName = "User $userId",
                    email = "user$userId@example.com",
                    avatarUrl = null,
                )
            }.fold(
                onSuccess = { profile ->
                    _uiState.value = ProfileUiState.Success(profile = profile)
                },
                onFailure = { throwable ->
                    _uiState.value = ProfileUiState.Error(
                        message = throwable.message ?: "Failed to load profile",
                    )
                },
            )
        }
    }
}
