package com.kojo.boilerplate.core.event

import com.kojo.boilerplate.core.coroutines.ApplicationScope
import com.kojo.boilerplate.core.coroutines.CoroutineFailureReporter
import com.kojo.boilerplate.core.coroutines.isFatal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The one subscription to [AppEventBus] that is always there, and the thing that makes an
 * [AppEventListener]'s delivery guarantee real.
 *
 * Everything about this class follows from one property of the bus: with `replay = 0`, an event
 * published while nothing is subscribed is thrown away, and `tryEmit` reports success anyway.
 * A UI collector is subscribed only while its screen is started, which for the event this app
 * publishes — a session dying mid-request — is exactly the wrong window. So the dispatcher
 * subscribes once, at process start, and stays subscribed for the life of the process.
 *
 * ## Why listeners are not simply given the flow
 *
 * They could each collect it themselves, and then each would need its own scope, its own
 * failure handling, and its own answer to "am I subscribed yet?". Holding one subscription and
 * fanning out in-process makes those one decision instead of *n*, and it is what lets a
 * listener be a `fun interface` with a single suspend function and no coroutine machinery in
 * it at all.
 *
 * ## Delivery is sequential, and deliberately so
 *
 * Listeners are called one after another from a single collector rather than each in its own
 * `launch`. Fanning out would decouple a slow listener from the rest, at the cost of losing
 * the ordering between them and of orphaning failures in coroutines nothing awaits. The
 * sequential version is also the one whose backpressure is honest: a listener that hangs
 * eventually fills the bus's buffer and makes [AppEventBus.tryPublish] return `false`, which is
 * a signal, where a fan-out would keep accepting events and quietly accumulate work.
 */
@Singleton
class AppEventDispatcher @Inject constructor(
    private val bus: AppEventBus,
    private val listeners: Set<@JvmSuppressWildcards AppEventListener>,
    private val reporter: CoroutineFailureReporter,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private var subscription: Job? = null

    /**
     * Subscribes to the bus. Called from `BoilerplateApp.onCreate`, and idempotent so that a
     * second call — a test, a process with more than one entry point — does not double-deliver
     * every event.
     *
     * [CoroutineStart.UNDISPATCHED] is load-bearing and is the sort of thing that works
     * anyway on a developer's machine. A default `launch` returns before its body has run, so
     * the subscriber is registered on some later dispatch; anything published in between is
     * dropped and reported as accepted. Starting undispatched runs the body on the calling
     * thread up to its first real suspension, and `collect` on a `SharedFlow` registers the
     * subscription *before* it suspends — so by the time this method returns, the bus has a
     * subscriber. `AppEventDispatcherTest` publishes on the line after `start()` for exactly
     * this reason, and that test fails without this argument.
     */
    fun start() {
        if (subscription?.isActive == true) return
        subscription = scope.launch(CoroutineName(COROUTINE_NAME), CoroutineStart.UNDISPATCHED) {
            bus.events.collect { event ->
                listeners.forEach { listener -> deliver(listener, event) }
            }
        }
    }

    /**
     * Hands [event] to [listener], absorbing whatever it throws.
     *
     * The subscription is a single `collect`, so an exception escaping a listener does not fail
     * that listener — it cancels the collection, and every listener stops receiving events for
     * the remaining life of the process. Nothing crashes and nothing looks wrong; the app just
     * stops reacting. Containing the failure per listener is what keeps one broken reaction
     * from taking the others with it.
     *
     * Two throwables are not absorbed. A [CancellationException] is the scope being torn down
     * and must reach the machinery, not a reporter. A fatal throwable means the VM has already
     * told us it cannot continue, so it is reported and then rethrown, where the application
     * scope's [com.kojo.boilerplate.core.coroutines.AppCoroutineExceptionHandler] escalates it
     * to the platform — the same treatment fatals get everywhere else in this app.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun deliver(listener: AppEventListener, event: AppEvent) {
        try {
            listener.onEvent(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            reporter.report(COROUTINE_NAME, failure)
            if (failure.isFatal) throw failure
        }
    }

    private companion object {
        const val COROUTINE_NAME = "app-events"
    }
}
