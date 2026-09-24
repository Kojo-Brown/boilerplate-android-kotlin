package com.kojo.boilerplate.core.security.integrity

/**
 * Every `StandardIntegrityErrorCode` Play can report, and what this app does about each.
 *
 * ### Why the numbers are written here rather than read from the library
 *
 * `com.google.android.play.core.integrity.model.StandardIntegrityErrorCode` is an Android
 * artifact from Google Maven, so a `when` over its constants can only be compiled — and
 * therefore only be tested — with the whole Android toolchain present. This mapping is the only
 * real decision in [PlayIntegrityAttestation], and it is the one that would otherwise be
 * verifiable exclusively by rooting a phone, clearing Play Store data, and turning off the
 * network in the right order. Written as ordinary Kotlin over an `Int`, it is seventeen
 * assertions in a unit test.
 *
 * The duplication that buys is then closed from the other side:
 * `PlayIntegrityErrorCodeContractTest` reflects over the real `StandardIntegrityErrorCode` and
 * fails if any constant here disagrees with the library's, or if the library declares one this
 * enum has never heard of. So a Play Integrity upgrade that adds a code is a red test naming it,
 * rather than a new code quietly falling into the `else` branch forever.
 *
 * @property code the value Play reports in `StandardIntegrityException.getErrorCode()`.
 * @property failure what the app does about it. See [AttestationFailure] for the outcomes
 *   and why seventeen codes reduce to six of them.
 */
internal enum class PlayIntegrityErrorCode(val code: Int, val failure: AttestationFailure) {

    /** Play reports success through this rather than through an exception; never seen here. */
    NO_ERROR(0, AttestationFailure.TRANSIENT),

    /** The installed Play Store is too old to carry the Integrity API at all. */
    API_NOT_AVAILABLE(-1, AttestationFailure.PLAY_UNAVAILABLE),

    /** No Play Store on this device. */
    PLAY_STORE_NOT_FOUND(-2, AttestationFailure.PLAY_UNAVAILABLE),

    /** The device could not reach Google. */
    NETWORK_ERROR(-3, AttestationFailure.TRANSIENT),

    /** Play does not have this app installed — a sideload, or an install it cannot see. */
    APP_NOT_INSTALLED(-5, AttestationFailure.PLAY_UNAVAILABLE),

    /** No Play services on this device. */
    PLAY_SERVICES_NOT_FOUND(-6, AttestationFailure.PLAY_UNAVAILABLE),

    /** The calling UID is not the one Play has for this package. */
    APP_UID_MISMATCH(-7, AttestationFailure.PLAY_UNAVAILABLE),

    /** This app has asked for too many tokens too quickly. */
    TOO_MANY_REQUESTS(-8, AttestationFailure.RATE_LIMITED),

    /** The Play service would not bind. Usually Play services being updated underneath. */
    CANNOT_BIND_TO_SERVICE(-9, AttestationFailure.TRANSIENT),

    /** Google's own backend was unavailable. */
    GOOGLE_SERVER_UNAVAILABLE(-12, AttestationFailure.TRANSIENT),

    /** The Play Store is installed but predates the standard request API. */
    PLAY_STORE_VERSION_OUTDATED(-14, AttestationFailure.PLAY_UNAVAILABLE),

    /** Play services are installed but predate the standard request API. */
    PLAY_SERVICES_VERSION_OUTDATED(-15, AttestationFailure.PLAY_UNAVAILABLE),

    /** The number in `gradle/play-integrity.properties` is not this app's Cloud project. */
    CLOUD_PROJECT_NUMBER_IS_INVALID(-16, AttestationFailure.MISCONFIGURED),

    /** The request hash exceeded [IntegrityRequestHash.MAX_LENGTH] bytes. */
    REQUEST_HASH_TOO_LONG(-17, AttestationFailure.MISCONFIGURED),

    /** Google's own name for "back off and try again". */
    CLIENT_TRANSIENT_ERROR(-18, AttestationFailure.TRANSIENT),

    /** The warmed-up provider expired, or the user cleared Play Store data. */
    INTEGRITY_TOKEN_PROVIDER_INVALID(-19, AttestationFailure.PROVIDER_EXPIRED),

    /** Play's catch-all. Not a statement about the device, so not treated as one. */
    INTERNAL_ERROR(-100, AttestationFailure.TRANSIENT),
    ;

    companion object {

        private val BY_CODE: Map<Int, PlayIntegrityErrorCode> = entries.associateBy { it.code }

        /**
         * What [code] means for the caller.
         *
         * An unrecognised value — one a later release of the library adds, or one this app has
         * never seen — is [AttestationFailure.TRANSIENT] rather than a refusal. A new code
         * should cost a retry, not a permanent silent decision to stop attesting; the contract
         * test above is what turns it into a code change rather than leaving it at that.
         */
        fun failureFor(code: Int): AttestationFailure =
            BY_CODE[code]?.failure ?: AttestationFailure.TRANSIENT
    }
}
