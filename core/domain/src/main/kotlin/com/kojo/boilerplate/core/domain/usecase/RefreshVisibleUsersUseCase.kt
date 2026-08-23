package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.domain.model.RefreshOutcome
import com.kojo.boilerplate.core.domain.sync.SyncMode
import com.kojo.boilerplate.core.domain.sync.SyncStrategy
import com.kojo.boilerplate.core.domain.sync.SyncStrategyFactory
import javax.inject.Inject

/**
 * Re-fetches the users a screen is showing and reports what did not arrive.
 *
 * The other half of finding 1 in `docs/solid.md`. `HomeViewModel.refresh()` held three
 * decisions that are application policy rather than presentation — what a partial failure
 * means, how the outcome is counted, and what an empty selection does — inside a class that
 * also owns search debouncing, the offline banner and the in-flight lock. They are out of the
 * `ViewModel` now, where they can be exercised without a `Dispatchers.Main` substitute or a
 * `stateIn` subscriber.
 *
 * ### What this owns after the strategies landed
 *
 * The three decisions themselves moved one level down, into
 * [com.kojo.boilerplate.core.domain.sync.VisibleUsersSyncStrategy], because they are decisions
 * about *that way of syncing*: [SyncMode.CURRENT_USER] answers all three differently. What is
 * left here is not a hop — it is the choice of mode, which is policy in its own right:
 *
 * > **A user-initiated refresh from a list screen covers what that screen is showing.**
 *
 * That is the decision a second list screen would otherwise make again, and could get a
 * different answer to — the test `docs/clean-architecture.md` sets for whether something
 * belongs in this layer. Reaching for a cheaper mode because the connection is metered, or a
 * broader one because a worker rather than a person asked, is the same decision made
 * differently, and this is where it would be made. A caller injecting
 * [SyncStrategyFactory] directly would be re-deciding it at every call site.
 *
 * ### What stays with the caller
 *
 * *Which* users are visible. That is a genuinely presentational question — the ids come from
 * whatever the screen currently shows, so a refresh under an active search covers what the
 * user is looking at rather than the whole table — and a use case that reached for them
 * itself would have to know about search state to get it right.
 *
 * Cancellation is untouched: [SyncStrategy.sync] propagates it, and nothing here catches.
 */
class RefreshVisibleUsersUseCase @Inject constructor(
    private val syncStrategies: SyncStrategyFactory,
) {

    suspend operator fun invoke(visibleUserIds: List<String>): RefreshOutcome =
        syncStrategies.create(SyncMode.VISIBLE_USERS).sync(visibleUserIds)
}
