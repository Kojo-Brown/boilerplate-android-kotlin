package com.kojo.boilerplate.core.coroutines

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Fan-out: running one suspending operation over many inputs at the same time.
 *
 * The sequential form of this — `ids.map { repository.sync(it) }` — costs the sum of the
 * round trips when it could have cost the slowest one. Rewriting it as a fan-out is a
 * three-line change and introduces three decisions that the sequential version never had
 * to make. Each one has a wrong answer that still compiles:
 *
 * 1. **How many at once?** `map { async { … } }` starts *every* item immediately. For a
 *    list that came from a database that is an unbounded number of in-flight requests
 *    sized by data rather than by design — the client's own thundering herd, aimed at one
 *    server. Both functions here take a [concurrency] bound and hold it with a
 *    [Semaphore].
 * 2. **What does one failure mean?** Either the result is only useful whole, or the parts
 *    stand alone. That is the entire difference between [mapConcurrently] and
 *    [mapConcurrentlyCatching], and it is a product decision, not a style choice.
 * 3. **What about cancellation?** It is not a failure and must never be reported as one —
 *    see `docs/structured-concurrency.md`. Both functions rethrow it.
 *
 * These build on `coroutineScope` rather than wrapping it: see the note on the scope
 * builders in `StructuredConcurrency.kt` for why those are used inline.
 */

/**
 * How many operations run at once when a caller does not say.
 *
 * Eight is chosen against the transport, not the data: OkHttp's dispatcher allows five
 * concurrent requests per host by default, so a bound near that keeps the pipe full
 * without queueing a long tail of coroutines that are only waiting for a connection.
 * A caller that knows its own workload — CPU-bound mapping, a chattier host, a batch
 * that must be gentle — should pass its own.
 */
const val DEFAULT_FAN_OUT_CONCURRENCY = 8

/**
 * One input that did not make it, kept next to the reason it did not.
 *
 * The input is the point. A bare list of throwables answers "how badly did that go?" but
 * not "which ones do I retry?", and reconstructing the association by position only works
 * until the first time the successes and failures are filtered apart.
 */
data class FanOutFailure<T>(
    val input: T,
    val cause: Throwable,
)

/**
 * The outcome of a fan-out that was allowed to partially fail.
 *
 * [successes] is in input order; [failures] likewise. The two together account for every
 * input exactly once, so `successes.size + failures.size` is the number of items attempted.
 */
data class FanOutResult<T, R>(
    val successes: List<R>,
    val failures: List<FanOutFailure<T>>,
) {

    /** How many inputs were attempted. */
    val attempted: Int get() = successes.size + failures.size

    /**
     * True when nothing failed — including when nothing was attempted.
     *
     * An empty fan-out succeeds vacuously, which is the answer that composes: a caller
     * refreshing an empty list has nothing to report to the user. A caller for which
     * "nothing to do" is itself notable should test [attempted].
     */
    val isCompleteSuccess: Boolean get() = failures.isEmpty()

    /** True when at least one input was attempted and every one of them failed. */
    val isCompleteFailure: Boolean get() = successes.isEmpty() && failures.isNotEmpty()

    /** True when some inputs succeeded and some failed — the case worth a message. */
    val isPartial: Boolean get() = successes.isNotEmpty() && failures.isNotEmpty()
}

/**
 * Applies [transform] to every element concurrently and returns the results in input order,
 * failing as a whole if any single element fails.
 *
 * This is the all-or-nothing half: use it when a partial result is not a result. `awaitAll`
 * rethrows the first failure as soon as it happens rather than waiting out the others, and
 * because the children live in a `coroutineScope` that failure also cancels the siblings —
 * so the work still in flight for an answer nobody can use is abandoned instead of finished.
 * The call itself does not return until those cancellations have completed, which is the
 * guarantee that makes it safe to release whatever the transforms were using.
 *
 * @param concurrency the maximum number of [transform] invocations in flight at once.
 * @throws IllegalArgumentException if [concurrency] is less than 1.
 */
suspend fun <T, R> Iterable<T>.mapConcurrently(
    concurrency: Int = DEFAULT_FAN_OUT_CONCURRENCY,
    transform: suspend (T) -> R,
): List<R> {
    val inputs = requireValidFanOut(concurrency)
    if (inputs.isEmpty()) return emptyList()

    val gate = Semaphore(concurrency)
    return coroutineScope {
        inputs
            .map { input -> async { gate.withPermit { transform(input) } } }
            .awaitAll()
    }
}

/**
 * Applies [transform] to every element concurrently and reports per-element outcomes,
 * letting the elements that worked through even when others failed.
 *
 * Use it when the parts stand alone: refreshing a list where eight rows of ten arriving is
 * a better screen than an error, or a batch upload where "three sent, one to retry" is the
 * outcome you intend to show.
 *
 * **`supervisorScope` is not what makes this work, and reaching for it instead is the
 * mistake this function exists to prevent.** `supervisorScope` stops a failing child from
 * cancelling its siblings, but `awaitAll` still rethrows the first failure it is handed, so
 * the fan-out fails at the await regardless — and any `Deferred` left un-awaited after that
 * throw takes its exception to the grave with it. Isolating the failure *inside* each child,
 * as a value, is what actually delivers a partial result: no child ever fails, so there is
 * nothing for a scope policy to arbitrate, and every failure is attached to the input that
 * produced it.
 *
 * Cancellation is the exception that is deliberately not turned into a value. `runCatching`
 * would happily record a [kotlinx.coroutines.CancellationException] as element 3's failure
 * and hand the caller a tidy report of a fan-out that was told to stop; [rethrowIfCancellation]
 * puts it back on its way, so a cancelled caller is cancelled rather than partially served.
 *
 * @param concurrency the maximum number of [transform] invocations in flight at once.
 * @throws IllegalArgumentException if [concurrency] is less than 1.
 */
suspend fun <T, R> Iterable<T>.mapConcurrentlyCatching(
    concurrency: Int = DEFAULT_FAN_OUT_CONCURRENCY,
    transform: suspend (T) -> R,
): FanOutResult<T, R> {
    val inputs = requireValidFanOut(concurrency)
    if (inputs.isEmpty()) return FanOutResult(successes = emptyList(), failures = emptyList())

    val gate = Semaphore(concurrency)
    val outcomes = coroutineScope {
        inputs
            .map { input ->
                async {
                    input to runCatching { gate.withPermit { transform(input) } }
                        .onFailure { it.rethrowIfCancellation() }
                }
            }
            .awaitAll()
    }

    val successes = ArrayList<R>(outcomes.size)
    val failures = ArrayList<FanOutFailure<T>>()
    outcomes.forEach { (input, outcome) ->
        outcome.fold(
            onSuccess = { successes += it },
            onFailure = { failures += FanOutFailure(input = input, cause = it) },
        )
    }
    return FanOutResult(successes = successes, failures = failures)
}

/**
 * Validates [concurrency] and snapshots this [Iterable] into a list.
 *
 * The snapshot is not incidental. `Iterable` promises nothing about how many times or from
 * which thread it can be iterated — a `Sequence` is single-pass, and a view over a mutable
 * collection can change underneath a second traversal. Taking one list up front means the
 * inputs the failures are attributed to are exactly the inputs that were fanned out over.
 */
private fun <T> Iterable<T>.requireValidFanOut(concurrency: Int): List<T> {
    require(concurrency >= 1) { "concurrency must be at least 1, was $concurrency" }
    return toList()
}
