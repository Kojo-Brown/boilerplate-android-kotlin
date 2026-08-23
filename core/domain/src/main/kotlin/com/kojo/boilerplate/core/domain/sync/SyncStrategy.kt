package com.kojo.boilerplate.core.domain.sync

import com.kojo.boilerplate.core.domain.model.RefreshOutcome

/**
 * One way of pulling users from the network into the local cache.
 *
 * ## Why an interface at all
 *
 * Before this, "refresh" meant exactly one thing — fan out over the ids the screen is
 * showing — and it was written inline in `RefreshVisibleUsersUseCase`. The upcoming Phase 9
 * items each need a *different* one over the same repository: a WorkManager job syncing under
 * constraints, an offline-first read that refreshes what it just served, an idempotent retry
 * of only the ids that did not land. Adding those as branches in one function is the shape
 * that turns into a `when` over a mode with four bodies in it, each reachable only through
 * the callers that happen to pass that mode.
 *
 * As separate implementations behind this interface, each one is constructed only when it is
 * chosen (see `SyncStrategyFactory`), tested on its own, and — the part that matters for a
 * boilerplate — added by writing a class and a binding rather than by editing a function
 * every other strategy also runs through.
 *
 * ## Why the result is [RefreshOutcome] and not the users
 *
 * Every strategy writes what it fetched to the database, and the screens observe the
 * database, so the rows update themselves. What a caller cannot learn from the list is the
 * shortfall, and that is the whole return value. See [RefreshOutcome].
 *
 * ## What an implementation must not do
 *
 * Swallow cancellation. Every strategy here runs inside a `viewModelScope` or a worker, and
 * a `runCatching` around the fetch would report a cancelled refresh as a failed one — an
 * error message for a screen the user has already left. `safeCall` and
 * `mapConcurrentlyCatching` both rethrow it; anything hand-rolled must too. See
 * `docs/structured-concurrency.md`.
 */
interface SyncStrategy {

    /**
     * The mode this strategy implements.
     *
     * Redundant with the map key it is bound under, and deliberately so: the key lives in an
     * annotation on a `@Binds` method in another file, and nothing in Dagger checks that the
     * two agree. Binding [CurrentUserSyncStrategy] under [SyncMode.VISIBLE_USERS] compiles
     * cleanly and produces an app that quietly refreshes the wrong thing.
     * `SyncStrategyFactory` compares the two on every resolution, which is only possible
     * because the strategy states its own answer here.
     */
    val mode: SyncMode

    /**
     * Fetches and caches, and reports how much of it arrived.
     *
     * @param userIds the users the caller is interested in. A strategy whose mode does not
     *   depend on the caller's selection — [SyncMode.CURRENT_USER] is the one today — ignores
     *   this, which is why it is a plain parameter rather than something each implementation
     *   is obliged to consume.
     */
    suspend fun sync(userIds: List<String>): RefreshOutcome
}
