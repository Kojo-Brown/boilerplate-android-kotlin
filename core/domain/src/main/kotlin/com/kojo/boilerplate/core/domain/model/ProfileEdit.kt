package com.kojo.boilerplate.core.domain.model

/**
 * A profile edit in the only form anything else in this layer ever sees it: normalised.
 *
 * ## Why the constructor is private
 *
 * Because "normalise first" is the rule the whole edit turns on, and in the green step that rule
 * lived in the order of two statements inside one function — which is a rule the next edit to
 * that function can reorder with nothing to stop it. [normalising] is the only way to obtain one
 * of these, so an un-normalised edit is not something that can exist to be validated, compared
 * or stored. The invariant stops being remembered and starts being structural.
 *
 * ## What normalising means, and what skipping it costs
 *
 * The display name is trimmed, and any run of whitespace inside it becomes a single space. The
 * avatar URL is trimmed, and blank becomes `null`: an empty text field and no avatar are the
 * same intention, and `avatarUrl` is nullable so that intention has exactly one representation
 * rather than two every reader has to remember to check for.
 *
 * The cost of skipping it is specific, silent and permanent. `UserRepository.saveUser` records
 * *which* fields this client changed, and a local edit only ever adds to that set — so a display
 * name differing from the stored one by a trailing space is a real change as far as the write
 * path can tell. It marks `DISPLAY_NAME` locally owned, and from then on `MergeConflictResolver`
 * discards whatever the server has for that field, for the life of the row. Nothing fails and
 * nothing is logged; the field simply stops syncing. See `docs/conflict-resolution.md`.
 *
 * ## Not a `data class`
 *
 * It has no `equals` because nothing compares two edits — what gets compared is the [User] that
 * [appliedTo] produces, against the row already stored. A `data class` here would also publish a
 * `copy` that bypasses [normalising], which is the one thing this type exists to prevent.
 */
class ProfileEdit private constructor(
    val displayName: String,
    val avatarUrl: String?,
) {

    /**
     * [user] carrying this edit's fields, and every other field — the id, the email — exactly as
     * it was.
     */
    fun appliedTo(user: User): User = user.copy(displayName = displayName, avatarUrl = avatarUrl)

    companion object {

        private val WHITESPACE_RUN = Regex("\\s+")

        /** The only way to make a [ProfileEdit]: from raw input, normalised on the way in. */
        fun normalising(displayName: String, avatarUrl: String?): ProfileEdit = ProfileEdit(
            displayName = displayName.trim().replace(WHITESPACE_RUN, " "),
            avatarUrl = avatarUrl?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}
