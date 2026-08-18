package com.kojo.boilerplate.core.telemetry

import kotlin.time.Duration

/**
 * Where the data layer reports what it just did.
 *
 * This is the seam between *measuring* — which is `TelemetryUserRepository`'s job and is pinned
 * by tests — and *where the measurement goes*, which is a product decision that changes per
 * app: Logcat in this boilerplate, Firebase, Datadog or an in-house event pipeline in a real
 * one. It is the same split as [com.kojo.boilerplate.core.coroutines.CoroutineFailureReporter],
 * for the same reason: swapping the destination is one `@Binds`, and the decorator's tests do
 * not need an analytics SDK standing behind them.
 *
 * Implementations must not throw, and must not block. They are called on whatever coroutine
 * made the repository call — a `viewModelScope` for a user-initiated refresh — so a sink that
 * does I/O inline adds its latency to the operation it is describing. Buffer, and flush
 * somewhere else.
 *
 * The decorator deliberately does **not** wrap [record] in a try/catch. A sink that throws is a
 * bug in the sink, and hiding it would mean the app silently stops being observable at the
 * moment observability is most needed.
 */
fun interface RepositoryTelemetry {

    /** Records one completed repository operation. */
    fun record(event: RepositoryOperationEvent)
}

/**
 * One repository operation, after it finished.
 *
 * [duration] is measured across the whole call as the *caller* experienced it — including any
 * retries underneath, and near-zero when a cache above answered without a request. Which of
 * those it was is not recoverable from a single event, and that is a consequence of where the
 * telemetry decorator sits rather than an oversight; `docs/decorator.md` covers the stack order
 * and what a second, inner telemetry layer would buy.
 */
data class RepositoryOperationEvent(
    val operation: RepositoryOperation,
    val duration: Duration,
    val outcome: RepositoryOutcome,
)

/**
 * The operations that are measured.
 *
 * An enum rather than a `String`, because these become metric names: a typo in a string is a
 * new time series that nobody is looking at and no test can see, while a missing enum constant
 * does not compile. The set is the network-backed operations only — `getUsers`, `getUser` and
 * `saveUser` are local, and `docs/decorator.md` says why measuring a subscription's "duration"
 * is not the same question.
 */
enum class RepositoryOperation {
    SYNC_CURRENT_USER,
    SYNC_USER,
    SYNC_USERS,
}

/** How an operation ended. */
sealed interface RepositoryOutcome {

    /** Everything the operation attempted arrived. */
    data object Succeeded : RepositoryOutcome

    /** Nothing arrived, because [cause] stopped it. */
    data class Failed(val cause: Throwable) : RepositoryOutcome

    /**
     * A fan-out where some inputs landed and some did not.
     *
     * Kept apart from [Failed] because the two mean opposite things to whoever reads the
     * dashboard: a partial refresh left the screen usable and mostly current, and collapsing it
     * into "failed" turns one flaky user out of eight into an outage.
     */
    data class PartiallyFailed(val succeeded: Int, val failed: Int) : RepositoryOutcome

    /**
     * The caller went away before the operation finished.
     *
     * Not a failure, and the single most common way a naive metric lies: every screen the user
     * leaves mid-refresh reports an error, the error rate tracks navigation speed, and the
     * alert that fires means nothing. It is still worth recording — a rising cancellation rate
     * is real signal about how long operations take relative to how long users wait.
     */
    data object Cancelled : RepositoryOutcome
}
