package com.kojo.boilerplate.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadItems()
    }

    fun retry() {
        loadItems()
    }

    private fun loadItems() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            runCatching {
                // Replace with real repository call in Phase 2 (Repository pattern)
                delay(300)
                listOf(
                    HomeItem(
                        id = "1",
                        title = "Getting Started",
                        description = "Learn the MVVM + UiState sealed class pattern",
                    ),
                    HomeItem(
                        id = "2",
                        title = "Architecture",
                        description = "Repository pattern with Kotlin Coroutines + Flow",
                    ),
                    HomeItem(
                        id = "3",
                        title = "Navigation",
                        description = "Typed routes with Kotlin Serialization",
                    ),
                    HomeItem(
                        id = "4",
                        title = "Persistence",
                        description = "Room 2.7 database with DAO + Entity",
                    ),
                )
            }.fold(
                onSuccess = { items ->
                    _uiState.value = HomeUiState.Success(
                        items = items,
                        greeting = "Boilerplate Android",
                    )
                },
                onFailure = { throwable ->
                    _uiState.value = HomeUiState.Error(
                        message = throwable.message ?: "Failed to load items",
                    )
                },
            )
        }
    }
}
