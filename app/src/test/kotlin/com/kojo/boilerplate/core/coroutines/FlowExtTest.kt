package com.kojo.boilerplate.core.coroutines

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowExtTest {

    @Test
    fun `asResult wraps each emission in Result success`() = runTest {
        val results = flowOf(1, 2, 3).asResult().toList()

        assertEquals(3, results.size)
        assertTrue(results.all { it.isSuccess })
        assertEquals(listOf(1, 2, 3), results.map { it.getOrThrow() })
    }

    @Test
    fun `asResult wraps terminal error in Result failure`() = runTest {
        val exception = RuntimeException("test error")
        val results = flow<Int> {
            emit(1)
            throw exception
        }.asResult().toList()

        assertEquals(2, results.size)
        assertTrue(results[0].isSuccess)
        assertEquals(1, results[0].getOrThrow())
        assertTrue(results[1].isFailure)
        assertEquals(exception, results[1].exceptionOrNull())
    }

    @Test
    fun `asResult on empty flow produces empty list`() = runTest {
        val results = flowOf<String>().asResult().toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `mapSuccess transforms successful values`() = runTest {
        val results = flowOf(1, 2, 3)
            .asResult()
            .mapSuccess { it * 10 }
            .toList()

        assertEquals(listOf(10, 20, 30), results.map { it.getOrThrow() })
    }

    @Test
    fun `mapSuccess preserves failure results`() = runTest {
        val exception = RuntimeException("fail")
        val results = flow<Int> { emit(1); throw exception }
            .asResult()
            .mapSuccess { it * 10 }
            .toList()

        assertEquals(2, results.size)
        assertEquals(10, results[0].getOrThrow())
        assertTrue(results[1].isFailure)
    }

    @Test
    fun `onSuccess invokes action for successful emissions only`() = runTest {
        val collected = mutableListOf<Int>()
        flow<Int> { emit(1); emit(2); throw RuntimeException("boom") }
            .asResult()
            .onSuccess { collected.add(it) }
            .toList()

        assertEquals(listOf(1, 2), collected)
    }

    @Test
    fun `onFailure invokes action for failure emissions only`() = runTest {
        val errors = mutableListOf<Throwable>()
        flow<Int> { emit(1); throw RuntimeException("boom") }
            .asResult()
            .onFailure { errors.add(it) }
            .toList()

        assertEquals(1, errors.size)
        assertFalse(errors[0].message.isNullOrEmpty())
    }
}
