package com.kojo.boilerplate.core.domain.model

/**
 * What observing one user's profile currently yields.
 *
 * This type exists to carry a *product* decision out of the presentation layer: a user id that
 * resolves to no row is a [Missing] profile, not an empty one. `docs/solid.md` finding 1 is
 * that the decision was written down twice — verbatim, in `ProfileViewModel` and
 * `ProfileDetailPaneViewModel` — in the layer furthest from the data. Naming the three
 * outcomes here is what lets both screens share one answer and lets a third caller inherit it
 * rather than copy it.
 *
 * Deliberately not `Result<User?>`. `Result` has two arms and this has three, and collapsing
 * [Missing] into either of them is the mistake the finding is about: as a success it becomes
 * an empty state, as a failure it becomes indistinguishable from a dropped connection, and
 * only one of those is worth offering a retry button for.
 *
 * Deliberately without a `Loading` arm, too. Loading is the absence of an emission, which
 * `stateIn`'s initial value already expresses at the point where a screen needs it; putting
 * it here would be a state this flow can never actually emit.
 */
sealed interface UserProfile {

    /** The row exists. */
    data class Loaded(val user: User) : UserProfile

    /** The query succeeded and there is no such user. [userId] is the one that was asked for. */
    data class Missing(val userId: String) : UserProfile

    /**
     * The query itself failed, after [com.kojo.boilerplate.core.coroutines.retryWithBackoff]
     * had already exhausted its attempts on the transient reading of [cause].
     */
    data class Unavailable(val cause: Throwable) : UserProfile
}
