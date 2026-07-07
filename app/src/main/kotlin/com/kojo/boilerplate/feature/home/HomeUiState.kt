package com.kojo.boilerplate.feature.home

data class HomeItem(
    val id: String,
    val title: String,
    val description: String,
)

sealed class HomeUiState {
    data object Loading : HomeUiState()

    data class Success(
        val items: List<HomeItem>,
        val greeting: String,
    ) : HomeUiState()

    data class Error(val message: String) : HomeUiState()
}
