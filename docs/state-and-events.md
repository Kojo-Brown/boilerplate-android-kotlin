# State and events

Which stream a value belongs in, and what `WhileSubscribed(5_000)` is actually buying.

There is one question behind all of it: **if the screen were destroyed and rebuilt right now,
should this happen again?** A rotation, a theme change, a font-size change, an unfolding
device, a return from the background — the composition is thrown away and rebuilt while the
ViewModel lives on, so everything the ViewModel exposes is collected again from scratch. A
spinner should still be spinning afterwards. A snackbar that was already shown should not be
shown a second time. The first is state; the second is an event; and the difference is not a
matter of taste, because the streams behave differently.

Every claim below is pinned by
[`StateAndEventSemanticsTest`](../app/src/test/kotlin/com/kojo/boilerplate/core/coroutines/StateAndEventSemanticsTest.kt)
and [`GoogleSignInViewModelTest`](../app/src/test/kotlin/com/kojo/boilerplate/feature/signin/GoogleSignInViewModelTest.kt).

## The table

| | `StateFlow` | `SharedFlow(replay = 0)` | `Channel(BUFFERED).receiveAsFlow()` |
|---|---|---|---|
| Has a current value | always | never | never |
| New collector receives | the current value | nothing | anything not yet taken |
| Emitted with nobody collecting | kept | **dropped** | buffered |
| Delivery | every collector, conflated | every collector | exactly one collector, once |
| Use for | UI state | app-wide broadcasts to *n* listeners | one-shot events for one screen |

Conflation is the other half of `StateFlow`'s contract and the one people are surprised by:
equal consecutive values are dropped, and a slow collector sees only the latest. That is
correct for "what does the screen look like" and fatal for "what happened" — two identical
errors in a row are one event, not none.

## One-shot events: why not `SharedFlow`

`SharedFlow` is the answer the question's phrasing suggests, and for a broadcast to several
independent listeners it is the right one. For a screen's own events it is not, and the
failure is silent: `MutableSharedFlow(replay = 0)` has no subscribers when the screen is
stopped, and an emission with no subscribers is thrown away. `tryEmit` still returns `true` —
the emission was accepted; there was simply nobody to accept it *for*.

That is not an edge case on a sign-in screen. The credential picker is another Activity on top
of ours, so the screen is stopped for the entire time the interesting thing is happening.
Sign-in completing while nothing is subscribed is the *ordinary* path.

A `Channel` buffers instead. The event waits until something receives it, which is what
[`GoogleSignInViewModel`](../app/src/main/kotlin/com/kojo/boilerplate/feature/signin/GoogleSignInViewModel.kt)
uses:

```kotlin
private val _events = Channel<GoogleSignInEvent>(Channel.BUFFERED)
val events: Flow<GoogleSignInEvent> = _events.receiveAsFlow()
```

`receiveAsFlow` and not `consumeAsFlow`: the second closes the channel when its collector goes
away, so the first configuration change would leave the ViewModel with a dead event stream.
`receiveAsFlow` survives collectors coming and going, which is the whole point.

The trade is that a `Channel` fans out to exactly one collector. For a screen that is a
feature — two collectors of a navigation event navigate twice. For an app-wide broadcast it is
disqualifying, and that is the case `SharedFlow` exists for.

## Reading events on the UI side

[`ObserveAsEvents`](../app/src/main/kotlin/com/kojo/boilerplate/core/ui/event/ObserveAsEvents.kt)
is to events what `collectAsStateWithLifecycle` is to state:

```kotlin
ObserveAsEvents(viewModel.events) { event ->
    when (event) {
        is GoogleSignInEvent.SignedIn -> onSignedIn(event.user)
        is GoogleSignInEvent.SignInFailed -> scope.launch {
            snackbarHostState.showSnackbar(event.message)
        }
    }
}
```

It collects only at `STARTED`, on `Dispatchers.Main.immediate`, and keeps the handler current
through `rememberUpdatedState` rather than restarting collection when a recomposition produces
a new lambda. Restarting on every recomposition is the subtle way to lose buffered events.

