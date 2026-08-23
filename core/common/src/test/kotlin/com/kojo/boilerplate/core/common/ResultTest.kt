package com.kojo.boilerplate.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun `safeCall rethrows cancellation instead of wrapping it`() = runTest {
        val cancellation = CancellationException("navigated away")

        val thrown = runCatching { safeCall<Int> { throw cancellation } }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun `safeCall completes as cancelled when its caller is cancelled`() = runTest {
        val started = CompletableDeferred<Unit>()
        var result: Result<Int>? = null

        val job = launch {
            result = safeCall {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()

        // Not a Result.failure: a cancelled caller has no one left to show an error to,
        // and swallowing the cancellation here would leave the parent waiting for a child
        // that reports success.
        assertNull(result)
        assertTrue(job.isCancelled)
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
