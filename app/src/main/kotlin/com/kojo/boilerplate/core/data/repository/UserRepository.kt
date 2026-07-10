package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.data.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun getUsers(): Flow<List<User>>
    fun getUser(id: String): Flow<User?>
    suspend fun saveUser(user: User)

    /**
     * Fetches the current authenticated user from the network, caches locally,
     * and returns the outcome wrapped in [Result].
     */
    suspend fun syncCurrentUser(): Result<User>

    /**
     * Fetches a user by [id] from the network, caches locally,
     * and returns the outcome wrapped in [Result].
     */
    suspend fun syncUser(id: String): Result<User>
}
