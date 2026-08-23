package com.kojo.boilerplate.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class InMemoryTokenProviderTest {

    private lateinit var provider: InMemoryTokenProvider

    @Before
    fun setUp() {
        provider = InMemoryTokenProvider()
    }

    @Test
    fun `initial state returns null tokens`() {
        assertNull(provider.getAccessToken())
        assertNull(provider.getRefreshToken())
    }

    @Test
    fun `updateTokens stores both tokens`() {
        provider.updateTokens("access-123", "refresh-456")
        assertEquals("access-123", provider.getAccessToken())
        assertEquals("refresh-456", provider.getRefreshToken())
    }

    @Test
    fun `clearTokens removes both tokens`() {
        provider.updateTokens("access-123", "refresh-456")
        provider.clearTokens()
        assertNull(provider.getAccessToken())
        assertNull(provider.getRefreshToken())
    }

    @Test
    fun `updateTokens overwrites previous values`() {
        provider.updateTokens("old-access", "old-refresh")
        provider.updateTokens("new-access", "new-refresh")
        assertEquals("new-access", provider.getAccessToken())
        assertEquals("new-refresh", provider.getRefreshToken())
    }
}
