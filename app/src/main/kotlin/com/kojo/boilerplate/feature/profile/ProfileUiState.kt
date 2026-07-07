package com.kojo.boilerplate.feature.profile

data class ProfileData(
    val userId: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String?,
)

sealed class ProfileUiState {
    data object Loading : ProfileUiState()

    data class Success(val profile: ProfileData) : ProfileUiState()

    data class Error(val message: String) : ProfileUiState()
}
