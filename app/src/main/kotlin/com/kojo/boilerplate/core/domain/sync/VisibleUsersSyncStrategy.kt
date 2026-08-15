package com.kojo.boilerplate.core.domain.sync

import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.domain.model.RefreshOutcome
import javax.inject.Inject

/**
 * Re-fetches exactly the users the caller named, all at once.
 *
 * This is the body `RefreshVisibleUsersUseCase` used to hold, unchanged. It moved because the
 * three decisions in it are decisions about *this way of syncing* rather than about refreshing
 * in general — [SyncMode.CURRENT_USER] answers all three differently and would have had to
 * route around them.
 *
 * 1. **An empty selection makes no request.** Nothing on screen means nothing to refresh, and
 *    the fan-out is skipped entirely rather than being handed an empty list to iterate. The
 *    repository would return an empty
 *    [com.kojo.boilerplate.core.coroutines.FanOutResult] either way, so this is not about
 *    correctness — it is about not opening a coroutine scope and a database transaction to
 *    discover that.
 * 2. **Ids are deduped, and the counts are per distinct user.** The same id twice is the same
 *    request twice, and without this "8 refreshed" could mean either eight users or five.
 *    `UserRepositoryImpl.syncUsers` also dedupes, so this is not the only guard — but it is
 *    the one that makes [RefreshOutcome.attempted] equal to the number of users the caller
 *    asked about, which is what the count is read as.
 * 3. **A partial failure is a partial success.** The successes are already written to the
 *    database and the observing list re-renders itself, so they need no return path; the
 *    shortfall is the whole report. Collapsing the fan-out into a single pass/fail would
 *    either throw away the users that did arrive or hide that part of the screen is stale.
 *
 * Cancellation is untouched: `syncUsers` propagates it, and nothing here catches.
 */
class VisibleUsersSyncStrategy @Inject constructor(
    private val userRepository: UserRepository,
) : SyncStrategy {

    override val mode: SyncMode = SyncMode.VISIBLE_USERS

    override suspend fun sync(userIds: List<String>): RefreshOutcome {
        val distinctIds = userIds.distinct()
        if (distinctIds.isEmpty()) return RefreshOutcome.NOTHING_TO_REFRESH

        val result = userRepository.syncUsers(distinctIds)
        return RefreshOutcome(
            refreshed = result.successes.size,
            failed = result.failures.size,
        )
    }
}
