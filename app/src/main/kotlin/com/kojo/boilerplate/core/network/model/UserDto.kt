package com.kojo.boilerplate.core.network.model

import com.kojo.boilerplate.core.data.model.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    @SerialName("id") val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("email") val email: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

fun UserDto.toDomain(): User = User(
    id = id,
    displayName = displayName,
    email = email,
    avatarUrl = avatarUrl,
)
