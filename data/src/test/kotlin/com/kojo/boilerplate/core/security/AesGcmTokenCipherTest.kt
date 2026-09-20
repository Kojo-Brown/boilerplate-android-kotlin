package com.kojo.boilerplate.core.security

import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cipher, on a plain JVM, against a plain JCA key.
 *
 * Substituting the key source is the entire point of [SecretKeySource] existing: AES-GCM is
 * ordinary JCA and behaves identically here and on a device, while `AndroidKeyStore` exists on
 * neither this JVM nor CI's. So the format, the tag check and the IV discipline — the parts a
 * mistake would actually live in — are all checked here, and what is left for a device is only
 * "does the keystore hand back a usable AES key", which no test double can answer anyway.
 *
 * The fixtures are obviously fake strings. A realistic-looking JWT in a test file is a thing
 * somebody eventually greps for and worries about.
 */
class AesGcmTokenCipherTest {

    private val keySource = FixedSecretKeySource()
    private val cipher = AesGcmTokenCipher(keySource)

    @Test
    fun `round trips a token`() {
        assertEquals("mock-access-token", cipher.decrypt(cipher.encrypt("mock-access-token")))
    }

    @Test
    fun `round trips a token with non-ascii characters`() {
        // UTF-8 rather than the platform default: a token is bytes, and a platform-default
        // decode would round-trip on this JVM and mangle on another.
        val token = "mock-token-Ü-✓-🔐"
        assertEquals(token, cipher.decrypt(cipher.encrypt(token)))
    }

    @Test
    fun `round trips an empty string`() {
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }

    @Test
    fun `the same plaintext never encrypts to the same ciphertext`() {
        // The IV is fresh per message. Were it not, two messages under one key would leak their
        // XOR and GCM's authentication key with it — the one failure in this file that would
        // leave every test above passing.
        val encryptions = (1..ENCRYPTION_SAMPLES).map { cipher.encrypt("mock-access-token") }
        assertEquals(ENCRYPTION_SAMPLES, encryptions.toSet().size)
    }

    @Test
    fun `each message carries its own iv`() {
        val ivs = (1..ENCRYPTION_SAMPLES)
            .map { Base64.getDecoder().decode(cipher.encrypt("mock-access-token")).take(IV_BYTES) }
        assertEquals(ENCRYPTION_SAMPLES, ivs.toSet().size)
    }

    @Test
    fun `the plaintext does not appear in the ciphertext`() {
        val encrypted = cipher.encrypt("mock-access-token")
        val bytes = Base64.getDecoder().decode(encrypted)
        assertFalse(encrypted.contains("mock-access-token"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("mock-access-token"))
    }

    @Test
    fun `a message is longer than its plaintext by exactly the iv and the tag`() {
        val plaintext = "mock-access-token"
        val bytes = Base64.getDecoder().decode(cipher.encrypt(plaintext))
        assertEquals(plaintext.toByteArray(Charsets.UTF_8).size + IV_BYTES + TAG_BYTES, bytes.size)
    }

    @Test
    fun `a tampered ciphertext does not decrypt`() {
        // The property that makes GCM worth the ceremony: an altered token fails the tag check
        // instead of decrypting to something else and being sent as a bearer credential.
        val bytes = Base64.getDecoder().decode(cipher.encrypt("mock-access-token"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        assertNull(cipher.decrypt(Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun `a tampered iv does not decrypt`() {
        val bytes = Base64.getDecoder().decode(cipher.encrypt("mock-access-token"))
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        assertNull(cipher.decrypt(Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun `a ciphertext from another key does not decrypt`() {
        // Standing in for the cases that matter in production and cannot be staged here: the
        // keystore key cleared with the app's data, invalidated by a lock-screen change, or
        // never present because a backup restored the ciphertext onto a different device.
        val fromElsewhere = AesGcmTokenCipher(FixedSecretKeySource()).encrypt("mock-access-token")
        assertNull(cipher.decrypt(fromElsewhere))
    }

    @Test
    fun `a value that is not base64 does not decrypt`() {
        assertNull(cipher.decrypt("not base64 at all !!"))
    }

    @Test
    fun `a value too short to be a message does not decrypt`() {
        // Below IV + tag there is nothing to check a tag against, and slicing an IV off it
        // would read past the end. Rejected before the cipher is touched.
        val truncated = Base64.getEncoder().encodeToString(ByteArray(IV_BYTES + TAG_BYTES - 1))
        assertNull(cipher.decrypt(truncated))
    }

    @Test
    fun `an empty value does not decrypt`() {
        assertNull(cipher.decrypt(""))
    }

    @Test
    fun `the key source is asked for the key rather than holding it in the cipher`() {
        // Cheap, and it pins the seam the Android keystore implementation depends on: a cipher
        // that resolved its key once at construction would keep working here and would hold a
        // stale keystore handle across an invalidation on a device.
        val counting = FixedSecretKeySource()
        val counted = AesGcmTokenCipher(counting)
        counted.decrypt(counted.encrypt("mock-access-token"))
        assertTrue(counting.requests >= 2)
    }

    @Test
    fun `two ciphers over the same key read each other`() {
        // The property the keystore implementation provides across process restarts, checked
        // here against the one thing that varies: a second instance.
        val other = AesGcmTokenCipher(keySource)
        assertEquals("mock-refresh-token", other.decrypt(cipher.encrypt("mock-refresh-token")))
    }

    @Test
    fun `access and refresh tokens encrypt independently`() {
        val access = cipher.encrypt("mock-access-token")
        val refresh = cipher.encrypt("mock-refresh-token")
        assertNotEquals(access, refresh)
        assertEquals("mock-access-token", cipher.decrypt(access))
        assertEquals("mock-refresh-token", cipher.decrypt(refresh))
    }

    /**
     * An AES-256 key from the JVM's own provider, generated once per instance.
     *
     * A hard-coded key would make the "another key" test above a fixture comparison rather than
     * the thing it is standing in for, and would put key bytes in the repository — which is
     * exactly the practice this whole change exists to remove.
     */
    private class FixedSecretKeySource : SecretKeySource {
        var requests: Int = 0
            private set

        private val key: SecretKey =
            KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }.generateKey()

        override fun secretKey(): SecretKey {
            requests++
            return key
        }
    }

    private companion object {
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val KEY_SIZE_BITS = 256

        /** Enough that a collision would be a real defect rather than bad luck. */
        const val ENCRYPTION_SAMPLES = 50
    }
}
