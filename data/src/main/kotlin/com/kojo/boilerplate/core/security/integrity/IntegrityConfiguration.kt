package com.kojo.boilerplate.core.security.integrity

/**
 * Whether this build can ask Play for integrity tokens, and on whose behalf.
 *
 * ### What a cloud project number is, and why it is not a secret
 *
 * The standard Play Integrity API mints tokens *encrypted to a Google Cloud project* — the one
 * linked to this app in Play Console, which is also the project whose service account the
 * backend uses to call `decodeIntegrityToken`. The number identifies that project. It is not a
 * credential: knowing it lets nobody decrypt a token, and it is visible in the Cloud console to
 * anybody with access to the project. It lives in `gradle/play-integrity.properties` for the
 * same reason the certificate pins do — it is deployment configuration that changes with the
 * project rather than with the code — and, like them, it is deliberately empty in this
 * repository.
 *
 * An empty value means [DISABLED], every [IntegrityAttestation.attest] answers
 * [AttestationFailure.NOT_CONFIGURED], and requests go out unattested. That is the honest
 * default: this boilerplate is not linked to a Cloud project, and a fabricated number would
 * produce `CLOUD_PROJECT_NUMBER_IS_INVALID` on every device, which is a slower way of arriving
 * at the same place with a worse error message.
 */
data class IntegrityConfiguration(

    /** The linked Cloud project, or `null` when this build has none and attestation is off. */
    val cloudProjectNumber: Long?,
) {

    init {
        require(cloudProjectNumber == null || cloudProjectNumber > 0) {
            "a cloud project number of $cloudProjectNumber is not a project; use null to turn " +
                "attestation off rather than a placeholder that fails on every device"
        }
    }

    companion object {

        /** What a build with no linked Cloud project carries. */
        val DISABLED = IntegrityConfiguration(cloudProjectNumber = null)

        /**
         * Reads `BuildConfig.INTEGRITY_CLOUD_PROJECT_NUMBER`, which `data/build.gradle.kts`
         * writes from `gradle/play-integrity.properties`.
         *
         * Strict, for the reason `PinningPolicy.parse` is: the value was put there by a human
         * editing a properties file, so every malformed case is a typo somebody is about to
         * ship. A number that does not parse must not quietly become [DISABLED] — that is
         * attestation switching itself off on a build that asked for it, and nothing downstream
         * would notice, because a build with attestation off looks exactly like a build whose
         * every device happens to be unable to attest.
         */
        fun parse(raw: String): IntegrityConfiguration {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return DISABLED
            val number = trimmed.toLongOrNull()
            require(number != null && number > 0) {
                "`$raw` is not a Google Cloud project number. Expected the decimal number Play " +
                    "Console shows for the Cloud project linked to this app, or an empty value " +
                    "to turn attestation off. See gradle/play-integrity.properties."
            }
            return IntegrityConfiguration(cloudProjectNumber = number)
        }
    }
}
