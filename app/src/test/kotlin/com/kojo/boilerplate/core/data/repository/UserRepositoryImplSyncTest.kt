package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.database.dao.FakeUserDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.kojo.boilerplate.core.network.api.UserApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserRepositoryImplSyncTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var userApi: UserApi
    private lateinit var userDao: FakeUserDao
    private lateinit var repository: UserRepositoryImpl

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        userApi = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(UserApi::class.java)

        userDao = FakeUserDao()
        repository = UserRepositoryImpl(userDao, userApi)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `syncCurrentUser returns success and caches user locally`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"1","display_name":"Alice","email":"alice@example.com"}"""),
        )

        val result = repository.syncCurrentUser()

        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("1", user.id)
        assertEquals("Alice", user.displayName)
        assertEquals("alice@example.com", user.email)

        val cached = repository.getUser("1").first()
        assertEquals("Alice", cached?.displayName)
    }

    @Test
    fun `syncCurrentUser returns failure on network error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val result = repository.syncCurrentUser()

        assertTrue(result.isFailure)
    }

    @Test
    fun `syncUser returns success and caches user locally`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"42","display_name":"Bob","email":"bob@example.com","avatar_url":null}"""),
        )

        val result = repository.syncUser("42")

        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("42", user.id)
        assertEquals("Bob", user.displayName)

        val cached = repository.getUser("42").first()
        assertEquals("Bob", cached?.displayName)
    }

    @Test
    fun `syncUser returns failure on HTTP 404`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val result = repository.syncUser("unknown")

        assertTrue(result.isFailure)
    }

    @Test
    fun `syncUser does not cache on failure`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(503))

        repository.syncUser("99")

        val cached = repository.getUser("99").first()
        assertTrue(cached == null)
    }
}
