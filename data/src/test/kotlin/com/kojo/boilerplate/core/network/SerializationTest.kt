package com.kojo.boilerplate.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.LoginRequest
import com.kojo.boilerplate.core.network.model.TokenResponse
import com.kojo.boilerplate.core.network.model.UserDto
import com.kojo.boilerplate.core.network.model.toDomain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class SerializationTest {

    private val server = MockWebServer()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private lateinit var userApi: UserApi

    @Before
    fun setUp() {
        server.start()
        userApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(UserApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- JSON encode / decode ---

    @Test
    fun `TokenResponse deserializes from snake_case JSON`() {
        val raw = """
            {
              "access_token": "abc123",
              "refresh_token": "def456",
              "token_type": "Bearer",
              "expires_in": 3600
            }
        """.trimIndent()

        val dto = json.decodeFromString<TokenResponse>(raw)

        assertEquals("abc123", dto.accessToken)
        assertEquals("def456", dto.refreshToken)
        assertEquals("Bearer", dto.tokenType)
        assertEquals(3600L, dto.expiresIn)
    }

    @Test
    fun `TokenResponse uses defaults for missing optional fields`() {
        val raw = """{"access_token":"t","refresh_token":"r"}"""
        val dto = json.decodeFromString<TokenResponse>(raw)

        assertEquals("Bearer", dto.tokenType)
        assertEquals(3600L, dto.expiresIn)
    }

    @Test
    fun `TokenResponse ignores unknown keys`() {
        val raw = """
            {
              "access_token": "x",
              "refresh_token": "y",
              "token_type": "Bearer",
              "expires_in": 1800,
              "unknown_field": "ignored"
            }
        """.trimIndent()

        val dto = json.decodeFromString<TokenResponse>(raw)
        assertEquals("x", dto.accessToken)
    }

    @Test
    fun `LoginRequest serializes to snake_case JSON`() {
        val request = LoginRequest(email = "user@example.com", password = "s3cr3t")
        val encoded = json.encodeToString(request)

        assertEquals("""{"email":"user@example.com","password":"s3cr3t"}""", encoded)
    }

    @Test
    fun `UserDto deserializes with nullable avatarUrl`() {
        val withAvatar = """
            {
              "id": "u1",
              "display_name": "Alice",
              "email": "alice@example.com",
              "avatar_url": "https://example.com/alice.jpg"
            }
        """.trimIndent()
        val withoutAvatar = """{"id":"u2","display_name":"Bob","email":"bob@example.com"}"""

        val dtoWithAvatar = json.decodeFromString<UserDto>(withAvatar)
        val dtoWithoutAvatar = json.decodeFromString<UserDto>(withoutAvatar)

        assertEquals("https://example.com/alice.jpg", dtoWithAvatar.avatarUrl)
        assertNull(dtoWithoutAvatar.avatarUrl)
    }

    @Test
    fun `UserDto toDomain maps all fields correctly`() {
        val dto = UserDto(
            id = "u1",
            displayName = "Alice",
            email = "alice@example.com",
            avatarUrl = "https://example.com/alice.jpg",
        )

        val user = dto.toDomain()

        assertEquals(dto.id, user.id)
        assertEquals(dto.displayName, user.displayName)
        assertEquals(dto.email, user.email)
        assertEquals(dto.avatarUrl, user.avatarUrl)
    }

    @Test
    fun `UserDto toDomain preserves null avatarUrl`() {
        val dto = UserDto(id = "u2", displayName = "Bob", email = "bob@example.com", avatarUrl = null)
        assertNull(dto.toDomain().avatarUrl)
    }

    // --- Retrofit + MockWebServer end-to-end ---

    @Test
    fun `UserApi getCurrentUser deserializes via Retrofit`() = runTest {
        val body = """
            {
              "id": "me",
              "display_name": "Current User",
              "email": "me@example.com",
              "avatar_url": null
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val dto = userApi.getCurrentUser()

        assertEquals("me", dto.id)
        assertEquals("Current User", dto.displayName)
        assertEquals("me@example.com", dto.email)
        assertNull(dto.avatarUrl)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/users/me", request.path)
    }

    @Test
    fun `UserApi getUser by id deserializes via Retrofit`() = runTest {
        val body = """{"id":"u42","display_name":"Jane","email":"jane@example.com"}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val dto = userApi.getUser("u42")

        assertEquals("u42", dto.id)
        assertEquals("Jane", dto.displayName)

        val request = server.takeRequest()
        assertEquals("/users/u42", request.path)
    }
}
