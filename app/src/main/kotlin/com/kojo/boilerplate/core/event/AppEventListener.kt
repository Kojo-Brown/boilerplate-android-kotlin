package com.kojo.boilerplate.core.event

/**
 * A reaction to [AppEvent] that must run whether or not any UI is on screen.
 *
 * Listeners are contributed into a set with `@IntoSet` in
 * [com.kojo.boilerplate.core.di.AppEventModule] and driven by [AppEventDispatcher], which holds
 * one process-lifetime subscription on their behalf. That indirection is what buys them the
 * guarantee the raw [AppEventBus] cannot give: a `SharedFlow` with no replay drops what is
 * published while nobody is subscribed, and "nobody is subscribed" is the normal state of a UI
 * collector.
 *
 * A reaction that only makes sense with a screen present — navigating, showing a snackbar — is
 * not one of these. It collects [AppEventBus.events] from the composition with
 * `ObserveAsEvents`, and accepts that it misses what happens while the screen is stopped.
 *
 * Implementations should return quickly. The dispatcher delivers to listeners in sequence from
 * a single collector, so a slow one delays the rest and eventually fills the bus's buffer;
 * anything long-running should hand off to WorkManager rather than doing the work here.
 */
fun interface AppEventListener {

    /**
     * Handles [event], or ignores it. Listeners receive every event and filter for their own —
     * a per-type registry would be more code and one more thing to keep in step with the
     * sealed hierarchy.
     *
     * Throwing is permitted and is reported by [AppEventDispatcher]; it does not unsubscribe
     * this listener or any other.
     */
    suspend fun onEvent(event: AppEvent)
}
