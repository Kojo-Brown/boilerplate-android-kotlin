package com.kojo.boilerplate.core.data.repository.decorator

import com.kojo.boilerplate.core.coroutines.BackoffPolicy
import com.kojo.boilerplate.core.coroutines.FanOutFailure
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.coroutines.isTransientFailure
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

/**
 * Retries the network-backed operations when they fail for a reason that might not repeat.
 *
 * ## The trap this exists to document
 *
 * `syncCurrentUser`, `syncUser` and `syncUsers` do not throw. `safeCall` turns a failed round
 * trip into `Result.failure`, and `mapConcurrentlyCatching` turns a failed fan-out child into a
 * [FanOutFailure] — both of them *values*. So the obvious retry wrapper,
 *
 * ```kotlin
 * repeat(maxRetries) { runCatching { delegate.syncUser(id) }.onSuccess { return it } }
 * ```
 *
 * compiles, reads correctly, and never retries anything: `runCatching` sees a returned
 * `Result.failure` as a perfectly successful call. A repository whose failures are values needs
 * a retry that inspects the value, which is what [retryingResult] does. Every method here that
 * retries does so by looking at what came back, not by catching.
 *
 * ## What is retried, and what is not
 *
 * Only the three `sync*` methods, and within them only failures [isTransient] agrees are worth
 * repeating — [isTransientFailure] by default, so a 404 or a deserialization error comes
 * straight back rather than costing the user three backoff steps of spinner to arrive at the
 * same answer.
 *
 * `getUsers` and `getUser` pass through untouched, and that is deliberate rather than an
 * omission. They are Room queries: their failures are schema and disk problems, which
 * [isTransientFailure] refuses by design, so a retry here would be dead code that looked like
 * cover. The retry those flows *do* want is a resubscription, which is a different operator
 * ([com.kojo.boilerplate.core.coroutines.retryWithBackoff]) applied where the subscription
 * lives — `ObserveUserProfileUseCase` — and stacking both would multiply the attempts of any
 * caller that used the two together.
 *
 * `saveUser` passes through for the same reason: a local upsert that failed will fail again.
 *
 * ## Where it sits
 *
 * Innermost, closest to the transport. A retry is a property of the request, so everything
 * above sees one logical operation rather than the attempts it took — one cache entry, one
 * telemetry event, one duration that includes the backoff. See `docs/decorator.md`.
 */
class RetryingUserRepository(
    override val delegate: UserRepository,
    private val policy: BackoffPolicy = BackoffPolicy.DEFAULT,
    private val random: Random = Random.Default,
    private val isTransient: (Throwable) -> Boolean = ::isTransientFailure,
) : UserRepositoryDecorator {

    override fun getUsers(): Flow<List<User>> = delegate.getUsers()

    override fun getUser(id: String): Flow<User?> = delegate.getUser(id)

    override suspend fun saveUser(user: User) = delegate.saveUser(user)

    override suspend fun syncCurrentUser(): Result<User> =
        retryingResult { delegate.syncCurrentUser() }

    override suspend fun syncUser(id: String): Result<User> =
        retryingResult { delegate.syncUser(id) }

    /**
     * Retries the shortfall, not the fan-out.
     *
     * Re-running the whole call would re-request every id that already arrived — for a list
     * refresh where one user in twenty 503s, that is nineteen redundant round trips and
     * nineteen redundant cache writes to recover one row. [FanOutFailure] carries the input
     * that produced it precisely so the next attempt can be exactly those inputs, and this is
     * the caller that was always meant to use it.
     *
     * A failure [isTransient] rejects is set aside on the attempt that produced it and never
     * retried, so a permanently missing user does not hold the retry budget open for the ids
     * that could still land.
     *
     * Both lists are put back in request order before returning. `FanOutResult` documents its
     * successes as being in input order, and retried users would otherwise arrive at the end,
     * ordered by which attempt happened to recover them — a caller rendering that list would
     * see rows reshuffle purely because of a transient failure.
     */
    override suspend fun syncUsers(ids: List<String>): FanOutResult<String, User> {
        val requested = ids.distinct()
        var result = delegate.syncUsers(requested)

        val successes = result.successes.toMutableList()
        val permanent = mutableListOf<FanOutFailure<String>>()
        var pending = result.failures.partitionPending(permanent)

        var attempt = 0L
        while (pending.isNotEmpty() && attempt < policy.maxRetries) {
            delay(policy.delayBefore(attempt, random))
            result = delegate.syncUsers(pending.map { it.input })
            successes += result.successes
            pending = result.failures.partitionPending(permanent)
            attempt++
        }

        val requestOrder = requested.withIndex().associate { (index, id) -> id to index }
        return FanOutResult(
            successes = successes.sortedBy { requestOrder[it.id] ?: requested.size },
            failures = (permanent + pending).sortedBy { requestOrder[it.input] ?: requested.size },
        )
    }

    /**
     * Splits this attempt's failures: the transient ones are returned to be retried, the rest
     * are added to [permanent] where they stay.
     */
    private fun List<FanOutFailure<String>>.partitionPending(
        permanent: MutableList<FanOutFailure<String>>,
    ): List<FanOutFailure<String>> {
        val (transient, terminal) = partition { isTransient(it.cause) }
        permanent += terminal
        return transient
    }

    /**
     * Re-invokes [block] while it keeps coming back with a transient failure *as a value*.
     *
     * Cancellation needs no handling here and gets none: nothing is caught, `delay` is
     * cancellable, and `safeCall` underneath rethrows a `CancellationException` rather than
     * folding it into the `Result` — so a cancelled call cannot present itself as a failure
     * worth retrying, and this loop cannot keep a screen the user has left doing work.
     */
    private suspend fun <T> retryingResult(block: suspend () -> Result<T>): Result<T> {
        var attempt = 0L
        while (true) {
            val result = block()
            val cause = result.exceptionOrNull() ?: return result
            if (attempt >= policy.maxRetries || !isTransient(cause)) return result

            delay(policy.delayBefore(attempt, random))
            attempt++
        }
    }
}
