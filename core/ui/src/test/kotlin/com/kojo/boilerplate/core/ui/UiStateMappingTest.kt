package com.kojo.boilerplate.core.ui

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `Result` → [UiState] bridge, which moved here from `core/common/ResultTest.kt` with the
 * functions it covers when the app was split into modules.
 */
class UiStateMappingTest {

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
}
