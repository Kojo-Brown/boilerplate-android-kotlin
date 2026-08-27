package com.kojo.boilerplate.core.coroutines

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The store here is a [MutableStateFlow] of a plain `String`, which stands in for a Room query
 * in the two ways that matter to this builder: it always has a current value, and it re-emits
 * to whoever is collecting when something writes to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkBoundResourceTest {

    @Test
    fun `emits what the store holds, then what it holds after the refresh`() = runTest {
        val store = MutableStateFlow("cached")

        val resources = networkBoundResource(
            query = { store },
            refresh = { store.value = "refreshed" },
        ).take(2).toList()

        assertEquals(listOf(Resource.Loading("cached"), Resource.Success("refreshed")), resources)
    }

    /**
     * The ordering that makes this offline-first rather than merely cached: the collector is
     * given something to render *before* the network is touched, not after it comes back.
     */
    @Test
    fun `the loading emission precedes the refresh`() = runTest {
        val order = mutableListOf<String>()
        val store = MutableStateFlow("cached")

        networkBoundResource(query = { store }, refresh = { order += "refresh" })
            .take(2)
            .collect { order += "emit ${it::class.simpleName}" }

        assertEquals(listOf("emit Loading", "refresh", "emit Success"), order)
    }

    /**
     * The state that `Result` cannot express, which is the argument for [Resource] existing:
     * the data is still here, and the attempt to make it newer is still a failure.
     */
    @Test
    fun `a refresh that fails keeps the stored value and carries the cause`() = runTest {
        val boom = IOException("offline")
        val store = MutableStateFlow("cached")

        val resources = networkBoundResource(query = { store }, refresh = { throw boom })
            .take(2)
            .toList()

        assertEquals(listOf(Resource.Loading("cached"), Resource.Failure("cached", boom)), resources)
    }

    /**
     * A failed refresh stays failed for the life of the subscription, including across writes
     * that some other part of the app makes. The arm describes what *this* subscription's
     * refresh did, and nothing that happens to the store afterwards changes that; recovering
     * means subscribing again. Pinned here because the alternative — quietly promoting the next
     * emission back to [Resource.Success] — would clear a stale-data warning on a write that
     * had nothing to do with the network.
     */
    @Test
    fun `a write after a failed refresh is still reported as a failure`() = runTest {
        val boom = IOException("offline")
        val store = MutableStateFlow("cached")
        val seen = mutableListOf<Resource<String>>()

        val job = launch {
            networkBoundResource(query = { store }, refresh = { throw boom }).collect { seen += it }
        }
        testScheduler.advanceUntilIdle()
        store.value = "written by something else"
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(
            listOf(
                Resource.Loading("cached"),
                Resource.Failure("cached", boom),
                Resource.Failure("written by something else", boom),
            ),
            seen,
        )
    }

    /**
     * A store that cannot be read is not a resource in a failed state, it is no resource at
     * all — so the failure travels as a throw for the collector's `catch` to handle, and the
     * refresh never runs, because there is nothing for its result to be written into or read
     * back from.
     */
    @Test
    fun `a query that fails terminates the flow rather than becoming a Failure`() = runTest {
        val boom = IllegalStateException("no such table")
        var refreshed = false

        val caught = runCatching {
            networkBoundResource<String>(
                query = { flow { throw boom } },
                refresh = { refreshed = true },
            ).toList()
        }.exceptionOrNull()

        assertSame(boom, caught)
        assertFalse(refreshed, "the refresh must not run when the store cannot be read")
    }

    /**
     * Cancellation is not failure. A collector that goes away mid-refresh has to cancel, not
     * arrive at [Resource.Failure] — the emission it would be given belongs to a screen that
     * is already gone, and the parent job would never see the cancellation it is waiting for.
     */
    @Test
    fun `cancellation during the refresh propagates instead of becoming a Failure`() = runTest {
        val store = MutableStateFlow("cached")
        val seen = mutableListOf<Resource<String>>()

        val job = launch {
            networkBoundResource(query = { store }, refresh = { awaitCancellation() })
                .collect { seen += it }
        }
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(listOf(Resource.Loading("cached")), seen)
        assertTrue(job.isCancelled, "the collecting job should have been cancelled")
    }

    /**
     * Twice and once, and both numbers are part of the contract rather than an implementation
     * detail: `query` is collected once for the value that accompanies [Resource.Loading] and
     * once for the stream that outlives the refresh, so it has to be a cold flow that can be
     * collected again. `refresh` runs per subscription, not per emission — the opposite would
     * answer its own write to the store with another request.
     */
    @Test
    fun `the store is collected twice per subscription and the refresh runs once`() = runTest {
        val collections = AtomicInteger(0)
        val refreshes = AtomicInteger(0)

        networkBoundResource(
            query = {
                flow {
                    collections.incrementAndGet()
                    emit("cached")
                    emit("cached again")
                }
            },
            refresh = { refreshes.incrementAndGet() },
        ).toList()

        assertEquals(2, collections.get())
        assertEquals(1, refreshes.get())
    }

    /**
     * The price of every arm carrying a value: with nothing to put in [Resource.Loading] there
     * is no loading emission, and the refresh does not start either. Room always answers, so
     * this is a documented edge rather than a case the app reaches — but a collector staying
     * silent is very different from one emitting a data-less loading state, and which of the
     * two happens should not be a surprise.
     */
    @Test
    fun `a store that never emits leaves the resource silent`() = runTest {
        var refreshed = false
        val seen = mutableListOf<Resource<String>>()

        val job = launch {
            networkBoundResource(
                query = { flow<String> { awaitCancellation() } },
                refresh = { refreshed = true },
            ).collect { seen += it }
        }
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(emptyList<Resource<String>>(), seen)
        assertFalse(refreshed, "the refresh must not run before the store has been read")
    }

    /**
     * [Resource.data] is declared on the interface so a caller that only wants the value never
     * has to `when` over three arms to reach it. That is the single-source-of-truth rule made
     * unavoidable: there is no arm to forget, because there is no arm without data.
     */
    @Test
    fun `every arm exposes the stored value through the interface`() {
        val boom = IOException("offline")
        val resources: List<Resource<String>> = listOf(
            Resource.Loading("cached"),
            Resource.Success("cached"),
            Resource.Failure("cached", boom),
        )

        assertEquals(listOf("cached", "cached", "cached"), resources.map { it.data })
    }
}
