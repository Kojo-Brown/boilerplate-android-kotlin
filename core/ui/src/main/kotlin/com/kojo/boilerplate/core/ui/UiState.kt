package com.kojo.boilerplate.core.ui

/**
 * Generic sealed class for representing async UI states.
 * Each screen defines its own concrete sealed class that follows this shape,
 * allowing per-screen Success data types while keeping Loading/Error uniform.
 *
 * Example per-screen usage:
 *   sealed class HomeUiState {
 *       data object Loading : HomeUiState()
 *       data class Success(val items: List<Item>) : HomeUiState()
 *       data class Error(val message: String) : HomeUiState()
 *   }
 */
sealed class UiState<out T> {
    data object Loading : UiState<Nothing>()
    data class Success<out T>(val data: T) : UiState<T>()
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>()
}

val <T> UiState<T>.isLoading: Boolean get() = this is UiState.Loading
val <T> UiState<T>.isSuccess: Boolean get() = this is UiState.Success
val <T> UiState<T>.isError: Boolean get() = this is UiState.Error

inline fun <T> UiState<T>.onLoading(action: () -> Unit): UiState<T> {
    if (this is UiState.Loading) action()
    return this
}

inline fun <T, R> UiState<T>.onSuccess(action: (T) -> R): UiState<T> {
    if (this is UiState.Success) action(data)
    return this
}

inline fun <T> UiState<T>.onError(action: (String, Throwable?) -> Unit): UiState<T> {
    if (this is UiState.Error) action(message, cause)
    return this
}
