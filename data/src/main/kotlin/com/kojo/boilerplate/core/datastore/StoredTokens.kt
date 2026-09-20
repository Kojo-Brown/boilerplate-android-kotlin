package com.kojo.boilerplate.core.datastore

import com.kojo.boilerplate.core.security.TokenCipher

/**
 * The four values the auth-token store can hold, and the one function that decides what they
 * mean.
 *
 * Two of them are the encrypted pair this app writes now. The other two are the plaintext pair
 * it used to write, and they are here because a store that already exists on a device does not
 * change shape when the app is updated — see [StoredTokenState.Present.reencrypt].
 *
 * ### Why this is a data class and not four reads inside the provider
 *
 * Everything below is decided without touching a `DataStore`, a `Context` or the Android
 * keystore, which is what lets `StoredTokensTest` run it on a plain JVM. That matters more here
 * than the shape of the code: `DataStoreTokenProviderTest` needs `androidx.datastore` and so
 * runs only in CI, and the logic most likely to be wrong in this change is not the cipher — AES
 * is AES — but exactly this: which combination of present, absent and undecryptable values means
 * signed in, signed out, or clear the store. Keeping it in a pure function is what makes that
 * answerable before a push rather than after one.
 */
internal data class StoredTokens(
    val accessCiphertext: String?,
    val refreshCiphertext: String?,
    val legacyAccessToken: String?,
    val legacyRefreshToken: String?,
)

/** What the storage layer should do with what it found. */
internal sealed interface StoredTokenState {

    /** Nothing is stored. Nobody is signed in, and there is nothing to clean up. */
    data object Empty : StoredTokenState

    /**
     * A usable pair.
     *
     * [reencrypt] is set when the pair came from the plaintext keys a previous version of this
     * app wrote, and it means exactly one thing to the caller: write these back through the
     * cipher and delete the plaintext. The session is deliberately *kept* rather than dropped.
     * Signing everyone out on upgrade would look like the cautious choice and buys nothing —
     * an attacker who could already read the file has the token, and clearing the app's copy
     * does not revoke it server-side — while costing every reader their session for a threat
     * that has already either happened or not.
     */
    data class Present(val tokens: AuthTokens, val reencrypt: Boolean) : StoredTokenState

    /**
     * Something is stored and it cannot be turned into a usable pair, so the store should be
     * cleared and the reader treated as signed out.
     *
     * The common cause is not corruption but a key that no longer exists: app data cleared, a
     * lock-screen change that invalidated the key, or a backup restored onto a device whose
     * keystore never held the key that produced these bytes. A half-written store — one of the
     * two values present without the other — lands here too, since a request signed with an
     * access token whose refresh token is missing fails at the first 401 with no way back.
     */
    data object Discard : StoredTokenState
}

/**
 * What [StoredTokens] means, given a cipher that may or may not still be able to read it.
 *
 * The encrypted pair wins whenever either half of it is present: once this app has written
 * ciphertext, a plaintext value left beside it is a leftover to be deleted and never a fallback
 * to be read. Treating it as a fallback would be a downgrade an attacker could arrange by
 * deleting two preference keys.
 */
internal fun StoredTokens.read(cipher: TokenCipher): StoredTokenState {
    if (accessCiphertext != null || refreshCiphertext != null) {
        val access = accessCiphertext?.let(cipher::decrypt)
        val refresh = refreshCiphertext?.let(cipher::decrypt)
        return if (access != null && refresh != null) {
            StoredTokenState.Present(AuthTokens(access, refresh), reencrypt = false)
        } else {
            StoredTokenState.Discard
        }
    }
    if (legacyAccessToken != null && legacyRefreshToken != null) {
        return StoredTokenState.Present(
            AuthTokens(legacyAccessToken, legacyRefreshToken),
            reencrypt = true,
        )
    }
    // One plaintext value without the other: unusable, and still plaintext, so it is cleared
    // rather than left in place.
    return if (legacyAccessToken != null || legacyRefreshToken != null) {
        StoredTokenState.Discard
    } else {
        StoredTokenState.Empty
    }
}
