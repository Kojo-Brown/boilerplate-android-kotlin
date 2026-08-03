package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.common.safeCall
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.toDomain
import com.kojo.boilerplate.core.database.entity.toEntity
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.toDomain
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val userApi: UserApi,
) : UserRepository {

    override fun getUsers(): Flow<List<User>> =
        userDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun getUser(id: String): Flow<User?> =
        userDao.observeById(id).map { it?.toDomain() }

    override suspend fun saveUser(user: User) {
        userDao.upsert(user.toEntity())
    }

    override suspend fun syncCurrentUser(): Result<User> = safeCall {
        cache(userApi.getCurrentUser().toDomain())
    }

    override suspend fun syncUser(id: String): Result<User> = safeCall {
        cache(userApi.getUser(id).toDomain())
    }

    /**
     * Commits a freshly fetched [user] to the local cache and returns it.
     *
     * The request itself stays cancellable — leaving a screen mid-flight should abandon it.
     * The write does not: by the time it runs the response is already in hand, and letting
     * a cancellation drop it wastes the round trip and leaves the cache holding data the
     * app has just proved to be stale. The upsert is a bounded, idempotent local write, so
     * this is [NonCancellable]'s intended use — finishing a short commit that has already
     * started — and not a way to make `sync` as a whole uncancellable.
     */
    private suspend fun cache(user: User): User {
        withContext(NonCancellable) { userDao.upsert(user.toEntity()) }
        return user
    }
}
