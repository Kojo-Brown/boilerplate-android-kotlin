package com.kojo.boilerplate.core.security

import javax.crypto.SecretKey

/**
 * Where [AesGcmTokenCipher] gets its key.
 *
 * Split from the cipher because the two halves are verifiable in completely different places.
 * AES-GCM is ordinary JCA and runs on any JVM, so `AesGcmTokenCipherTest` exercises the format,
 * the tag check and the IV discipline on a plain unit-test JVM. Getting a key *out of the
 * Android keystore* runs nowhere but a device, and no amount of test scaffolding changes that —
 * so it is one small class behind this interface rather than a branch inside the cipher.
 *
 * The seam is deliberately not "a key", because a `SecretKey` handed to a constructor has to
 * come from somewhere, and in production that somewhere must never be the app's own memory or,
 * worse, its source. Asking for the key per operation also lets the keystore implementation
 * decide when to look it up; see [AndroidKeystoreSecretKeySource].
 */
interface SecretKeySource {

    /**
     * The AES key for this installation, creating it on first use.
     *
     * Implementations must return the *same* key for the lifetime of the stored data. Returning
     * a fresh key would make every previously stored token undecryptable — which the storage
     * layer would read, correctly but expensively, as "this reader is signed out".
     */
    fun secretKey(): SecretKey
}
