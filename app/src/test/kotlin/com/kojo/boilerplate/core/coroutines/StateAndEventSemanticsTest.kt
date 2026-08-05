package com.kojo.boilerplate.core.coroutines

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Executable version of `docs/state-and-events.md`.
 *
 * Every claim the guide makes about which stream to reach for is asserted here against
 * kotlinx.coroutines itself rather than against a wrapper of ours, because the guide is a claim
 * about those primitives: that a `StateFlow` replays, that a `SharedFlow` with no replay drops
 * what it is given while nobody is subscribed, that a `Channel` buffers, and that
 * `WhileSubscribed(5_000)` is what carries a subscription across a configuration change. A
 * coroutines upgrade that changes any of them fails this suite instead of quietly turning the
 * guide — and the three ViewModels that follow it — into fiction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateAndEventSemanticsTest {

    @Test
    fun `a StateFlow replays its current value to every new collector`() = runTest {
        val state = MutableStateFlow("idle")
        state.value = "signed-in"

        assertEquals("signed-in", state.first())
        // The collector that replaces it after a configuration change sees the same thing,
        // which is exactly right for state and exactly wrong for something that should
        // happen once.
        assertEquals("signed-in", state.first())
    }

    @Test
    fun `a SharedFlow with no replay drops what it is given while nobody is subscribed`() =
        runTest {
            val events = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)

            // tryEmit reports success: the emission was accepted, there was simply no one to
            // accept it *for*. Nothing about the call site says the event was thrown away.
            assertTrue(events.tryEmit("signed-in"))

            assertNull(withTimeoutOrNull(NO_EVENT_TIMEOUT) { events.first() })
        }

    @Test
    fun `a Channel buffers an event raised with no collector attached`() = runTest {
        val channel = Channel<String>(Channel.BUFFERED)
        val events = channel.receiveAsFlow()

        channel.trySend("signed-in")

        assertEquals("signed-in", events.first())
    }

    @Test
    fun `a Channel delivers each event exactly once`() = runTest {
        val channel = Channel<String>(Channel.BUFFERED)
        val events = channel.receiveAsFlow()
        channel.trySend("signed-in")

        assertEquals("signed-in", events.first())

        assertNull(withTimeoutOrNull(NO_EVENT_TIMEOUT) { events.first() })
    }

    @Test
    fun `WhileSubscribed does not restart the upstream across a rotation-sized gap`() = runTest {
        var subscriptions = 0
        val state = countingUpstream { ++subscriptions }.stateIn(
            scope = backgroundScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT.inWholeMilliseconds),
            initialValue = 0,
        )

        val before = launch { state.collect() }
        runCurrent()
        assertEquals(1, subscriptions)

        before.cancel()
        runCurrent()
        advanceTimeBy(SHORT_GAP)

        val after = launch { state.collect() }
        runCurrent()

        // The upstream was never stopped, so there is nothing to start again: no second query,
        // no second network call, no spinner between the two halves of a rotation.
        assertEquals(1, subscriptions)
        after.cancel()
    }

    @Test
    fun `WhileSubscribed restarts the upstream past the timeout but still serves the last value`() =
        runTest {
            var subscriptions = 0
            val state = countingUpstream { ++subscriptions }.stateIn(
                scope = backgroundScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT.inWholeMilliseconds),
                initialValue = 0,
            )

            val before = launch { state.collect() }
            runCurrent()
            assertEquals(1, state.value)

            before.cancel()
            runCurrent()
            advanceTimeBy(LONG_GAP)

            // The upstream is gone — the point of the timeout is that a screen the user left
            // stops holding a database or socket subscription open. The value it produced is
            // not gone with it: `replayExpirationMillis` defaults to infinite, so a screen
            // coming back renders data first and refreshes underneath, rather than flashing
            // its initial value.
            assertEquals(1, state.value)

            val after = launch { state.collect() }
            runCurrent()

            assertEquals(2, subscriptions)
            assertEquals(2, state.value)
            after.cancel()
        }

    /**
     * A flow that counts every subscription, emits the running total, and then stays open the
     * way a Room query or a socket does. A flow that completes would make `WhileSubscribed`
     * unobservable: there would be nothing left to keep alive.
     */
    private fun countingUpstream(onSubscribe: () -> Int) = flow {
        emit(onSubscribe())
        awaitCancellation()
    }

    private companion object {
        /** The policy the ViewModels in this app use. */
        val STOP_TIMEOUT: Duration = 5.seconds

        /** A configuration change: gone and back well inside the timeout. */
        val SHORT_GAP: Duration = 4.seconds

        /** The user actually left the screen. */
        val LONG_GAP: Duration = 6.seconds

        /** Virtual time under `runTest`, so an absence assertion costs nothing to make. */
        val NO_EVENT_TIMEOUT: Duration = 1_000.milliseconds
    }
}
