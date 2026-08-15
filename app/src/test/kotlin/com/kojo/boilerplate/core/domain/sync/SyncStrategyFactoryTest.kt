package com.kojo.boilerplate.core.domain.sync

import com.kojo.boilerplate.core.data.repository.FakeUserRepository
import com.kojo.boilerplate.core.domain.model.RefreshOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

class SyncStrategyFactoryTest {

    private val repository = FakeUserRepository()

    @Test
    fun `it returns the strategy bound under the requested mode`() {
        val factory = syncStrategyFactoryOver(repository)

        assertInstanceOf(
            VisibleUsersSyncStrategy::class.java,
            factory.create(SyncMode.VISIBLE_USERS),
        )
        assertInstanceOf(
            CurrentUserSyncStrategy::class.java,
            factory.create(SyncMode.CURRENT_USER),
        )
    }

    /**
     * The reason the map holds `Provider`s rather than instances. With two strategies over one
     * repository the difference is invisible; the moment one of them takes a `WorkManager` or
     * a second data source, an eager map would build the machinery of every sync the app knows
     * how to do in order to answer a question about one of them.
     */
    @Test
    fun `resolving one mode constructs only that mode's strategy`() {
        val visibleUsersBuilds = AtomicInteger()
        val currentUserBuilds = AtomicInteger()
        val factory = SyncStrategyFactory(
            mapOf(
                SyncMode.VISIBLE_USERS to Provider<SyncStrategy> {
                    visibleUsersBuilds.incrementAndGet()
                    VisibleUsersSyncStrategy(repository)
                },
                SyncMode.CURRENT_USER to Provider<SyncStrategy> {
                    currentUserBuilds.incrementAndGet()
                    CurrentUserSyncStrategy(repository)
                },
            ),
        )

        factory.create(SyncMode.VISIBLE_USERS)

        assertEquals(1, visibleUsersBuilds.get())
        assertEquals(0, currentUserBuilds.get())
    }

    /**
     * A mode with no binding is the failure Dagger cannot see: the map is assembled from
     * whatever `@IntoMap` methods exist, and a mode nobody wired up produces a map that is
     * simply missing a key. `SyncStrategyModuleContractTest` is what stops that shipping; this
     * is what it looks like if one ever does.
     */
    @Test
    fun `an unbound mode fails loudly and names the mode`() {
        val factory = SyncStrategyFactory(emptyMap())

        val failure = assertThrows(IllegalStateException::class.java) {
            factory.create(SyncMode.CURRENT_USER)
        }

        assertTrue(
            failure.message.orEmpty().contains(SyncMode.CURRENT_USER.name),
            "The message should name the unbound mode, was: ${failure.message}",
        )
    }

    /**
     * The mistake the `mode` property exists to catch. A map key is an annotation on a
     * `@Binds` method in another file; binding the current-user strategy under
     * [SyncMode.VISIBLE_USERS] type-checks, compiles, and produces an app that refreshes the
     * wrong thing. Nothing but this comparison notices.
     */
    @Test
    fun `a strategy bound under the wrong key fails rather than silently syncing the wrong thing`() {
        val factory = SyncStrategyFactory(
            mapOf(
                SyncMode.VISIBLE_USERS to Provider<SyncStrategy> {
                    StubSyncStrategy(SyncMode.CURRENT_USER)
                },
            ),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            factory.create(SyncMode.VISIBLE_USERS)
        }

        assertTrue(
            failure.message.orEmpty().contains(StubSyncStrategy::class.java.name),
            "The message should name the offending strategy, was: ${failure.message}",
        )
    }
}

/** A strategy that does nothing except claim a mode, so the key check can be exercised. */
private class StubSyncStrategy(override val mode: SyncMode) : SyncStrategy {
    override suspend fun sync(userIds: List<String>): RefreshOutcome =
        RefreshOutcome.NOTHING_TO_REFRESH
}
