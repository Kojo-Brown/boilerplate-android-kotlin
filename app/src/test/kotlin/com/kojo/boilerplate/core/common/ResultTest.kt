package com.kojo.boilerplate.core.common

import com.kojo.boilerplate.core.ui.UiState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTest {

    // safeCall

    @Test
    fun `safeCall returns success when block completes normally`() = runTest {
        val result = safeCall { 42 }
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrThrow())
    }

    @Test
    fun `safeCall returns failure when block throws`() = runTest {
        val exception = RuntimeException("network error")
        val result = safeCall<Int> { throw exception }
        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    @Test
    fun `safeCall wraps any Throwable, not just Exception`() = runTest {
        val error = OutOfMemoryError("oom")
        val result = safeCall<Unit> { throw error }
        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }

    // toUiState

    @Test
    fun `toUiState converts success to UiState Success`() {
        val result = Result.success("hello")
        val state = result.toUiState()
        assertTrue(state is UiState.Success)
        assertEquals("hello", (state as UiState.Success).data)
    }

    @Test
    fun `toUiState converts failure to UiState Error with message`() {
        val exception = IllegalStateException("bad state")
        val result = Result.failure<String>(exception)
        val state = result.toUiState()
        assertTrue(state is UiState.Error)
        val error = state as UiState.Error
        assertEquals("bad state", error.message)
        assertEquals(exception, error.cause)
    }

    @Test
    fun `toUiState uses fallback message when exception has no message`() {
        val result = Result.failure<Int>(RuntimeException())
        val state = result.toUiState() as UiState.Error
        assertEquals("An unexpected error occurred", state.message)
    }

    // toUiStateFlow

    @Test
    fun `toUiStateFlow maps each Result to UiState`() = runTest {
        val flow = flowOf(
            Result.success(1),
            Result.success(2),
            Result.failure(RuntimeException("err")),
        )
        val states = flow.toUiStateFlow().toList()

        assertEquals(3, states.size)
        assertTrue(states[0] is UiState.Success)
        assertTrue(states[1] is UiState.Success)
        assertTrue(states[2] is UiState.Error)
        assertEquals(1, (states[0] as UiState.Success).data)
        assertEquals(2, (states[1] as UiState.Success).data)
        assertEquals("err", (states[2] as UiState.Error).message)
    }

    // getOrDefault

    @Test
    fun `getOrDefault returns value on success`() {
        val result = Result.success(99)
        assertEquals(99, result.getOrDefault(0))
    }

    @Test
    fun `getOrDefault returns default on failure`() {
        val result = Result.failure<Int>(RuntimeException())
        assertEquals(0, result.getOrDefault(0))
    }

    // flatMap

    @Test
    fun `flatMap chains successful Results`() {
        val result = Result.success(5).flatMap { Result.success(it * 2) }
        assertTrue(result.isSuccess)
        assertEquals(10, result.getOrThrow())
    }

    @Test
    fun `flatMap short-circuits on initial failure`() {
        val exception = RuntimeException("first")
        val result = Result.failure<Int>(exception).flatMap { Result.success(it * 2) }
        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }

    @Test
    fun `flatMap propagates failure from transform`() {
        val exception = RuntimeException("second")
        val result = Result.success(5).flatMap { Result.failure<Int>(exception) }
        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
    }
}
