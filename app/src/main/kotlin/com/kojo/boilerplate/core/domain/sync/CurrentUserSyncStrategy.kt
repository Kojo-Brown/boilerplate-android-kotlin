package com.kojo.boilerplate.core.domain.sync

import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.domain.model.RefreshOutcome
import javax.inject.Inject

/**
 * Re-fetches the signed-in user, and nothing else.
 *
 * The cheapest sync the app can perform: one request, one row, whatever the screen is showing.
 * That is the point of it — a caller on a metered connection, or a background worker that
 * woke up to keep the account current rather than to serve a screen, wants the smallest
 * useful refresh rather than the most complete one.
 *
 * It is also the first caller `UserRepository.syncCurrentUser` has ever had. `docs/solid.md`
 * records that method as dead weight on the interface — an interface-segregation finding
 * counted in callers — and this is half of that finding repaid.
 *
 * **This strategy has no production caller yet**, which is the honest gap to carry forward
 * rather than to hide: nothing in the UI selects [SyncMode.CURRENT_USER] today.
 * `WorkManager background sync` is the next item in Phase 9 and is its intended one. The
 * strategy is written now because the seam it plugs into is what this item is *for*, and a
 * Strategy with a single implementation demonstrates nothing — but "written and tested, not
 * yet called" is what it is, and `docs/sync-strategy.md` says so too.
 */
class CurrentUserSyncStrategy @Inject constructor(
    private val userRepository: UserRepository,
) : SyncStrategy {

    override val mode: SyncMode = SyncMode.CURRENT_USER

    /**
     * @param userIds ignored. Which users are on screen has no bearing on who is signed in,
     *   and reading them here to "also" refresh them would make this the visible-users
     *   strategy with an extra request attached.
     */
    override suspend fun sync(userIds: List<String>): RefreshOutcome =
        userRepository.syncCurrentUser().fold(
            onSuccess = { RefreshOutcome(refreshed = 1, failed = 0) },
            onFailure = { RefreshOutcome(refreshed = 0, failed = 1) },
        )
}
