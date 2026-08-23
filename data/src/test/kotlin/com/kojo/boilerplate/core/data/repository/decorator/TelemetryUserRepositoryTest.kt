package com.kojo.boilerplate.core.data.repository.decorator

import com.kojo.boilerplate.core.coroutines.FanOutFailure
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.telemetry.RepositoryOperation
import com.kojo.boilerplate.core.telemetry.RepositoryOperationEvent
import com.kojo.boilerplate.core.telemetry.RepositoryOutcome
import com.kojo.boilerplate.core.telemetry.RepositoryTelemetry
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Collects what the decorator reports, in order. */
private class RecordingTelemetry : RepositoryTelemetry {
    val events = mutableListOf<RepositoryOperationEvent>()
    override fun record(event: RepositoryOperationEvent) {
        events += event
    }
}

/**
 * What the telemetry decorator records, and — as much of the point — what it does not.
 *
 * The delegate advances a [TestTimeSource] while it "works", so an assertion on a duration is an
 * equality rather than a range: the decorator is supposed to report the time the call took, and
 * a test that only checked it was positive would pass for a stopwatch that was never started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryUserRepositoryTest {

    private val delegate = ScriptedUserRepository()
    private val telemetry = RecordingTelemetry()
    private val timeSource = TestTimeSource()

    private val repository = TelemetryUserRepository(
        delegate = delegate,
        telemetry = telemetry,
        timeSource = timeSource,
    )

    @Test
    fun `a successful sync is recorded with the time it took`() = runTest {
        delegate.syncUserHandler = { id ->
            timeSource += WORK_DURATION
            Result.success(testUser(id))
        }

        repository.syncUser("7")

        assertEquals(
            listOf(RepositoryOperationEvent(RepositoryOperation.SYNC_USER, WORK_DURATION, RepositoryOutcome.Succeeded)),
            telemetry.events,
        )
    }

    @Test
    fun `a failure returned as a value is recorded as a failure`() = runTest {
        val cause = IOException("connection reset")
        delegate.syncCurrentUserHandler = { Result.failure(cause) }

        repository.syncCurrentUser()

        val event = telemetry.events.single()
        assertEquals(RepositoryOperation.SYNC_CURRENT_USER, event.operation)
        assertSame(cause, (event.outcome as RepositoryOutcome.Failed).cause)
    }

    @Test
    fun `a fan-out that lost some of its inputs is partial, not failed`() = runTest {
        delegate.syncUsersHandler = { ids ->
            FanOutResult(
                successes = ids.filterNot { it == "2" }.map(::testUser),
                failures = ids.filter { it == "2" }.map { FanOutFailure(it, IOException("reset")) },
            )
        }

        repository.syncUsers(listOf("1", "2", "3"))

        assertEquals(
            RepositoryOutcome.PartiallyFailed(succeeded = 2, failed = 1),
            telemetry.events.single().outcome,
        )
    }

    @Test
    fun `a fan-out that lost nothing is a plain success`() = runTest {
        repository.syncUsers(listOf("1", "2"))

        assertEquals(RepositoryOutcome.Succeeded, telemetry.events.single().outcome)
    }

    @Test
    fun `a cancelled call is recorded as cancelled and stays cancelled`() = runTest {
        val neverAnswers = CompletableDeferred<Result<User>>()
        delegate.syncUserHandler = { neverAnswers.await() }

        val call = launch { repository.syncUser("7") }
        runCurrent()
        call.cancel()
        runCurrent()

        assertEquals(RepositoryOutcome.Cancelled, telemetry.events.single().outcome)
        assertTrue(call.isCancelled, "The cancellation must reach the caller, not stop at the decorator")
    }

    @Test
    fun `a delegate that throws is recorded and the throwable is not swallowed`() = runTest {
        val cause = IllegalStateException("the layer below threw")
        delegate.syncUserHandler = { throw cause }

        val thrown = runCatching { repository.syncUser("7") }.exceptionOrNull()

        assertSame(cause, thrown)
        assertSame(cause, (telemetry.events.single().outcome as RepositoryOutcome.Failed).cause)
    }

    @Test
    fun `local reads and writes are not measured`() = runTest {
        delegate.users.value = listOf(testUser("1"))

        repository.getUsers().first()
        repository.getUser("1").first()
        repository.saveUser(testUser("2"))

        assertTrue(
            telemetry.events.isEmpty(),
            "A subscription has no duration, and a local upsert has no outcome worth a metric",
        )
    }

    private companion object {
        val WORK_DURATION = 25.milliseconds
    }
}
