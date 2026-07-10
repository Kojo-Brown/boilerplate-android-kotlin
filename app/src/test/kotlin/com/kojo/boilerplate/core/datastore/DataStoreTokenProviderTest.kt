package com.kojo.boilerplate.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreTokenProviderTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val tempDir: File = Files.createTempDirectory("datastore_test").toFile()

    private fun createDataStore() = PreferenceDataStoreFactory.create(
        scope = testScope,
        produceFile = { File(tempDir, "test_auth_tokens.preferences_pb") },
    )

    private lateinit var provider: DataStoreTokenProvider

    @Before
    fun setUp() {
        provider = DataStoreTokenProvider(
            dataStore = createDataStore(),
            ioDispatcher = testDispatcher,
            appScope = testScope,
        )
    }

    @After
    fun tearDown() {
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
    fun `tokensFlow emits current tokens after update`() = testScope.runTest {
        provider.updateTokens("access-flow", "refresh-flow")
        advanceUntilIdle()
        val tokens = provider.tokensFlow.first()
        assertEquals(AuthTokens("access-flow", "refresh-flow"), tokens)
    }

    @Test
    fun `tokensFlow emits null after clearTokens`() = testScope.runTest {
        provider.updateTokens("access-flow", "refresh-flow")
        advanceUntilIdle()
        provider.clearTokens()
        advanceUntilIdle()
        val tokens = provider.tokensFlow.first()
        assertNull(tokens)
    }
}
