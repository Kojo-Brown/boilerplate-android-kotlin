package com.kojo.boilerplate.core.auth

data class GoogleUser(
    val id: String,
    val email: String,
    val displayName: String,
    val profilePictureUrl: String?,
    val idToken: String,
)
