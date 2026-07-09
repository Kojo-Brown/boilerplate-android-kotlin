package com.kojo.boilerplate.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private val server = MockWebServer()
    private val tokenProvider = InMemoryTokenProvider()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider))
            .build()
    }

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `adds Authorization header when token is present`() {
        tokenProvider.updateTokens("test-access-token", "test-refresh-token")
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/api/resource")).build()).execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer test-access-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `omits Authorization header when no token`() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/api/resource")).build()).execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `uses updated token after updateTokens called`() {
        tokenProvider.updateTokens("first-token", "first-refresh")
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(Request.Builder().url(server.url("/api/resource")).build()).execute()
        server.takeRequest()

        tokenProvider.updateTokens("second-token", "second-refresh")
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(Request.Builder().url(server.url("/api/resource")).build()).execute()
        val recorded = server.takeRequest()

        assertEquals("Bearer second-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `clears Authorization header after clearTokens`() {
        tokenProvider.updateTokens("some-token", "some-refresh")
        tokenProvider.clearTokens()
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/api/resource")).build()).execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }
}
