package com.kojo.boilerplate.core.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Cancellation-safe building blocks for structured concurrency.
 *
 * Structured concurrency gives three guarantees for free: a scope does not complete until
 * every child it started has completed, a failing child cancels its siblings (under
 * `coroutineScope`) or does not (under `supervisorScope`), and cancellation propagates
 * down the whole tree. `coroutineScope` and `supervisorScope` are the builders that
 * provide them and are meant to be used inline, so nothing here wraps them — see
 * `docs/structured-concurrency.md` for which one to reach for.
 *
 * What does need encoding is the part the language does not enforce: a cancelled coroutine
 * cannot suspend, so any cleanup that itself suspends — closing a session, releasing a
 * lock, flushing a write — silently does not run when the caller is cancelled, and every
 * `catch (Throwable)` on the way out is a chance to mistake cancellation for a failure and
 * swallow it.
 */

/**
 * True when this throwable is coroutine cancellation rather than a real failure.
 *
 * Cancellation travels as an exception but is not an error: it is the parent telling this
 * coroutine to stop. Reporting it as a failure produces the "operation failed" toast that
 * appears right as the user navigates away from a screen.
 */
val Throwable.isCancellation: Boolean
    get() = this is CancellationException

/**
 * Rethrows this throwable when it is cancellation, and returns normally otherwise.
 *
 * Call it first in any `catch` that is broad enough to catch a [CancellationException],
 * so that the coroutine machinery still sees the cancellation it is waiting for:
 *
 * ```
 * try {
 *     upload(file)
 * } catch (failure: Throwable) {
 *     failure.rethrowIfCancellation()
 *     logger.warn(failure) { "upload failed" }
 * }
 * ```
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

/**
 * Runs [block] and then always runs [cleanup], including when the caller is cancelled.
 *
 * [cleanup] receives the throwable that ended [block] — a [CancellationException] when the
 * caller was cancelled, the failure when [block] threw, or `null` when it returned
 * normally — so a single cleanup can distinguish "commit" from "roll back".
 *
 * A plain `try`/`finally` is not enough when the cleanup suspends. Once a coroutine is
 * cancelled its job is in the cancelling state and every suspension point in it throws
 * immediately, so a suspending cleanup in a `finally` block is skipped exactly in the case
 * it exists for — it is not that the `finally` does not run, it is that the cleanup inside
 * it dies at its first `await`, `delay` or DAO call. [cleanup] therefore runs in
 * [NonCancellable], which is the one legitimate use of that context: a short, bounded step
 * that must finish, not a way to opt a long operation out of cancellation.
 *
 * Failures follow [AutoCloseable.use]: if [block] failed, a failure from [cleanup] is
 * attached to it with [Throwable.addSuppressed] rather than replacing it, because the first
 * failure is the one that explains the others.
 */
@Suppress("TooGenericExceptionCaught")
// Catching Throwable is the point: this function has to observe *every* way out of
// [block] — including cancellation — to be able to hand it to [cleanup]. Narrowing either
// catch would let some exits skip the cleanup. Nothing is swallowed: whatever [block]
// ended with is rethrown unchanged by the `getOrThrow()` on the last line.
suspend fun <T> withCleanup(
    cleanup: suspend (failure: Throwable?) -> Unit,
    block: suspend () -> T,
): T {
    val outcome = runCatching { block() }
    val failure = outcome.exceptionOrNull()

    withContext(NonCancellable) {
        try {
            cleanup(failure)
        } catch (cleanupFailure: Throwable) {
            if (failure == null) throw cleanupFailure
            failure.addSuppressed(cleanupFailure)
        }
    }

    return outcome.getOrThrow()
}

/**
 * The [AutoCloseable.use] shape for a resource whose release suspends.
 *
 * `use` requires an [AutoCloseable] and calls `close()` on the caller's thread, so it
 * cannot express "release this by making a suspending call" — and writing that by hand as
 * `try`/`finally` hits the cancellation gap [withCleanup] documents. [release] runs exactly
 * once, whether [block] returns, throws, or the caller is cancelled.
 *
 * ```
 * scanner.useCancellationSafe(release = { it.shutdown() }) { session ->
 *     session.scan()
 * }
 * ```
 */
suspend fun <T, R> T.useCancellationSafe(
    release: suspend (T) -> Unit,
    block: suspend (T) -> R,
): R {
    val resource = this
    return withCleanup(
        cleanup = { release(resource) },
        block = { block(resource) },
    )
}
