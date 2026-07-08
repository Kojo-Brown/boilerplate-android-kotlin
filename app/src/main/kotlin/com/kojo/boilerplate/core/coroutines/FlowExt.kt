package com.kojo.boilerplate.core.coroutines

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

fun <T> Flow<T>.asResult(): Flow<Result<T>> =
    map<T, Result<T>> { Result.success(it) }
        .catch { emit(Result.failure(it)) }

inline fun <T, R> Flow<Result<T>>.mapSuccess(crossinline transform: (T) -> R): Flow<Result<R>> =
    map { result -> result.map { transform(it) } }

inline fun <T> Flow<Result<T>>.onSuccess(crossinline action: suspend (T) -> Unit): Flow<Result<T>> =
    map { result ->
        if (result.isSuccess) action(result.getOrThrow())
        result
    }

inline fun <T> Flow<Result<T>>.onFailure(
    crossinline action: suspend (Throwable) -> Unit,
): Flow<Result<T>> =
    map { result ->
        result.exceptionOrNull()?.let { action(it) }
        result
    }
