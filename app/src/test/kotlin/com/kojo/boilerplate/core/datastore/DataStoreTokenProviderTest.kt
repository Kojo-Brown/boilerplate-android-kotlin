package com.kojo.boilerplate.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    private lateinit var provider: DataStoreTokenProvider

    @Before
    fun setUp() {
        provider = DataStoreTokenProvider(
            dataStore = PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(tempDir, "test_auth_tokens.preferences_pb") },
            ),
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

    private companion object {
        // Generous enough that a loaded CI runner never trips it, short enough that a
        // reintroduced deadlock fails the test rather than the job.
        const val TIMEOUT_MS = 10_000L
    }
}
