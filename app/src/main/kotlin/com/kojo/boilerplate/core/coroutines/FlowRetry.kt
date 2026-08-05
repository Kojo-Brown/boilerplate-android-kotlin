package com.kojo.boilerplate.core.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import retrofit2.HttpException
import java.io.IOException
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
 */

/** Retries before the failure is allowed through. Four attempts total, then the UI sees it. */
private const val DEFAULT_MAX_RETRIES = 3

/** Short enough that a blip is invisible, long enough that a dead server is not hammered. */
private val DEFAULT_INITIAL_DELAY = 500.milliseconds

/** With three retries the sequence is 0.5s, 1s, 2s, so the cap only binds if it is raised. */
private val DEFAULT_MAX_DELAY = 8.seconds

private const val DEFAULT_BACKOFF_FACTOR = 2.0

/** A factor below this shrinks the delay instead of backing off. */
private const val MIN_BACKOFF_FACTOR = 1.0

private const val NO_JITTER = 0.0
private const val FULL_JITTER = 1.0

/**
 * Fraction of each delay given up to randomness. Every client that lost the same server
 * reconnects on the same schedule otherwise, and the retry storm finishes what the outage
 * started. A quarter is enough to smear the herd without making the backoff unpredictable.
 */
private const val DEFAULT_JITTER_RATIO = 0.25

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
 * The delay is `initialDelay * backoffFactor^attempt`, capped at [maxDelay], with up to
 * [jitterRatio] of it subtracted at random — so a delay is always in
 * `[d * (1 - jitterRatio), d]` and never exceeds [maxDelay]. [random] is a parameter so tests
 * get a fixed sequence instead of an assertion on a range.
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
    maxRetries: Int = DEFAULT_MAX_RETRIES,
    initialDelay: Duration = DEFAULT_INITIAL_DELAY,
    maxDelay: Duration = DEFAULT_MAX_DELAY,
    backoffFactor: Double = DEFAULT_BACKOFF_FACTOR,
    jitterRatio: Double = DEFAULT_JITTER_RATIO,
    random: Random = Random.Default,
    isTransient: (Throwable) -> Boolean = ::isTransientFailure,
): Flow<T> {
    require(maxRetries >= 0) { "maxRetries must not be negative, was $maxRetries" }
    require(initialDelay >= Duration.ZERO) { "initialDelay must not be negative, was $initialDelay" }
    // An infinite cap would make the jitter subtraction INFINITE - INFINITE, and a retry that
    // never fires is a hang, not a backoff.
    require(maxDelay.isFinite()) { "maxDelay must be finite, was $maxDelay" }
    require(backoffFactor >= MIN_BACKOFF_FACTOR) {
        "backoffFactor must be at least $MIN_BACKOFF_FACTOR, was $backoffFactor"
    }
    require(jitterRatio in NO_JITTER..FULL_JITTER) {
        "jitterRatio must be in $NO_JITTER..$FULL_JITTER, was $jitterRatio"
    }

    return retryWhen { cause, attempt ->
        if (cause is CancellationException) return@retryWhen false
        if (attempt >= maxRetries) return@retryWhen false
        if (!isTransient(cause)) return@retryWhen false

        delay(
            backoffDelay(
                attempt = attempt,
                initialDelay = initialDelay,
                maxDelay = maxDelay,
                backoffFactor = backoffFactor,
                jitterRatio = jitterRatio,
                random = random,
            ),
        )
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

@Suppress("LongParameterList") // Private arithmetic helper; the alternative is a parameter object.
private fun backoffDelay(
    attempt: Long,
    initialDelay: Duration,
    maxDelay: Duration,
    backoffFactor: Double,
    jitterRatio: Double,
    random: Random,
): Duration {
    // pow() overflows to Infinity for a large enough attempt; Duration.times(Double) maps that
    // to INFINITE rather than wrapping, so minOf still yields maxDelay.
    val exponential = initialDelay * backoffFactor.pow(attempt.toDouble())
    val capped = minOf(exponential, maxDelay)
    if (jitterRatio == NO_JITTER) return capped
    return capped - capped * jitterRatio * random.nextDouble()
}
