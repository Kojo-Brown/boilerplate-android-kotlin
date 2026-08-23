# Unidirectional data flow

One `UiState` out, one `UiEvent` in, one `UiEffect` for what happens once — and why the
uniformity is the point rather than the ceremony.

The three types are per screen and the shape is not. Every view model in this app extends
[`UdfViewModel<S, E, F>`](../core/ui/src/main/kotlin/com/kojo/boilerplate/core/ui/udf/UdfViewModel.kt)
and exposes exactly three things:

```kotlin
abstract val state: StateFlow<S>          // what the screen renders
abstract fun onEvent(event: E)            // the only way in
val effects: Flow<F>                      // what happens exactly once
```

Nothing else is public.
[`UnidirectionalDataFlowContractTest`](../app/src/test/kotlin/com/kojo/boilerplate/architecture/UnidirectionalDataFlowContractTest.kt)
fails the build if a fourth member appears.

## What this replaced, and what it cost

`CLAUDE.md` has said "a single `UiState` per screen; no state in composables" since the repo
existed. Here is what the two most-developed screens actually looked like:

| | public flows | public methods |
|---|---|---|
| `HomeViewModel` | `uiState`, `searchQuery`, `isOffline`, `refreshState` | `updateSearchQuery`, `retry`, `refresh`, `dismissRefreshResult` |
| `TextRecognitionViewModel` | `uiState`, `isFlashEnabled`, `isPaused` | `onTextDetected`, `onPermissionDenied`, `onError`, `resumeScanning`, `toggleFlash` |

Every one of those was added for a reason, and none of them looked like drift at the time.
Three things came with them.

**Two flows are two values that can disagree.** `TextRecognitionViewModel` guarded its
detection with `uiState is Scanning && !isPaused`, reading both flags because neither could be
trusted alone — and `isPaused` was true in exactly the cases where the scan state was
`TextDetected`, so it was never information. It was a second copy of one, kept in step by
hand, and the guard was the place where the two were reconciled on every camera frame. Folding
the screen into one value deleted the flag: `isPaused` is now a computed property, and there is
no window in which it can be wrong.

**The screen had to know which member was which.** A composable collecting four flows and
calling four methods is written against eight decisions rather than against a contract, and
each of those is a place the next screen can diverge from this one.

**State that has to be cleared by hand is an event.** `RefreshState.Finished` was rendered as a
banner with a Dismiss button and cleared by `dismissRefreshResult()`. Rule 2 of
[`state-and-events.md`](./state-and-events.md) names that exact shape, and the failure is the
one that document describes: the banner survived every rotation until someone pressed the
button, and pressing it *during* a rotation lost the press. It is now a
`HomeUiEffect.RefreshIncomplete` shown as a snackbar — delivered once, and gone.

## The three types

### `UiState` — what the screen renders

One type per screen, and all of it. The screen collects `state` and nothing else.

The interesting choice is data class or sealed class, and the answer is usually **both**: a
data class of independent fields, with the mutually-exclusive part as a sealed field inside it.
`HomeUiState` is the case that makes the argument:

```kotlin
data class HomeUiState(
    val content: HomeContent = HomeContent.Loading,   // Loading | Users | Error
    val searchQuery: String = "",
    val isOffline: Boolean = false,
    val isRefreshing: Boolean = false,
)
```

A single *sealed* `HomeUiState` would be wrong, and the reasons are recorded next to the
fields: an `Error` case would exclude the search text the user had typed, and a `Loading` case
would exclude the offline banner. That is not hypothetical — it is why the offline flag was
split out into its own flow in the first place. What the fields do *not* justify is four
subscriptions. A data class keeps every distinction and still hands the composable one object
that cannot be observed half-updated.

The rule that falls out: **a field for what is independently true, a sealed type for what is
mutually exclusive.** The barcode screen's torch is a field because it is on or off underneath
whatever the scan is doing; its scan state is sealed because "scanning **and** permission
denied" is not a state that screen has.

Screens with nothing independent keep the sealed type they already had —
`ProfileUiState` is `Loading | Success | Error` and a one-field wrapper around it would be
ceremony.

### `UiEvent` — the only way in

Sealed, one member per interaction the view model needs to know about. The screen never calls a
method; it sends an event.

Two rules that are easy to get wrong:

- **Name the event for what happened, not for what should happen next.** `RetryClicked`, not
  `Reload`. An event named for the reaction has already decided what the tap means, in the
  composable, which is the layer that should not be deciding. `HomeUiEvent` has both
  `RetryClicked` and `RefreshClicked` for this reason: a user would call both "try again", and
  they are different operations — one resubscribes to the database, the other asks the network.
  Only the view model knows there is a difference.
- **"The user did something" is the wrong admission test; "the view model needs to know" is the
  right one.** Three of the barcode screen's five events come from the camera stack rather than
  from a finger — the analyser recognising a code, CameraX failing to bind, the permission
  dialog coming back denied. A screen that reported those by calling methods while reporting
  taps through `onEvent` would have two ways in again.

### `UiEffect` — what happens once

Sealed, and often absent. The distinction from state is the one
[`state-and-events.md`](./state-and-events.md) is built on: if the screen were destroyed and
rebuilt right now, should this happen again?

An effect exists when the **view model decides** that something should happen once. That is a
narrower test than "one-shot", and it is what keeps the type from filling up with forwarding:

