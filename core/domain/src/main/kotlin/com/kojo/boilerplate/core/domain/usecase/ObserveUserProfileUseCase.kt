package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.coroutines.Resource
import com.kojo.boilerplate.core.coroutines.networkBoundResource
import com.kojo.boilerplate.core.coroutines.retryWithBackoff
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.model.UserProfile
import com.kojo.boilerplate.core.domain.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull

/**
 * Observes one user, offline-first: the cached row immediately, a network refresh behind it,
 * and the database as the only thing either screen ever renders from.
 *
 * This is finding 1 of `docs/solid.md` extracted. `ProfileViewModel` and
 * `ProfileDetailPaneViewModel` held this pipeline — retry, dedupe, null-check, catch — in two
 * verbatim copies that differed only in where the id came from: a `SavedStateHandle` route for
 * the full screen, an `@Assisted` parameter for the two-pane detail. Neither of those is a
 * reason for the *policy* to differ, so the policy is here and the difference stays where it
 * belongs, in how each screen obtains its id.
 *
 * ### The refresh, which did not used to happen
 *
 * Until this became a [networkBoundResource] the profile screen never asked the network for
 * anything. It observed `getUser`, which observes Room, and Room only ever held what some
 * *other* screen's sync had put there — so a user opened from a deep link, or one whose row was
 * written weeks ago by a list refresh, was rendered from that row indefinitely. The two halves
 * existed and nothing joined them; `docs/offline-first.md` is the argument for joining them
 * here rather than in either ViewModel.
 *
 * `syncUser` and not the API directly: it is the repository's contract to fetch *and* commit,
 * and going through the injected `UserRepository` means the refresh inherits the decorator
 * stack — `RetryingUserRepository`'s backoff, `CachingUserRepository`'s 30-second freshness
 * window and its coalescing of concurrent callers, `TelemetryUserRepository`'s timing. That
 * last one is what makes a two-pane layout cost one request rather than two, and it is why
 * this use case does not carry a freshness policy of its own.
 *
 * `getOrThrow()` turns the repository's [Result] back into a throw so that
 * [networkBoundResource] can catch it and label the store's contents [Resource.Failure]. The
 * round trip is deliberate: the builder is generic over stores, and a throw is the only failure
 * channel every `refresh` shares.
 *
 * ### Mapping three resource states onto three profile states
 *
 * [UserProfile] still has no `Loading` arm, and still should not: loading is the absence of an
 * emission, which is what `stateIn`'s initial value already expresses. That is exactly what
 * [Resource.Loading] with no cached row becomes here — nothing, dropped by [mapNotNull]. With a
 * cached row it is [UserProfile.Loaded], because a row that is on its way to being refreshed is
 * still a row the screen should be showing.
 *
 * [Resource.Failure] is the decision worth stating: **a failed refresh over a cached row is
 * [UserProfile.Loaded], not [UserProfile.Unavailable]**. Blanking a profile the app already has
 * because the network went away is the failure mode offline-first exists to remove, and it is
 * strictly what the old pipeline did too — it never refreshed, so it never had a refresh
 * failure to render. [UserProfile.Unavailable] is kept for the case where there is genuinely
 * nothing to show: the refresh failed *and* the store is empty.
 *
 * Known gap, and a UI one rather than a policy one: a screen showing a cached row after a
 * failed refresh gets no signal that it is stale, because [UserProfile] has nowhere to put one.
 * `HomeUiState.isOffline` is how the list screen says it; giving the profile screen the
 * equivalent belongs with the Phase 10 UI items and needs a fourth arm here.
 *
 * ### Order of operators
 *
 * - **Retry inside [networkBoundResource]'s `query`, not around the resource.** What
 *   `retryWithBackoff` fixes is a *store read* that threw, and resubscribing the resource would
 *   re-run the refresh as well — a second network request to recover from a database error.
 *   Keeping it inside the query is also what preserves the old behaviour exactly: the retry
 *   covers what it always covered.
 * - **Dedupe after the map, which is where it moved to.** It used to sit on the [User] before
 *   the mapping, on the argument that comparing a row is cheaper than allocating a
 *   [UserProfile] for a duplicate. That argument is still true and is now outweighed: the same
 *   unchanged row arrives under two different arms — once as [Resource.Loading] while the
 *   refresh is in flight, once as [Resource.Success] when it lands — so a dedupe upstream of
 *   the mapping compares two values that differ and lets both through, and the screen sees
 *   `Loaded(alice)` twice. Comparing the profile is the comparison that answers "did anything
 *   the user can see change?".
 * - **Catch last.** It has to be downstream of the retry, or it would swallow the failure
 *   before `retryWhen` ever saw it and no retry would happen at all. It now catches one thing
 *   only — a store read that failed every attempt — because a failed *refresh* arrives as a
 *   value rather than a throw.
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

    operator fun invoke(userId: String): Flow<UserProfile> = networkBoundResource(
        query = { userRepository.getUser(userId).retryWithBackoff() },
        refresh = { userRepository.syncUser(userId).getOrThrow() },
    )
        .mapNotNull { resource -> resource.toProfile(userId) }
        .distinctUntilChanged()
        .catch { throwable -> emit(UserProfile.Unavailable(throwable)) }

    /**
     * `null` means "say nothing yet", and is reachable from one place only: a refresh in flight
     * over a store that has no such row. Every other combination has something to tell the
     * screen.
     */
    private fun Resource<User?>.toProfile(userId: String): UserProfile? = when (this) {
        is Resource.Loading -> data?.let(UserProfile::Loaded)
        is Resource.Success -> data?.let(UserProfile::Loaded) ?: UserProfile.Missing(userId)
        is Resource.Failure -> data?.let(UserProfile::Loaded) ?: UserProfile.Unavailable(cause)
    }
}
