package com.kojo.boilerplate.core.ui.udf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The contract every screen in this app is written against: one [state] to render, one
 * [onEvent] to send interactions to, one [effects] stream for what must happen exactly once.
 *
 * Data flows one way round the loop — state down into the composable, events back up, effects
 * out sideways — and the value of pinning it in a base class is that the *shape* stops being a
 * per-screen decision. Before this, `HomeViewModel` exposed four `StateFlow`s and four public
 * methods, `BarcodeScannerViewModel` two flows and five methods, and each screen's composable
 * had to know which was which. `docs/unidirectional-data-flow.md` records what that cost.
 *
 * ### Why [state] is abstract rather than a `MutableStateFlow` here
 *
 * The obvious base class owns a `MutableStateFlow` and hands subclasses a `setState { }`. It
 * would be wrong for half of this app. `HomeViewModel` and the two profile view models derive
 * their state from an upstream flow and end in
 * `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`, which is what keeps the Room
 * query and the connectivity callback running only while something is looking and across a
 * rotation but no longer — see `docs/state-and-events.md`. A base-owned `MutableStateFlow`
 * can only be filled by a `viewModelScope.launch { upstream.collect { … } }`, which subscribes
 * eagerly for the life of the view model and throws that away. Declaring [state] abstract
 * costs each subclass one line and lets it choose; both shapes appear in this app.
 *
 * @param S what the screen renders. One type per screen, and the only thing it renders from.
 * @param E what the user can do. Sealed, and the only way into the view model.
 * @param F what happens once. Sealed, or [Nothing] for a screen that decides no such thing.
 */
abstract class UdfViewModel<S, E : UiEvent, F : UiEffect> : ViewModel() {

    /**
     * What the screen renders, and all of it: a screen collects this and nothing else.
     *
     * Subclasses building it with `stateIn` inherit that operator's trap — with no collector,
     * `state.value` is the initial value however long the view model has been alive. A test
     * asserting against `.value` without collecting is asserting against `Loading`, and any
     * `onEvent` handler reading `state.value` to decide something is reading the same. Both
     * cases show up in this app and both keep a collector alive; see `HomeViewModelTest`.
     */
    abstract val state: StateFlow<S>

    /**
     * A [Channel] rather than a `SharedFlow`, for the reason `docs/state-and-events.md` sets
     * out at length: a `MutableSharedFlow(replay = 0)` drops what it is given while nobody is
     * subscribed, and `tryEmit` still reports success. A screen is unsubscribed whenever it is
     * stopped, which on the sign-in screen is the *ordinary* case — the credential picker is
     * another Activity on top of it, so sign-in almost always completes with this screen
     * stopped. A `Channel` buffers instead, and the effect arrives when collection resumes.
     *
     * `receiveAsFlow` and not `consumeAsFlow`: the second closes the channel when its
     * collector goes away, so the first configuration change would leave a dead stream behind.
     *
     * The trade is that a `Channel` fans out to exactly one collector, which for a screen is a
     * feature — two collectors of a navigation effect navigate twice. Broadcasting to several
     * independent listeners is a different problem with a different answer: `AppEventBus`.
     */
    private val _effects = Channel<F>(Channel.BUFFERED)

    /** Read with `ObserveAsEvents`, which collects only at `STARTED`. Never with `collectAsState`. */
    val effects: Flow<F> = _effects.receiveAsFlow()

    /**
     * The single entry point. Every interaction the screen offers arrives here, and a screen
     * that needs something the sealed [E] does not name has to add a member — which is the
     * point, because that addition is visible in one file and the `when` below stops compiling
     * until it is handled.
     */
    abstract fun onEvent(event: E)

    /**
     * `send` inside a launch, not `trySend`.
     *
     * `trySend` is the obvious choice for a non-suspending caller and it fails silently: a
     * `Channel.BUFFERED` holds 64, and a view model that keeps producing while the screen is
     * stopped can fill it. `trySend` then returns a failure nobody looks at and the effect is
     * gone — the same silent loss the `SharedFlow` above was rejected for, reintroduced one
     * layer down. `send` suspends until there is room instead, so nothing is dropped.
     *
     * Ordering survives the extra launch. `viewModelScope` dispatches on
     * `Dispatchers.Main.immediate`, so two calls made in sequence are queued in sequence, and
     * `send` completes without suspending whenever the buffer has room — which is every case
     * short of the overflow this exists to handle.
     *
     * Uncallable when `F` is [Nothing]: there is no value to pass it.
     */
    protected fun emitEffect(effect: F) {
        viewModelScope.launch { _effects.send(effect) }
    }
}