- `GoogleSignInUiEffect.SignInFailed` **is** an effect. Whether a failure is worth telling the
  user about is a judgement made in the view model, on the exception — a cancelled credential
  picker is not news to the user who cancelled it.
- `HomeUiEffect.RefreshIncomplete` **is** an effect. The view model decides that a clean
  refresh reports nothing, because the refreshed rows are already visible.
- Tapping a user to open their profile **is not**. It means one thing, always, and routing it
  through the view model adds a handler that can only forward. It stays a composable parameter
  — which is also what lets `HomeTwoPaneScreen` reuse `HomeScreen` with the same tap selecting
  a pane instead of navigating.
- Copying a barcode to the clipboard **is not**. The composable owns the `ClipboardManager` and
  no decision is made on the way past.

Four of this app's six screens decide nothing of that kind, and they say so in their own
signature:

```kotlin
class ProfileViewModel : UdfViewModel<ProfileUiState, ProfileUiEvent, Nothing>()
```

`Nothing` has no instances, so "this screen emits no effects" is enforced by the compiler
rather than written in a comment that goes stale — and `emitEffect` becomes uncallable, because
there is no value to pass it.

## Why `effects` is a `Channel` and `state` is a `StateFlow`

Unchanged from [`state-and-events.md`](./state-and-events.md), which has the full table; the
short version is that a `StateFlow` replays its current value to whoever collects next, and
after a rotation there is always a next collector, so an effect held as state fires again. A
`MutableSharedFlow(replay = 0)` fails the other way: it drops what it is given while nobody is
subscribed, and `tryEmit` still returns `true`. On the sign-in screen that is the *ordinary*
path, not an edge case — the credential picker is another Activity on top of ours, so sign-in
almost always completes with the screen stopped.

`emitEffect` sends rather than `trySend`ing, for the same reason one layer down: `trySend` on a
full buffer returns a failure nobody looks at, which is the silent loss `SharedFlow` was
rejected for.

## Where the state comes from

`state` is abstract rather than a `MutableStateFlow` on the base class, and that is a
deliberate cost of one line per subclass. Both shapes appear in this app:

```kotlin
// Derived: Home, Profile, ProfileDetailPane
override val state: StateFlow<HomeUiState> = combine(content, searchQuery, offline, refreshing)
    { … }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

// Held: SignIn, BarcodeScanner, TextRecognition
private val _state = MutableStateFlow(BarcodeScannerUiState())
override val state: StateFlow<BarcodeScannerUiState> = _state.asStateFlow()
```

A base-owned `MutableStateFlow` can only be filled by a
`viewModelScope.launch { upstream.collect { … } }`, which subscribes eagerly for the life of
the view model. That would throw away `WhileSubscribed(5_000)` — the Room query, the search
debounce and the connectivity callback all start on the first collector and stop five seconds
after the last one leaves, which is long enough to cover a rotation and short enough that a
backgrounded screen holds nothing open.

One trap comes with the derived shape and it is worth stating twice: **with no collector,
`state.value` is the initial value forever.** An `onEvent` handler that reads `state.value` to
decide something is reading it from a composition that is collecting, so it is fine on device;
a test that reads it without collecting is asserting against `Loading` whatever the repository
was told to return. Every view model test here that touches a `stateIn`-backed state keeps a
collector on `backgroundScope` for exactly this reason.

## `combine` needs every input to emit

Folding four flows into one state moved a hazard from the screen into the view model.
`combine` produces nothing until *all* of its inputs have emitted, so a single slow input
stalls the whole screen — including the search field the user is typing into, which is not
waiting on anything. Both of `HomeViewModel`'s cold inputs therefore open with a value:

```kotlin
.onStart { emit(HomeContent.Loading) }              // decouples the field from the first DB read
.map { !it.isOnline }.onStart { emit(false) }       // assume online; no banner flash on cold start
.distinctUntilChanged()
```

Both emit exactly what `stateIn`'s initial value already holds, so nothing renders twice —
`stateIn` conflates the duplicate away. The `distinctUntilChanged` absorbs the second one when
the real status agrees with the assumption.

## Adding a screen

1. `<Screen>UiState` — data class of independent fields; sealed for the mutually-exclusive
   part; `@Immutable` on both, `val` only, `ImmutableList` never `List`
   ([immutability.md](./immutability.md)).
2. `<Screen>UiEvent : UiEvent` — sealed, one member per interaction, named for what happened.
3. `<Screen>UiEffect : UiEffect` — sealed, only for what the view model decides happens once.
   `Nothing` if that is none of it.
4. `class <Screen>ViewModel : UdfViewModel<S, E, F>()` — override `state`, override `onEvent`
   with an exhaustive `when`.
5. Add the class to `EXPECTED_VIEW_MODELS` in `UnidirectionalDataFlowContractTest` and in
   `StabilityContractTest`. Both assert the whole list rather than a subset, so a new screen
   has to be acknowledged rather than silently uncovered.

On the composable side:

```kotlin
val state by viewModel.state.collectAsStateWithLifecycle()
ObserveAsEvents(viewModel.effects) { effect -> … }   // never collectAsState
```

`ObserveAsEvents` collects only at `STARTED` and keeps its handler current without restarting
collection; the reasons are in its own KDoc and they are load-bearing.
