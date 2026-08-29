package com.kojo.boilerplate.core.domain.sync.conflict

import com.kojo.boilerplate.core.domain.model.User

/**
 * A field of [User] that a local edit can change, and that a sync can therefore disagree about.
 *
 * ## Why this exists rather than a `Boolean` on the row
 *
 * "This row has unsaved local changes" is enough for [ConflictPolicy.LAST_WRITE_WINS], which
 * throws the local side away wholesale and never asks which parts of it were local. It is not
 * enough for [ConflictPolicy.MERGE]: merging means keeping the fields the user changed and
 * taking the server's value for every other one, and a single flag cannot say which is which.
 * Given only "dirty", a merge has to either keep the whole local row — which is
 * last-write-wins with the winner reversed — or keep none of it, which is last-write-wins.
 * The set is what makes the two policies actually differ.
 *
 * ## Why not a three-way merge against a stored base
 *
 * The textbook merge keeps the last server-known row alongside the working one and diffs both
 * against it. That is strictly more information — it can tell "the user set the name to X"
 * apart from "the user set the name to X and the server independently set it to X" — and it
 * costs a full duplicate of every row, forever, to answer a question that only matters while
 * an edit is unpushed. The dirty set is the cheap approximation: one short column, and the
 * case it gets wrong (both sides made the same change) resolves to the same value anyway.
 *
 * ## [id] is absent on purpose
 *
 * It is the primary key. A row whose id changed is a different row, not a conflicted one, and
 * every resolver here is only ever called with a local and a remote record that already agree
 * on it.
 */
enum class UserField {

    DISPLAY_NAME {
        override fun differs(left: User, right: User): Boolean = left.displayName != right.displayName

        override fun copyInto(target: User, source: User): User =
            target.copy(displayName = source.displayName)
    },

    EMAIL {
        override fun differs(left: User, right: User): Boolean = left.email != right.email

        override fun copyInto(target: User, source: User): User =
            target.copy(email = source.email)
    },

    AVATAR_URL {
        override fun differs(left: User, right: User): Boolean = left.avatarUrl != right.avatarUrl

        override fun copyInto(target: User, source: User): User =
            target.copy(avatarUrl = source.avatarUrl)
    },
    ;

    /** Whether [left] and [right] hold different values for this field. */
    abstract fun differs(left: User, right: User): Boolean

    /** [target] with this one field taken from [source]. Every other field is [target]'s. */
    abstract fun copyInto(target: User, source: User): User

    companion object {

        /**
         * The fields on which [left] and [right] disagree.
         *
         * Ordered by declaration, and a [LinkedHashSet] rather than an `EnumSet`, so that the
         * set a row is persisted with reads the same on the way out as it did going in —
         * `UserFieldSetConverter` in `:data` joins it to a string, and a stable order is what
         * keeps two equal sets from producing two different column values.
         */
        fun changedBetween(left: User, right: User): Set<UserField> =
            entries.filterTo(LinkedHashSet()) { it.differs(left, right) }
    }
}
