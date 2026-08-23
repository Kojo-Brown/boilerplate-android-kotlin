package com.kojo.boilerplate.core.coroutines

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Retries before the failure is allowed through. Four attempts total, then the caller sees it. */
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

/**
 * The schedule a retry follows: how many times, how long between, and how much of that is
 * randomised.
 *
 * This exists as a type because there are now two things that retry on this schedule, and they
 * retry different shapes of work. [retryWithBackoff] resubscribes a cold [kotlinx.coroutines.flow.Flow]
 * whose upstream threw; `RetryingUserRepository` re-invokes a suspend function whose failure
 * arrived as a [Result] rather than as an exception. Neither can be written in terms of the
 * other — one is a `retryWhen` predicate, the other a loop — but the *sequence of delays* is
 * the same decision in both, and a second hand-written copy of `initialDelay * factor^attempt`
 * with its own cap and its own jitter is how two retries in one app end up quietly disagreeing
 * about what a backoff is.
 *
 * The parameters are validated on construction rather than at the first delay, so a policy that
 * cannot produce a sensible schedule fails where it is written instead of on the first outage —
 * which, for a retry, may be weeks later and on someone else's device.
 *
 * [Random] is deliberately *not* a property. It is a source of values rather than part of the
 * schedule, and holding one would give this data class an `equals` that depends on the identity
 * of a generator. It is passed to [delayBefore] instead, which is also what lets a test pin an
 * exact delay sequence rather than assert a range.
 */
data class BackoffPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val initialDelay: Duration = DEFAULT_INITIAL_DELAY,
    val maxDelay: Duration = DEFAULT_MAX_DELAY,
    val backoffFactor: Double = DEFAULT_BACKOFF_FACTOR,
    val jitterRatio: Double = DEFAULT_JITTER_RATIO,
) {

    init {
        require(maxRetries >= 0) { "maxRetries must not be negative, was $maxRetries" }
        require(initialDelay >= Duration.ZERO) {
            "initialDelay must not be negative, was $initialDelay"
        }
        // An infinite cap would make the jitter subtraction INFINITE - INFINITE, and a retry
        // that never fires is a hang, not a backoff.
        require(maxDelay.isFinite()) { "maxDelay must be finite, was $maxDelay" }
        require(backoffFactor >= MIN_BACKOFF_FACTOR) {
            "backoffFactor must be at least $MIN_BACKOFF_FACTOR, was $backoffFactor"
        }
        require(jitterRatio in NO_JITTER..FULL_JITTER) {
            "jitterRatio must be in $NO_JITTER..$FULL_JITTER, was $jitterRatio"
        }
    }

    /**
     * How long to wait before the retry that follows [attempt] failures.
     *
     * The delay is `initialDelay * backoffFactor^attempt`, capped at [maxDelay], with up to
     * [jitterRatio] of it subtracted at random — so the result is always in
     * `[d * (1 - jitterRatio), d]` and never exceeds [maxDelay].
     *
     * @param attempt how many attempts have already failed; `0` for the first retry.
     */
    fun delayBefore(attempt: Long, random: Random = Random.Default): Duration {
        // pow() overflows to Infinity for a large enough attempt; Duration.times(Double) maps
        // that to INFINITE rather than wrapping, so minOf still yields maxDelay.
        val exponential = initialDelay * backoffFactor.pow(attempt.toDouble())
        val capped = minOf(exponential, maxDelay)
        if (jitterRatio == NO_JITTER) return capped
        return capped - capped * jitterRatio * random.nextDouble()
    }

    companion object {

        /** The schedule everything in this app retries on unless it says otherwise. */
        val DEFAULT = BackoffPolicy()
    }
}
