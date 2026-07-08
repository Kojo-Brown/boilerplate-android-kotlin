package com.kojo.boilerplate.core.data.model

data class User(
    val id: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String? = null,
)
