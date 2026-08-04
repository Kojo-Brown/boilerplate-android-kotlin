package com.kojo.boilerplate.core.coroutines

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the behaviour `docs/coroutine-errors.md` describes, including the two rows of the
 * table in [AppCoroutineExceptionHandler]'s KDoc that say when a handler is *not* consulted.
 * Those are the ones worth a test: they are the cases where a handler looks installed and
 * silently is not, and no compiler warning marks them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppCoroutineExceptionHandlerTest {

    private val reporter = RecordingCoroutineFailureReporter()
    private val handler = AppCoroutineExceptionHandler(reporter)

    // What it does with a failure

    @Test
    fun `reports the failure and the name of the coroutine it came from`() {
        val boom = IllegalStateException("upload failed")

        handler.handleException(CoroutineName("sync"), boom)

        val report = reporter.reports.single()
        assertEquals("sync", report.coroutineName)
        assertSame(boom, report.failure)
    }

    @Test
    fun `reports a null name for a coroutine started without one`() {
        handler.handleException(EmptyCoroutineContext, IllegalStateException("boom"))

        assertNull(reporter.reports.single().coroutineName)
    }

    @Test
    fun `does not report cancellation, which is not a failure`() {
        handler.handleException(EmptyCoroutineContext, CancellationException("navigated away"))

        assertTrue(reporter.reports.isEmpty())
    }

    // When the machinery consults it

    @Test
    fun `reports a failure from a root coroutine of a scope that carries it`() = runTest {
        val boom = IllegalStateException("no caller left")
        val scope = scopeWith(SupervisorJob())

        scope.launch { throw boom }
        advanceUntilIdle()

        assertFailureReported(boom)
        scope.cancel()
    }

    @Test
    fun `a handler on a nested launch is ignored in favour of the root coroutine's`() = runTest {
        val nested = RecordingCoroutineFailureReporter()
        val boom = IllegalStateException("thrown two levels down")
        val scope = scopeWith(Job())

        // The mistake this documents: `launch(handler)` reads as "handle failures of this
        // work", but a non-root coroutine hands its failure to its parent, so `nested` is
        // never consulted and the scope's handler is what actually runs.
        scope.launch { launch(AppCoroutineExceptionHandler(nested)) { throw boom } }
        advanceUntilIdle()

        assertFailureReported(boom)
        assertTrue(nested.reports.isEmpty())
        scope.cancel()
    }

    @Test
    fun `is not consulted for an async failure, which the Deferred still holds`() = runTest {
        val scope = scopeWith(SupervisorJob())

        scope.async { throw IllegalStateException("awaited later, or never") }
        advanceUntilIdle()

        // The failure has a caller that can still see it — `await()` — so it is not
        // uncaught, and routing it here would report a failure the caller goes on to handle.
        assertTrue(reporter.reports.isEmpty())
        scope.cancel()
    }

    @Test
    fun `is not consulted when the scope is cancelled`() = runTest {
        val scope = scopeWith(SupervisorJob())

        scope.launch { awaitCancellation() }
        advanceUntilIdle()
        scope.cancel()
        advanceUntilIdle()

        assertTrue(reporter.reports.isEmpty())
    }

    // Fatal errors

    @Test
    fun `hands a fatal error to the thread's uncaught handler after reporting it`() {
        val fatal = OutOfMemoryError("heap exhausted")

        val escalated = handleOnWorkerThread(fatal)

        assertSame(fatal, reporter.reports.single().failure)
        assertSame(fatal, escalated)
    }

    @Test
    fun `absorbs an ordinary failure instead of crashing the process`() {
        val boom = IllegalStateException("recoverable")

        val escalated = handleOnWorkerThread(boom)

        assertSame(boom, reporter.reports.single().failure)
        assertNull(escalated)
    }

    @Test
    fun `does not treat every Error as fatal`() {
        val assertionError = AssertionError("a bug, not a dead VM")

        val escalated = handleOnWorkerThread(assertionError)

        assertSame(assertionError, reporter.reports.single().failure)
        assertNull(escalated)
    }

    // A reporter that itself fails

    @Test
    fun `attaches a failing reporter's exception to the original rather than throwing`() {
        val reportingFailure = IllegalStateException("crash reporter offline")
        val boom = IllegalStateException("the actual bug")
        val failingHandler = AppCoroutineExceptionHandler(
            RecordingCoroutineFailureReporter(throwing = reportingFailure),
        )

        failingHandler.handleException(EmptyCoroutineContext, boom)

        assertSame(reportingFailure, boom.suppressedExceptions.single())
    }

    @Test
    fun `escalates when the reporter fails fatally, even though the failure did not`() {
        val failingHandler = AppCoroutineExceptionHandler(
            RecordingCoroutineFailureReporter(throwing = OutOfMemoryError("heap exhausted")),
        )
        val boom = IllegalStateException("the actual bug")

        val escalated = handleOnWorkerThread(boom, failingHandler)

        // The original failure is what goes to the crash handler, with the reporter's
        // failure suppressed inside it: the bug is the story, the dead reporter is context.
        assertSame(boom, escalated)
    }

    // Helpers

    /**
     * A scope carrying [handler], driven by the test scheduler so `advanceUntilIdle` runs it.
     *
     * Deliberately not [TestScope]: `runTest` fails a test on any uncaught exception in its
     * own scope, which would make "the handler reported it" indistinguishable from "the test
     * framework caught it first".
     */
    private fun TestScope.scopeWith(job: Job): CoroutineScope =
        CoroutineScope(job + StandardTestDispatcher(testScheduler) + handler)

    /**
     * Asserts exactly one failure was reported, matching [expected] by type and message.
     *
     * By type and message rather than by identity, for the reason `StructuredConcurrencyTest`
     * documents: kotlinx.coroutines copies a throwable as it propagates from a child to its
     * parent so it can attach the launching stack trace, so what the handler is handed at the
     * top of the chain can be an equal but distinct instance. The tests that call
     * `handleException` directly stay on `assertSame`, because nothing copies it there.
     */
    private fun assertFailureReported(expected: Throwable) {
        val reported = reporter.reports.single().failure
        assertEquals(expected::class.java, reported::class.java)
        assertEquals(expected.message, reported.message)
    }

    /**
     * Runs the handler on a thread of its own and returns whatever reached that thread's
     * uncaught-exception handler, or `null` if nothing did.
     *
     * A dedicated thread rather than the test thread: escalation ends at
     * `Thread.uncaughtExceptionHandler`, and substituting that on a thread JUnit owns would
     * leak into every test that ran after it. `join()` before reading gives the
     * happens-before edge the recorded values need.
     */
    private fun handleOnWorkerThread(
        failure: Throwable,
        handler: AppCoroutineExceptionHandler = this.handler,
    ): Throwable? {
        val escalated = AtomicReference<Throwable>()
        val worker = Thread { handler.handleException(EmptyCoroutineContext, failure) }
        worker.setUncaughtExceptionHandler { _, throwable -> escalated.set(throwable) }
        worker.start()
        worker.join()
        return escalated.get()
    }
}

/**
 * A [CoroutineFailureReporter] that records what it was asked to report, and optionally
 * fails the way a real crash-reporting SDK can.
 */
private class RecordingCoroutineFailureReporter(
    private val throwing: Throwable? = null,
) : CoroutineFailureReporter {

    data class Report(val coroutineName: String?, val failure: Throwable)

    val reports = mutableListOf<Report>()

    override fun report(coroutineName: String?, failure: Throwable) {
        reports += Report(coroutineName, failure)
        throwing?.let { throw it }
    }
}
