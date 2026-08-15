package com.kojo.boilerplate.core.domain.sync

import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.FakeUserRepository
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.domain.model.RefreshOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleUsersSyncStrategyTest {

    private val users = listOf(
        User(id = "user-1", displayName = "Alice", email = "alice@example.com"),
        User(id = "user-2", displayName = "Bob", email = "bob@example.com"),
        User(id = "user-3", displayName = "Carla", email = "carla@example.com"),
    )

    private val repository = FakeUserRepository(users)
    private val strategy = VisibleUsersSyncStrategy(repository)

    /**
     * The half of the wiring Dagger cannot check: the map key is an annotation in another
     * file and nothing makes it agree with the implementation. `SyncStrategyFactory` compares
     * the two, and this is the value it compares against.
     */
    @Test
    fun `it declares the mode it is bound under`() {
        assertEquals(SyncMode.VISIBLE_USERS, strategy.mode)
    }

    @Test
    fun `every id succeeding is counted as refreshed`() = runTest {
        val outcome = strategy.sync(users.map { it.id })

        assertEquals(RefreshOutcome(refreshed = 3, failed = 0), outcome)
        assertEquals(3, outcome.attempted)
    }

    /**
     * The policy the fan-out exists for. One id failing says nothing about the other two, so
     * the successes are kept and reported alongside the shortfall rather than being collapsed
     * into a single pass or fail.
     */
    @Test
    fun `a partial failure reports both halves`() = runTest {
        repository.syncUsersFailing = setOf("user-2")

        assertEquals(RefreshOutcome(refreshed = 2, failed = 1), strategy.sync(users.map { it.id }))
    }

    @Test
    fun `every id failing is still an outcome rather than an exception`() = runTest {
        repository.syncUsersFailing = users.map { it.id }.toSet()

        assertEquals(RefreshOutcome(refreshed = 0, failed = 3), strategy.sync(users.map { it.id }))
    }

    /**
     * Nothing on screen means no request at all — not a fan-out over an empty list. Asserted
     * through the repository rather than through the count, because both spellings return
     * `0/0` and only one of them opens a coroutine scope to do it.
     */
    @Test
    fun `an empty selection makes no request`() = runTest {
        val outcome = strategy.sync(emptyList())

        assertEquals(RefreshOutcome.NOTHING_TO_REFRESH, outcome)
        assertEquals(0, outcome.attempted)
        assertEquals(emptyList<String>(), repository.syncUsersRequested)
    }

    /**
     * The same id twice is the same request twice. Without the dedupe "2 refreshed" over
     * `[user-1, user-1]` would mean two users to a reader and one user to the database.
     */
    @Test
    fun `duplicate ids are one request and one count`() = runTest {
        val outcome = strategy.sync(listOf("user-1", "user-1", "user-2", "user-1"))

        assertEquals(listOf("user-1", "user-2"), repository.syncUsersRequested)
        assertEquals(RefreshOutcome(refreshed = 2, failed = 0), outcome)
    }

    /** First occurrence wins, so the request order still tracks what the screen showed. */
    @Test
    fun `dedupe preserves the order the ids were given in`() = runTest {
        strategy.sync(listOf("user-3", "user-1", "user-3", "user-2"))

        assertEquals(listOf("user-3", "user-1", "user-2"), repository.syncUsersRequested)
    }

    /**
     * A refresh the caller walked away from is cancelled, not failed.
     *
     * Nothing in the strategy catches, so this holds today; it is asserted because the
     * tempting way to write a strategy — wrap the fetch in `runCatching` and count the
     * failures — passes every other test in this file and turns a cancellation into
     * `RefreshOutcome(0, 1)`, which a screen renders as "1 user could not be refreshed"
     * after the user has already left it.
     */
    @Test
    fun `cancellation propagates rather than being counted as a failure`() = runTest {
        val syncStarted = CompletableDeferred<Unit>()
        val cancellable = VisibleUsersSyncStrategy(SuspendingSyncUsersRepository(syncStarted))

        val job = launch { cancellable.sync(listOf("user-1")) }
        syncStarted.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }
}

/**
 * A repository whose fan-out starts and then never finishes, so a test can cancel it while it
 * is genuinely in flight. Everything else is delegated to [FakeUserRepository] rather than
 * stubbed out, so the only behaviour this changes is the one under test.
 */
private class SuspendingSyncUsersRepository(
    private val syncStarted: CompletableDeferred<Unit>,
    delegate: UserRepository = FakeUserRepository(),
) : UserRepository by delegate {

    override suspend fun syncUsers(ids: List<String>): FanOutResult<String, User> {
        syncStarted.complete(Unit)
        awaitCancellation()
    }
}
