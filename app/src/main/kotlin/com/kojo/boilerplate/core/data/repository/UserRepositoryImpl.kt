package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject

class UserRepositoryImpl @Inject constructor() : UserRepository {

    private val _users = MutableStateFlow(
        listOf(
            User(
                id = "1",
                displayName = "Alice Johnson",
                email = "alice@example.com",
                avatarUrl = null,
            ),
            User(
                id = "2",
                displayName = "Bob Smith",
                email = "bob@example.com",
                avatarUrl = null,
            ),
            User(
                id = "3",
                displayName = "Carol White",
                email = "carol@example.com",
                avatarUrl = null,
            ),
        ),
    )

    override fun getUsers(): Flow<List<User>> = _users

    override fun getUser(id: String): Flow<User?> =
        _users.map { list -> list.firstOrNull { it.id == id } }

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
}
