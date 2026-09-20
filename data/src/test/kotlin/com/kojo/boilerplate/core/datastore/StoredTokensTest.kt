package com.kojo.boilerplate.core.datastore

import com.kojo.boilerplate.core.security.TokenCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which combination of stored values means signed in, signed out, or clear the store.
 *
 * This is the part of encrypted token storage most likely to be wrong, and — unlike the cipher,
 * and unlike `DataStoreTokenProvider`, which needs `androidx.datastore` and so runs only in CI —
 * it is decidable on a plain JVM. That is the whole reason `StoredTokens.read` is a function
 * over a data class rather than four reads inside the provider.
 *
 * The cases that are easy to get wrong, and each has a test below:
 *
 * - **Half a store.** One value present without the other is not a session. A request signed
 *   with an access token whose refresh token is missing works until the first 401 and then has
 *   nowhere to go.
 * - **A plaintext fallback.** Once ciphertext exists, a plaintext value beside it is a leftover
 *   to delete, never a value to read — otherwise deleting two preference keys downgrades the
 *   store to plaintext, which an attacker with file access can arrange.
 * - **Undecryptable is not empty.** It has to be distinguishable from `Empty`, because only one
 *   of the two has something left on disk to clean up.
 */
class StoredTokensTest {

    private val cipher = ReversingCipher()

    @Test
    fun `an empty store is Empty`() {
        assertSame(StoredTokenState.Empty, stored().read(cipher))
    }

    @Test
    fun `an encrypted pair is present and needs no re-encryption`() {
        val state = stored(
            access = cipher.encrypt("mock-access-token"),
            refresh = cipher.encrypt("mock-refresh-token"),
        ).read(cipher)

        val present = state as StoredTokenState.Present
        assertEquals(AuthTokens("mock-access-token", "mock-refresh-token"), present.tokens)
        assertEquals(false, present.reencrypt)
    }

    @Test
    fun `an undecryptable pair is discarded`() {
        val state = stored(access = UNREADABLE, refresh = UNREADABLE).read(cipher)
        assertSame(StoredTokenState.Discard, state)
    }

    @Test
    fun `one undecryptable half discards the whole store`() {
        val state = stored(
            access = cipher.encrypt("mock-access-token"),
            refresh = UNREADABLE,
        ).read(cipher)
        assertSame(StoredTokenState.Discard, state)
    }

    @Test
    fun `an encrypted access token with no refresh token is discarded`() {
        val state = stored(access = cipher.encrypt("mock-access-token")).read(cipher)
        assertSame(StoredTokenState.Discard, state)
    }

    @Test
    fun `an encrypted refresh token with no access token is discarded`() {
        val state = stored(refresh = cipher.encrypt("mock-refresh-token")).read(cipher)
        assertSame(StoredTokenState.Discard, state)
    }

    @Test
    fun `a legacy plaintext pair is present and asks to be re-encrypted`() {
        val state = stored(
            legacyAccess = "mock-legacy-access-token",
            legacyRefresh = "mock-legacy-refresh-token",
        ).read(cipher)

        val present = state as StoredTokenState.Present
        assertEquals(
            AuthTokens("mock-legacy-access-token", "mock-legacy-refresh-token"),
            present.tokens,
        )
        assertTrue(present.reencrypt)
    }

    @Test
    fun `half a legacy pair is discarded rather than left in place`() {
        assertSame(
            StoredTokenState.Discard,
            stored(legacyAccess = "mock-legacy-access-token").read(cipher),
        )
        assertSame(
            StoredTokenState.Discard,
            stored(legacyRefresh = "mock-legacy-refresh-token").read(cipher),
        )
    }

    @Test
    fun `ciphertext wins over plaintext left beside it`() {
        val state = stored(
            access = cipher.encrypt("mock-access-token"),
            refresh = cipher.encrypt("mock-refresh-token"),
            legacyAccess = "mock-legacy-access-token",
            legacyRefresh = "mock-legacy-refresh-token",
        ).read(cipher)

        val present = state as StoredTokenState.Present
        assertEquals(AuthTokens("mock-access-token", "mock-refresh-token"), present.tokens)
        // Not a re-encryption: the plaintext here is a leftover the next write removes, and
        // reporting it as a migration would overwrite the newer encrypted pair with the older
        // plaintext one.
        assertEquals(false, present.reencrypt)
    }

    @Test
    fun `undecryptable ciphertext does not fall back to plaintext beside it`() {
        // The downgrade this ordering exists to refuse. Answering `Present` here would let
        // anything that can corrupt one preference key choose which of the two the app reads.
        val state = stored(
            access = UNREADABLE,
            refresh = UNREADABLE,
            legacyAccess = "mock-legacy-access-token",
            legacyRefresh = "mock-legacy-refresh-token",
        ).read(cipher)
        assertSame(StoredTokenState.Discard, state)
    }

    private fun stored(
        access: String? = null,
        refresh: String? = null,
        legacyAccess: String? = null,
        legacyRefresh: String? = null,
    ) = StoredTokens(
        accessCiphertext = access,
        refreshCiphertext = refresh,
        legacyAccessToken = legacyAccess,
        legacyRefreshToken = legacyRefresh,
    )

    /**
     * A stand-in cipher that reverses its input, so a test can write a value this "decrypts"
     * and a value it does not.
     *
     * Not real encryption and not pretending to be: what is under test here is a decision table
     * over present, absent and unreadable, and the real cipher is covered on its own in
     * `AesGcmTokenCipherTest`. A mock would work too and would say less at the call site —
     * `cipher.encrypt(…)` reads as what it is, where `every { decrypt(any()) } returns null`
     * has to be matched up with the fixture by eye.
     */
    private class ReversingCipher : TokenCipher {
        override fun encrypt(plaintext: String): String = plaintext.reversed()

        override fun decrypt(ciphertext: String): String? =
            if (ciphertext == UNREADABLE) null else ciphertext.reversed()
    }

    private companion object {
        /** A value the stand-in cipher refuses, standing in for a key that is gone. */
        const val UNREADABLE = "!"
    }
}
