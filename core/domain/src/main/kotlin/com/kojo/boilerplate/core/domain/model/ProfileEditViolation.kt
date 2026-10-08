package com.kojo.boilerplate.core.domain.model

/**
 * A rule a profile edit can break.
 *
 * ## Why each entry carries its own check
 *
 * The alternative — an enum of bare names, and an `if` per name somewhere else — lets a rule be
 * *declared* without ever being *applied*, and that failure has no symptom at all: the entry
 * exists, a screen's exhaustive `when` renders a message for it, and nothing ever produces it.
 * This repository has shipped two of exactly that shape (a keep rule naming a package that never
 * existed, a baseline-profile rule matching no class in the APK) and both were expensive
 * precisely because they read as working code. [brokenBy] walks `entries`, so a rule that is
 * added is a rule that runs, and `EditUserProfileUseCaseTest` holds every entry to being
 * reachable from some input.
 *
 * It is the shape [com.kojo.boilerplate.core.domain.sync.conflict.UserField] already uses, for
 * the same reason: the entry and its behaviour are one declaration, so they cannot drift apart.
 *
 * The messages are not here. They are localised and they name the field the person is looking at,
 * which makes them presentation — and an exhaustive `when` over these in a screen is what stops a
 * rule added here from going unrendered.
 */
enum class ProfileEditViolation {

    /** The display name is empty, or was nothing but whitespace. */
    DISPLAY_NAME_BLANK {
        override fun isBrokenBy(edit: ProfileEdit): Boolean = edit.displayName.isEmpty()
    },

    /** The display name is longer than [MAX_DISPLAY_NAME_LENGTH]. */
    DISPLAY_NAME_TOO_LONG {
        override fun isBrokenBy(edit: ProfileEdit): Boolean =
            edit.displayName.length > MAX_DISPLAY_NAME_LENGTH
    },

    /**
     * The avatar URL is not an `https://` URL.
     *
     * A prefix test and nothing more, which is the whole claim: it rejects `http://`, a bare host
     * and a `file://` path. It does not assert that the URL resolves, that it points at an image,
     * or even that it parses — the first two need the network, and the third belongs to whatever
     * eventually loads it.
     *
     * `== false` rather than `!`: a `null` avatar URL means no avatar, which breaks no rule.
     */
    AVATAR_URL_NOT_HTTPS {
        override fun isBrokenBy(edit: ProfileEdit): Boolean =
            edit.avatarUrl?.startsWith(HTTPS_SCHEME) == false
    },
    ;

    /**
     * Whether [edit] breaks this rule.
     *
     * Takes a [ProfileEdit] rather than the raw fields, so every rule is necessarily measured
     * against the normalised edit: a length limit applied to un-trimmed input is a limit on how
     * carelessly the name was typed.
     */
    abstract fun isBrokenBy(edit: ProfileEdit): Boolean

    companion object {

        /**
         * The longest display name this app will store, measured after normalisation.
         *
         * Public because an edit screen needs it for a character counter, and a screen carrying
         * its own copy is the thing that ends up disagreeing with this one.
         */
        const val MAX_DISPLAY_NAME_LENGTH = 50

        /**
         * Every rule [edit] breaks, in declaration order.
         *
         * All of them rather than the first one: a form that reports a single error per
         * submission makes the person submit once per mistake, and the order of the checks — an
         * implementation detail — decides which mistake they hear about first.
         */
        fun brokenBy(edit: ProfileEdit): Set<ProfileEditViolation> =
            entries.filterTo(LinkedHashSet()) { it.isBrokenBy(edit) }
    }
}

/**
 * Declared at file scope rather than in the companion because an enum entry's body cannot reach a
 * private companion member.
 */
private const val HTTPS_SCHEME = "https://"
