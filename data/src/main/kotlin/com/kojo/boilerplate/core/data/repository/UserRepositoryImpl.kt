package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.common.safeCall
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.coroutines.IoDispatcher
import com.kojo.boilerplate.core.coroutines.mapConcurrentlyCatching
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.database.entity.toDomain
import com.kojo.boilerplate.core.database.entity.toEntity
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.repository.UserRepository
import com.kojo.boilerplate.core.domain.sync.IdempotencyKeyGenerator
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictPolicy
import com.kojo.boilerplate.core.domain.sync.conflict.MergeConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import com.kojo.boilerplate.core.domain.sync.conflict.VersionedUser
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.toVersioned
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
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
 * Both directions. A fetched row meets the resolver because a response is not automatically
 * newer than what is stored — see `docs/conflict-resolution.md` — and a local edit meets it
 * because [saveUser] has to record *which* fields it changed for the resolver to have anything
 * to merge later. Neither path upserts directly; the read-modify-write is
 * [UserDao.upsertResolving], inside a transaction, because concurrent syncs of one id are
 * ordinary here. The fetched half of that lives in [writer], which the push path shares.
 *
 * @param ioDispatcher deliberately has no default. A default would let a caller construct
 *   this without one, and every test that did would silently run against the real IO pool.
 * @param writer the commit path for anything the server sends back, carrying the app's single
 *   conflict policy. A constructor parameter and not a lookup, for the same reason
 *   [ioDispatcher] is: it is what lets a test state which policy it is asserting about.
 * @param idempotencyKeys names the mutation each local edit creates. See [saveUser].
 */
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val userApi: UserApi,
    private val writer: ResolvingUserWriter,
    private val idempotencyKeys: IdempotencyKeyGenerator,
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
     *
     * ## Why the edit is named, and when the name changes
     *
     * This is where a mutation is created, so this is where it is named: the row gets an
     * idempotency key, and every attempt to push it carries that key until the server
     * acknowledges it. The rule is one line and both halves of it are load-bearing —
     * **a new key when, and only when, the payload changes.**
     *
     * *When*, because the key promises the server that two requests carrying it ask for the
     * same thing. A second edit to a row whose first edit is still unsent asks for something
     * different; reusing the key would let a server that had already seen the first request
     * recognise the second as a duplicate and drop it, and the app would have shown the user a
     * change it then quietly abandoned.
     *
     * *Only when*, because a key that changes for any other reason is not a name. A save that
     * writes the values already stored — a screen re-submitting an unchanged form, a retry of
     * `saveUser` itself — produces the identical payload, so it keeps the identical key and
     * stays the same single mutation. Without that, a push in flight would be renamed
     * underneath itself and its acknowledgement would no longer match the row, costing a
     * second round trip to send a change the server already has.
     *
     * The payload is the pending fields and their local values, which is exactly what
     * `updateUserRequest` sends. Nothing else can change it: a fetch can only shrink the
     * pending set (`MergeConflictResolver` intersects, `LastWriteWinsConflictResolver` clears),
     * and it cannot alter the value of a field it left pending. That is what makes the key
     * derivable here, once, rather than recomputed at send time.
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
                ).toEntity(pendingChangeKey = keyFor(local, user, changed))
            }
        }
    }

    /**
     * The key the row should carry after this save: the one it already had when nothing about
     * the mutation changed, a fresh one when something did, and none at all when the save left
     * nothing pending.
     *
     * The middle case is the interesting one and it is deliberately conservative — it keeps the
     * old key only when the pending set is identical *and* every field in it holds the value
     * being written. Anything else mints, including a set that shrank, which cannot happen from
     * here but would be a new payload if it ever did.
     *
     * The `?: idempotencyKeys.newKey()` on the last line is not reachable through any sequence
     * of calls: a row with a non-empty pending set always has a key. It is there because the
     * column is nullable and a total expression is cheaper than an assumption — and minting is
     * the safe answer if the impossible happens, where reusing `null` would send a push with no
     * name on it.
     */
    private fun keyFor(local: UserEntity?, user: User, changed: Set<UserField>): String? = when {
        changed.isEmpty() -> null
        local == null -> idempotencyKeys.newKey()
        changed != local.locallyChanged -> idempotencyKeys.newKey()
        changed.any { it.differs(local.toDomain(), user) } -> idempotencyKeys.newKey()
        else -> local.pendingChangeKey ?: idempotencyKeys.newKey()
    }

    override suspend fun syncCurrentUser(): Result<User> = withContext(ioDispatcher) {
        safeCall { writer.commit(userApi.getCurrentUser().toVersioned()) }
    }

    override suspend fun syncUser(id: String): Result<User> = withContext(ioDispatcher) {
        safeCall { writer.commit(userApi.getUser(id).toVersioned()) }
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
                writer.commit(userApi.getUser(id).toVersioned())
            }
        }
}
