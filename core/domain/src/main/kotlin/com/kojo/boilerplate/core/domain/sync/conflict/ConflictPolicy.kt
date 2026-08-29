package com.kojo.boilerplate.core.domain.sync.conflict

/**
 * How this app reconciles a fetched row with a local one that has moved on without it.
 *
 * A closed enum, and the `when` in `ConflictResolverModule` is exhaustive over it, so adding a
 * policy without choosing what to do about it does not compile. That is deliberately a
 * stronger check than the one [com.kojo.boilerplate.core.domain.sync.SyncMode] gets: sync
 * modes are resolved by map lookup at runtime, so a missing one can only be caught by a test,
 * whereas the policy is picked once and the compiler can see the whole choice.
 *
 * ## Why the app picks one rather than choosing per call
 *
 * Which policy is right is a property of the *data*, not of the caller. If two clients editing
 * a display name concurrently should both keep their edit until the server says otherwise,
 * that is true of a background sync and a pull-to-refresh alike. A per-call choice would mean
 * a row's history depended on which screen happened to refresh it, which is how a store ends
 * up in a state no single policy can explain.
 */
enum class ConflictPolicy {

    /**
     * The newer version replaces the older one, whole.
     *
     * The simplest thing that is still correct about *ordering*: a response that arrives late
     * carrying an older version does not clobber a newer row. What it gives up is the local
     * edit — a user who renamed themselves offline sees the rename disappear the moment the
     * server sends any newer version of that row, even one whose change was to an unrelated
     * field.
     *
     * Right when the server is the only writer that matters: a feed, a catalogue, a read-only
     * profile fetched from an identity provider. Wrong wherever the user can type.
     */
    LAST_WRITE_WINS,

    /**
     * The newer version replaces the older one field by field, except where this client holds
     * an unpushed edit.
     *
     * The server's row becomes the new base, and the fields in
     * [VersionedUser.locallyChanged] are re-applied on top of it, so a change to the display
     * name survives a server update to the avatar. A local edit that the server has since
     * caught up with stops being pending rather than being re-applied forever.
     *
     * The cost is that a divergence can persist: a field this client changed keeps beating the
     * server's value on every sync until the edit is pushed and acknowledged. That is the
     * correct behaviour for an unsaved edit and an unbounded one for an edit that will never
     * be pushed, which is the argument for an outbox — and why this policy is stated in terms
     * of "unpushed", not "different".
     */
    MERGE,
    ;

    companion object {

        /**
         * What the app is wired to use, resolved in `ConflictResolverModule`.
         *
         * [MERGE], because the one row this app writes locally is the signed-in user's own
         * profile and the one thing a user does to it is edit a field. Under
         * [LAST_WRITE_WINS] the six-hourly background sync would be enough to silently undo
         * that edit, with no request having failed and nothing to show the user.
         */
        val DEFAULT: ConflictPolicy = MERGE
    }
}
