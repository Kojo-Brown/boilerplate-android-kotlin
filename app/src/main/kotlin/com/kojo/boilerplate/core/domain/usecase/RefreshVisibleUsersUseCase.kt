package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.domain.model.RefreshOutcome
import javax.inject.Inject

/**
 * Re-fetches a set of users from the network and reports what did not arrive.
 *
 * The other half of finding 1 in `docs/solid.md`. `HomeViewModel.refresh()` held three
 * decisions that are application policy rather than presentation — what a partial failure
 * means, how the outcome is counted, and what an empty selection does — inside a class that
 * also owns search debouncing, the offline banner and the in-flight lock. They are here now,
 * where they can be exercised without a `ViewModel`, a `Dispatchers.Main` substitute or a
 * `stateIn` subscriber.
 *
 * ### What stays with the caller
 *
 * *Which* users are visible. That is a genuinely presentational question — the ids come from
 * whatever the screen currently shows, so a refresh under an active search covers what the
 * user is looking at rather than the whole table — and a use case that reached for them
 * itself would have to know about search state to get it right.
 *
 * ### What is policy, and lives here
 *
 * 1. **An empty selection makes no request.** Nothing on screen means nothing to refresh, and
 *    the fan-out is skipped entirely rather than being handed an empty list to iterate. The
 *    repository would return an empty [com.kojo.boilerplate.core.coroutines.FanOutResult]
 *    either way, so this is not about correctness — it is about not opening a coroutine scope
 *    and a database transaction to discover that.
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
class RefreshVisibleUsersUseCase @Inject constructor(
    private val userRepository: UserRepository,
) {

    suspend operator fun invoke(visibleUserIds: List<String>): RefreshOutcome {
        val distinctIds = visibleUserIds.distinct()
        if (distinctIds.isEmpty()) return RefreshOutcome.NOTHING_TO_REFRESH

        val result = userRepository.syncUsers(distinctIds)
        return RefreshOutcome(
            refreshed = result.successes.size,
            failed = result.failures.size,
        )
    }
}
