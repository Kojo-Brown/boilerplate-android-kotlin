package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeUserRepository(initialUsers: List<User> = emptyList()) : UserRepository {

    private val _users = MutableStateFlow(initialUsers)

    var shouldThrowOnGetUsers: Throwable? = null
    var shouldThrowOnGetUser: Throwable? = null

    var syncCurrentUserResult: Result<User> = Result.failure(NotImplementedError("not configured"))
    var syncUserResult: Result<User> = Result.failure(NotImplementedError("not configured"))

    override fun getUsers(): Flow<List<User>> = _users.map { list ->
        shouldThrowOnGetUsers?.let { throw it }
        list
    }

    override fun getUser(id: String): Flow<User?> = _users.map { list ->
        shouldThrowOnGetUser?.let { throw it }
        list.firstOrNull { it.id == id }
    }

    override suspend fun saveUser(user: User) {
        _users.update { current ->
            val index = current.indexOfFirst { it.id == user.id }
            if (index >= 0) {
                current.toMutableList().also { it[index] = user }
            } else {
                current + user
            }
        }
    }

    override suspend fun syncCurrentUser(): Result<User> = syncCurrentUserResult

    override suspend fun syncUser(id: String): Result<User> = syncUserResult

    fun setUsers(users: List<User>) {
        _users.value = users
    }
}
