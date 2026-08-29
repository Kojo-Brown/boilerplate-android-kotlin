package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.common.safeCall
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.coroutines.IoDispatcher
import com.kojo.boilerplate.core.coroutines.mapConcurrentlyCatching
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.toDomain
import com.kojo.boilerplate.core.database.entity.toEntity
import com.kojo.boilerplate.core.database.entity.toVersioned
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.repository.UserRepository
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictPolicy
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolution
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.MergeConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import com.kojo.boilerplate.core.domain.sync.conflict.VersionedUser
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.toVersioned
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
 * ## Every write goes through the conflict resolver
 *
 * Both directions. A fetched row meets [conflictResolver] because a response is not
 * automatically newer than what is stored — see `docs/conflict-resolution.md` — and a local
 * edit meets it because [saveUser] has to record *which* fields it changed for the resolver to
 * have anything to merge later. Neither path upserts directly; the read-modify-write is
 * [UserDao.upsertResolving], inside a transaction, because concurrent syncs of one id are
 * ordinary here.
 *
 * @param ioDispatcher deliberately has no default. A default would let a caller construct
 *   this without one, and every test that did would silently run against the real IO pool.
 * @param conflictResolver the app's single policy, selected in `ConflictResolverModule`. A
 *   constructor parameter and not a lookup, for the same reason [ioDispatcher] is: it is what
 *   lets a test state which policy it is asserting about.
 */
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val userApi: UserApi,
    private val conflictResolver: ConflictResolver,
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

    /**
     * A local edit: the fields are written as given, and the row records *which* of them this
     * client changed.
     *
     * ## Why this is a read-modify-write and not an upsert
     *
     * Because "which fields changed" is only answerable against the row that is already there.
     * An upsert of the new values loses that, and the next sync then has nothing to merge —
     * under [ConflictPolicy.MERGE] the edit would be indistinguishable from stale data and the
     * server would silently win, which is the bug this whole item is about.
     *
     * The version is carried over untouched. Versions are the server's to assign, and a local
     * edit is not a server change: incrementing it here would make this row claim to have seen
     * a server version that does not exist, and the fetch that eventually carries the real one
     * would be discarded as stale.
     *
     * ## Why the dirty set unions rather than replaces
     *
     * Once a field is marked pending, this row no longer holds the server's value for it, so a
     * later edit cannot tell whether the field has been changed *back* to what the server
     * said. Unioning keeps it marked, which is the conservative answer: at worst the field
     * wins a conflict against a value it already equals, and [MergeConflictResolver] clears
     * the mark the next time the server's row agrees with it.
     *
     * A row that does not exist yet is created with every field pending. It has never been
     * synced, so none of its values came from the server, and the first fetch to arrive is a
     * genuine conflict with all three rather than a fast-forward over them.
     */
    override suspend fun saveUser(user: User) {
        withContext(ioDispatcher) {
            userDao.upsertResolving(user.id) { local ->
                val changed = if (local == null) {
                    UserField.entries.toSet()
                } else {
                    local.locallyChanged + UserField.changedBetween(local.toDomain(), user)
                }
                VersionedUser(
                    user = user,
                    version = local?.version ?: 0L,
                    locallyChanged = changed,
                ).toEntity()
            }
        }
    }

    override suspend fun syncCurrentUser(): Result<User> = withContext(ioDispatcher) {
        safeCall { cache(userApi.getCurrentUser().toVersioned()) }
    }

    override suspend fun syncUser(id: String): Result<User> = withContext(ioDispatcher) {
        safeCall { cache(userApi.getUser(id).toVersioned()) }
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
                cache(userApi.getUser(id).toVersioned())
            }
        }

    /**
     * Commits a freshly fetched [remote] to the local cache, as far as the conflict policy
     * allows, and returns what the cache holds afterwards.
     *
     * ## The return value is the stored row, not the response
     *
     * `syncUser` returns `Result<User>` and a caller reads it as "the user, now". When the
     * resolver declines the write — a stale response, or a local edit that beat it — the
     * response is precisely what the store does *not* hold, and handing it back would let a
     * caller render a value that Room will never emit. Returning what
     * [UserDao.upsertResolving] committed keeps the two answers the same.
     *
     * Under [ConflictPolicy.MERGE] that value can be a third thing: neither the response nor
     * the previous row, but the merge of them.
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
    private suspend fun cache(remote: VersionedUser): User {
        val stored = withContext(NonCancellable) {
            userDao.upsertResolving(remote.user.id) { local ->
                when (val resolution = conflictResolver.resolve(local?.toVersioned(), remote)) {
                    ConflictResolution.KeepLocal -> null
                    is ConflictResolution.Write -> resolution.record.toEntity()
                }
            }
        }

        // `upsertResolving` returns null only when there was no row and the resolver wrote
        // nothing, which no resolver does: `resolve` with a null local always writes. The
        // elvis is here because the DAO's signature permits it, not because it is reachable.
        return stored?.toDomain() ?: remote.user
    }
}
