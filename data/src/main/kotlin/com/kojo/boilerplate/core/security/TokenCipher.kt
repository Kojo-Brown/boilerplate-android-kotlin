package com.kojo.boilerplate.core.security

/**
 * Turns an auth token into something that is safe to write to disk, and back.
 *
 * The interface exists so that the *storage* layer never names a cipher, a key or a provider:
 * `DataStoreTokenProvider` holds one of these and knows only that what it writes is opaque and
 * that what it reads back may no longer be recoverable. That second half is the part worth
 * having an interface for — see [decrypt].
 *
 * Both halves take and return `String` rather than `ByteArray` because the store underneath is
 * a `Preferences` `DataStore`, whose value types are the primitives plus `String`. Base64 is
 * therefore part of this contract and not an implementation detail: [encrypt] returns something
 * a `stringPreferencesKey` can hold.
 */
interface TokenCipher {

    /** The ciphertext of [plaintext], as an opaque string. Never the same twice for one input. */
    fun encrypt(plaintext: String): String

    /**
     * The plaintext behind [ciphertext], or `null` if it cannot be recovered.
     *
     * `null` is a routine answer rather than an error, and that is the whole reason this method
     * does not simply throw. An encrypted token becomes permanently unreadable in several
     * situations that are nobody's bug: the key was cleared with the app's data, the key was
     * invalidated by a lock-screen change, or a backup restored the ciphertext onto a device
     * whose keystore never held the key that produced it. The only correct response to all of
     * them is to treat the reader as signed out and clear the store, which is what
     * `DataStoreTokenProvider` does with a `null` from here.
     *
     * A failure that is *not* about this data — a keystore that cannot be reached at all —
     * propagates instead. See `AesGcmTokenCipher` for exactly where that line is drawn.
     */
    fun decrypt(ciphertext: String): String?
}
