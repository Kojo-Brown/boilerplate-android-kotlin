package com.kojo.boilerplate.core.security

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM over a key this device holds and this process cannot read.
 *
 * ### Why GCM, and why the IV comes from the cipher
 *
 * GCM is authenticated encryption: the tag is checked on the way out, so a token that was
 * altered on disk fails to decrypt rather than coming back subtly wrong and being sent to the
 * server as a bearer credential. That property is the reason the mode is worth the ceremony —
 * a plain `AES/CBC` store would hand back whatever the edited bytes decrypt to.
 *
 * GCM's one sharp edge is that reusing an IV with the same key is catastrophic: two messages
 * under one (key, IV) pair leak their XOR, and the authentication key itself becomes
 * recoverable. So the IV is never chosen here. [Cipher.init] in encrypt mode is left to
 * generate one, and it is read back off the cipher afterwards — which is both the correct JCA
 * idiom and the *only* thing that works against the Android keystore, where a key built with
 * `setRandomizedEncryptionRequired(true)` rejects a caller-supplied IV outright. A future
 * edit that "helpfully" passes a `GCMParameterSpec` to the encrypt-mode init would therefore
 * fail loudly on a device rather than silently weakening anything, which is the good direction
 * for that mistake to fail in.
 *
 * ### The stored format
 *
 * `Base64( IV ‖ ciphertext ‖ tag )`, with a 12-byte IV and a 128-bit tag — GCM's standard
 * sizes, and what both the SunJCE and the Android keystore providers produce by default. The
 * IV is not a secret and is stored beside the ciphertext as usual. Base64 is not an encoding
 * detail that could be swapped freely: [TokenCipher] returns a `String` because the store
 * underneath is a `Preferences` `DataStore`.
 *
 * ### Which failures are `null` and which throw
 *
 * [decrypt] answers `null` for everything that means *this stored value cannot be recovered*:
 * a bad tag ([javax.crypto.AEADBadTagException]), a key the keystore has invalidated
 * ([android.security.keystore.KeyPermanentlyInvalidatedException], an `InvalidKeyException`),
 * a value that is not Base64, and a value too short to be a well-formed message. All four are
 * `GeneralSecurityException` or `IllegalArgumentException`, and all four mean the same thing
 * to the caller: clear the store and treat the reader as signed out.
 *
 * Anything else propagates — in practice a `ProviderException` from a keystore that cannot be
 * reached at all. That is a broken device or a broken process rather than a fact about this
 * token, and the honest response is to fail the call that asked. Mapping it to `null` would
 * spend the reader's session on a transient hardware fault and then say nothing about it.
 */
@Singleton
class AesGcmTokenCipher @Inject constructor(
    private val keySource: SecretKeySource,
) : TokenCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySource.secretKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) {
            // Not defensive noise: the reader below slices the first IV_BYTES off the message,
            // so a provider that chose a different IV length would produce values this class
            // cannot read back. Better to fail on the write than to write something unreadable.
            "expected a $IV_BYTES-byte GCM IV from ${cipher.provider.name}, got ${iv.size}"
        }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + body)
    }

    override fun decrypt(ciphertext: String): String? {
        val message = decodeBase64(ciphertext) ?: return null
        if (message.size < IV_BYTES + TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keySource.secretKey(),
                GCMParameterSpec(TAG_BITS, message, 0, IV_BYTES),
            )
            val plaintext = cipher.doFinal(message, IV_BYTES, message.size - IV_BYTES)
            String(plaintext, Charsets.UTF_8)
        } catch (expected: GeneralSecurityException) {
            // The documented `null` cases: a bad tag, and a key the keystore has invalidated.
            null
        }
    }

    private fun decodeBase64(value: String): ByteArray? =
        try {
            Base64.getDecoder().decode(value)
        } catch (expected: IllegalArgumentException) {
            // Not Base64 at all — a value written by something other than encrypt(), or one
            // truncated in place. Unrecoverable in exactly the same way a bad tag is.
            null
        }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM's standard IV length, and the only one the Android keystore will generate. */
        const val IV_BYTES = 12

        /** A full-length GCM tag. Truncating it would weaken the forgery bound for nothing. */
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS
    }
}
