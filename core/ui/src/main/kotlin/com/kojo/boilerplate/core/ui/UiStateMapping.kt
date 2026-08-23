package com.kojo.boilerplate.core.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/*
 * These two lived next to `safeCall` in `core/common/Result.kt` while the app was one module.
 * They are here now because they are the only part of that file that knows what a screen is:
 * `Result` is a `kotlin.Result` and `UiState` is a `:core:ui` type, so keeping the bridge in
 * `:core:common` would have made every module that wants `safeCall` — the data layer included —
 * depend on the presentation module. The dependency goes the other way round, and the compiler
 * enforces it now rather than a convention.
 */

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
