package com.kojo.boilerplate.core.security.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The only decision `PlayIntegrityAttestation` takes, as a table.
 *
 * Each row is a claim about what the app does when Play says something, and the claims are not
 * interchangeable: treating a rooted device's `PLAY_UNAVAILABLE` as transient is a retry loop on
 * every request for the life of the install, and treating a flight-mode `NETWORK_ERROR` as
 * permanent is an install that stops attesting the first time it is offline and never starts
 * again.
 *
 * Reachable at all only because the mapping is written over an `Int` rather than over the
 * library's constants — see [PlayIntegrityErrorCode] for that trade and
 * [PlayIntegrityErrorCodeContractTest] for the test that closes it.
 */
class PlayIntegrityErrorCodeTest {

    @Test
    fun `every code maps to the outcome the app is written around`() {
        assertEquals(AttestationFailure.PLAY_UNAVAILABLE, failureOf(PlayIntegrityErrorCode.API_NOT_AVAILABLE))
        assertEquals(AttestationFailure.PLAY_UNAVAILABLE, failureOf(PlayIntegrityErrorCode.PLAY_STORE_NOT_FOUND))
        assertEquals(
            AttestationFailure.PLAY_UNAVAILABLE,
            failureOf(PlayIntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED),
        )
        assertEquals(
            AttestationFailure.PLAY_UNAVAILABLE,
            failureOf(PlayIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND),
        )
        assertEquals(
            AttestationFailure.PLAY_UNAVAILABLE,
            failureOf(PlayIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED),
        )
        assertEquals(AttestationFailure.PLAY_UNAVAILABLE, failureOf(PlayIntegrityErrorCode.APP_NOT_INSTALLED))
        assertEquals(AttestationFailure.PLAY_UNAVAILABLE, failureOf(PlayIntegrityErrorCode.APP_UID_MISMATCH))

        assertEquals(AttestationFailure.TRANSIENT, failureOf(PlayIntegrityErrorCode.NETWORK_ERROR))
        assertEquals(AttestationFailure.TRANSIENT, failureOf(PlayIntegrityErrorCode.CANNOT_BIND_TO_SERVICE))
        assertEquals(
            AttestationFailure.TRANSIENT,
            failureOf(PlayIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE),
        )
        assertEquals(AttestationFailure.TRANSIENT, failureOf(PlayIntegrityErrorCode.CLIENT_TRANSIENT_ERROR))
        assertEquals(AttestationFailure.TRANSIENT, failureOf(PlayIntegrityErrorCode.INTERNAL_ERROR))

        assertEquals(AttestationFailure.RATE_LIMITED, failureOf(PlayIntegrityErrorCode.TOO_MANY_REQUESTS))
        assertEquals(
            AttestationFailure.PROVIDER_EXPIRED,
            failureOf(PlayIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID),
        )
        assertEquals(
            AttestationFailure.MISCONFIGURED,
            failureOf(PlayIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID),
        )
        assertEquals(
            AttestationFailure.MISCONFIGURED,
            failureOf(PlayIntegrityErrorCode.REQUEST_HASH_TOO_LONG),
        )
    }

    @Test
    fun `no two codes share a number`() {
        // The lookup is a map keyed by code, so a duplicate would silently drop one entry and
        // send it to the `else` branch — a mapping this file says is covered and is not.
        val codes = PlayIntegrityErrorCode.entries.map { it.code }
        assertEquals(codes.size, codes.distinct().size)
    }

    @Test
    fun `an unknown code is transient rather than a refusal`() {
        // A code a later release of the library adds should cost a retry, not a permanent silent
        // decision to stop attesting. -42 and 7 stand in for "not in the table"; the contract
        // test beside this one is what turns a genuinely new code into a code change.
        assertSame(AttestationFailure.TRANSIENT, PlayIntegrityErrorCode.failureFor(-42))
        assertSame(AttestationFailure.TRANSIENT, PlayIntegrityErrorCode.failureFor(7))
    }

    @Test
    fun `only the recoverable outcomes are marked transient`() {
        // `transient` is what a caller would branch a retry on, so the two halves of the enum
        // are pinned here rather than left implied by the constructor arguments.
        assertEquals(
            setOf(
                AttestationFailure.TRANSIENT,
                AttestationFailure.RATE_LIMITED,
                AttestationFailure.PROVIDER_EXPIRED,
            ),
            AttestationFailure.entries.filter { it.transient }.toSet(),
        )
    }

    private fun failureOf(code: PlayIntegrityErrorCode): AttestationFailure {
        // Through the public lookup rather than through `code.failure`, so that the table and
        // the function that reads it are covered together.
        assertSame(code.failure, PlayIntegrityErrorCode.failureFor(code.code))
        return PlayIntegrityErrorCode.failureFor(code.code)
    }
}
