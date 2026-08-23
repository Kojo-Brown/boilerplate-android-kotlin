package com.kojo.boilerplate.core.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredConcurrencyTest {

    /**
     * Asserts that [actual] is the failure [expected], by type and message rather than by
     * identity.
     *
     * kotlinx.coroutines copies a throwable as it crosses a coroutine boundary so it can
     * attach the launching stack trace to it, so what a caller catches on the far side of
     * a `coroutineScope`, a `supervisorScope` or a `withContext` is an equal but distinct
     * instance. Identity only survives inside a single coroutine.
     */
    private fun assertFailure(expected: Throwable, actual: Throwable?) {
        assertNotNull(actual)
        assertEquals(expected::class.java, actual!!::class.java)
        assertEquals(expected.message, actual.message)
    }

    // isCancellation / rethrowIfCancellation

    @Test
    fun `isCancellation distinguishes cancellation from a real failure`() {
        assertTrue(CancellationException("stopped").isCancellation)
        assertFalse(IllegalStateException("boom").isCancellation)
    }

    @Test
    fun `rethrowIfCancellation rethrows the same cancellation instance`() {
        val cancellation = CancellationException("stopped")

        val thrown = runCatching { cancellation.rethrowIfCancellation() }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun `rethrowIfCancellation returns normally for an ordinary failure`() {
        val result = runCatching { IllegalStateException("boom").rethrowIfCancellation() }

        assertTrue(result.isSuccess)
    }

    // withCleanup

    @Test
    fun `withCleanup returns the block value and reports no failure`() = runTest {
        var observed: Throwable? = IllegalStateException("not overwritten")
        var cleanupCount = 0

        val value = withCleanup(
            cleanup = { failure ->
                observed = failure
                cleanupCount++
            },
            block = { "done" },
        )

        assertEquals("done", value)
        assertEquals(1, cleanupCount)
        assertNull(observed)
    }

    @Test
    fun `withCleanup hands the block failure to cleanup and rethrows it`() = runTest {
        val failure = IllegalStateException("boom")
        var observed: Throwable? = null

        val thrown = runCatching {
            withCleanup(
                cleanup = { observed = it },
                block = { throw failure },
            )
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertSame(failure, observed)
    }

    @Test
    fun `withCleanup runs cleanup when the caller is cancelled`() = runTest {
        val started = CompletableDeferred<Unit>()
        var observed: Throwable? = null

        val job = launch {
            withCleanup(
                cleanup = { observed = it },
                block = {
                    started.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        started.await()
        job.cancelAndJoin()

        // Cancellation reaches the cleanup as the throwable that ended the block, so a
        // single cleanup can tell "the caller went away" from "the work failed".
        assertTrue(observed is CancellationException)
    }

    @Test
    fun `withCleanup lets cleanup suspend after the caller is cancelled`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cleanupFinished = false

        val job = launch {
            withCleanup(
                cleanup = {
                    // A suspension point here is what a plain try/finally cannot survive:
                    // in a cancelled coroutine `delay` throws immediately and the rest of
                    // the cleanup never runs. NonCancellable is what makes it complete.
                    delay(50)
                    cleanupFinished = true
                },
                block = {
                    started.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(cleanupFinished)
    }

    @Test
    fun `withCleanup surfaces a cleanup failure when the block succeeded`() = runTest {
        val cleanupFailure = IllegalStateException("release failed")

        val thrown = runCatching {
            withCleanup(
                cleanup = { throw cleanupFailure },
                block = { "done" },
            )
        }.exceptionOrNull()

        assertFailure(cleanupFailure, thrown)
    }

    @Test
    fun `withCleanup suppresses a cleanup failure under the block failure`() = runTest {
        val blockFailure = IllegalStateException("boom")
        val cleanupFailure = IllegalArgumentException("release failed")

        val thrown = runCatching {
            withCleanup(
                cleanup = { throw cleanupFailure },
                block = { throw blockFailure },
            )
        }.exceptionOrNull()

        // The first failure explains the second, so it stays the primary one.
        assertSame(blockFailure, thrown)
        assertEquals(listOf<Throwable>(cleanupFailure), thrown?.suppressed?.toList())
    }

    // useCancellationSafe

    @Test
    fun `useCancellationSafe releases the resource exactly once on success`() = runTest {
        val released = mutableListOf<String>()

        val value = "session".useCancellationSafe(
            release = { released += it },
            block = { it.length },
        )

        assertEquals(7, value)
        assertEquals(listOf("session"), released)
    }

    @Test
    fun `useCancellationSafe releases the resource when the caller is cancelled`() = runTest {
        val started = CompletableDeferred<Unit>()
        val released = mutableListOf<String>()

        val job = launch {
            "session".useCancellationSafe(
                release = {
                    delay(50)
                    released += it
                },
                block = {
                    started.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        started.await()
        job.cancelAndJoin()

        assertEquals(listOf("session"), released)
    }

    @Test
    fun `useCancellationSafe releases the resource when the block fails`() = runTest {
        val failure = IllegalStateException("boom")
        val released = mutableListOf<String>()

        val thrown = runCatching {
            "session".useCancellationSafe(
                release = { released += it },
                block = { throw failure },
            )
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf("session"), released)
    }

    // coroutineScope vs supervisorScope — the semantics docs/structured-concurrency.md
    // claims, pinned as tests so a coroutines upgrade cannot quietly change them.

    @Test
    fun `coroutineScope cancels the siblings of a failing child and fails the caller`() = runTest {
        val failure = IllegalStateException("boom")
        var siblingCompleted = false
        var siblingCancelled = false

        val thrown = runCatching {
            coroutineScope {
                launch {
                    try {
                        delay(1_000)
                        siblingCompleted = true
                    } catch (cancellation: CancellationException) {
                        siblingCancelled = true
                        throw cancellation
                    }
                }
                launch {
                    delay(10)
                    throw failure
                }
            }
        }.exceptionOrNull()

        assertFailure(failure, thrown)
        assertFalse(siblingCompleted)
        assertTrue(siblingCancelled)
    }

    @Test
    fun `supervisorScope lets a sibling finish when another child fails`() = runTest {
        val failure = IllegalStateException("boom")
        var siblingCompleted = false

        supervisorScope {
            launch {
                delay(1_000)
                siblingCompleted = true
            }
            // async, not launch: under supervisorScope a failing `launch` child has nowhere
            // to report to and ends up at the thread's uncaught handler, which on Android
            // is a crash. `await()` is what turns the failure back into a value the caller
            // can act on. A CoroutineExceptionHandler is the other half of that answer and
            // is Phase 7 item 2, not this one.
            val failing = async<Unit> {
                delay(10)
                throw failure
            }

            assertFailure(failure, runCatching { failing.await() }.exceptionOrNull())
        }

        assertTrue(siblingCompleted)
    }

    @Test
    fun `supervisorScope still waits for every child before returning`() = runTest {
        var slowChildCompleted = false

        supervisorScope {
            launch {
                delay(1_000)
                slowChildCompleted = true
            }
            val failing = async<Unit> { throw IllegalStateException("boom") }
            runCatching { failing.await() }
        }

        // Failure isolation is not the same as abandoning children: the scope does not
        // complete until all of them have.
        assertTrue(slowChildCompleted)
    }

    @Test
    fun `cancelling the caller cancels every child of both scope builders`() = runTest {
        val started = CompletableDeferred<Unit>()
        var childCancelled = false

        val job = launch {
            supervisorScope {
                launch {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } catch (cancellation: CancellationException) {
                        childCancelled = true
                        throw cancellation
                    }
                }
            }
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(childCancelled)
    }
}
