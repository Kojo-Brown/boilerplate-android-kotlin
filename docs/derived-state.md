# Derived state and `remember` keying

Two tools for the same job — stop a composable doing work whose answer has not changed — and
the two places in this app where that job exists.

The companion pages are [`recomposition.md`](recomposition.md), which is about the lambdas a
composable hands downwards, and [`immutability.md`](immutability.md), which is about the types
it takes. Both are about making a recomposition *skippable*. This one is about not being
invalidated in the first place, which is the cheaper thing to be.

`derivedStateOf` and `remember` are not interchangeable and it is worth saying why up front:

- **`remember`** answers *when should this value be computed again?* Its keys are the answer.
- **`derivedStateOf`** answers *which changes should reach the code reading this value?* Its
  block is the answer.

## `derivedStateOf`

### What it actually does

A composable is invalidated by the *state objects* it read, not by the values it used. A screen
in this app reads one:

```kotlin
val state by viewModel.state.collectAsStateWithLifecycle()
```

That is a single `State<HomeUiState>`. Every scope that touches `state.anything` becomes a
reader of the whole object, so a change to any one field invalidates every one of them —
including the scopes that only ever looked at a field that did not move.

`derivedStateOf` interposes a second state object that recomputes when its dependencies change
and **notifies its own readers only when the recomputed result differs**. The scope reading the
derived value is a reader of the derivation, not of the state behind it.

### When it pays

Exactly one condition, and it is a claim about runtime frequencies rather than about the code:

> the source changes more often than the result, **and** the scope doing the reading is not
> already reading the source for something else.

Both halves matter, and the second is the one that is easy to miss. A derivation placed in a
scope that reads the fast-changing field anyway cuts nothing — the scope is invalidated by the
other read, and the derivation is a second state object to maintain on the way.

### Where it is used here

One place: the refresh control in `HomeScreen`'s app bar.

```kotlin
val isRefreshing by remember { derivedStateOf { state.isRefreshing } }
…
actions = { RefreshAction(inProgress = isRefreshing, onRefresh = { … }) }
```

`HomeUiState.searchQuery` is bound to the text field **undebounced**, deliberately — a character
that appears 300ms after it is typed reads as a broken keyboard, and `HomeViewModel` debounces
the derived query instead. So `state` is a new object on every keystroke. The app bar's `actions`
slot is a composable lambda, which carries its own recompose scope, and the only thing it wants
out of that object is one `Boolean` that changes when a refresh starts or ends. Reading
`state.isRefreshing` there made the app bar a reader of the search field.

What that cost before the change is small and worth stating plainly: the slot was invalidated per
keystroke, re-ran, called `RefreshAction` with an unchanged `Boolean`, and `RefreshAction`
skipped. The change removes the invalidation rather than the render — the search field and the
app bar are simply no longer connected.

### Three candidates that look like this one and are not

**`state.searchQuery` in the Scaffold body.** The body reads `isOffline`, `searchQuery` and
`content`. Deriving any of them changes nothing: `SearchBar` needs `searchQuery`, so that scope
is invalidated per keystroke whatever the other two are wrapped in. Deriving `searchQuery` itself
is the anti-pattern in its purest form — a derivation that changes exactly as often as its
source is the source, plus an allocation and a dependency record.

**The flash action on the two camera screens.** `BarcodeScannerUiState` and
`TextRecognitionUiState` are two fields each, and the `actions` slot reads both. There is nothing
to narrow. Neither field changes at a rate worth narrowing either: `_state.update { … }` returns
the *same instance* when a frame arrives while a result is already on screen, and a
`MutableStateFlow` conflates an equal value, so the ML Kit analyser's several-frames-a-second
does not reach the composition at all. That guard is in the view models, and it is what makes
these screens not need this page.

**`useListDetailLayout()`.** The textbook shape — `currentWindowAdaptiveInfo()` updates
continuously through a drag-resize, and the `Boolean` it is narrowed to changes only at the
COMPACT boundary — and the one case `derivedStateOf` **cannot** address. A derivation narrows a
read it can perform *inside its own block*, and a block is not a composable context: by the time
`useListDetailLayout()` has returned a value, its state reads are already recorded against the
caller. Narrowing that one needs an API that hands back a `State`, which
`currentWindowAdaptiveInfo()` is not. Worth knowing, because "wrap it in `derivedStateOf`" is the
reflex and here it silently buys nothing.

## `remember` keying

Three forms, and the difference between them is the whole subject:

