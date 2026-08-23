package com.kojo.boilerplate.core.domain.sync

/**
 * Which users a sync covers.
 *
 * This is the map key the [SyncStrategy] implementations are bound under — see
 * `SyncStrategyModule` — so it is deliberately a closed enum rather than a `String` or a
 * `KClass`. A closed set is what lets `SyncStrategyModuleContractTest` assert that *every*
 * mode has a binding: with an open key type there would be no roster to compare against, and
 * the first mode that shipped without a binding would be found by a user rather than by CI.
 *
 * A mode names *what to fetch*, not *when* or *why*. "Because the user tapped refresh" and
 * "because a background worker woke up" are the caller's business — both can ask for the same
 * mode, and the strategy behind it does not change because the trigger did.
 */
enum class SyncMode {

    /**
     * The users the caller names — in practice whatever a list screen is currently showing.
     *
     * The set is the caller's because it is a presentational question: a refresh under an
     * active search covers what the user is looking at rather than the whole table.
     */
    VISIBLE_USERS,

    /**
     * The signed-in user, and nothing else.
     *
     * One request whatever the screen holds, which is what makes it the mode a constrained
     * caller can afford.
     */
    CURRENT_USER,
}
