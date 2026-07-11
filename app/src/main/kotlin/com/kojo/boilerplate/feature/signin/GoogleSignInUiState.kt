package com.kojo.boilerplate.feature.signin

import com.kojo.boilerplate.core.auth.GoogleUser

sealed class GoogleSignInUiState {
    data object Idle : GoogleSignInUiState()
    data object Loading : GoogleSignInUiState()
    data class Success(val user: GoogleUser) : GoogleSignInUiState()
    data class Error(val message: String) : GoogleSignInUiState()
}
