package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.coroutines.FanOutResult
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

    /**
     * Fetches every user in [ids] concurrently, caches each one that arrives, and reports
     * the successes and the failures side by side.
     *
     * Deliberately *not* `Result<List<User>>`. The API has no bulk endpoint — `users/{id}`
     * is the only way to read a user — so a refresh of a visible list is N independent
     * requests, and one of them 500ing says nothing about the other N-1. Collapsing that
     * into a single failure would throw away the users that did arrive and replace a
     * mostly-current screen with an error; collapsing it into a single success would hide
     * that part of the screen is stale. [FanOutResult] is the shape that can express both.
     *
     * Users that were fetched are cached whether or not their siblings failed: a response
     * already in hand is not worth discarding because an unrelated request did not come
     * back.
     */
    suspend fun syncUsers(ids: List<String>): FanOutResult<String, User>
}
