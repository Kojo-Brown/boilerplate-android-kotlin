package com.kojo.boilerplate.navigation

import kotlinx.serialization.Serializable

sealed interface AppDestination {
    @Serializable
    data object SignIn : AppDestination

    @Serializable
    data object Home : AppDestination

    @Serializable
    data class Profile(val userId: String) : AppDestination
}
