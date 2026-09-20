package com.kojo.boilerplate.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kojo.boilerplate.core.security.TokenCipher
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * These tests deliberately run against real dispatchers rather than a TestDispatcher.
 *
 * [DataStoreTokenProvider] is consumed by [com.kojo.boilerplate.core.network.AuthInterceptor]
 * and [com.kojo.boilerplate.core.network.TokenAuthenticator], both of which OkHttp calls
 * synchronously — there is no suspending seam to hand them. That is why `ensureLoaded()`
 * bridges with `runBlocking`, and that blocking read is the behaviour under test here.
 *
 * Wiring a TestDispatcher into it is not merely unrealistic, it deadlocks: `runBlocking`
 * parks the calling thread until the read completes, but a TestDispatcher's work only runs
 * when its scheduler is advanced, and the thread that would advance it is the parked one.
 * With the DataStore built on the same TestScope, nothing can make progress. The earlier
 * version of this file did exactly that, and `testDebugUnitTest` hung until the CI job
 * timed out rather than failing.
 *
 * Determinism comes from [awaitPendingWrites], which joins the persistence the provider
 * launches and never returns a handle to, rather than from sleeping or polling. Every wait
 * is bounded by [TIMEOUT_MS] so a reintroduced deadlock fails this test instead of hanging
 * the job.
 */
class DataStoreTokenProviderTest {

    private val tempDir: File = Files.createTempDirectory("datastore_test").toFile()

