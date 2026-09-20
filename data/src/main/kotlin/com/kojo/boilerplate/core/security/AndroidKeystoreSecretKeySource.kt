package com.kojo.boilerplate.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The AES key for this installation, held in the `AndroidKeyStore`.
 *
 * This is the half of the token-storage item that the phrase "never plaintext" actually rests
 * on. Encrypting a token with a key kept beside it moves the secret rather than protecting it:
 * anything that can read the store can read the key. A keystore key is different in kind — on
 * hardware-backed devices the material never enters the app's address space at all, and the
 * `SecretKey` this returns is a handle that the keystore performs operations *on behalf of*.
 * Even on a device with no secure hardware the key is held by the system keystore daemon under
 * the app's own uid, so it does not travel with the app's data directory.
 *
 * ### What this key deliberately does not require
 *
 * No `setUserAuthenticationRequired(true)`. Biometric or lock-screen gating on *this* key would
 * mean the access token cannot be read while the device is locked, and this app refreshes in
 * the background: `UserSyncWorker` runs on a six-hourly WorkManager schedule and signs its
 * request with the token read through here. Gating it would turn every background sync into a
 * silent failure on a locked device. Per-operation biometric gating is the right tool for a
 * *step-up* secret — a payment key, a stored password — and this is a session credential; the
 * item's "biometric" variant belongs with a feature that has a foreground prompt to attach it
 * to. Nothing here forecloses that: it is one more line on the builder below.
 *
 * `setUnlockedDeviceRequired(true)` is absent for the same reason and also needs API 28, above
 * this app's `minSdk` of 26.
 *
 * ### Why the lock is not optional
 *
 * `KeyGenerator.generateKey()` with an alias that already exists **replaces** the key, and the
 * old one is gone. Two threads racing through a naive `if (absent) generate()` would therefore
 * mint two keys and leave whichever lost the race unable to read what it wrote. That race is
 * entirely reachable here: `TokenProvider` is called from OkHttp's interceptor and its
 * authenticator, which run on whatever threads the dispatcher hands out, so two in-flight
 * requests on a fresh install are enough. The double-checked read below is `@Volatile` +
 * `synchronized`, and the key is cached for the process because a keystore round trip on every
 * outbound request is real latency on the request path.
 */
@Singleton
class AndroidKeystoreSecretKeySource @Inject constructor() : SecretKeySource {

    private val generationLock = Any()

    @Volatile
    private var cached: SecretKey? = null

    override fun secretKey(): SecretKey =
        cached ?: synchronized(generationLock) {
            cached ?: loadOrCreate().also { cached = it }
        }

    private fun loadOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: generate()
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                // GCM is a stream mode, so there is nothing to pad. Asking for a padding here
                // is rejected at generation time rather than at first use.
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // The keystore then refuses any caller-supplied IV for this key, which turns
                // "AesGcmTokenCipher must let the cipher choose the IV" from a convention that
                // a future edit could quietly undo into something the platform enforces.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * Namespaced by what it protects. The keystore is shared across the app, and an alias
         * as generic as "key" is how two unrelated features end up fighting over one entry —
         * with the loser's data becoming undecryptable at the moment the winner regenerates.
         */
        const val KEY_ALIAS = "com.kojo.boilerplate.auth_tokens"

        const val KEY_SIZE_BITS = 256
    }
}
