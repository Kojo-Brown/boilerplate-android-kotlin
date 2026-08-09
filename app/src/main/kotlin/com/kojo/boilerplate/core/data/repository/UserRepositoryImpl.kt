package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.common.safeCall
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.coroutines.IoDispatcher
import com.kojo.boilerplate.core.coroutines.mapConcurrentlyCatching
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.toDomain
import com.kojo.boilerplate.core.database.entity.toEntity
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.toDomain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The repository owns the thread its work runs on; no caller has to know.
 *
 * Room and Retrofit both dispatch their *own* suspending work, which is what makes the
 * threading here easy to get wrong: with neither `flowOn` nor `withContext` present, the
 * library calls are still safe and only the code around them is misplaced. What is left on
 * the caller's thread is the mapping — [toDomain] over every row of a query result,
 * [toEntity] on every write — and `Flow` operators run in the *collector's* context, so
 * `getUsers()` mapped the whole list on whatever collected it. For a `ViewModel` collecting
 * into `viewModelScope` that is the main thread.
 *
 * That contract used to live in the callers instead: `HomeViewModel` and both profile view
 * models each appended `.flowOn(ioDispatcher)` and injected a dispatcher to do it. Three
 * copies of one decision, no way to test any of them, and a fourth caller would have had
 * nothing to fail if it forgot. Owning it here means a caller cannot get it wrong, and
 * [ioDispatcher] being a constructor parameter is what makes "does it actually confine?" a
 * question a test can answer — see `UserRepositoryImplDispatcherTest`.
 *
 * @param ioDispatcher deliberately has no default. A default would let a caller construct
 *   this without one, and every test that did would silently run against the real IO pool.
 */
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val userApi: UserApi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : UserRepository {

    override fun getUsers(): Flow<List<User>> =
        userDao.observeAll()
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override fun getUser(id: String): Flow<User?> =
        userDao.observeById(id)
            .map { it?.toDomain() }
            .flowOn(ioDispatcher)

    override suspend fun saveUser(user: User) {
        withContext(ioDispatcher) { userDao.upsert(user.toEntity()) }
    }

    override suspend fun syncCurrentUser(): Result<User> = withContext(ioDispatcher) {
        safeCall { cache(userApi.getCurrentUser().toDomain()) }
    }

    override suspend fun syncUser(id: String): Result<User> = withContext(ioDispatcher) {
        safeCall { cache(userApi.getUser(id).toDomain()) }
    }

    /**
     * `distinct()` before the fan-out, because the same id twice is the same request twice.
     * Nothing upstream guarantees the caller deduplicated — a list built from two sources,
     * or a screen that shows a user in both a "recent" and an "all" section, produces
     * duplicates without anyone deciding to — and each one would cost a round trip and a
     * redundant write to arrive at the byte-identical row. It also keeps [FanOutResult]
     * honest: with duplicates in, "8 refreshed" could mean eight users or five.
     *
     * `mapConcurrentlyCatching` and not a `supervisorScope` here: this is the shape that
     * keeps each failure attached to the id that caused it, which is what a caller needs to
     * retry only the ones that did not land. See `docs/fan-out.md`.
     *
     * The whole fan-out runs inside one `withContext(ioDispatcher)` rather than one per
     * child. `withContext` is a suspension and a possible thread hand-off each time it is
     * entered; the children inherit this context, so paying for it once at the top is the
     * same confinement for a fraction of the dispatches.
     */
    override suspend fun syncUsers(ids: List<String>): FanOutResult<String, User> =
        withContext(ioDispatcher) {
            ids.distinct().mapConcurrentlyCatching { id ->
                cache(userApi.getUser(id).toDomain())
            }
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
     *
     * [NonCancellable] is a [kotlinx.coroutines.Job], and nothing else. Reading
     * `withContext(NonCancellable)` as "and this part runs somewhere safe" is the trap: it
     * replaces the job and inherits everything else, so the dispatcher it runs on is
     * whichever one is already installed. That is [ioDispatcher] because of the
     * `withContext` at the call sites above, and was the caller's thread before them.
     */
    private suspend fun cache(user: User): User {
        withContext(NonCancellable) { userDao.upsert(user.toEntity()) }
        return user
    }
}
