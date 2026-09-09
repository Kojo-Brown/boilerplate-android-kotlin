# The Compose compiler's stability report, as a CI gate

What the Compose compiler writes down about this app's stability, how to get it out of a
build, how to read it, and what CI does with it.

The companion pages are [`immutability.md`](immutability.md), which covers the stability of the
*types* a composable takes, and [`recomposition.md`](recomposition.md), which covers the
*lambdas* it is handed. Both are arguments about how the code should be written. This one is
about the only artefact that says whether the argument worked.

## Why a report rather than a test

`StabilityContractTest` already holds every type reachable from a view model's `StateFlow` to
the `@Immutable`/`@Stable` contract, by reflection over compiled classes. It is a strong check
and it cannot answer the question that actually matters, because that question is not about a
type at all.

Skipping is decided **per composable, over all of its parameters at once**. A screen made
entirely of annotated state still recomposes on every frame if one parameter is an androidx
type the compiler treats as unstable, or if a default expression is unstable, or if the
function returns something other than `Unit`. None of those is a property of a state class,
so none of them is visible to a reflective audit of state classes.

The compiler knows the answer — it has to, in order to emit the skipping code — and it will
write it down if asked. That is what these reports are. Left unread they are the worst kind of
diagnostic: a screen that stops skipping produces no build failure, no warning and no log
line. It produces jank, months later, in a screen nobody has touched, traceable back to a
one-line change that looked free.

## Getting the reports

```
./gradlew compileDebugKotlin -PcomposeCompilerReports=true --no-build-cache
```

Both flags are load-bearing.

`-PcomposeCompilerReports=true` is what `configureComposeCompilerReports` in `build-logic`
reads before setting `metricsDestination` and `reportsDestination` on the Compose compiler
extension. It is opt-in because setting those destinations changes the compiler arguments,
which changes every Compose module's compile-task cache key: with the reports always on, a
developer would pay a full recompile of the UI modules the first time they ran a command
without them.

`--no-build-cache` is the one that surprises people. The destinations are
`FilesSubpluginOption`s of the compiler plugin's default (`INTERNAL`) kind, so the Kotlin
compile task does **not** declare them as outputs. A task Gradle restores from the build cache
therefore produces no reports at all — and CI shares a build cache across runs, so the second
run on a branch is exactly where that would bite. This is the same shape as the exported-schema
problem in [`room-migrations.md`](room-migrations.md): a file produced as a side effect of a
task that did not run.

The output lands in each module's own build directory, one directory per kind:

```
feature/home/build/compose-reports/home_debug-composables.txt
feature/home/build/compose-reports/home_debug-classes.txt
feature/home/build/compose-metrics/home_debug-module.json
```

CI uploads all of it in the `gate-reports` artifact, so the evidence for a failed run is one
download rather than one re-run.

## Reading them

**`-composables.txt`** is the one to read first. Each entry is a composable with the compiler's
verdict in front of it and its parameters underneath:

```
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun HomeScreen(
  stable modifier: Modifier? = @static Companion
  stable state: HomeUiState
  stable onEvent: Function1<HomeEvent, Unit>
)
```

- **`restartable`** — the compiler wrapped the body in a restart group, so the runtime *can*
  re-execute this function on its own when something it reads changes.
- **`skippable`** — when it is re-executed with arguments equal to the ones it already has, it
  returns immediately. This is the property worth having.
- **`readonly`** and **`inline`** — neither is restartable, so neither can be unskippable.
  They are not a problem and the gate does not look at them.

`restartable` without `skippable` is the shape to fix: that composable re-runs its whole body
whenever its caller recomposes, however cheap or unchanged its arguments are. The parameter
list is the diagnosis — an `unstable` parameter is nearly always the cause and always the first
thing to look at.

**`-classes.txt`** explains *why* a type is unstable, member by member:

```
unstable class ProfileUiState {
  stable val name: String
  unstable val badges: List<Badge>
  <runtime stability> = Unstable
}
```

Most classes in this file are irrelevant to Compose — every view model in the app is reported
unstable, correctly, because a view model is not a composable parameter. The entries that
matter are the ones that appear as parameter types above.

**`-module.json`** is the aggregate: `totalComposables`, `skippableComposables`,
`restartableComposables`, `inferredUnstableClasses` and a dozen more counts. The verifier
prints these per module on every run, so a CI log carries the trend even when the gate is
green.

## The gate

`scripts/verify-compose-metrics.py`, run in the `gates` job immediately after
`compileDebugKotlin`. It fails on three things:

1. **A Compose module that reported nothing.** The expected set is derived from
   `settings.gradle.kts` and each module's `build.gradle.kts` — the modules applying a
   `*.compose` convention plugin — rather than from a list in the script, so a new UI module is
   covered the day it is created. Because the reports are a side effect of a cacheable task,
   "absent" is the failure mode most likely to happen and least likely to be noticed, so it is
   a failure and never a skip.
2. **A restartable composable that is not skippable**, unless
   `config/compose-metrics/unskippable-allowlist.txt` names it. The failure quotes the unstable
   parameter and, where the type is one this repository declares, the member of it that the
   compiler objected to.
3. **An allowlist entry that no longer matches anything.** An exemption that outlives its
   composable is worse than no exemption: it silently covers whatever is named the same thing
   next.

Every entry in the allowlist carries a reason and the parser rejects one that does not. The two
fixes worth trying before adding an entry:

- An unstable parameter **declared here** is an `@Immutable`/`@Stable` question, and
  [`immutability.md`](immutability.md) is the answer to it. A `List` property is the usual
  culprit and `ImmutableList` the usual fix.
- An unstable parameter **from a library** is usually a parameter that should not be crossing
  the boundary at all. A composable taking a `NavHostController` can nearly always take the two
  lambdas it calls instead — which is also what makes it previewable.

`scripts/verify-compose-metrics.test.py` drives the verifier over fixture repositories written
into a temp directory, so each of those failures is shown to fire when it should and only when
it should. It needs no Gradle, no Android SDK and no network, which is what keeps the gate's
logic reviewable in an environment where the Compose compiler cannot run at all. CI runs it
before the compile, so a broken verifier is reported in seconds rather than after twenty
minutes of Gradle.

## What this does not cover

**It is a static verdict, not a measurement.** "Skippable" means the runtime *may* skip, given
equal arguments; whether it actually does depends on what the caller passes and how often.
Layout Inspector's recomposition counts are the measurement, and
[`recomposition.md`](recomposition.md) is the procedure for taking them.

**Only the debug variant is reported**, because `compileDebugKotlin` is the gate CI runs.
Release compiles the same source with the same Compose plugin, so a stability difference
between the two would be surprising, but it is not checked here.

**Nothing gates on the aggregate numbers.** A baseline of
`skippableComposables`/`totalComposables` per module would catch a slow slide that the
per-composable rule permits — every individual composable staying accounted for while the
allowlist grows. The counts are printed on every run so the trend is in the log; turning them
into a threshold needs a few runs' worth of history first.
