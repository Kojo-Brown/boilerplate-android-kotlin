package com.kojo.boilerplate.feature.profile

import androidx.compose.runtime.Immutable

@Immutable
data class ProfileData(
    val userId: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String?,
)

@Immutable
sealed class ProfileUiState {
    data object Loading : ProfileUiState()

    @Immutable
    data class Success(val profile: ProfileData) : ProfileUiState()

    @Immutable
    data class Error(val message: String) : ProfileUiState()
}
