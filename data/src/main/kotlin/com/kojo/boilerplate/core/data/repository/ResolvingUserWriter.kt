package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.database.entity.keyForResolvedPendingSet
import com.kojo.boilerplate.core.database.entity.toDomain
import com.kojo.boilerplate.core.database.entity.toEntity
import com.kojo.boilerplate.core.database.entity.toVersioned
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictPolicy
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolution
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.VersionedUser
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Commits a row that came back from the server, as far as the conflict policy allows.
 *
 * A class rather than a private method on `UserRepositoryImpl`, because there are now two
 * callers and the rule they share is subtle. The read path (`UserRepositoryImpl`) commits
 * fetched rows; the write path (`PendingUserChangeRepositoryImpl`) commits the row a `PATCH`
 * returned. Two copies of "resolve, then decide what happens to the pending set and the key
 * naming it" would be two places for the answer to drift, and the drift would be invisible —
 * each copy would go on passing its own tests.
 *
 * Everything here runs inside the caller's `withContext(ioDispatcher)`; this class installs no
 * dispatcher of its own, only the [NonCancellable] job described on [commit].
 *
 * @param conflictResolver the app's single policy, selected in `ConflictResolverModule`.
 */
class ResolvingUserWriter @Inject constructor(
    private val userDao: UserDao,
    private val conflictResolver: ConflictResolver,
) {

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
     * The write does not: by the time it runs the response is already in hand, and letting a
     * cancellation drop it wastes the round trip and leaves the cache holding data the app has
     * just proved to be stale. The upsert is a bounded, idempotent local write, so this is
     * [NonCancellable]'s intended use — finishing a short commit that has already started — and
     * not a way to make a sync as a whole uncancellable.
     *
     * [NonCancellable] is a [kotlinx.coroutines.Job], and nothing else. Reading
     * `withContext(NonCancellable)` as "and this part runs somewhere safe" is the trap: it
     * replaces the job and inherits everything else, so the dispatcher it runs on is whichever
     * one is already installed. That is the caller's IO dispatcher, because every call site
     * enters from inside one.
     */
    suspend fun commit(remote: VersionedUser): User =
        write(remote) { local -> resolved(local, remote) }

    /**
     * Commits the response to a push, clearing the pending edit that [sentKey] named.
     *
     * ## The check that makes a mid-flight edit safe
     *
     * The row is cleared only while it is still holding the key that was sent. A request is not
     * instantaneous, and `saveUser` can land while one is in the air — at which point the row
     * carries a *newer* mutation under a new key, and the acknowledgement in hand says nothing
     * about it. Clearing on the strength of "the push succeeded" would discard an edit the user
     * made seconds earlier, with no error anywhere and nothing to notice afterwards. The key is
     * what tells the two cases apart, and comparing it is the whole of the check.
     *
     * When the keys disagree the response is still worth having — it is the server's current
     * row, carrying the version the update assigned it — so it takes the ordinary resolution
     * path instead of being dropped. Under [ConflictPolicy.MERGE] that lays the newer pending
     * fields back over it and leaves them, under their own key, for the next push.
     *
     * When they agree, the response is written as it stands rather than being resolved. It is
     * not a competing copy of the row: it *is* the row, as the server rewrote it at this
     * client's request, so there is nothing for a policy to arbitrate. Sending it through the
     * resolver would ask "is this newer than what is stored?" about a value that is newer by
     * construction, and a server that does not maintain versions — which answers every write
     * with version `0` — would have its own acknowledgement declined as stale.
     *
     * @param sentKey the [UserEntity.pendingChangeKey] that travelled in the `Idempotency-Key`
     *   header of the request this is the response to.
     * @param remote the row the server returned, which is the row after the update was applied.
     */
    suspend fun commitAcknowledging(sentKey: String, remote: VersionedUser): User =
        write(remote) { local ->
            if (local?.pendingChangeKey == sentKey) {
                remote.toEntity(pendingChangeKey = null)
            } else {
                resolved(local, remote)
            }
        }

    /**
     * What the policy says should be stored, or `null` to leave the row alone.
     *
     * The key travels with the pending set it names — see
     * [keyForResolvedPendingSet][com.kojo.boilerplate.core.database.entity.keyForResolvedPendingSet].
     */
    private fun resolved(local: UserEntity?, remote: VersionedUser): UserEntity? =
        when (val resolution = conflictResolver.resolve(local?.toVersioned(), remote)) {
            ConflictResolution.KeepLocal -> null
            is ConflictResolution.Write -> resolution.record.toEntity(
                pendingChangeKey = resolution.record.keyForResolvedPendingSet(local?.pendingChangeKey),
            )
        }

    /**
     * Runs one resolution inside [UserDao.upsertResolving]'s transaction and reports the row
     * that transaction left behind.
     *
     * [resolve] is not a `suspend` function and must not become one: it is invoked on the
     * transaction's thread, and a suspension there holds a database transaction open across
     * arbitrary work. Every `ConflictResolver` is a pure function for the same reason.
     */
    private suspend fun write(
        remote: VersionedUser,
        resolve: (local: UserEntity?) -> UserEntity?,
    ): User {
        val stored = withContext(NonCancellable) {
            userDao.upsertResolving(remote.user.id, resolve)
        }

        // `upsertResolving` returns null only when there was no row and the resolution wrote
        // nothing, which cannot happen: `resolve` with a null local always writes, and so does
        // the acknowledgement branch above. The elvis is here because the DAO's signature
        // permits it, not because it is reachable.
        return stored?.toDomain() ?: remote.user
    }
}
