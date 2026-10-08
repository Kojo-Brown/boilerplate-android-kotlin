package com.kojo.boilerplate.core.domain.model

/**
 * What happened to one attempt at editing a profile locally.
 *
 * Four arms, because a screen that submits an edit has four genuinely different things to do
 * next, and three of them are not errors:
 *
 * - [Saved] — tell the person it is saved, and let the sync carry it.
 * - [Unchanged] — say nothing. Nothing was written, so nothing is pending, so there is no
 *   "saving…" to show and no failure to report either.
 * - [Rejected] — put the violations next to the fields they belong to. The person can fix this.
 * - [UnknownUser] — the person cannot fix this and neither can the screen. See
 *   [com.kojo.boilerplate.core.domain.usecase.EditUserProfileUseCase] for why it is a distinct
 *   answer rather than a failure to save.
 *
 * Deliberately not `Result<User>`. Two of the four arms are ordinary outcomes rather than
 * failures, and `Result` can only say "not a success" about either — which would leave the
 * screen to tell an unchanged form apart from a blank display name by reading an exception
 * type. That is the mistake [UserProfile] records for the read side, in the same words.
 */
sealed interface ProfileEditOutcome {

    /** The edit changed something, was valid, and has been written. [user] is the stored row. */
    data class Saved(val user: User) : ProfileEditOutcome

    /**
     * The edit was valid and, once normalised, asked for exactly what is already stored. The
     * store was not written to; [user] is the row that was already there.
     */
    data class Unchanged(val user: User) : ProfileEditOutcome

    /**
     * The edit was not valid and nothing was written. [violations] is every rule the edit
     * broke, not the first one — see
     * [com.kojo.boilerplate.core.domain.usecase.EditUserProfileUseCase].
     */
    data class Rejected(val violations: Set<ProfileEditViolation>) : ProfileEditOutcome

    /**
     * There is no locally held row with this id, so there was nothing to edit and nothing was
     * written.
     */
    data class UnknownUser(val userId: String) : ProfileEditOutcome
}

/**
 * A rule a profile edit can break.
 *
 * An enum rather than messages, because the strings belong to the presentation layer — they are
 * localised and they name the field the person is looking at — and because an exhaustive `when`
 * over these in a screen is what stops a rule added here from being silently unrendered.
 */
enum class ProfileEditViolation {

    /** The display name is empty, or is nothing but whitespace. */
    DISPLAY_NAME_BLANK,

    /** The display name is longer than [MAX_DISPLAY_NAME_LENGTH] once normalised. */
    DISPLAY_NAME_TOO_LONG,

    /**
     * The avatar URL is not an `https://` URL.
     *
     * A prefix check and nothing more, which is the whole claim: this rejects `http://`, a bare
     * host and a `file://` path. It does not assert that the URL resolves, that it points at an
     * image, or that it parses — the first two need the network and the third belongs to
     * whatever eventually loads it.
     */
    AVATAR_URL_NOT_HTTPS,
    ;

    companion object {

        /**
         * The longest display name this app will store, measured after normalisation.
         *
         * Public because the edit screen needs it for a character counter, and a screen that
         * carried its own copy would be the thing that disagrees with this one.
         */
        const val MAX_DISPLAY_NAME_LENGTH = 50
    }
}
