package com.kojo.boilerplate.core.domain.sync.conflict

/**
 * What to do with a fetched row, once a [ConflictResolver] has compared it to what is stored.
 *
 * ## Why "do nothing" is one of the answers
 *
 * The obvious signature for a resolver is `(local, remote) -> VersionedUser`: hand back the row
 * that should be stored and let the caller write it. It cannot express the most common outcome
 * — that the fetched row is stale or identical and there is nothing to write — except by
 * returning the local row and having the caller write it back over itself. Room takes that
 * literally: an upsert of a byte-identical row is still a write, and it invalidates the
 * queries observing that table, so every screen showing the user re-emits. On a list refresh
 * that fans out over twenty ids, nineteen of which came back unchanged, that is nineteen
 * spurious recompositions per refresh.
 *
 * [KeepLocal] is therefore not an optimisation the caller may skip. It is the difference
 * between "the store already says this" and "the store now says this".
 */
sealed interface ConflictResolution {

    /**
     * The stored row stands. Nothing is written.
     *
     * Covers three cases that are worth naming even though they take the same action: the
     * fetched row is older than what is stored, it is identical to what is stored, or it lost
     * to an unpushed local edit.
     */
    data object KeepLocal : ConflictResolution

    /**
     * [record] replaces what is stored.
     *
     * Not necessarily the fetched row: under [ConflictPolicy.MERGE] it is the fetched row with
     * this client's unpushed fields laid back over it, which is a value neither side held.
     */
    data class Write(val record: VersionedUser) : ConflictResolution
}
