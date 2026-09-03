package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.coroutines.IoDispatcher
import com.kojo.boilerplate.core.coroutines.mapConcurrentlyCatching
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.database.entity.toDomain
import com.kojo.boilerplate.core.domain.model.PushOutcome
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.repository.PendingUserChangeRepository
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.toVersioned
import com.kojo.boilerplate.core.network.model.updateUserRequest
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Sends the pending edits in the `users` table, each under the key its row is holding.
 *
 * The key is read from the row and never generated here, and that is the single most important
 * line in this class. Generating one at send time compiles, reads perfectly well, and removes
 * the entire guarantee: every attempt would introduce itself to the server as a new change, so
 * a retry after a lost response would apply the edit a second time — which is precisely the
 * situation the header exists to prevent, arrived at by way of the code that was supposed to
 * prevent it. `UserRepositoryImpl.saveUser` is the only place a key is minted, because a local
 * edit is the only thing that creates a mutation to name.
 *
 * @param ioDispatcher deliberately has no default, for the reason `UserRepositoryImpl` gives.
 * @param writer the shared commit path; [ResolvingUserWriter.commitAcknowledging] is what makes
 *   an acknowledgement clear the right edit and not a newer one.
 */
class PendingUserChangeRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val userApi: UserApi,
    private val writer: ResolvingUserWriter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PendingUserChangeRepository {

    /**
     * ## Why the rows are read once, up front
     *
     * The queue is a query, so it could be re-read between sends — and re-reading it would let a
     * `saveUser` that landed mid-push add a row to the batch it is already in the middle of. The
     * one-shot read gives each run a fixed set of rows to answer for, which is what makes
     * [PushOutcome]'s counts add up to something a caller can act on. An edit made during a push
     * goes out on the next one, moments later in the case of the worker.
     *
     * ## Why the fan-out and not a loop
     *
     * The same reason `syncUsers` fans out: these are independent requests against independent
     * rows, and a serial loop makes the run as long as the sum of them. `mapConcurrentlyCatching`
     * also keeps each failure attached to the row that produced it and rethrows cancellation
     * rather than counting it — see `docs/fan-out.md`. Concurrency is safe *because* of the
     * keys: two overlapping attempts at one row would carry one name, so even the pathological
     * interleaving costs a duplicate request rather than a duplicate change.
     *
     * A row that somehow has pending fields and no key is skipped rather than sent under a fresh
     * one, and counted as a failure so it is visible in the outcome. It cannot happen —
     * `MIGRATION_3_4` backfills the rows that predate the column and `toEntity` is the only
     * writer of either half — and if it ever did, sending is the one response that could do
     * real damage: an unnamed change is a change that gets applied again on every retry.
     */
    override suspend fun pushPendingChanges(): PushOutcome = withContext(ioDispatcher) {
        val pending = userDao.findPendingChanges()
        if (pending.isEmpty()) return@withContext PushOutcome.NOTHING_TO_PUSH

        val (sendable, unnamed) = pending.partition { it.pendingChangeKey != null }
        val sent = sendable.mapConcurrentlyCatching { row -> push(row) }

        PushOutcome(
            pushed = sent.successes.size,
            failed = sent.failures.size + unnamed.size,
        )
    }

    /**
     * One row: send what is pending, then commit whatever the server says the row now is.
     *
     * The body carries only the fields in [UserEntity.locallyChanged], so a value this client is
     * holding for a field it did not edit — which may be the server's own, and may be stale —
     * cannot travel back and overwrite a change someone else made to the same row.
     */
    private suspend fun push(row: UserEntity): User {
        val key = requireNotNull(row.pendingChangeKey) { "unnamed rows are filtered out above" }
        val response = userApi.updateUser(
            id = row.id,
            idempotencyKey = key,
            update = updateUserRequest(user = row.toDomain(), changed = row.locallyChanged),
        )

        return writer.commitAcknowledging(sentKey = key, remote = response.toVersioned())
    }
}
