package com.kojo.boilerplate.core.testing

import com.kojo.boilerplate.core.coroutines.FanOutFailure
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.repository.UserRepository
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

    /**
     * Every user handed to [saveUser], in call order.
     *
     * A caller that decides *not* to write — because the edit changed nothing, or because it was
     * rejected — cannot be held to that by reading the rows back afterwards: the row it would
     * have written is the row that is already there, so the store looks identical either way.
     * This is what makes "and writes nothing" an assertion rather than a hope.
     */
    var savedUsers: List<User> = emptyList()
        private set

    override suspend fun saveUser(user: User) {
        savedUsers = savedUsers + user
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

    /**
     * Ids listed here fail the next [syncUsers]; every other id succeeds with whatever the
     * fake is currently holding, or with a placeholder if it holds nothing for that id.
     */
    var syncUsersFailing: Set<String> = emptySet()

    /** Ids passed to the most recent [syncUsers] call, in the order they were given. */
    var syncUsersRequested: List<String> = emptyList()
        private set

    override suspend fun syncUsers(ids: List<String>): FanOutResult<String, User> {
        syncUsersRequested = ids
        val successes = mutableListOf<User>()
        val failures = mutableListOf<FanOutFailure<String>>()
        ids.distinct().forEach { id ->
            if (id in syncUsersFailing) {
                failures += FanOutFailure(id, IllegalStateException("sync failed for $id"))
            } else {
                successes += _users.value.firstOrNull { it.id == id }
                    ?: User(id = id, displayName = "User $id", email = "user$id@example.com")
            }
        }
        return FanOutResult(successes = successes, failures = failures)
    }

    fun setUsers(users: List<User>) {
        _users.value = users
    }
}
