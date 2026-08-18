package com.kojo.boilerplate.core.data.repository.decorator

import com.kojo.boilerplate.core.coroutines.BackoffPolicy
import com.kojo.boilerplate.core.coroutines.FanOutFailure
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.data.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The retry decorator, against a delegate that reports failure the way the real one does —
 * as a value.
 *
 * Jitter is switched off in the policy so a delay is an exact number rather than a range: what
 * these tests are about is *whether* and *how often* a retry happens, and the arithmetic of the
 * schedule already has its own tests in `BackoffPolicyTest` and `FlowRetryTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryingUserRepositoryTest {

    private val delegate = ScriptedUserRepository()

    private val policy = BackoffPolicy(maxRetries = 3, jitterRatio = 0.0)

    private val repository = RetryingUserRepository(delegate = delegate, policy = policy)

    private fun httpException(code: Int): HttpException = HttpException(
        Response.error<Unit>(code, "{}".toResponseBody("application/json".toMediaTypeOrNull())),
    )

    @Test
    fun `a transient failure returned as a value is still retried`() = runTest {
        var attempts = 0
        delegate.syncUserHandler = { id ->
            attempts++
            if (attempts < THIRD_ATTEMPT) {
                Result.failure(IOException("connection reset"))
            } else {
                Result.success(testUser(id))
            }
        }

        val result = repository.syncUser("7")

        assertEquals(testUser("7"), result.getOrNull())
        assertEquals(listOf("7", "7", "7"), delegate.syncUserCalls)
    }

    @Test
    fun `a failure another attempt cannot fix comes straight back`() = runTest {
        val notFound = httpException(HTTP_NOT_FOUND)
        delegate.syncUserHandler = { Result.failure(notFound) }

        val result = repository.syncUser("7")

        assertSame(notFound, result.exceptionOrNull())
        assertEquals(listOf("7"), delegate.syncUserCalls, "A 404 is an answer, not an outage")
    }

    @Test
    fun `the retry budget is finite and the last failure is what the caller sees`() = runTest {
        val outage = IOException("no route to host")
        delegate.syncCurrentUserHandler = { Result.failure(outage) }

        val result = repository.syncCurrentUser()

        assertSame(outage, result.exceptionOrNull())
        assertEquals(ATTEMPTS_WITH_THREE_RETRIES, delegate.syncCurrentUserCalls)
    }

    @Test
    fun `attempts are spaced by the policy's schedule`() = runTest {
        delegate.syncUserHandler = { Result.failure(IOException("timeout")) }

        val startedAt = currentTime
        repository.syncUser("7")

        // 500ms + 1s + 2s: three retries on the default factor of two, with jitter disabled.
        assertEquals(THREE_RETRY_TOTAL_DELAY.inWholeMilliseconds, currentTime - startedAt)
    }

    @Test
    fun `nothing is retried when the first attempt succeeds`() = runTest {
        val startedAt = currentTime

        assertEquals(testUser("7"), repository.syncUser("7").getOrNull())
        assertEquals(1, delegate.syncUserCalls.size)
        assertEquals(0L, currentTime - startedAt, "A success must not pay for a backoff")
    }

    @Test
    fun `a fan-out retries only the ids that did not land`() = runTest {
        delegate.syncUsersHandler = { ids ->
            if (delegate.syncUsersCalls.size == 1) {
                FanOutResult(
                    successes = listOf(testUser("1"), testUser("3")),
                    failures = listOf(FanOutFailure("2", IOException("connection reset"))),
                )
            } else {
                FanOutResult(successes = ids.map(::testUser), failures = emptyList())
            }
        }

        val result = repository.syncUsers(listOf("1", "2", "3"))

        assertEquals(listOf(listOf("1", "2", "3"), listOf("2")), delegate.syncUsersCalls)
        assertTrue(result.isCompleteSuccess)
        assertEquals(
            listOf("1", "2", "3"),
            result.successes.map(User::id),
            "The recovered user belongs where it was asked for, not at the end",
        )
    }

    @Test
    fun `a fan-out failure that is not transient is set aside rather than retried`() = runTest {
        val notFound = httpException(HTTP_NOT_FOUND)
        delegate.syncUsersHandler = { ids ->
            FanOutResult(
                successes = ids.filterNot { it == "2" }.map(::testUser),
                failures = ids.filter { it == "2" }.map { FanOutFailure(it, notFound) },
            )
        }

        val result = repository.syncUsers(listOf("1", "2"))

        assertEquals(listOf(listOf("1", "2")), delegate.syncUsersCalls)
        assertEquals(listOf("2"), result.failures.map { it.input })
        assertSame(notFound, result.failures.single().cause)
    }

    @Test
    fun `a fan-out gives up on the ids that keep failing and keeps the ones that landed`() = runTest {
        delegate.syncUsersHandler = { ids ->
            FanOutResult(
                successes = ids.filterNot { it == "2" }.map(::testUser),
                failures = ids.filter { it == "2" }.map { FanOutFailure(it, IOException("reset")) },
            )
        }

        val result = repository.syncUsers(listOf("1", "2"))

        assertEquals(ATTEMPTS_WITH_THREE_RETRIES, delegate.syncUsersCalls.size)
        assertEquals(listOf("1"), result.successes.map(User::id))
        assertEquals(listOf("2"), result.failures.map { it.input })
    }

    @Test
    fun `cancelling the caller stops the retries`() = runTest {
        delegate.syncUserHandler = { Result.failure(IOException("timeout")) }

        val call = launch { repository.syncUser("7") }
        runCurrent()
        assertEquals(1, delegate.syncUserCalls.size)

        call.cancel()
        runCurrent()

        assertEquals(1, delegate.syncUserCalls.size, "A cancelled call must not keep retrying")
    }

    @Test
    fun `local reads and writes pass through untouched`() = runTest {
        delegate.users.value = listOf(testUser("1"))

        assertEquals(listOf(testUser("1")), repository.getUsers().first())
        assertEquals(testUser("1"), repository.getUser("1").first())

        repository.saveUser(testUser("2"))
        assertEquals(listOf(testUser("2")), delegate.savedUsers)
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val THIRD_ATTEMPT = 3
        const val ATTEMPTS_WITH_THREE_RETRIES = 4
        val THREE_RETRY_TOTAL_DELAY = 500.milliseconds + 1.seconds + 2.seconds
    }
}
