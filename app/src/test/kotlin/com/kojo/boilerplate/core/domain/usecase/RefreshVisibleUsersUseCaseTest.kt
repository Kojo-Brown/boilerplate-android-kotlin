package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.FakeUserRepository
import com.kojo.boilerplate.core.domain.model.RefreshOutcome
import com.kojo.boilerplate.core.domain.sync.SyncMode
import com.kojo.boilerplate.core.domain.sync.SyncStrategy
import com.kojo.boilerplate.core.domain.sync.SyncStrategyFactory
import com.kojo.boilerplate.core.domain.sync.syncStrategyFactoryOver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.inject.Provider

/**
 * What is left to test here after the strategies landed is the *selection*: which mode a
 * user-initiated refresh from a list screen resolves to, and that the outcome comes back
 * unchanged. How that mode behaves — dedupe, the empty case, partial failure — belongs to
 * `VisibleUsersSyncStrategyTest`, which is where those assertions now live.
 *
 * The end-to-end cases below run through the real [com.kojo.boilerplate.core.domain.sync.VisibleUsersSyncStrategy]
 * rather than a double, so this file still fails if the delegation stops reaching it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshVisibleUsersUseCaseTest {

    private val users = listOf(
        User(id = "user-1", displayName = "Alice", email = "alice@example.com"),
        User(id = "user-2", displayName = "Bob", email = "bob@example.com"),
        User(id = "user-3", displayName = "Carla", email = "carla@example.com"),
    )

    private val repository = FakeUserRepository(users)
    private val refreshVisibleUsers = RefreshVisibleUsersUseCase(syncStrategyFactoryOver(repository))

    /**
     * The policy this use case owns, in the only form that can be asserted directly: a
     * refresh of a list screen asks for [SyncMode.VISIBLE_USERS] and nothing else. The
     * recording factory below fails the test if it ever asks for a second mode as well.
     */
    @Test
    fun `it refreshes the visible users and no other mode`() = runTest {
        val requestedModes = mutableListOf<SyncMode>()
        val strategy = RecordingSyncStrategy()
        val factory = SyncStrategyFactory(
            SyncMode.entries.associateWith { mode ->
                Provider<SyncStrategy> {
                    requestedModes += mode
                    strategy
                }
            },
        )

        RefreshVisibleUsersUseCase(factory)(listOf("user-1", "user-2"))

        assertEquals(listOf(SyncMode.VISIBLE_USERS), requestedModes)
        assertEquals(listOf(listOf("user-1", "user-2")), strategy.received)
    }

    @Test
    fun `the ids the screen is showing reach the strategy unchanged`() = runTest {
        refreshVisibleUsers(users.map { it.id })

        assertEquals(users.map { it.id }, repository.syncUsersRequested)
    }

    @Test
    fun `every id succeeding is counted as refreshed`() = runTest {
        val outcome = refreshVisibleUsers(users.map { it.id })

        assertEquals(RefreshOutcome(refreshed = 3, failed = 0), outcome)
        assertEquals(3, outcome.attempted)
    }

    /**
     * The outcome is returned as the strategy reported it. A use case that summarised it —
     * folding a partial failure into a single "failed", say — would throw away the shortfall
     * the screen renders.
     */
    @Test
    fun `a partial failure reaches the caller with both halves intact`() = runTest {
        repository.syncUsersFailing = setOf("user-2")

        assertEquals(
            RefreshOutcome(refreshed = 2, failed = 1),
            refreshVisibleUsers(users.map { it.id }),
        )
    }

    @Test
    fun `an empty selection is still the visible-users mode, and makes no request`() = runTest {
        val outcome = refreshVisibleUsers(emptyList())

        assertEquals(RefreshOutcome.NOTHING_TO_REFRESH, outcome)
        assertEquals(emptyList<String>(), repository.syncUsersRequested)
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

/** Records what it was asked to sync, so the delegation can be asserted rather than inferred. */
private class RecordingSyncStrategy : SyncStrategy {

    val received = mutableListOf<List<String>>()

    override val mode: SyncMode = SyncMode.VISIBLE_USERS

    override suspend fun sync(userIds: List<String>): RefreshOutcome {
        received += userIds
        return RefreshOutcome.NOTHING_TO_REFRESH
    }
}
