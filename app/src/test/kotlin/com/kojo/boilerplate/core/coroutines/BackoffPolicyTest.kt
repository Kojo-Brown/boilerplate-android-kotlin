package com.kojo.boilerplate.core.coroutines

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The retry schedule, on its own.
 *
 * `FlowRetryTest` covers the same arithmetic through the flow operator, which is where it used
 * to live. It is tested directly here because it now has a second caller —
 * `RetryingUserRepository` — and a schedule shared by two mechanisms should be verifiable
 * without going through either.
 */
class BackoffPolicyTest {

    private val noJitter = BackoffPolicy(initialDelay = 500.milliseconds, jitterRatio = 0.0)

    @Test
    fun `each attempt waits the previous delay multiplied by the factor`() {
        assertEquals(500.milliseconds, noJitter.delayBefore(attempt = 0))
        assertEquals(1.seconds, noJitter.delayBefore(attempt = 1))
        assertEquals(2.seconds, noJitter.delayBefore(attempt = 2))
    }

    @Test
    fun `the delay is capped`() {
        val policy = BackoffPolicy(initialDelay = 1.seconds, maxDelay = 4.seconds, jitterRatio = 0.0)

        assertEquals(4.seconds, policy.delayBefore(attempt = LATE_ATTEMPT))
    }

    @Test
    fun `an attempt far enough out overflows the exponential and still yields the cap`() {
        val policy = BackoffPolicy(maxDelay = 8.seconds, jitterRatio = 0.0)

        assertEquals(8.seconds, policy.delayBefore(attempt = Long.MAX_VALUE))
    }

    @Test
    fun `jitter only ever subtracts, and never more than its share`() {
        val policy = BackoffPolicy(initialDelay = 1.seconds, jitterRatio = 0.25)
        val random = Random(seed = 1)

        repeat(SAMPLES) {
            val delay = policy.delayBefore(attempt = 0, random = random)
            assertTrue(
                delay in 750.milliseconds..1.seconds,
                "A quarter of a second is the most that may come off a one-second delay, was $delay",
            )
        }
    }

    @Test
    fun `a schedule that cannot back off is rejected where it is written`() {
        assertThrows<IllegalArgumentException> { BackoffPolicy(maxRetries = -1) }
        assertThrows<IllegalArgumentException> { BackoffPolicy(initialDelay = (-1).seconds) }
        assertThrows<IllegalArgumentException> { BackoffPolicy(maxDelay = Duration.INFINITE) }
        assertThrows<IllegalArgumentException> { BackoffPolicy(backoffFactor = 0.5) }
        assertThrows<IllegalArgumentException> { BackoffPolicy(jitterRatio = 1.5) }
    }

    private companion object {
        const val LATE_ATTEMPT = 10L
        const val SAMPLES = 100
    }
}