    // The DataStore's internal actor lives here. It never completes, so it is kept apart
    // from the scope below — otherwise there would be no way to wait on the provider's
    // writes without also waiting on an actor that runs forever.
    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The provider's fire-and-forget persistence. Isolating it is what makes
    // awaitPendingWrites() possible.
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cipher = ReversingCipher()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var provider: DataStoreTokenProvider

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempDir, "test_auth_tokens.preferences_pb") },
        )
        provider = DataStoreTokenProvider(
            dataStore = dataStore,
            cipher = cipher,
            ioDispatcher = Dispatchers.IO,
            appScope = appScope,
        )
    }

    @After
    fun tearDown() {
        appScope.cancel()
        dataStoreScope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `initial state returns null tokens`() {
        assertNull(provider.getAccessToken())
        assertNull(provider.getRefreshToken())
    }

    @Test
    fun `updateTokens stores both tokens in cache`() {
        provider.updateTokens("access-abc", "refresh-xyz")
        assertEquals("access-abc", provider.getAccessToken())
        assertEquals("refresh-xyz", provider.getRefreshToken())
    }

    @Test
    fun `clearTokens removes cached tokens`() {
        provider.updateTokens("access-abc", "refresh-xyz")
        provider.clearTokens()
        assertNull(provider.getAccessToken())
        assertNull(provider.getRefreshToken())
    }

    @Test
    fun `updateTokens overwrites previous tokens`() {
        provider.updateTokens("old-access", "old-refresh")
        provider.updateTokens("new-access", "new-refresh")
        assertEquals("new-access", provider.getAccessToken())
        assertEquals("new-refresh", provider.getRefreshToken())
    }

    @Test
    fun `tokensFlow emits current tokens after update`() = runBlocking {
        provider.updateTokens("access-flow", "refresh-flow")
        awaitPendingWrites()

        val tokens = withTimeout(TIMEOUT_MS) { provider.tokensFlow.first() }

        assertEquals(AuthTokens("access-flow", "refresh-flow"), tokens)
    }

    @Test
    fun `tokensFlow emits null after clearTokens`() = runBlocking {
        provider.updateTokens("access-flow", "refresh-flow")
        awaitPendingWrites()
        assertEquals(
            AuthTokens("access-flow", "refresh-flow"),
            withTimeout(TIMEOUT_MS) { provider.tokensFlow.first() },
        )

        provider.clearTokens()
        awaitPendingWrites()

        assertNull(withTimeout(TIMEOUT_MS) { provider.tokensFlow.first() })
    }

    @Test
    fun `what reaches the file is ciphertext and not the token`() = runBlocking {
        // The item itself, asserted against the bytes the store actually holds rather than
        // against the provider's own read-back — which would round-trip through the same cipher
        // and pass just as happily if nothing were encrypted at all.
        provider.updateTokens("mock-access-token", "mock-refresh-token")
        awaitPendingWrites()

        val prefs = withTimeout(TIMEOUT_MS) { dataStore.data.first() }
        assertEquals(cipher.encrypt("mock-access-token"), prefs[ACCESS_CIPHERTEXT])
        assertNull(prefs[LEGACY_ACCESS])

        val raw = File(tempDir, "test_auth_tokens.preferences_pb").readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("mock-access-token"))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("mock-refresh-token"))
    }

    @Test
    fun `a plaintext store written by an older version is adopted and re-encrypted`() = runBlocking {
        writePlaintext("mock-legacy-access-token", "mock-legacy-refresh-token")

        // The read is what migrates, so the session survives the upgrade.
        assertEquals("mock-legacy-access-token", provider.getAccessToken())
        assertEquals("mock-legacy-refresh-token", provider.getRefreshToken())
        awaitPendingWrites()

        val prefs = withTimeout(TIMEOUT_MS) { dataStore.data.first() }
        assertEquals(cipher.encrypt("mock-legacy-access-token"), prefs[ACCESS_CIPHERTEXT])
        assertNull(prefs[LEGACY_ACCESS])
        assertNull(prefs[LEGACY_REFRESH])

        val raw = File(tempDir, "test_auth_tokens.preferences_pb").readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("mock-legacy-access-token"))
    }

    @Test
    fun `a store this device can no longer decrypt reads as signed out and is cleared`() =
        runBlocking {
            // A key cleared with the app's data, invalidated by a lock-screen change, or a
            // backup restored onto a device that never held it. Nothing throws; the reader is
            // simply signed out, and the unreadable bytes must not be left behind to be
            // rediscovered on the next launch.
            dataStore.edit { prefs ->
                prefs[ACCESS_CIPHERTEXT] = ReversingCipher.UNREADABLE
                prefs[REFRESH_CIPHERTEXT] = ReversingCipher.UNREADABLE
            }

            assertNull(provider.getAccessToken())
            assertNull(provider.getRefreshToken())
            awaitPendingWrites()

            val prefs = withTimeout(TIMEOUT_MS) { dataStore.data.first() }
            assertTrue(prefs.asMap().isEmpty())
        }

    @Test
    fun `clearTokens removes the plaintext an older version left behind`() = runBlocking {
        writePlaintext("mock-legacy-access-token", "mock-legacy-refresh-token")

        provider.clearTokens()
        awaitPendingWrites()

        val prefs = withTimeout(TIMEOUT_MS) { dataStore.data.first() }
        assertTrue(prefs.asMap().isEmpty())
    }

    @Test
    fun `tokensFlow decrypts what is stored`() = runBlocking {
        provider.updateTokens("mock-access-token", "mock-refresh-token")
        awaitPendingWrites()

        assertEquals(
            AuthTokens("mock-access-token", "mock-refresh-token"),
            withTimeout(TIMEOUT_MS) { provider.tokensFlow.first() },
        )
    }

    @Test
    fun `tokensFlow emits null for a store it cannot decrypt`() = runBlocking {
        dataStore.edit { prefs ->
            prefs[ACCESS_CIPHERTEXT] = ReversingCipher.UNREADABLE
            prefs[REFRESH_CIPHERTEXT] = ReversingCipher.UNREADABLE
        }

        assertNull(withTimeout(TIMEOUT_MS) { provider.tokensFlow.first() })
    }

    /** The shape a previous version of this app left on disk. */
    private suspend fun writePlaintext(access: String, refresh: String) {
        dataStore.edit { prefs ->
            prefs[LEGACY_ACCESS] = access
            prefs[LEGACY_REFRESH] = refresh
        }
    }

    /**
     * updateTokens and clearTokens update the cache synchronously and persist on appScope
     * without returning anything to wait on — deliberately, since OkHttp calls the
     * interceptor and authenticator synchronously. Joining appScope's children is what
     * makes the persisted value observable at a defined point instead of racing it: an
     * earlier version polled the flow with `first { it != null }` and timed out.
     */
    private suspend fun awaitPendingWrites() {
        withTimeout(TIMEOUT_MS) { appScope.coroutineContext.job.children.toList().joinAll() }
    }

    /**
     * A stand-in cipher that reverses its input.
     *
     * The real one is `AesGcmTokenCipher`, covered on its own in `AesGcmTokenCipherTest`. What
     * is under test here is the storage layer around it — which keys are written, which are
     * removed, and what a value that will not decrypt does to the session — and a reversible
     * transformation makes every assertion above readable at the call site. It is still enough
     * to catch the failure this file most needs to catch: a token reaching the file unchanged.
     */
    private class ReversingCipher : TokenCipher {
        override fun encrypt(plaintext: String): String = plaintext.reversed()

        override fun decrypt(ciphertext: String): String? =
            if (ciphertext == UNREADABLE) null else ciphertext.reversed()

        companion object {
            /** A value this cipher refuses, standing in for a keystore key that is gone. */
            const val UNREADABLE = "!"
        }
    }

    private companion object {
        // Generous enough that a loaded CI runner never trips it, short enough that a
        // reintroduced deadlock fails the test rather than the job.
        const val TIMEOUT_MS = 10_000L

        // Named here rather than imported from the production file, deliberately: these are the
        // on-disk key names, and a test that imported the same constants would keep passing
        // through a rename that orphaned every store already on a device.
        val ACCESS_CIPHERTEXT = stringPreferencesKey("access_token_ciphertext")
        val REFRESH_CIPHERTEXT = stringPreferencesKey("refresh_token_ciphertext")
        val LEGACY_ACCESS = stringPreferencesKey("access_token")
        val LEGACY_REFRESH = stringPreferencesKey("refresh_token")
    }
}
