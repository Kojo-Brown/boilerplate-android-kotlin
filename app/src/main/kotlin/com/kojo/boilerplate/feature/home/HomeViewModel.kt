package com.kojo.boilerplate.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kojo.boilerplate.core.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        observeUsers()
    }

    fun retry() {
        observeUsers()
    }

    private fun observeUsers() {
        userRepository.getUsers()
            .onStart { _uiState.value = HomeUiState.Loading }
            .map { users ->
                users.map { user ->
                    HomeItem(
                        id = user.id,
                        title = user.displayName,
                        description = user.email,
                    )
                }
            }
            .onEach { items ->
                _uiState.value = HomeUiState.Success(
                    items = items,
                    greeting = "Boilerplate Android",
                )
            }
            .catch { throwable ->
                _uiState.value = HomeUiState.Error(
                    message = throwable.message ?: "Failed to load users",
                )
            }
            .launchIn(viewModelScope)
    }
}
