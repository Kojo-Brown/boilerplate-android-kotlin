# Recomposition profiling

How to get recomposition counts out of a running build of this app, what a bad count looks
like, and what a pass over these six screens actually found.

The companion page is [`immutability.md`](immutability.md), which covers the stability of the
*types* a composable takes. This one covers the stability of the *lambdas* it is handed, which
is the half no annotation can fix and no `data class` can express.

Both are arguments about how to write the code. [`compose-metrics.md`](compose-metrics.md) is
what checks the argument held: the compiler's per-composable skippability verdict, gated in CI.
Read that first when a screen is slow — it costs a build rather than a device.

## Turning the counts on

Recomposition counts come from Layout Inspector, and they are off by default.

1. Run a **debug** build on a device or emulator. The counts come from the Compose runtime's
   tooling hooks, which `androidx.compose.ui:ui-tooling` adds and which this app pulls in as a
   `debugImplementation` (see `configureCompose` in `build-logic`). A release build reports
   nothing.
2. **View → Tool Windows → App Inspection → Layout Inspector**, and pick the process.
3. In the Layout Inspector toolbar, enable **Show Recomposition Counts**. Two columns appear
   beside the component tree:
   - **Composition** — how many times that node has been *recomposed* since counting started.
   - **Skip** — how many times it was reached and *skipped* because none of its inputs changed.
4. Use the **reset** button next to those columns before each experiment. The counts are
   cumulative from when the inspector attached, which includes the first composition and every
   frame you spent finding the screen.

The numbers only mean something against a stated interaction. "Type five characters into the
search field" or "tap three rows in the list" is an experiment; "open the app and look" is not.

## Reading them

The column to watch is **Skip**, not Composition. A composable that recomposes on every frame
of a scroll is doing its job if its inputs really are changing; a composable that recomposes
with a Skip count stuck at zero is one whose inputs the runtime cannot prove unchanged, and
that is the defect.

Two shapes account for nearly all of it:

- **An unstable parameter type.** The runtime cannot trust `equals`, so it cannot decide the
  input is unchanged. `immutability.md` covers this, and `StabilityContractTest` gates it.
- **A parameter that is a new instance every time.** Usually a lambda. The type is fine; the
  value is different on each pass, so the comparison fails however good `equals` is.

The second is the one this page is about, and it is invisible in review: `onClick = { … }` is
the same eight characters whether the lambda behind it is memoised or reallocated.

## What decides whether a lambda is memoised

The Compose compiler wraps a lambda in a `remember` keyed on what the lambda captures. Whether
it does that, and what the key compares with, depends on **strong skipping**, which has been on
by default since Kotlin 2.0.20 and is on here: this project pins Kotlin 2.1.0 and declares no
`composeCompiler { }` block, so the plugin defaults apply.

| | Strong skipping off | Strong skipping on (here) |
|---|---|---|
| Lambda capturing only stable values | memoised, key compared with `equals` | same |
| Lambda capturing an unstable value | **not memoised** — reallocated every recomposition | memoised, unstable captures compared with `===` |

So `onClick = { viewModel.onEvent(RefreshClicked) }` — the shape every screen in this app was
written in — behaves completely differently under the two modes. With strong skipping off it is
a fresh instance on every recomposition of the screen, every child holding one sees a changed
parameter, and the Skip column below that screen reads zero all the way down. With strong
skipping on, `viewModel` is the same instance across recompositions (it is remembered by
`hiltViewModel()`), the `===` comparison holds, and the same lambda comes back.

## What the pass over this app found

**No hotspot of the kind this page exists to catch.** Every callback in the six screens
captures either a function-typed parameter, a `@Stable` holder, or the view model — and the
last of those is memoised by instance identity under the mode this build compiles in. The
navigation callbacks in `AppNavHost` capture `NavHostController`, which is the same case.

That is a finding about the *compiler flag*, not about the code, and it is worth separating the
two:

- The screens skip today.
- They skip because of a default. Strong skipping was opt-in in Kotlin 2.0.0, became the
  default in 2.0.20, and is one `composeCompiler { featureFlags = … }` line away from being
  turned off — by someone chasing a different problem, months from now, with no reason to
  connect it to a search field that has become sluggish.
- Nothing failed when the code relied on it, and nothing would fail when the reliance broke.
  That is the same "silent, and expensive much later" failure mode `StabilityContractTest` was
  written for.

So the callbacks were changed to stop depending on it. `rememberEventSink` hands a composable
one `(E) -> Unit` for its view model's `onEvent`, remembered against the view model:

```kotlin
val onEvent = rememberEventSink(viewModel)
…
RefreshAction(inProgress = state.isRefreshing, onRefresh = { onEvent(RefreshClicked) })
```

A function type is in the Compose compiler's known-stable set, so `{ onEvent(RefreshClicked) }`
captures only stable values and is memoised under **either** mode. The screen's skipping is now
a property of its source rather than of how the module was compiled.

[`RecompositionContractTest`](../app/src/test/kotlin/com/kojo/boilerplate/architecture/RecompositionContractTest.kt)
holds it there: a composable may name its view model to read `state`, to read `effects`, and to
pass it to `rememberEventSink` — and `testDebugUnitTest` goes red on anything else.

## What this page does not claim

**The counts above were not read off a device.** The scheduled agent that maintains this
repository runs on Linux with no Android SDK, no emulator and no Android Studio —
`dl.google.com` is unreachable from it, which is also why `scripts/jvm-harness/` exists — so
none of Layout Inspector, `--metricsDestination` or a Macrobenchmark run can be executed here.
What is written down is the procedure, and an analysis of the six screens against the Compose
compiler's documented memoisation rules. Anyone with a device should run the experiments below
and put the real numbers in this file; a measured count that contradicts the analysis above is
the analysis being wrong, not the device.

Three experiments worth running, in the order they are worth running:

1. **Type into the Home search field.** Watch `RefreshAction` and the `TopAppBar` — neither
   reads `searchQuery`, so both should show a Skip count climbing with each keystroke and a
   Composition count that does not move.
2. **Tap rows in the two-pane layout on a tablet.** Watch the navigation rail. Selecting a user
   invalidates the scope in `AppNavHost` that `MainNavScaffold` is called from, so the rail is
   re-invoked on every tap; whether it *skips* depends on `content`, which is a composable
   lambda capturing the selection. This is the one place a real measurement is most likely to
   disagree with the analysis, and it is a scope-hoisting question rather than a lambda one.
3. **Pull-to-refresh with the list on screen.** `HomeContent.Users` is `@Immutable` over an
   `ImmutableList`, so a refresh that returns identical rows should skip the entire list rather
   than rebuild it — the claim `immutability.md` makes, measured.

## The rule, in one line

Never let a lambda a composable passes downwards capture the view model. Capture the event sink
instead.
