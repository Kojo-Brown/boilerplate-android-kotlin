package com.kojo.boilerplate.core.event

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bus's contract, which is mostly the contract of the `MutableSharedFlow` underneath it —
 * asserted here rather than taken on trust, because the two properties the app depends on pull
 * in opposite directions and it is the *combination* that has to hold.
 *
 * Fan-out to every subscriber is why this is a `SharedFlow` and not the `Channel` the screens
 * use: a session expiry has to reach both the credential listener and the navigation graph,
 * and a `Channel` would give it to whichever asked first. Dropping when nobody is subscribed
 * is the price of no replay, and is the reason [AppEventDispatcher] exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppEventBusTest {

    private val bus = SharedFlowAppEventBus()

    @Test
    fun `every subscriber receives every event`() = runTest {
        val credentials = mutableListOf<AppEvent>()
        val navigation = mutableListOf<AppEvent>()
        backgroundScope.launch { bus.events.collect(credentials::add) }
        backgroundScope.launch { bus.events.collect(navigation::add) }
        runCurrent()

        bus.publish(AppEvent.SessionExpired)
        runCurrent()

        assertEquals(listOf(AppEvent.SessionExpired), credentials)
        assertEquals(
            listOf(AppEvent.SessionExpired),
            navigation,
            "Both subscribers must see the event. A Channel-backed bus passes the first " +
                "assertion and fails this one, which is the difference the app depends on.",
        )
    }

    @Test
    fun `an event published with no subscriber is dropped, and tryPublish still reports success`() =
        runTest {
            assertTrue(
                bus.tryPublish(AppEvent.SessionExpired),
                "tryEmit accepts the emission whether or not anyone is listening; a true " +
                    "return is not a delivery receipt.",
            )

            val received = mutableListOf<AppEvent>()
            backgroundScope.launch { bus.events.collect(received::add) }
            runCurrent()

            assertEquals(emptyList<AppEvent>(), received)
        }

    @Test
    fun `a subscriber does not see what was published before it subscribed`() = runTest {
        val early = mutableListOf<AppEvent>()
        backgroundScope.launch { bus.events.collect(early::add) }
        runCurrent()

        bus.publish(AppEvent.SessionExpired)
        runCurrent()

        val late = mutableListOf<AppEvent>()
        backgroundScope.launch { bus.events.collect(late::add) }
        runCurrent()

        assertEquals(1, early.size)
        assertEquals(
            emptyList<AppEvent>(),
            late,
            "replay = 0 is what keeps the composition that replaces this one after a " +
                "rotation from re-handling an event that has already been handled.",
        )
    }

    @Test
    fun `tryPublish refuses once a stalled subscriber has filled the buffer`() = runTest {
        val stalled = CompletableDeferred<Unit>()
        backgroundScope.launch { bus.events.collect { stalled.await() } }
        runCurrent()

        val accepted = (1..ATTEMPTS).takeWhile { bus.tryPublish(AppEvent.SessionExpired) }.count()

        assertTrue(
            accepted > 0,
            "The buffer exists so that tryPublish works while a subscriber is mid-event.",
        )
        assertFalse(
            accepted == ATTEMPTS,
            "BufferOverflow.SUSPEND is what turns a full buffer into a false return. Under " +
                "DROP_OLDEST every attempt is accepted and $ATTEMPTS events vanish with no " +
                "caller ever told.",
        )
    }

    private companion object {
        /** Far above the bus's buffer, so a refusal is the buffer filling and not a coincidence. */
        const val ATTEMPTS = 100
    }
}