| Form | Recomputes | For |
|---|---|---|
| `remember { … }` | never, while composed | a value with no inputs, or a state holder's *initial* value |
| `remember(a, b) { … }` | when `a` or `b` changes | a value computed from inputs |
| `rememberUpdatedState(x)` | never — but reads stay current | a long-lived effect that must see the latest `x` |

The third is already in use: `ObserveAsEvents` holds its handler that way precisely so that the
`LaunchedEffect` collecting the event channel is **not** keyed on it, because a collection
cancelled between taking an element and emitting it loses that element.

The second is what `MainNavScaffold` now uses:

```kotlin
val navItems = remember(navController, currentTopLevel) { persistentListOf(…) }
```

The list is built from exactly two things, so it is keyed on exactly those two. This is not a
hotspot and the point is not the three allocations it saves. `items` is the parameter
`AdaptiveNavigationScaffold` skips on; `ImmutableList` is in the Compose compiler's known-stable
set, which puts that parameter on **structural** equality; `AdaptiveNavItem` is a `data class`
holding an `ImageVector`; and `ImageVector.equals` compares the whole path tree. An unkeyed
rebuild therefore pays a deep comparison of two Material icons per item, every pass, to conclude
that nothing changed. Keyed, the same question is an identity hit.

How often "every pass" is, is not up to `MainNavScaffold`. `useListDetailLayout()` returns a
value, so it is not restartable, so the window-metrics state it reads is recorded against its
caller — the `Home` entry in `AppNavHost`. Every posture or window-size update recomposes that
entry and re-invokes the scaffold, which during a drag-resize in split-screen is per frame.

### The failure mode the keyed form has

`remember(key) { … }` throws the old value away when `key` changes. That is right for a computed
value and wrong for anything the user has touched, which is why a state holder is the one thing
that stays unkeyed:

```kotlin
var selectedUserId by rememberSaveable { mutableStateOf<String?>(null) }
```

Keying that on anything would reset the selection whenever the key moved. `mutableStateOf` takes
an *initial* value by definition; `DerivedStateContractTest` knows the state factories by name
for that reason.

## What the contract test holds, and what it cannot

[`DerivedStateContractTest`](../app/src/test/kotlin/com/kojo/boilerplate/architecture/DerivedStateContractTest.kt)
runs under `testDebugUnitTest` and enforces three things:

- **Every `derivedStateOf` sits directly inside a `remember`.** Unremembered, a derived state is
  rebuilt every recomposition, so it keeps neither the recorded dependencies nor the previous
  result — the two things it exists for. It is then strictly the plain read plus overhead, and
  nothing anywhere reports it.
- **No unkeyed `remember { }` reads a parameter of its composable** — the frozen-argument bug —
  unless what it builds is a state holder.
- **The call sites are pinned.** Both `derivedStateOf` and `remember` sites are counted per file
  and compared against a list, so adding either is a deliberate act.

It cannot check the thing that actually decides whether a derivation belongs: whether it
narrows. That is a claim about how often a state changes at runtime, which is not in the source.
The pin is the substitute — it puts a reviewer in front of the claim, and this page is where the
claim goes.

## What this page does not claim

**Nothing here was measured on a device.** The scheduled agent that maintains this repository
runs on Linux with no Android SDK and no emulator — `dl.google.com` answers 403 on CONNECT, which
is why `scripts/jvm-harness/` exists — so Layout Inspector and Macrobenchmark can neither of them
run here. What is written down is an analysis against the Compose runtime's documented
invalidation rules, and the procedure for checking it is in
[`recomposition.md`](recomposition.md#turning-the-counts-on).

### The one open question from `recomposition.md`, resolved on paper

That page left experiment 2 — tapping rows in the two-pane layout and watching the navigation
rail — as "the one place a real measurement is most likely to disagree with the analysis", on
the reading that selecting a user invalidates the scope `MainNavScaffold` is called from. It
does not, and the reason is the same mechanism that makes `Scaffold`'s slots useful:

`selectedUserId` is read inside the `content` lambda passed to `MainNavScaffold`, and a
composable lambda is compiled into a `ComposableLambda` whose `invoke` opens a **restart group**
of its own. The read is therefore recorded against the lambda, not against the `composable<Home>`
entry that declares the state. A tap invalidates the lambda; the entry is not invalidated, the
scaffold is not re-invoked, and the rail is untouched.

So the rail is not on the selection path at all, and the `remember` above is not what puts it
there — a window-metrics update is. That is a claim about `ComposableLambdaImpl` rather than a
count off a device, and the experiment is still worth running; it is just no longer the one most
likely to disagree.
