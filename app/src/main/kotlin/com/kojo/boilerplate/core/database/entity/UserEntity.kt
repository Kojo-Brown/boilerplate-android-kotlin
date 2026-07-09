package com.kojo.boilerplate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kojo.boilerplate.core.data.model.User

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String?,
)

fun UserEntity.toDomain(): User = User(
    id = id,
    displayName = displayName,
    email = email,
    avatarUrl = avatarUrl,
)

fun User.toEntity(): UserEntity = UserEntity(
    id = id,
    displayName = displayName,
    email = email,
    avatarUrl = avatarUrl,
)
