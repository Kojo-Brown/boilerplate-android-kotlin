package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.FakeUserRepository
import com.kojo.boilerplate.core.domain.model.RefreshOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshVisibleUsersUseCaseTest {

    private val users = listOf(
        User(id = "user-1", displayName = "Alice", email = "alice@example.com"),
        User(id = "user-2", displayName = "Bob", email = "bob@example.com"),
        User(id = "user-3", displayName = "Carla", email = "carla@example.com"),
    )

    private val repository = FakeUserRepository(users)
    private val refreshVisibleUsers = RefreshVisibleUsersUseCase(repository)

    @Test
    fun `every id succeeding is counted as refreshed`() = runTest {
        val outcome = refreshVisibleUsers(users.map { it.id })

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

        val outcome = refreshVisibleUsers(users.map { it.id })

        assertEquals(RefreshOutcome(refreshed = 2, failed = 1), outcome)
    }

    @Test
    fun `every id failing is still an outcome rather than an exception`() = runTest {
        repository.syncUsersFailing = users.map { it.id }.toSet()

        assertEquals(RefreshOutcome(refreshed = 0, failed = 3), refreshVisibleUsers(users.map { it.id }))
    }

    /**
     * Nothing on screen means no request at all — not a fan-out over an empty list. Asserted
     * through the repository rather than through the count, because both spellings return
     * `0/0` and only one of them opens a coroutine scope to do it.
     */
    @Test
    fun `an empty selection makes no request`() = runTest {
        val outcome = refreshVisibleUsers(emptyList())

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
        val outcome = refreshVisibleUsers(listOf("user-1", "user-1", "user-2", "user-1"))

        assertEquals(listOf("user-1", "user-2"), repository.syncUsersRequested)
        assertEquals(RefreshOutcome(refreshed = 2, failed = 0), outcome)
    }

    /** First occurrence wins, so the request order still tracks what the screen showed. */
    @Test
    fun `dedupe preserves the order the ids were given in`() = runTest {
        refreshVisibleUsers(listOf("user-3", "user-1", "user-3", "user-2"))

        assertEquals(listOf("user-3", "user-1", "user-2"), repository.syncUsersRequested)
    }

    /** Counts are what a screen renders; a negative one is a bug worth failing loudly on. */
    @Test
    fun `RefreshOutcome rejects negative counts`() {
        assertThrows(IllegalArgumentException::class.java) {
            RefreshOutcome(refreshed = -1, failed = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RefreshOutcome(refreshed = 0, failed = -1)
        }
    }
}
