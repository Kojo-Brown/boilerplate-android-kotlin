package com.kojo.boilerplate.core.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FanOutTest {

    /**
     * Long enough that a sequential run would be unmistakable on the virtual clock: ten
     * items at 100ms each is 1000ms in series and 100ms with room to run them together.
     */
    private val work = 100L

    /**
     * Counts how many [block] invocations overlapped, which is the only direct evidence that
     * a concurrency bound is doing anything.
     *
     * Both counters are plain `var`s and that is safe here rather than sloppy: `runTest`
     * drives every coroutine on a single-threaded scheduler, so the increments cannot
     * interleave. A real thread pool would need an atomic.
     */
    private class ConcurrencyProbe {
        var inFlight: Int = 0
        var peak: Int = 0

        suspend fun <T> track(block: suspend () -> T): T {
            inFlight++
            peak = maxOf(peak, inFlight)
            try {
                return block()
            } finally {
                inFlight--
            }
        }
    }

    // mapConcurrently — all or nothing

    @Test
    fun `mapConcurrently returns results in input order`() = runTest {
        val results = (1..10).mapConcurrently { input ->
            // Reverse the delays so completion order is the opposite of input order; a
            // function that collected results as they arrived would return them backwards.
            delay((10 - input) * work)
            input * 2
        }

        assertEquals((1..10).map { it * 2 }, results)
    }

    @Test
    fun `mapConcurrently runs the whole batch at once when the bound allows`() = runTest {
        val start = currentTime

        (1..10).mapConcurrently(concurrency = 10) { delay(work) }

        // Sequentially this is 1000ms. Anything at 100ms ran all ten together.
        assertEquals(work, currentTime - start)
    }

    @Test
    fun `mapConcurrently never exceeds the concurrency bound`() = runTest {
        val probe = ConcurrencyProbe()

        (1..10).mapConcurrently(concurrency = 3) { probe.track { delay(work) } }

        assertEquals(3, probe.peak)
        assertEquals(0, probe.inFlight)
    }

    @Test
    fun `mapConcurrently with a bound takes as long as the batches it forces`() = runTest {
        val start = currentTime

        (1..10).mapConcurrently(concurrency = 5) { delay(work) }

        // Ten items through five slots is two waves, so 2 x 100ms — the bound is a real
        // constraint on wall-clock, not merely on how many coroutines exist.
        assertEquals(2 * work, currentTime - start)
    }

    @Test
    fun `mapConcurrently on an empty input returns empty without failing`() = runTest {
        assertEquals(emptyList<Int>(), emptyList<Int>().mapConcurrently { it })
    }

    @Test
    fun `mapConcurrently rejects a concurrency below one`() = runTest {
        val thrown = runCatching { listOf(1).mapConcurrently(concurrency = 0) { it } }
            .exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `mapConcurrently rethrows the failure of a single element`() = runTest {
        val thrown = runCatching {
            (1..5).mapConcurrently { input ->
                if (input == 3) error("element $input failed") else input
            }
        }.exceptionOrNull()

        assertEquals("element 3 failed", thrown?.message)
    }

    @Test
    fun `mapConcurrently cancels the siblings of a failed element`() = runTest {
        var started = 0
        var cancelled = 0

        runCatching {
            (1..5).mapConcurrently(concurrency = 5) { input ->
                started++
                if (input == 3) {
                    // Delayed so that every sibling is genuinely in flight before the
                    // failure. Throwing on the first pass would cancel the elements that
                    // had not been dispatched yet, whose bodies then never run at all —
                    // which proves nothing about cancelling work already underway.
                    delay(work)
                    error("element $input failed")
                }
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }
        }

        // The four survivors were abandoned rather than awaited: a result nobody can use is
        // not worth finishing. mapConcurrently having returned at all is the other half —
        // coroutineScope does not rethrow until every child has completed its cancellation.
        assertEquals(5, started)
        assertEquals(4, cancelled)
    }

    // mapConcurrentlyCatching — partial failure

    @Test
    fun `mapConcurrentlyCatching keeps the successes when some elements fail`() = runTest {
        val result = (1..5).mapConcurrentlyCatching { input ->
            if (input % 2 == 0) error("element $input failed") else input
        }

        assertEquals(listOf(1, 3, 5), result.successes)
        assertEquals(listOf(2, 4), result.failures.map { it.input })
        assertEquals(
            listOf("element 2 failed", "element 4 failed"),
            result.failures.map { it.cause.message },
        )
    }

    @Test
    fun `mapConcurrentlyCatching does not cancel siblings when an element fails`() = runTest {
        val completed = mutableListOf<Int>()

        val result = (1..5).mapConcurrentlyCatching(concurrency = 5) { input ->
            if (input == 1) error("element $input failed")
            // Outlives the failure: under a scope that propagated it, none of these four
            // would ever reach the line below.
            delay(work)
            completed += input
            input
        }

        assertEquals(listOf(2, 3, 4, 5), completed)
        assertEquals(listOf(2, 3, 4, 5), result.successes)
        assertEquals(1, result.failures.single().input)
    }

    @Test
    fun `mapConcurrentlyCatching classifies a fully successful batch`() = runTest {
        val result = (1..3).mapConcurrentlyCatching { it }

        assertTrue(result.isCompleteSuccess)
        assertFalse(result.isCompleteFailure)
        assertFalse(result.isPartial)
        assertEquals(3, result.attempted)
    }

    @Test
    fun `mapConcurrentlyCatching classifies a fully failed batch`() = runTest {
        val result = (1..3).mapConcurrentlyCatching { error("element $it failed") }

        assertFalse(result.isCompleteSuccess)
        assertTrue(result.isCompleteFailure)
        assertFalse(result.isPartial)
        assertEquals(3, result.attempted)
    }

    @Test
    fun `mapConcurrentlyCatching classifies a partial batch`() = runTest {
        val result = (1..3).mapConcurrentlyCatching { input ->
            if (input == 2) error("element $input failed") else input
        }

        assertFalse(result.isCompleteSuccess)
        assertFalse(result.isCompleteFailure)
        assertTrue(result.isPartial)
        assertEquals(3, result.attempted)
    }

    @Test
    fun `mapConcurrentlyCatching treats an empty batch as vacuously successful`() = runTest {
        val result = emptyList<Int>().mapConcurrentlyCatching { it }

        assertTrue(result.isCompleteSuccess)
        assertFalse(result.isCompleteFailure)
        assertEquals(0, result.attempted)
    }

    @Test
    fun `mapConcurrentlyCatching never exceeds the concurrency bound`() = runTest {
        val probe = ConcurrencyProbe()

        (1..10).mapConcurrentlyCatching(concurrency = 2) { probe.track { delay(work) } }

        assertEquals(2, probe.peak)
    }

    @Test
    fun `mapConcurrentlyCatching holds no permit for an element that failed`() = runTest {
        val probe = ConcurrencyProbe()

        // Every element fails, so a permit released only on the success path would leak one
        // per element and the second wave would never start.
        val result = (1..10).mapConcurrentlyCatching(concurrency = 2) {
            probe.track<Unit> { error("boom") }
        }

        assertEquals(10, result.failures.size)
        assertEquals(0, probe.inFlight)
    }

    @Test
    fun `mapConcurrentlyCatching rejects a concurrency below one`() = runTest {
        val thrown = runCatching { listOf(1).mapConcurrentlyCatching(concurrency = 0) { it } }
            .exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    /**
     * The failure mode the function is built to avoid. `runCatching` inside each child would
     * otherwise record the cancellation as element N's failure and hand back a report of a
     * fan-out that was told to stop — leaving the caller completing normally with a partial
     * result instead of completing as cancelled.
     */
    @Test
    fun `mapConcurrentlyCatching completes as cancelled rather than reporting a failure`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var result: FanOutResult<Int, Int>? = null

            val job = launch {
                result = (1..3).mapConcurrentlyCatching<Int, Int>(concurrency = 3) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }

            started.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertNull(result)
        }

    @Test
    fun `mapConcurrently completes as cancelled rather than failing its caller`() = runTest {
        val started = CompletableDeferred<Unit>()

        val job = launch {
            (1..3).mapConcurrently(concurrency = 3) {
                started.complete(Unit)
                awaitCancellation()
            }
        }

        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    /**
     * A `CancellationException` the transform raised for its own reasons is rethrown too,
     * which is the same call `safeCall` makes: nothing downstream can tell that exception
     * apart from the parent's, so the safe reading is the one that preserves cancellation.
     */
    @Test
    fun `mapConcurrentlyCatching rethrows a CancellationException raised by the transform`() =
        runTest {
            val thrown = runCatching {
                listOf(1).mapConcurrentlyCatching {
                    throw CancellationException("transform stopped")
                }
            }.exceptionOrNull()

            assertTrue(thrown is CancellationException)
        }
}
