package com.kojo.boilerplate.core.auth

import androidx.compose.runtime.Immutable

/**
 * Annotated because it is reachable from `GoogleSignInUiState.Success` and therefore is a
 * Compose input, even though it lives in the data layer rather than in a feature package.
 * Stability follows the value, not the package it is declared in.
 */
@Immutable
data class GoogleUser(
    val id: String,
    val email: String,
    val displayName: String,
    val profilePictureUrl: String?,
    val idToken: String,
)
