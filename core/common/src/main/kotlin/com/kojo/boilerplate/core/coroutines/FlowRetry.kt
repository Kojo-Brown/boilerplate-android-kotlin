package com.kojo.boilerplate.core.coroutines

import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import retrofit2.HttpException

/**
 * Automatic recovery for cold flows whose upstream can fail transiently.
 *
 * A `Flow` is over the moment it throws: there is no "resume from the error", the collector's
 * `catch` runs and the subscription is gone. For a screen backed by `stateIn`, that means one
 * dropped connection leaves the UI holding an error state until the user finds the retry
 * button — which is the wrong default for a failure that would have cleared on its own a
 * second later.
 *
 * [retryWhen] is the operator for it, and everything interesting is in the predicate.
 * See `docs/flow-operators.md` for the decision table.
 *
 * The schedule itself — how many attempts, how long between them, how much jitter — lives in
 * [BackoffPolicy], which `RetryingUserRepository` retries suspend calls on as well. The
 * defaults below are that policy's, named rather than repeated so the two cannot drift.
 */

private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500
private const val HTTP_STATUS_CEILING = 600

/**
 * Resubscribes to this flow with exponential backoff when it fails transiently.
 *
 * Three things have to be true for a retry, and each one is a way this can be got wrong:
 *
 * 1. **The failure is not cancellation.** Cancellation travels as an exception but means the
 *    collector is gone, so retrying it re-runs work for a screen the user has left and, worse,
 *    swallows the cancellation the parent is waiting for. [kotlinx.coroutines.flow.catch]
 *    already rethrows a [CancellationException] that belongs to the *current* job, but a
 *    `CancellationException` raised by upstream code for its own reasons is an ordinary
 *    throwable to that check, so the guard is made explicit here.
 * 2. **The failure is transient.** Retrying a 404 or a serialization error just delays the
 *    same error by [maxRetries] backoff steps while the user watches a spinner. [isTransient]
 *    defaults to [isTransientFailure]; pass a narrower one where the caller knows better.
 * 3. **There are attempts left.** `attempt` is the number of retries already made, so the
 *    upstream is subscribed at most `maxRetries + 1` times.
 *
 * The delay is [BackoffPolicy.delayBefore] for the attempt, so it is
 * `initialDelay * backoffFactor^attempt`, capped at [maxDelay], with up to [jitterRatio] of it
 * subtracted at random. [random] is a parameter so tests get a fixed sequence instead of an
 * assertion on a range.
 *
 * Retrying resubscribes, which for a hot-backed source such as a Room query means everything
 * it has already emitted is emitted again. Follow this with
 * [kotlinx.coroutines.flow.distinctUntilChanged] when the collector should not see that.
 *
 * ```kotlin
 * userRepository.getUsers()
 *     .retryWithBackoff()
 *     .distinctUntilChanged()
 * ```
 */
@Suppress("LongParameterList") // Every parameter has a default; this is one call plus knobs.
fun <T> Flow<T>.retryWithBackoff(
    maxRetries: Int = BackoffPolicy.DEFAULT.maxRetries,
    initialDelay: Duration = BackoffPolicy.DEFAULT.initialDelay,
    maxDelay: Duration = BackoffPolicy.DEFAULT.maxDelay,
    backoffFactor: Double = BackoffPolicy.DEFAULT.backoffFactor,
    jitterRatio: Double = BackoffPolicy.DEFAULT.jitterRatio,
    random: Random = Random.Default,
    isTransient: (Throwable) -> Boolean = ::isTransientFailure,
): Flow<T> {
    // Constructed eagerly, outside the returned flow, so an unusable schedule fails at the
    // call site rather than on the first upstream failure.
    val policy = BackoffPolicy(
        maxRetries = maxRetries,
        initialDelay = initialDelay,
        maxDelay = maxDelay,
        backoffFactor = backoffFactor,
        jitterRatio = jitterRatio,
    )

    return retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        if (attempt >= policy.maxRetries) return@retryWhen false
        if (!isTransient(cause)) return@retryWhen false

        delay(policy.delayBefore(attempt, random))
        true
    }
}

/**
 * The default answer to "is this worth trying again?".
 *
 * [IOException] covers the whole transport layer — no route to host, connection reset, read
 * timeout — which is exactly the class of failure that clears by itself. A [HttpException]
 * means the server answered, so the status decides: 408 and 429 are the server asking to be
 * asked again, 5xx is a fault on its side. Every other 4xx is a statement about the request,
 * and sending it again unchanged gets the same answer.
 *
 * Deliberately absent: [kotlinx.serialization.SerializationException] and its kin. A response
 * that does not parse is a contract mismatch, and three more round trips will not fix it.
 */
fun isTransientFailure(cause: Throwable): Boolean = when (cause) {
    is IOException -> true
    is HttpException -> cause.code().isRetryableStatus()
    else -> false
}

private fun Int.isRetryableStatus(): Boolean =
    this == HTTP_REQUEST_TIMEOUT ||
        this == HTTP_TOO_MANY_REQUESTS ||
        this in HTTP_SERVER_ERROR_FLOOR until HTTP_STATUS_CEILING
