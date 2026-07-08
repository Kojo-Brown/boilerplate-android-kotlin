package com.kojo.boilerplate.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kojo.boilerplate.core.coroutines.IoDispatcher
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userRepository: UserRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _retrySignal = MutableStateFlow(0)

    val uiState: StateFlow<HomeUiState> = _retrySignal
        .flatMapLatest {
            combine<List<User>, String, HomeUiState>(
                userRepository.getUsers(),
                _searchQuery,
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
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = HomeUiState.Loading,
        )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun retry() {
        _retrySignal.update { it + 1 }
    }
}
