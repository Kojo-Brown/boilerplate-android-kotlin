package com.kojo.boilerplate.core.event

import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The app-wide broadcast channel for [AppEvent].
 *
 * An interface rather than the `MutableSharedFlow` itself, because the two halves of a shared
 * flow are different capabilities and almost nothing needs both: a listener needs [events] and
 * must not be able to emit onto it, and a producer needs [publish] and has no business
 * collecting. Exposing the mutable flow directly is how a bus acquires a listener that reacts
 * to an event by publishing another one.
 */
interface AppEventBus {

    /**
     * The broadcast, with no replay: a subscriber sees what is published while it is
     * subscribed and nothing that happened before.
     *
     * That is the correct default and the sharp edge at the same time. Replaying would mean a
     * screen recreated by a rotation re-handling an event it already handled — the failure
     * `docs/state-and-events.md` works through — but it also means an event published while
     * nothing is subscribed is gone, silently. [AppEventDispatcher] is the answer: it holds a
     * subscription for the life of the process, so listeners that must never miss an event are
     * registered there rather than collecting this directly.
     */
    val events: SharedFlow<AppEvent>

    /**
     * Publishes [event], suspending if every subscriber is still busy with a full buffer.
     *
     * Preferred wherever the caller is already in a coroutine: suspending applies backpressure
     * to whatever is producing events too fast, rather than choosing between dropping one and
     * growing a queue without bound.
     */
    suspend fun publish(event: AppEvent)

    /**
     * Publishes [event] without suspending, for callers that are not in a coroutine — an
     * OkHttp `Authenticator` on a request thread, a platform callback.
     *
     * @return `false` when the buffer is full, meaning a subscriber is far enough behind that
     *   the event could not be accepted. It is **not** an indicator that anyone received it:
     *   with no subscribers at all, the emission is dropped and this returns `true`, which is
     *   the trap `StateAndEventSemanticsTest` pins. A caller that cares about delivery needs a
     *   subscriber that is always there, not a truthy return value.
     */
    fun tryPublish(event: AppEvent): Boolean
}

/**
 * The [AppEventBus] the app runs with, backed by a `MutableSharedFlow`.
 *
 * The buffer configuration is the whole of the design:
 *
 * - `replay = 0` for the reason above — a replayed event is an event handled twice.
 * - `extraBufferCapacity` non-zero so that [tryPublish] has somewhere to put an event when a
 *   subscriber has not finished the previous one. With a zero buffer, `tryEmit` can only
 *   succeed when every subscriber is idle at that instant, so the non-suspending path would
 *   fail intermittently under exactly the conditions it exists for.
 * - [BufferOverflow.SUSPEND] rather than `DROP_OLDEST`. A dropping buffer makes [tryPublish]
 *   return `true` unconditionally and quietly discards a pending event instead; for events
 *   that mean "the session is gone", losing one without a word is the worst of the available
 *   outcomes. Suspending gives [publish] backpressure and gives [tryPublish] a `false` its
 *   caller can act on.
 *
 * Sixteen is chosen to be larger than any burst this app can produce — the only publisher is
 * a token refresh failing — so a full buffer means a subscriber is wedged, not that the app is
 * busy.
 */
class SharedFlowAppEventBus @Inject constructor() : AppEventBus {

    private val mutableEvents = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    override val events: SharedFlow<AppEvent> = mutableEvents.asSharedFlow()

    override suspend fun publish(event: AppEvent) {
        mutableEvents.emit(event)
    }

    override fun tryPublish(event: AppEvent): Boolean = mutableEvents.tryEmit(event)

    private companion object {
        const val BUFFER_CAPACITY = 16
    }
}
