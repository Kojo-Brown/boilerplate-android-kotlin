package com.kojo.boilerplate.core.event

import com.kojo.boilerplate.core.coroutines.CoroutineFailureReporter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dispatcher's job is to make an [AppEventListener] a safe thing to write: it receives
 * everything, its failures do not become anyone else's, and it is subscribed before the first
 * event can be published.
 *
 * Each of those is a property that fails silently when it is missing — a dropped event, a
 * listener that quietly stops receiving, a reaction that never runs — so none of them shows up
 * as a crash or a failing build. They are only ever caught here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppEventDispatcherTest {

    private val bus = SharedFlowAppEventBus()
    private val reporter = RecordingFailureReporter()

    @Test
    fun `delivers every event to every listener`() = runTest {
        val credentials = RecordingListener()
        val navigation = RecordingListener()
        dispatcherWith(credentials, navigation).start()

        bus.publish(AppEvent.SessionExpired)
        runCurrent()

        assertEquals(listOf(AppEvent.SessionExpired), credentials.received)
        assertEquals(listOf(AppEvent.SessionExpired), navigation.received)
    }

    /**
     * The reason `start()` launches undispatched.
     *
     * With a default `launch`, this method's `start()` returns before the coroutine body has
     * run, so the bus has no subscriber when the next line publishes — and a `SharedFlow` with
     * no replay drops that emission and reports success. The test reads as though it could not
     * possibly fail; remove [kotlinx.coroutines.CoroutineStart.UNDISPATCHED] and it does.
     */
    @Test
    fun `an event published immediately after start is delivered`() = runTest {
        val listener = RecordingListener()
        dispatcherWith(listener).start()

        assertTrue(bus.tryPublish(AppEvent.SessionExpired))
        runCurrent()

        assertEquals(listOf(AppEvent.SessionExpired), listener.received)
    }

    @Test
    fun `a listener that throws is reported, and the others still receive that event`() = runTest {
        val boom = IllegalStateException("credential store unavailable")
        val throwing = RecordingListener { throw boom }
        val healthy = RecordingListener()
        dispatcherWith(throwing, healthy).start()

        bus.publish(AppEvent.SessionExpired)
        runCurrent()

        assertEquals(listOf(AppEvent.SessionExpired), healthy.received)
        assertSame(boom, reporter.failures.single())
    }

    /**
     * The failure mode this class exists to prevent, and the one that is invisible in
     * production: the subscription is a single `collect`, so an exception escaping a listener
     * cancels it, and *every* listener stops receiving events for the life of the process.
     * Nothing crashes. The app simply stops reacting.
     */
    @Test
    fun `a listener that throws does not end the subscription for the rest`() = runTest {
        val throwing = RecordingListener { throw IllegalStateException("boom") }
        val healthy = RecordingListener()
        dispatcherWith(throwing, healthy).start()

        bus.publish(AppEvent.SessionExpired)
        runCurrent()
        bus.publish(AppEvent.SessionExpired)
        runCurrent()

        assertEquals(2, healthy.received.size)
        assertEquals(2, throwing.received.size, "The failing listener is not unsubscribed either.")
        assertEquals(2, reporter.failures.size)
    }

    @Test
    fun `start is idempotent, so a second call does not double-deliver`() = runTest {
        val listener = RecordingListener()
        val dispatcher = dispatcherWith(listener)
        dispatcher.start()
        dispatcher.start()

        bus.publish(AppEvent.SessionExpired)
        runCurrent()

        assertEquals(listOf(AppEvent.SessionExpired), listener.received)
    }

    /**
     * A fatal throwable is the one kind that is not absorbed: it is reported, then rethrown so
     * the application scope's handler can escalate it to the platform. Absorbing it would leave
     * the app running on a VM that has already said it cannot continue.
     *
     * Run against a scope of its own, because the escalation is the point and a
     * `backgroundScope` would fail the test instead of letting it observe one.
     */
    @Test
    fun `a fatal throwable is reported and escalated to the scope`() {
        val escalated = mutableListOf<Throwable>()
        val testScope = TestScope()
        val scope = CoroutineScope(
            StandardTestDispatcher(testScope.testScheduler) +
                SupervisorJob() +
                CoroutineExceptionHandler { _, failure -> escalated += failure },
        )
        val fatal = OutOfMemoryError("heap")
        val listener = RecordingListener { throw fatal }
        AppEventDispatcher(bus, setOf(listener), reporter, scope).start()

        try {
            assertTrue(bus.tryPublish(AppEvent.SessionExpired))
            testScope.testScheduler.runCurrent()

            assertSame(fatal, reporter.failures.single(), "Reported before it is escalated.")
            assertSame(fatal, escalated.single())
        } finally {
            scope.cancel()
        }
    }

    /**
     * A `LinkedHashSet` rather than `setOf`, so the delivery order the assertions describe is
     * the order the listeners were written in. Dagger's `@IntoSet` gives no ordering guarantee
     * of its own, which is a fact about the production graph worth not hiding here: a listener
     * that depends on running before another one is relying on something nothing promises.
     */
    private fun TestScope.dispatcherWith(vararg listeners: AppEventListener): AppEventDispatcher =
        AppEventDispatcher(
            bus = bus,
            listeners = LinkedHashSet(listeners.toList()),
            reporter = reporter,
            scope = backgroundScope,
        )
}

private class RecordingListener(
    private val onEach: (AppEvent) -> Unit = {},
) : AppEventListener {

    val received = mutableListOf<AppEvent>()

    override suspend fun onEvent(event: AppEvent) {
        // Recorded before [onEach] runs, so a throwing listener still shows that it was given
        // the event — which is what distinguishes "it failed" from "it was unsubscribed".
        received += event
        onEach(event)
    }
}

private class RecordingFailureReporter : CoroutineFailureReporter {

    val failures = mutableListOf<Throwable>()

    override fun report(coroutineName: String?, failure: Throwable) {
        failures += failure
    }
}
