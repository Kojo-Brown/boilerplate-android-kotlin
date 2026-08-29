package com.kojo.boilerplate.core.coroutines

import com.kojo.boilerplate.core.common.safeCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Offline-first reads: one flow that serves the local store immediately, refreshes it from the
 * network, and keeps serving the local store either way.
 *
 * The shape is the one usually called `NetworkBoundResource`. What it is for is a rule the app
 * would otherwise have to keep by hand at every call site: **the local store is the single
 * source of truth, and a network response is only ever a write to it.** A screen reads the
 * store and nothing else, so it renders the same whether the data arrived a second ago or a
 * week ago, and a failed refresh cannot blank it.
 *
 * ## What was here before
 *
 * Two halves that no caller was obliged to connect. `UserRepository.getUser` observes Room and
 * never asks the network; `UserRepository.syncUser` asks the network, writes to Room, and
 * returns a one-shot [Result]. A screen that used only the first showed whatever had been
 * cached, indefinitely — `ObserveUserProfileUseCase` did exactly that, so the profile screen
 * never refreshed at all. A screen that used only the second refreshed and had nowhere to put
 * the result. Joining them is a five-line pipeline with three ways to get it wrong, which is
 * the argument for writing it once.
 *
 * ## The three states, and why all of them carry data
 *
 * [Resource] has no arm without a value, because every emission this builder makes comes from
 * [query] and the store always answers. That is the single-source-of-truth rule expressed in
 * the type: a caller cannot be handed "loading" or "failed" *instead of* the data, only
 * alongside it. The canonical implementations of this pattern make the payload nullable, and
 * the nullability is then load-bearing at every call site — an `if (data != null)` that is
 * really asking "has the store been read yet?", a question this builder answers before it
 * emits anything.
 *
 * The arm describes **the refresh**, not the data:
 *
 * - [Resource.Loading] — the store's current contents; a refresh is in flight.
 * - [Resource.Success] — the store's contents after a refresh that succeeded.
 * - [Resource.Failure] — the store's contents, plus the reason the refresh did not land.
 *
 * ## One lambda for the refresh, not the usual two
 *
 * The canonical form takes `fetch` and `saveFetchResult` separately so the builder can
 * guarantee the network value never reaches the caller. Here [refresh] does both and returns
 * [Unit], which guarantees the same thing — there is no value to leak — and matches what this
 * app already has: `UserRepository.syncUser` fetches *and* commits, because committing is what
 * makes it a repository method rather than an API call. Splitting the two would mean either a
 * no-op `saveFetchResult` at every call site or moving the Room write up out of the layer that
 * owns the database, and the second is a worse trade than the first is a wart.
 *
 * ## A failing [query] is not a [Resource.Failure]
 *
 * Only [refresh] is caught. If [query] itself fails there is no local value, so there is no
 * resource to describe and the flow terminates with the failure — a collector's `catch` is
 * where that belongs. [Resource.Failure] means something narrower and more useful: the store
 * was read, and it may be out of date.
 *
 * ## Freshness lives elsewhere, deliberately
 *
 * There is no `shouldFetch` parameter. This refreshes on every subscription, and *whether that
 * costs a request* is decided one layer down by `CachingUserRepository`, which already holds a
 * freshness window and coalesces concurrent callers onto one request. Adding the same decision
 * here would be a second place to make it and a second place for it to be wrong: a two-pane
 * layout subscribing twice would then have to agree with itself about how old is too old. A
 * per-row age policy would still belong in the repository, next to the column carrying it; the
 * row has since gained a `version`, but deliberately no timestamp — see
 * `docs/conflict-resolution.md`.
 *
 * ## Retrying
 *
 * A resource whose refresh failed stays failed: the outcome describes the fetch *this*
 * subscription made, and no later emission from [query] changes what that fetch did. Retrying
 * means subscribing again, which is `flatMapLatest` over a retry signal in the ViewModel — the
 * shape `HomeViewModel` already uses. Wrapping the [query] flow in
 * [retryWithBackoff] is the other half and covers the other failure: a store read that threw.
 *
 * ```kotlin
 * networkBoundResource(
 *     query = { userRepository.getUser(id).retryWithBackoff() },
 *     refresh = { userRepository.syncUser(id).getOrThrow() },
 * )
 * ```
 *
 * @param query opens a stream over the local store. Called twice per subscription — once to
 *   read the value that accompanies [Resource.Loading], once for the stream that outlives the
 *   refresh — so it must be a cold flow that can be collected more than once, which a Room
 *   `@Query` returning `Flow` is. A [query] that never emits leaves this flow silent rather
 *   than emitting a data-less loading state; that is the price of every arm carrying a value.
 * @param refresh fetches and commits to the store that [query] reads. Its failure becomes
 *   [Resource.Failure]; cancellation stays cancellation and propagates.
 */
fun <T> networkBoundResource(
    query: () -> Flow<T>,
    refresh: suspend () -> Unit,
): Flow<Resource<T>> = flow {
    emit(Resource.Loading(query().first()))

    // safeCall and not a try/catch: it is the one in this codebase that rethrows a
    // CancellationException instead of reporting it. A collector that goes away mid-refresh
    // must cancel, not arrive at Resource.Failure — see docs/structured-concurrency.md.
    val cause = safeCall { refresh() }.exceptionOrNull()

    // The store, from the top, for as long as the collector wants it. Re-reading rather than
    // continuing the first collection is what makes the write that `refresh` just performed
    // visible: `first()` above closed that subscription, and Room delivers the new row to
    // whoever is subscribed when it lands.
    emitAll(
        query().map { value ->
            if (cause == null) Resource.Success(value) else Resource.Failure(value, cause)
        },
    )
}

/**
 * A value from the local store, together with what happened to the refresh that accompanied it.
 *
 * Deliberately not `Result<T>`. `Result` has two arms and this has three, and the third —
 * "here is the data, and the attempt to make it newer failed" — is the entire point of an
 * offline-first read. As a `Result.failure` it loses the data the screen should still be
 * showing; as a `Result.success` it loses the fact that the screen is out of date.
 *
 * Deliberately not nullable in [data] either. See the note on [networkBoundResource].
 */
sealed interface Resource<out T> {

    /** What the local store holds. Present on every arm, because the store is the truth. */
    val data: T

    /** A refresh is in flight. [data] is what the store held when it started. */
    data class Loading<out T>(override val data: T) : Resource<T>

    /** The refresh landed. [data] is the store's contents, which now reflect it. */
    data class Success<out T>(override val data: T) : Resource<T>

    /**
     * The refresh did not land. [data] is the store's contents, which may be out of date, and
     * [cause] is why they were not made current.
     *
     * Not an error state for the screen to render *instead of* the data. Whether a stale row
     * is worth interrupting the user over is a decision the screen makes, and it can only make
     * it because both halves are here.
     */
    data class Failure<out T>(override val data: T, val cause: Throwable) : Resource<T>
}