## What this replaced

The sign-in screen used to drive both from `uiState`:

```kotlin
// before
LaunchedEffect(uiState) {
    if (uiState is GoogleSignInUiState.Error) {
        snackbarHostState.showSnackbar((uiState as GoogleSignInUiState.Error).message)
        viewModel.clearError()   // ← the tell
    }
}
```

`clearError()` is the tell. State that has to be cleared by hand after it is consumed is an
event wearing state's clothes, and the manual clear is the part that does not survive a
configuration change:

1. The sign-in fails. `uiState` becomes `Error("Network error")`.
2. The snackbar shows. `showSnackbar` suspends until it is dismissed, so `clearError()` has
   not run yet.
3. The user rotates the device. The composition — and with it the `LaunchedEffect` — is
   cancelled mid-suspension. `clearError()` never runs.
4. The new composition starts a `LaunchedEffect` keyed on the same `Error` state, and shows
   the same snackbar again. Rotate five times, see it five times.

The navigation callback had the same shape and got away with it only because the destination
pops the sign-in entry off the back stack immediately — correct by accident, one navigation
graph change away from double navigation.

Now `GoogleSignInUiState` has no `Error` case at all. The state after a failed sign-in is the
state the screen started in — `Idle`, offering the button — and the reason is a
`SignInFailed` event that is delivered once and gone.

## `WhileSubscribed(5_000)`

Every flow-backed `uiState` in this app ends the same way:

```kotlin
.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000L),
    initialValue = HomeUiState.Loading,
)
```

`SharingStarted` decides when the upstream — the Room query, the network call, the DataStore
read — actually runs:

| | Upstream starts | Upstream stops |
|---|---|---|
| `Eagerly` | when the ViewModel is constructed | when its scope is cancelled |
| `Lazily` | on the first collector | when its scope is cancelled |
| `WhileSubscribed(0)` | on the first collector | the moment the last one leaves |
| `WhileSubscribed(5_000)` | on the first collector | 5s after the last one leaves |

`Eagerly` and `Lazily` both hold the subscription open for a screen the user left ten minutes
ago. `WhileSubscribed(0)` has the opposite problem: a configuration change drops the collector
and adds a new one a moment later, so a zero timeout tears the query down and rebuilds it
across every rotation — a second database read, a second network call, and a spinner in
between.

Five seconds is comfortably longer than an Activity recreation (tens of milliseconds) and
short enough that a user who actually left is not holding a socket open. There is nothing
magic in the number; it is the standard value because it is the first round number safely past
the case it exists for.

Two things that follow from it, and both are easy to get wrong:

- **The cached value outlives the subscription.** `stateIn`'s `replayExpirationMillis`
  defaults to infinite, so when the upstream does stop the last value stays. A screen returned
  to after a minute renders its data immediately and refreshes underneath, instead of flashing
  `Loading`. Passing `WhileSubscribed(5_000, replayExpirationMillis = 0)` resets to
  `initialValue` on expiry and puts that flash back.
- **Nothing runs until something collects.** With no subscriber, `uiState.value` is the
  initial value forever. That is a live trap in tests: assert against `.value` without
  collecting and you are asserting against `Loading`, whatever the repository was told to
  return. `HomeViewModelTest` keeps a collector on `backgroundScope` for exactly this reason.

## Rules

1. Ask whether the value should happen again after the screen is rebuilt. "Yes" is state;
   "no" is an event.
2. State that has to be cleared after it is read is an event. Move it.
3. One-shot events for one screen: `Channel(BUFFERED).receiveAsFlow()`, read with
   `ObserveAsEvents`.
4. Broadcasts to several independent listeners: `SharedFlow` — and accept that a listener that
   is not subscribed misses it.
5. Flow-backed state: `stateIn(viewModelScope, WhileSubscribed(5_000), <initial>)`, and expose
   `StateFlow`, never the cold flow.
6. Never navigate from a `LaunchedEffect` keyed on state.
