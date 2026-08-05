package com.kojo.boilerplate.core.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Every delay in [retryWithBackoff] is a `delay()` on the collecting coroutine, so `runTest`
 * runs the whole backoff schedule on virtual time: the assertions below read the real
 * millisecond values out of [TestScope.currentTime] without any test taking 3.5 seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowRetryTest {

    // Collecting a flow that is expected to fail. `catch (expected: Throwable)` is broad on
    // purpose — one test here asserts that a CancellationException is *not* retried, which
    // means it has to be observed rather than propagated.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun Flow<*>.collectFailure(): Throwable? =
        try {
            toList()
            null
        } catch (expected: Throwable) {
            expected
        }

    /** Records the virtual time at which each subscription begins, then fails with [cause]. */
    private fun TestScope.alwaysFailing(
        subscriptions: MutableList<Long>,
        cause: () -> Throwable,
    ): Flow<Int> = flow {
        subscriptions += currentTime
        throw cause()
    }

    private fun httpException(code: Int): HttpException = HttpException(
        Response.error<Unit>(code, "{}".toResponseBody("application/json".toMediaTypeOrNull())),
    )

    @Test
    fun `a flow that does not fail is passed through untouched`() = runTest {
        val values = flowOf(1, 2, 3).retryWithBackoff().toList()

        assertEquals(listOf(1, 2, 3), values)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a transient failure is retried and the retry's values reach the collector`() = runTest {
        var subscriptions = 0
        val flaky = flow {
            subscriptions++
            if (subscriptions == 1) throw SocketTimeoutException("read timed out")
            emit("recovered")
        }

        val values = flaky.retryWithBackoff(jitterRatio = 0.0).toList()

        assertEquals(listOf("recovered"), values)
        assertEquals(2, subscriptions)
    }

    @Test
    fun `values emitted before the failure are re-emitted by the retry`() = runTest {
        var subscriptions = 0
        val flaky = flow {
            subscriptions++
            emit("first")
            if (subscriptions == 1) throw IOException("dropped mid-stream")
            emit("second")
        }

        val values = flaky.retryWithBackoff(jitterRatio = 0.0).toList()

        // Resubscribing replays the prefix — the reason distinctUntilChanged belongs
        // downstream of this operator wherever the collector cannot tolerate a repeat.
        assertEquals(listOf("first", "first", "second"), values)
    }

    @Test
    fun `the original failure reaches the collector once the retries are exhausted`() = runTest {
        val subscriptions = mutableListOf<Long>()
        val cause = IOException("still down")

        val failure = alwaysFailing(subscriptions) { cause }
            .retryWithBackoff(maxRetries = 3, jitterRatio = 0.0)
            .collectFailure()

        assertSame(cause, failure)
        assertEquals(4, subscriptions.size) // the first attempt plus three retries
    }

    @Test
    fun `maxRetries of zero disables retrying`() = runTest {
        val subscriptions = mutableListOf<Long>()

        val failure = alwaysFailing(subscriptions) { IOException("down") }
            .retryWithBackoff(maxRetries = 0)
            .collectFailure()

        assertTrue(failure is IOException)
        assertEquals(1, subscriptions.size)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a non-transient failure is not retried`() = runTest {
        val subscriptions = mutableListOf<Long>()

        val failure = alwaysFailing(subscriptions) { IllegalStateException("bad response shape") }
            .retryWithBackoff()
            .collectFailure()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, subscriptions.size)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `cancellation is not retried`() = runTest {
        val subscriptions = mutableListOf<Long>()

        // A CancellationException raised by upstream code is not the collecting job's own
        // cancellation cause, so kotlinx's catchImpl hands it to the retryWhen predicate like
        // any other throwable. Without the explicit guard in retryWithBackoff it would be
        // retried, and the caller waiting on the cancellation would never see it.
        val failure = alwaysFailing(subscriptions) { CancellationException("upstream stopped") }
            .retryWithBackoff()
            .collectFailure()

        assertTrue(failure is CancellationException)
        assertEquals(1, subscriptions.size)
    }

    @Test
    fun `a custom isTransient predicate overrides the default`() = runTest {
        val subscriptions = mutableListOf<Long>()

        val failure = alwaysFailing(subscriptions) { IllegalStateException("worth another go") }
            .retryWithBackoff(maxRetries = 2, jitterRatio = 0.0) { it is IllegalStateException }
            .collectFailure()

        assertTrue(failure is IllegalStateException)
        assertEquals(3, subscriptions.size)
    }

    @Test
    fun `delays grow exponentially from initialDelay`() = runTest {
        val subscriptions = mutableListOf<Long>()

        alwaysFailing(subscriptions) { IOException("down") }
            .retryWithBackoff(
                maxRetries = 3,
                initialDelay = 100.milliseconds,
                maxDelay = 1.minutes,
                jitterRatio = 0.0,
            )
            .collectFailure()

        // Gaps of 100, 200, 400 — not 100, 100, 100, and not 100, 300, 700.
        assertEquals(listOf(0L, 100L, 300L, 700L), subscriptions)
    }

    @Test
    fun `delays stop growing at maxDelay`() = runTest {
        val subscriptions = mutableListOf<Long>()

        alwaysFailing(subscriptions) { IOException("down") }
            .retryWithBackoff(
                maxRetries = 4,
                initialDelay = 100.milliseconds,
                maxDelay = 250.milliseconds,
                jitterRatio = 0.0,
            )
            .collectFailure()

        // Gaps of 100, 200, 250, 250: the third would have been 400.
        assertEquals(listOf(0L, 100L, 300L, 550L, 800L), subscriptions)
    }

    @Test
    fun `jitter keeps every delay inside its band and never above maxDelay`() = runTest {
        val subscriptions = mutableListOf<Long>()

        alwaysFailing(subscriptions) { IOException("down") }
            .retryWithBackoff(
                maxRetries = 3,
                initialDelay = 1.seconds,
                maxDelay = 1.minutes,
                jitterRatio = 0.25,
                random = Random(seed = 1234),
            )
            .collectFailure()

        val gaps = subscriptions.zipWithNext { previous, next -> next - previous }
        val nominal = listOf(1_000L, 2_000L, 4_000L)
        assertEquals(nominal.size, gaps.size)
        gaps.forEachIndexed { index, gap ->
            val expected = nominal[index]
            assertTrue(
                gap in (expected * 3 / 4)..expected,
                "retry ${index + 1} waited ${gap}ms, outside [${expected * 3 / 4}, $expected]",
            )
        }
        // Jitter has to actually move something, or the seed is being ignored.
        assertFalse(gaps == nominal, "expected jitter to shorten at least one delay")
    }

    @Test
    fun `the same seed produces the same schedule`() = runTest {
        suspend fun schedule(): List<Long> {
            val subscriptions = mutableListOf<Long>()
            alwaysFailing(subscriptions) { IOException("down") }
                .retryWithBackoff(maxRetries = 3, random = Random(seed = 7))
                .collectFailure()
            return subscriptions.zipWithNext { previous, next -> next - previous }
        }

        assertEquals(schedule(), schedule())
    }

    @Test
    fun `isTransientFailure retries transport failures and retryable statuses`() {
        assertTrue(isTransientFailure(IOException("connection reset")))
        assertTrue(isTransientFailure(SocketTimeoutException("read timed out")))
        assertTrue(isTransientFailure(httpException(HTTP_REQUEST_TIMEOUT_CODE)))
        assertTrue(isTransientFailure(httpException(HTTP_TOO_MANY_REQUESTS_CODE)))
        assertTrue(isTransientFailure(httpException(HTTP_INTERNAL_SERVER_ERROR_CODE)))
        assertTrue(isTransientFailure(httpException(HTTP_GATEWAY_TIMEOUT_CODE)))
    }

    @Test
    fun `isTransientFailure gives up on failures another attempt cannot fix`() {
        assertFalse(isTransientFailure(httpException(HTTP_BAD_REQUEST_CODE)))
        assertFalse(isTransientFailure(httpException(HTTP_UNAUTHORIZED_CODE)))
        assertFalse(isTransientFailure(httpException(HTTP_NOT_FOUND_CODE)))
        assertFalse(isTransientFailure(httpException(HTTP_CONFLICT_CODE)))
        assertFalse(isTransientFailure(IllegalStateException("unparseable payload")))
    }

    @Test
    fun `invalid configuration is rejected where it is written, not on collection`() {
        val source = flowOf(1)

        assertThrows<IllegalArgumentException> { source.retryWithBackoff(maxRetries = -1) }
        assertThrows<IllegalArgumentException> {
            source.retryWithBackoff(initialDelay = (-1).milliseconds)
        }
        assertThrows<IllegalArgumentException> { source.retryWithBackoff(backoffFactor = 0.5) }
        assertThrows<IllegalArgumentException> { source.retryWithBackoff(jitterRatio = 1.5) }
        assertNull(runCatching { source.retryWithBackoff() }.exceptionOrNull())
    }

    private companion object {
        const val HTTP_BAD_REQUEST_CODE = 400
        const val HTTP_UNAUTHORIZED_CODE = 401
        const val HTTP_NOT_FOUND_CODE = 404
        const val HTTP_REQUEST_TIMEOUT_CODE = 408
        const val HTTP_CONFLICT_CODE = 409
        const val HTTP_TOO_MANY_REQUESTS_CODE = 429
        const val HTTP_INTERNAL_SERVER_ERROR_CODE = 500
        const val HTTP_GATEWAY_TIMEOUT_CODE = 504
    }
}
