package com.kojo.boilerplate.core.data.repository.decorator

import com.kojo.boilerplate.core.coroutines.FanOutFailure
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.data.model.User
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The caching decorator: which calls reach the delegate, and which are answered without it.
 *
 * Time is a [TestTimeSource] the test advances by hand rather than the virtual clock `runTest`
 * provides. The two measure different things — the scheduler's clock moves when a coroutine
 * delays, and nothing here delays — so a freshness window driven by `advanceTimeBy` would never
 * expire. Advancing the source explicitly also keeps the expiry tests honest about what they are
 * asserting: the entry is stale because 30 seconds passed, not because something waited.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CachingUserRepositoryTest {

    private val delegate = ScriptedUserRepository()
    private val timeSource = TestTimeSource()

    private fun TestScope.caching(
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        scope: CoroutineScope = backgroundScope,
    ) = CachingUserRepository(
        delegate = delegate,
        scope = scope,
        freshness = FRESHNESS,
        maxEntries = maxEntries,
        timeSource = timeSource,
    )

    @Test
    fun `a second sync inside the freshness window costs no request`() = runTest {
        val repository = caching()

        assertEquals(testUser("7"), repository.syncUser("7").getOrNull())
        timeSource += FRESHNESS / 2
        assertEquals(testUser("7"), repository.syncUser("7").getOrNull())

        assertEquals(listOf("7"), delegate.syncUserCalls)
    }

    @Test
    fun `the window expires`() = runTest {
        val repository = caching()

        repository.syncUser("7")
        timeSource += FRESHNESS
        repository.syncUser("7")

        assertEquals(listOf("7", "7"), delegate.syncUserCalls)
    }

    @Test
    fun `a failure is not cached`() = runTest {
        delegate.syncUserHandler = { Result.failure(IOException("connection reset")) }
        val repository = caching()

        repository.syncUser("7")
        repository.syncUser("7")

        assertEquals(
            listOf("7", "7"),
            delegate.syncUserCalls,
            "Caching a failure turns one bad response into an outage that lasts the whole window",
        )
    }

    @Test
    fun `the current user has its own keyspace`() = runTest {
        val repository = caching()

        repository.syncUser("me")
        repository.syncCurrentUser()

        assertEquals(
            1,
            delegate.syncCurrentUserCalls,
            "users/me and users/{id} are different requests even when they name the same person",
        )
    }

    @Test
    fun `simultaneous callers share one request`() = runTest {
        val response = CompletableDeferred<Result<User>>()
        delegate.syncUserHandler = { response.await() }
        val repository = caching()

        val first = async { repository.syncUser("7") }
        val second = async { repository.syncUser("7") }
        runCurrent()

        assertEquals(listOf("7"), delegate.syncUserCalls)

        response.complete(Result.success(testUser("7")))
        assertEquals(testUser("7"), first.await().getOrNull())
        assertEquals(testUser("7"), second.await().getOrNull())
    }

    /**
     * The reason the shared request is hosted in `@ApplicationScope` rather than started with
     * the first caller's `async`. Written the obvious way, this test fails: the second caller
     * awaits a `Deferred` that is a child of the first caller's job, so the first screen closing
     * takes down a request the second screen is still waiting for.
     */
    @Test
    fun `a caller that goes away does not cancel the request another is waiting for`() = runTest {
        val response = CompletableDeferred<Result<User>>()
        delegate.syncUserHandler = { response.await() }
        val repository = caching()

        val leaving = launch { repository.syncUser("7") }
        runCurrent()
        val staying = async { repository.syncUser("7") }
        runCurrent()

        leaving.cancel()
        runCurrent()

        response.complete(Result.success(testUser("7")))
        assertEquals(testUser("7"), staying.await().getOrNull())
        assertEquals(listOf("7"), delegate.syncUserCalls)
    }

    @Test
    fun `a shared request that failed is not left behind for the next caller to join`() = runTest {
        val response = CompletableDeferred<Result<User>>()
        delegate.syncUserHandler = { response.await() }
        val repository = caching()

        val first = async { repository.syncUser("7") }
        runCurrent()
        response.complete(Result.failure(IOException("connection reset")))
        assertTrue(first.await().isFailure)

        delegate.syncUserHandler = { Result.success(testUser(it)) }
        assertEquals(testUser("7"), repository.syncUser("7").getOrNull())
        assertEquals(listOf("7", "7"), delegate.syncUserCalls)
    }

    @Test
    fun `a fan-out asks only for the users that are not already current`() = runTest {
        val repository = caching()
        repository.syncUser("2")

        val result = repository.syncUsers(listOf("1", "2", "3"))

        assertEquals(listOf(listOf("1", "3")), delegate.syncUsersCalls)
        assertEquals(
            listOf("1", "2", "3"),
            result.successes.map(User::id),
            "A cache hit belongs where it was requested, not appended after the fetched users",
        )
    }

    @Test
    fun `a fan-out with nothing stale makes no request at all`() = runTest {
        val repository = caching()
        repository.syncUsers(listOf("1", "2"))
        delegate.syncUsersCalls.clear()

        val result = repository.syncUsers(listOf("1", "2"))

        assertTrue(delegate.syncUsersCalls.isEmpty())
        assertEquals(listOf("1", "2"), result.successes.map(User::id))
    }

    @Test
    fun `a fan-out caches what it fetched, so a later single sync is a hit`() = runTest {
        val repository = caching()
        repository.syncUsers(listOf("1", "2"))

        repository.syncUser("1")

        assertTrue(delegate.syncUserCalls.isEmpty())
    }

    @Test
    fun `a fan-out failure leaves nothing cached for that user`() = runTest {
        delegate.syncUsersHandler = { ids ->
            FanOutResult(
                successes = ids.filterNot { it == "2" }.map(::testUser),
                failures = ids.filter { it == "2" }.map { FanOutFailure(it, IOException("reset")) },
            )
        }
        val repository = caching()
        repository.syncUsers(listOf("1", "2"))
        delegate.syncUsersCalls.clear()

        repository.syncUsers(listOf("1", "2"))

        assertEquals(listOf(listOf("2")), delegate.syncUsersCalls)
    }

    @Test
    fun `a local write invalidates the user it wrote`() = runTest {
        val repository = caching()
        repository.syncUser("7")

        repository.saveUser(testUser("7").copy(displayName = "Renamed"))
        repository.syncUser("7")

        assertEquals(
            listOf("7", "7"),
            delegate.syncUserCalls,
            "A cache that outlives a write is how an edit appears to be lost",
        )
    }

    @Test
    fun `the cache is bounded and drops the least recently used entry`() = runTest {
        val repository = caching(maxEntries = 2)

        repository.syncUser("1")
        repository.syncUser("2")
        // Serving "1" from the cache counts as a use, which makes "2" the least recently used —
        // so admitting "3" evicts "2" and not the older "1".
        repository.syncUser("1")
        repository.syncUser("3")
        delegate.syncUserCalls.clear()

        repository.syncUser("2")
        repository.syncUser("3")

        assertEquals(listOf("2"), delegate.syncUserCalls)
    }

    @Test
    fun `local reads pass through to the database, which is the real cache`() = runTest {
        delegate.users.value = listOf(testUser("1"))
        val repository = caching()

        assertEquals(listOf(testUser("1")), repository.getUsers().first())
        assertEquals(testUser("1"), repository.getUser("1").first())
        assertNull(repository.getUser("absent").first())
    }

    private companion object {
        val FRESHNESS = 30.seconds
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
