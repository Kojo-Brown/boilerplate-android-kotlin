package com.kojo.boilerplate.feature.signin

import com.kojo.boilerplate.core.auth.GoogleUser

/**
 * What the sign-in screen looks like right now — and nothing else.
 *
 * There is no `Error` case. A failed sign-in leaves the screen back at [Idle] and reports the
 * reason as a [GoogleSignInEvent.SignInFailed]; see that type for why the distinction matters.
 */
sealed class GoogleSignInUiState {
    data object Idle : GoogleSignInUiState()
    data object Loading : GoogleSignInUiState()
    data class Success(val user: GoogleUser) : GoogleSignInUiState()
}
