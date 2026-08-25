package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.domain.model.RefreshOutcome
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.sync.BackgroundSyncOutcome
import com.kojo.boilerplate.core.domain.sync.SyncMode
import com.kojo.boilerplate.core.domain.sync.SyncStrategy
import com.kojo.boilerplate.core.domain.sync.SyncStrategyFactory
import com.kojo.boilerplate.core.testing.FakeUserRepository
import com.kojo.boilerplate.core.testing.syncStrategyFactoryOver
import java.io.IOException
import javax.inject.Provider
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The three decisions `PerformBackgroundSyncUseCase` owns, each asserted where it can be:
 * which mode a worker uses, what a shortfall means, and where the attempt budget runs out.
 *
 * The strategy is real — `syncStrategyFactoryOver` builds the same map `SyncStrategyModule`
 * binds — so these fail if the delegation stops reaching
 * [com.kojo.boilerplate.core.domain.sync.CurrentUserSyncStrategy] as well as if the mapping
 * changes. The cases that need a failure the fake cannot produce use a stub strategy instead,
 * and say so.
 *
 * Nothing here touches WorkManager. What a `Result.retry()` does to a schedule is the
 * framework's behaviour rather than this app's, and `WorkManagerBackgroundSyncSchedulerTest`
 * covers the request that configures it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerformBackgroundSyncUseCaseTest {

    private val user = User(id = "user-1", displayName = "Alice", email = "alice@example.com")
    private val repository = FakeUserRepository(listOf(user))
    private val performBackgroundSync = PerformBackgroundSyncUseCase(syncStrategyFactoryOver(repository))

    /**
     * Decision 1, in the only form that can be asserted directly. The recording factory
     * answers every mode, so asking for the wrong one — or for a second one as well — shows
     * up as a list that is not exactly this.
     */
    @Test
    fun `it syncs the current user and no other mode`() = runTest {
        val requestedModes = mutableListOf<SyncMode>()
        val factory = SyncStrategyFactory(
            SyncMode.entries.associateWith { mode ->
                Provider<SyncStrategy> {
                    requestedModes += mode
                    StubSyncStrategy(mode, RefreshOutcome(refreshed = 1, failed = 0))
                }
            },
        )

        PerformBackgroundSyncUseCase(factory)(attempt = 0)

        assertEquals(listOf(SyncMode.CURRENT_USER), requestedModes)
    }

    /**
     * A worker has no screen, so it names no ids. Asserted because passing a stale selection
     * would be invisible today — `CurrentUserSyncStrategy` ignores the parameter — and would
     * start mattering the moment a mode that reads it is chosen here instead.
     */
    @Test
    fun `it names no user ids`() = runTest {
        val strategy = StubSyncStrategy(SyncMode.CURRENT_USER, RefreshOutcome(refreshed = 1, failed = 0))

        useCaseOver(strategy)(attempt = 0)

        assertEquals(listOf(emptyList<String>()), strategy.received)
    }

    @Test
    fun `a sync that lands is a success`() = runTest {
        repository.syncCurrentUserResult = Result.success(user)

        assertEquals(BackgroundSyncOutcome.SUCCESS, performBackgroundSync(attempt = 0))
    }

    @Test
    fun `a sync that fails is retried while the budget lasts`() = runTest {
        repository.syncCurrentUserResult = Result.failure(IOException("offline"))

        assertEquals(BackgroundSyncOutcome.RETRY, performBackgroundSync(attempt = 0))
    }

    /**
     * The boundary, from both sides. `attempt` counts the runs before this one, so the fourth
     * run arrives with `attempt == 3` — an off-by-one here is the difference between three
     * attempts and four, and neither side of it is visible without both assertions.
     */
    @Test
    fun `the attempt budget runs out on the fourth run`() = runTest {
        repository.syncCurrentUserResult = Result.failure(IOException("offline"))

        assertEquals(BackgroundSyncOutcome.RETRY, performBackgroundSync(attempt = 2))
        assertEquals(BackgroundSyncOutcome.FAILURE, performBackgroundSync(attempt = 3))
        assertEquals(BackgroundSyncOutcome.FAILURE, performBackgroundSync(attempt = 4))
    }

    /**
     * Decision 2's exception half. WorkManager reads a throw out of `doWork()` as
     * `Result.failure()` and drops the occurrence without retrying, so an `IOException` from
     * an offline moment has to be caught here to reach the backoff at all.
     */
    @Test
    fun `a thrown failure is retried rather than escaping to the worker`() = runTest {
        val strategy = ThrowingSyncStrategy { IOException("socket closed") }

        assertEquals(BackgroundSyncOutcome.RETRY, useCaseOver(strategy)(attempt = 0))
        assertEquals(BackgroundSyncOutcome.FAILURE, useCaseOver(strategy)(attempt = 3))
    }

    /**
     * Cancellation is not a failure and must not spend an attempt. WorkManager cancels the
     * worker's coroutine when a constraint stops being met mid-run; swallowing that would
     * report a sync that was stopped as a sync that failed.
     */
    @Test
    fun `cancellation propagates instead of being counted as a failure`() {
        val strategy = ThrowingSyncStrategy { CancellationException("constraint no longer met") }

        // `runBlocking` rather than `runTest`: `assertThrows` takes a blocking lambda, and a
        // `runTest` body cannot be handed to one. Nothing here suspends for long enough to
        // want a test scheduler.
        assertThrows(CancellationException::class.java) {
            runBlocking { useCaseOver(strategy)(attempt = 0) }
        }
    }

    /**
     * A partial success is still a shortfall. `CURRENT_USER` cannot produce one — it is a
     * single request — but the mapping is written over `RefreshOutcome.failed` rather than
     * over the mode, so a future background mode that fans out inherits this behaviour and
     * this is what pins it.
     */
    @Test
    fun `a partial failure is a shortfall and gets another attempt`() = runTest {
        val strategy = StubSyncStrategy(SyncMode.CURRENT_USER, RefreshOutcome(refreshed = 4, failed = 1))

        assertEquals(BackgroundSyncOutcome.RETRY, useCaseOver(strategy)(attempt = 0))
    }

    /** Nothing to refresh is not a failure: there is nothing another attempt would fix. */
    @Test
    fun `an empty outcome is a success`() = runTest {
        val strategy = StubSyncStrategy(SyncMode.CURRENT_USER, RefreshOutcome.NOTHING_TO_REFRESH)

        assertEquals(BackgroundSyncOutcome.SUCCESS, useCaseOver(strategy)(attempt = 0))
    }

    /**
     * `runAttemptCount` is never negative, so a negative one means the caller is passing
     * something else — a 1-based count being the likely something else, which would silently
     * cost an attempt.
     */
    @Test
    fun `a negative attempt is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { performBackgroundSync(attempt = -1) }
        }
    }

    private fun useCaseOver(strategy: SyncStrategy): PerformBackgroundSyncUseCase =
        PerformBackgroundSyncUseCase(
            SyncStrategyFactory(mapOf(strategy.mode to Provider<SyncStrategy> { strategy })),
        )
}

/** Returns what it was built with, and records what it was asked for. */
private class StubSyncStrategy(
    override val mode: SyncMode,
    private val outcome: RefreshOutcome,
) : SyncStrategy {

    val received = mutableListOf<List<String>>()

    override suspend fun sync(userIds: List<String>): RefreshOutcome {
        received += userIds
        return outcome
    }
}

/**
 * Throws what the factory produces, once per call.
 *
 * `FakeUserRepository` returns a failed `Result` rather than throwing, which is the right
 * shape for `syncCurrentUser` and the wrong one for testing the catch: `safeCall` only sees a
 * throw. A fresh exception per call keeps a suppressed-cancellation surprise from travelling
 * between assertions on the same instance.
 */
private class ThrowingSyncStrategy(private val failure: () -> Throwable) : SyncStrategy {

    override val mode: SyncMode = SyncMode.CURRENT_USER

    override suspend fun sync(userIds: List<String>): RefreshOutcome = throw failure()
}
