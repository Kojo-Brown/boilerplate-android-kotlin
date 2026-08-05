package com.kojo.boilerplate.core.ui.event

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Collects a one-shot event stream for as long as the screen is at least
 * [Lifecycle.State.STARTED], and hands every event to [onEvent] exactly once.
 *
 * This is the counterpart to `collectAsStateWithLifecycle` for things that are *not* state:
 * navigate, show a snackbar, fire a toast. State is a value the screen renders and re-renders;
 * an event happens once and must not happen again because the Activity was recreated. The full
 * decision guide is in `docs/state-and-events.md`.
 *
 * Three details carry the behaviour:
 *
 * - `repeatOnLifecycle(STARTED)` means nothing is collected while the screen is stopped, so an
 *   event that arrives then is not delivered into a dead composition — it waits in the
 *   producer's buffer and arrives when the screen comes back. That only holds if the producer
 *   buffers when nobody is listening, which is why the ViewModels here back their events with a
 *   `Channel` rather than a `SharedFlow` with no replay.
 * - `Dispatchers.Main.immediate` runs the handler on the current dispatch rather than
 *   scheduling one, so a navigation event takes effect before the next frame instead of
 *   letting the screen it is leaving render once more.
 * - [rememberUpdatedState] keeps the handler current without restarting collection. The
 *   `LaunchedEffect` deliberately does not key on [onEvent] — screens pass a lambda that is a
 *   new instance on every recomposition, and keying on it would tear down and restart the
 *   collection each time. A `receiveAsFlow` collection that is cancelled between taking an
 *   element from the channel and emitting it downstream loses that element, so restarting on
 *   every frame is how events go missing. For the same reason [events] must be a stable
 *   property on the ViewModel, not a getter that builds a new flow on each read.
 */
@Composable
fun <T> ObserveAsEvents(events: Flow<T>, onEvent: (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEvent by rememberUpdatedState(onEvent)

    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) {
                events.collect { event -> currentOnEvent(event) }
            }
        }
    }
}
