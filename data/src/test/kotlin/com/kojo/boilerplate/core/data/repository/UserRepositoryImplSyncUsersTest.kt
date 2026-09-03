package com.kojo.boilerplate.core.data.repository

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.kojo.boilerplate.core.database.dao.FakeUserDao
import com.kojo.boilerplate.core.network.api.UserApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit

/**
 * `syncUsers` against a real HTTP stack, because what is being tested is the behaviour of N
 * concurrent requests and a mock of `UserApi` would only replay whatever the test decided
 * concurrency looks like.
 *
 * The server answers per-path rather than from an enqueued script: a fan-out completes in
 * whatever order the responses arrive, so `enqueue()` — which serves its queue in order,
 * blind to what was asked for — would attach the wrong body to the wrong request as soon as
 * two of them overtook each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryImplSyncUsersTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var userApi: UserApi
    private lateinit var userDao: FakeUserDao
    private lateinit var repository: UserRepositoryImpl

    private val json = Json { ignoreUnknownKeys = true }

    private val testDispatcher = StandardTestDispatcher()

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
        repository = userRepositoryOver(userDao, userApi, testDispatcher)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun userBody(id: String): String =
        """{"id":"$id","display_name":"User $id","email":"user$id@example.com"}"""

    /** Serves `GET /users/{id}` for every id, failing the ones named in [failing] with a 500. */
    private fun serveUsers(failing: Set<String> = emptySet()) {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val id = request.path.orEmpty().substringAfterLast('/')
                return if (id in failing) {
                    MockResponse().setResponseCode(500)
                } else {
                    MockResponse().setResponseCode(200).setBody(userBody(id))
                }
            }
        }
    }

    @Test
    fun `syncUsers returns every user when they all succeed`() = runTest(testDispatcher) {
        serveUsers()

        val result = repository.syncUsers(listOf("1", "2", "3"))

        assertTrue(result.isCompleteSuccess)
        assertEquals(3, result.attempted)
        assertEquals(listOf("1", "2", "3"), result.successes.map { it.id })
    }

    @Test
    fun `syncUsers returns successes in the order they were requested`() =
        runTest(testDispatcher) {
            serveUsers()

            val result = repository.syncUsers(listOf("3", "1", "2"))

            // Not the order the responses came back in: a caller pairing these against its
            // own list by position has to be able to rely on that.
            assertEquals(listOf("3", "1", "2"), result.successes.map { it.id })
        }

    @Test
    fun `syncUsers caches every user that arrived`() = runTest(testDispatcher) {
        serveUsers()

        repository.syncUsers(listOf("1", "2"))

        assertEquals("User 1", repository.getUser("1").first()?.displayName)
        assertEquals("User 2", repository.getUser("2").first()?.displayName)
    }

    @Test
    fun `syncUsers reports the failures alongside the successes`() = runTest(testDispatcher) {
        serveUsers(failing = setOf("2", "4"))

        val result = repository.syncUsers(listOf("1", "2", "3", "4"))

        assertTrue(result.isPartial)
        assertEquals(listOf("1", "3"), result.successes.map { it.id })
        assertEquals(listOf("2", "4"), result.failures.map { it.input })
        assertTrue(result.failures.all { it.cause is HttpException })
    }

    @Test
    fun `syncUsers still caches the users that arrived when a sibling failed`() =
        runTest(testDispatcher) {
            serveUsers(failing = setOf("2"))

            repository.syncUsers(listOf("1", "2", "3"))

            // The whole point of the partial-failure shape: one 500 does not discard two
            // responses already in hand.
            assertEquals("User 1", repository.getUser("1").first()?.displayName)
            assertEquals("User 3", repository.getUser("3").first()?.displayName)
            assertNull(repository.getUser("2").first())
        }

    @Test
    fun `syncUsers reports a complete failure when nothing arrives`() = runTest(testDispatcher) {
        serveUsers(failing = setOf("1", "2"))

        val result = repository.syncUsers(listOf("1", "2"))

        assertTrue(result.isCompleteFailure)
        assertEquals(2, result.attempted)
    }

    @Test
    fun `syncUsers requests a repeated id once`() = runTest(testDispatcher) {
        serveUsers()

        val result = repository.syncUsers(listOf("1", "2", "1", "2", "1"))

        assertEquals(2, mockWebServer.requestCount)
        assertEquals(listOf("1", "2"), result.successes.map { it.id })
    }

    @Test
    fun `syncUsers on an empty list makes no request`() = runTest(testDispatcher) {
        serveUsers()

        val result = repository.syncUsers(emptyList())

        assertEquals(0, mockWebServer.requestCount)
        assertEquals(0, result.attempted)
        assertTrue(result.isCompleteSuccess)
    }
}
