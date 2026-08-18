package com.kojo.boilerplate.core.data.repository.decorator

import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A user with the given id, filled in with obviously fake details. */
fun testUser(id: String): User = User(
    id = id,
    displayName = "User $id",
    email = "user$id@example.com",
)

/**
 * The delegate a decorator test wraps: every call is recorded, and every answer is scripted.
 *
 * Distinct from `FakeUserRepository`, which models a working repository and is what a *caller*
 * needs. What a decorator test needs is the opposite: it asserts on how many times the layer
 * underneath was called and with what, and it has to be able to make a call fail on the first
 * attempt and succeed on the second, or hang until the test decides otherwise. Answers are
 * suspend lambdas for exactly that — a queue of canned results cannot express "block here".
 */
class ScriptedUserRepository : UserRepository {

    val users = MutableStateFlow(emptyList<User>())

    var syncCurrentUserCalls: Int = 0
        private set

    /** Ids passed to [syncUser], one entry per call, in call order. */
    val syncUserCalls = mutableListOf<String>()

    /** Id lists passed to [syncUsers], one entry per call, in call order. */
    val syncUsersCalls = mutableListOf<List<String>>()

    val savedUsers = mutableListOf<User>()

    var syncCurrentUserHandler: suspend () -> Result<User> = { Result.success(testUser("me")) }

    var syncUserHandler: suspend (String) -> Result<User> = { Result.success(testUser(it)) }

    var syncUsersHandler: suspend (List<String>) -> FanOutResult<String, User> = { ids ->
        FanOutResult(successes = ids.map(::testUser), failures = emptyList())
    }

    override fun getUsers(): Flow<List<User>> = users

    override fun getUser(id: String): Flow<User?> = users.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun saveUser(user: User) {
        savedUsers += user
    }

    override suspend fun syncCurrentUser(): Result<User> {
        syncCurrentUserCalls++
        return syncCurrentUserHandler()
    }

    override suspend fun syncUser(id: String): Result<User> {
        syncUserCalls += id
        return syncUserHandler(id)
    }

    override suspend fun syncUsers(ids: List<String>): FanOutResult<String, User> {
        syncUsersCalls += ids
        return syncUsersHandler(ids)
    }
}
