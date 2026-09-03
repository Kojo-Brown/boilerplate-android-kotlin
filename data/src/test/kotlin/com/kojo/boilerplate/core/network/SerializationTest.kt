package com.kojo.boilerplate.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.LoginRequest
import com.kojo.boilerplate.core.network.model.TokenResponse
import com.kojo.boilerplate.core.network.model.UpdateUserRequest
import com.kojo.boilerplate.core.network.model.UserDto
import com.kojo.boilerplate.core.network.model.toDomain
import com.kojo.boilerplate.core.network.model.updateUserRequest
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

    @Test
    fun `UpdateUserRequest names the changed fields and sends the rest as null`() {
        val request = updateUserRequest(
            user = User(
                id = "u1",
                displayName = "Ada",
                email = "ada@example.com",
                avatarUrl = "https://example.com/ada.jpg",
            ),
            changed = setOf(UserField.DISPLAY_NAME),
        )

        assertEquals(
            """{"changed_fields":["display_name"],"display_name":"Ada","email":null,"avatar_url":null}""",
            json.encodeToString(request),
        )
    }

    /**
     * The tri-state the `changed_fields` list exists to resolve: a cleared avatar and an avatar
     * this update does not concern both serialise as `"avatar_url":null`, and only the list
     * separates them. `encodeDefaults = true` is what rules out the usual answer, where an
     * absent key means "unchanged" — see `UpdateUserRequest`.
     */
    @Test
    fun `a cleared field and an untouched field differ only in changed_fields`() {
        val user = User(id = "u1", displayName = "Ada", email = "ada@example.com", avatarUrl = null)

        val cleared = json.encodeToString(updateUserRequest(user, setOf(UserField.AVATAR_URL)))
        val untouched = json.encodeToString(updateUserRequest(user, setOf(UserField.EMAIL)))

        assertEquals(
            """{"changed_fields":["avatar_url"],"display_name":null,"email":null,"avatar_url":null}""",
            cleared,
        )
        assertEquals(
            """{"changed_fields":["email"],"display_name":null,"email":"ada@example.com","avatar_url":null}""",
            untouched,
        )
    }

    /**
     * The header is the only part of this request the server acts on twice, so it is worth
     * seeing it leave the client rather than trusting the annotation. A `@Header` misspelled, or
     * written as a `@Query`, compiles and produces a `PATCH` that is not idempotent at all.
     */
    @Test
    fun `UserApi updateUser sends the idempotency key as a header`() = runTest {
        val body = """{"id":"u42","display_name":"Ada","email":"ada@example.com","version":8}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val dto = userApi.updateUser(
            id = "u42",
            idempotencyKey = "11111111-2222-3333-4444-555555555555",
            update = UpdateUserRequest(
                changedFields = listOf("display_name"),
                displayName = "Ada",
                email = null,
                avatarUrl = null,
            ),
        )

        assertEquals(8L, dto.version)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/users/u42", request.path)
        assertEquals("11111111-2222-3333-4444-555555555555", request.getHeader("Idempotency-Key"))
        assertEquals(
            """{"changed_fields":["display_name"],"display_name":"Ada","email":null,"avatar_url":null}""",
            request.body.readUtf8(),
        )
    }
}
