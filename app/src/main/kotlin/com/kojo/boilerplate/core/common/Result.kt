package com.kojo.boilerplate.core.common

import com.kojo.boilerplate.core.ui.UiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Executes [block] inside a try/catch and wraps the outcome in [Result].
 * Catches all [Throwable]s so callers never need naked try/catch for I/O.
 */
suspend fun <T> safeCall(block: suspend () -> T): Result<T> = runCatching { block() }

/**
 * Maps a [Result] to the equivalent [UiState]:
 * - [Result.success] → [UiState.Success]
 * - [Result.failure] → [UiState.Error] with the exception message
 */
fun <T> Result<T>.toUiState(): UiState<T> = fold(
    onSuccess = { UiState.Success(it) },
    onFailure = { UiState.Error(it.message ?: "An unexpected error occurred", it) },
)

/**
 * Transforms each [Result] emission in this [Flow] into a [UiState] emission.
 */
fun <T> Flow<Result<T>>.toUiStateFlow(): Flow<UiState<T>> = map { it.toUiState() }

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
