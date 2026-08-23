package com.kojo.boilerplate.core.common

import com.kojo.boilerplate.core.coroutines.rethrowIfCancellation

/**
 * Executes [block] inside a try/catch and wraps the outcome in [Result].
 * Catches all [Throwable]s so callers never need naked try/catch for I/O.
 *
 * Cancellation is the one thing it does not wrap. `runCatching` catches
 * [kotlinx.coroutines.CancellationException] like any other throwable, which breaks
 * structured concurrency: the coroutine being cancelled would return a `Result.failure`
 * instead of completing as cancelled, its parent would never see the cancellation it is
 * waiting for, and the caller would render an error state for a screen the user has
 * already left. [rethrowIfCancellation] puts it back on its way.
 */
suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
    runCatching { block() }
        .onFailure { it.rethrowIfCancellation() }

/**
 * Returns the encapsulated value if this result is a success, otherwise returns [default].
 */
fun <T> Result<T>.getOrDefault(default: T): T = getOrElse { default }

/**
 * Transforms the encapsulated value with [transform] if this is a success.
 * Exceptions thrown by [transform] are caught and wrapped as [Result.failure].
 */
inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> =
    fold(onSuccess = { transform(it) }, onFailure = { Result.failure(it) })
