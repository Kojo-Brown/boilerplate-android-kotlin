package com.kojo.boilerplate.core.domain.sync

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
class CurrentUserSyncStrategyTest {

    private val currentUser =
        User(id = "user-1", displayName = "Alice", email = "alice@example.com")

    private val repository = FakeUserRepository(listOf(currentUser))
    private val strategy = CurrentUserSyncStrategy(repository)

    @Test
    fun `it declares the mode it is bound under`() {
        assertEquals(SyncMode.CURRENT_USER, strategy.mode)
    }

    @Test
    fun `a fetched user is one refreshed row`() = runTest {
        repository.syncCurrentUserResult = Result.success(currentUser)

        assertEquals(RefreshOutcome(refreshed = 1, failed = 0), strategy.sync(emptyList()))
    }

    /**
     * A failure is reported the same way a fan-out reports one — as a count, not an
     * exception. The caller renders "could not refresh", which is the same sentence whether
     * one request failed or eight did.
     */
    @Test
    fun `a failed fetch is one failed row rather than an exception`() = runTest {
        repository.syncCurrentUserResult = Result.failure(IllegalStateException("500"))

        val outcome = strategy.sync(emptyList())

        assertEquals(RefreshOutcome(refreshed = 0, failed = 1), outcome)
        assertEquals(1, outcome.attempted)
    }

    /**
     * The distinguishing property of this mode: what the screen is showing does not change
     * what it fetches. A strategy that quietly folded the visible ids in would be the
     * visible-users strategy with an extra request attached, and the only thing that would
     * notice is the request count.
     */
    @Test
    fun `the visible ids are ignored`() = runTest {
        repository.syncCurrentUserResult = Result.success(currentUser)

        val outcome = strategy.sync(listOf("user-2", "user-3", "user-4"))

        assertEquals(RefreshOutcome(refreshed = 1, failed = 0), outcome)
        assertEquals(emptyList<String>(), repository.syncUsersRequested)
    }

    /**
     * `UserRepositoryImpl.syncCurrentUser` wraps its work in `safeCall`, which rethrows
     * cancellation instead of folding it into the `Result` — so a cancelled sync never
     * reaches the `fold` here at all. This asserts the half that is this class's to keep:
     * nothing between the repository and the caller catches it on the way past.
     */
    @Test
    fun `cancellation propagates rather than being counted as a failure`() = runTest {
        val syncStarted = CompletableDeferred<Unit>()
        val cancellable = CurrentUserSyncStrategy(SuspendingCurrentUserRepository(syncStarted))

        val job = launch { cancellable.sync(emptyList()) }
        syncStarted.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }
}

/** A repository whose current-user fetch starts and then never finishes. */
private class SuspendingCurrentUserRepository(
    private val syncStarted: CompletableDeferred<Unit>,
    delegate: UserRepository = FakeUserRepository(),
) : UserRepository by delegate {

    override suspend fun syncCurrentUser(): Result<User> {
        syncStarted.complete(Unit)
        awaitCancellation()
    }
}
