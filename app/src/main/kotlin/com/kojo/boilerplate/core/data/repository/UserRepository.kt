package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.data.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun getUsers(): Flow<List<User>>
    fun getUser(id: String): Flow<User?>
    suspend fun saveUser(user: User)
}
