package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.coroutines.retryWithBackoff
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Observes one user and reports whether their profile is loaded, missing, or unavailable.
 *
 * This is finding 1 of `docs/solid.md` extracted. `ProfileViewModel` and
 * `ProfileDetailPaneViewModel` held this pipeline — retry, dedupe, null-check, catch — in two
 * verbatim copies that differed only in where the id came from: a `SavedStateHandle` route for
 * the full screen, an `@Assisted` parameter for the two-pane detail. Neither of those is a
 * reason for the *policy* to differ, so the policy is here and the difference stays where it
 * belongs, in how each screen obtains its id.
 *
 * ### Order of operators
 *
 * `retryWithBackoff` then `distinctUntilChanged` then `map`, and each pair has a wrong way
 * round that still compiles:
 *
 * - **Retry before dedupe.** Retrying resubscribes, and a resubscribed Room query replays
 *   every row it has already delivered. Deduping downstream of the retry drops that replay;
 *   deduping upstream of it would let a byte-identical list through on every recovery.
 * - **Dedupe before map.** `distinctUntilChanged` compares [User], which is the cheaper
 *   comparison and the one that is actually meaningful — mapping first would allocate a
 *   [UserProfile] per duplicate emission only to discard it.
 * - **Catch last.** It has to be downstream of the retry, or it would swallow the failure
 *   before `retryWhen` ever saw it and no retry would happen at all.
 *
 * `catch` rethrows a [kotlinx.coroutines.CancellationException] belonging to the collecting
 * job, so a screen the user has left cancels rather than emitting [UserProfile.Unavailable] on
 * its way out.
 *
 * ### No dispatcher, deliberately
 *
 * The repository confines its own I/O and row mapping, so what is left here is one null check
 * and one allocation per emission. That is cheaper than the thread hand-off a `flowOn` would
 * add to pay for it — see `docs/dispatchers.md`. A dispatcher belongs here only if this
 * transform grows real work.
 */
class ObserveUserProfileUseCase @Inject constructor(
    private val userRepository: UserRepository,
) {

    operator fun invoke(userId: String): Flow<UserProfile> = userRepository.getUser(userId)
        .retryWithBackoff()
        .distinctUntilChanged()
        .map<User?, UserProfile> { user ->
            if (user != null) UserProfile.Loaded(user) else UserProfile.Missing(userId)
        }
        .catch { throwable -> emit(UserProfile.Unavailable(throwable)) }
}
