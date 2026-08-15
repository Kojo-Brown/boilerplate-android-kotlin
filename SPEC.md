# Spec: boilerplate-android-kotlin

> Spec-driven. Mark `[x]` only after pushing.

## Phase 0 — Green Baseline (blocks all feature work)
- [x] Confirm the Gradle build resolves: verify every version in `libs.versions.toml` actually exists — every version resolves; the blockers were structural, not versions (PR #18)
- [x] Get `compileDebugKotlin`, `lintDebug`, `detekt`, and `testDebugUnitTest` passing locally — all four green in CI; six stacked failures behind them, from detekt never being configured to a test suite that deadlocked rather than failed (PR #19)
- [x] Promote `workflow-templates/ci.yml` to `.github/workflows/ci.yml` and confirm it runs green on a PR — promoted with `gates.yml` folded in and `workflow-templates/` removed; the template had never been runnable (PR #20)
- [x] Confirm `assembleDebug` produces an installable APK in CI — the APK is now checked, not just built: well-formed archive, 4-byte aligned, debug-signed under a v2+ scheme across API 26–35, carrying the package, versionCode, versionName, minSdk and targetSdk the build declares, with a launchable activity and the debuggable flag (PR #21)

Item 1 complete as of PR #18 (2026-07-31). `dependency-resolution.yml` resolved
31 configurations / 2309 module nodes with 0 failures in 1m41s, so every version
in `libs.versions.toml` exists — including `androidx.compose.material3.adaptive:adaptive`,
which the Compose BOM 2024.12.01 does supply despite being declared without one.

Nothing was wrong with the versions. The build simply could not be invoked: the
Gradle wrapper was missing `gradlew`, `gradlew.bat` and `gradle-wrapper.jar`;
there was no `gradle.properties`, so `android.useAndroidX` was unset; and
`room { }` sat inside `android { }`, where it does not compile.

Known gaps carried into item 2: the resolution gate checks metadata only, so a
published-but-empty module would still pass it — `compileDebugKotlin` and
`assembleDebug` are what close that. `detekt` is referenced by CLAUDE.md but is
not configured anywhere in the build, so item 2 has to add it. The wrapper has
no `distributionSha256Sum`. `README.md` still advertises "Retrofit 3 + OkHttp 5"
while the catalog pins Retrofit 2.11.0 / OkHttp 4.12.0 — a real migration, not a
doc typo. None of the gates in CLAUDE.md beyond `./gradlew --version` have run.

Item 2 complete as of PR #19 (2026-08-02). `compileDebugKotlin` (74s),
`lintDebug` (73s), `detekt` (8s) and `testDebugUnitTest` (107 tests, 0 failures,
17s) all green. That is the first time any of the four has produced a result:
92 Kotlin files / ~6,300 lines had never been compiled, linted or tested.

**Every gate was verified in CI, not locally, and that is not a shortcut taken
by choice.** `dl.google.com` is blocked by the scheduled agent's egress policy
(403 on CONNECT), and that one host is both the Android SDK download and — via
the `maven.google.com` redirect — Google's Maven repository. AGP itself does
not resolve there, so `./gradlew --version` is the only gate in CLAUDE.md that
can run in that environment. Future runs should expect the same and treat the
PR checks as the source of truth.

Known gaps carried out of item 2 (PR #19):

**AGP 8.7.3 is now the limiting version, and it forced a dependency backwards.**
`lifecycle` had to drop from 2.9.0 to 2.8.7 because 2.9.0 ships lint checks built
against a newer Kotlin Analysis API than the lint bundled with AGP 8.7.3. The two
disagree on whether `KaCallableMemberCall` is a class or an interface, and
`lintAnalyzeDebug` crashed outright — `Unexpected failure during lint analysis of
MainActivity.kt` inside `androidx.lifecycle.lint.NonNullableMutableLiveDataDetector`
— rather than reporting findings. Disabling `NullSafeMutableLiveData`, which lint
itself suggests, was rejected: it silences a check to hide a toolchain mismatch that
could take out any other detector just as easily.

Raising AGP is the forward fix and should be its own item. It is not a one-line
change — AGP 8.9+ needs a newer Gradle than the wrapper's 8.10.2 in some
combinations, so the wrapper moves with it — and it should land with lifecycle
restored to 2.9.x and a re-run of all four gates.

Also still open from item 1: the wrapper has no `distributionSha256Sum`, and
`README.md` advertises "Retrofit 3 + OkHttp 5" while the catalog pins Retrofit
2.11.0 / OkHttp 4.12.0 — a real migration, not a doc typo.

The four gates now run in `.github/workflows/ci.yml`, which item 3 promoted from
`workflow-templates/ci.yml` with `gates.yml` folded into it and the template
directory removed. The template had never been runnable: it set up no Android
SDK, and it called the variant-less `lint`/`test` tasks instead of the
`lintDebug`/`testDebugUnitTest` that CLAUDE.md specifies.

Item 3 also brought in the template's `assembleDebug` job, so the task is now
observed in CI. On PR #20 it ran for the first time in this repository's history
and succeeded in 2m38s, uploading a 50,300,882-byte `app-debug` artifact.

That is where item 4 starts, not where it ends: exit code 0 is not the same
claim as an installable APK, and nothing yet checks that what lands in
`app-debug.apk` is signed with the debug key, aligned, and carries the expected
package, `versionCode` and `minSdk`.

One caveat on how PR #20 went green. It changed no Kotlin source, so
`gradle/actions/setup-gradle` restored main's build cache and the gate tasks
came back `UP-TO-DATE`/`FROM-CACHE` — the whole gates job finished in 67s
against the ~4m of real work PR #19 measured. All four steps did run and each
reported `BUILD SUCCESSFUL`, so the workflow's wiring (SDK present, task names
correct, steps ordered) is genuinely proven. But the gate *outcomes* on that run
were cache hits inherited from main rather than fresh executions. The first PR
that touches `app/src` is what will exercise them cold.

**Phase 0 complete as of PR #21 (2026-08-02).** `scripts/verify-apk.sh` now runs
in the `build` job and checks what `assembleDebug`'s exit code never could: the
archive is readable and carries `AndroidManifest.xml`, 16 `classes*.dex` files
and `resources.arsc`; uncompressed entries are 4-byte aligned; `apksigner
verify` passes across API 26–35 under an APK Signature Scheme v2 with the
Android debug certificate; the package, `versionCode`, `versionName`, `minSdk`
and `targetSdk` match the build; and there is a launchable
`com.kojo.boilerplate.MainActivity` in a debuggable APK. All of it green against
a real 86,387,904-byte APK.

The expected identity is not a second copy of those values.
`:app:writeDebugApkIdentity` writes them out of the build DSL, so changing
`defaultConfig` moves the expectation with it and the check keeps proving AGP
propagated the declared values into the artifact rather than comparing two
hand-maintained lists.

**The verifier is itself tested.** `scripts/verify-apk.test.sh` (27 cases)
drives the script against stub build-tools so each check is shown to fail when
it should and only when it should. It needs nothing but bash — no SDK, no APK,
no network — which is the only reason any of this could be verified in the
scheduled agent's environment at all, where `dl.google.com` is still 403 on
CONNECT and no Gradle gate runs.

Two of the three CI rounds this item took were spent on the runner's
build-tools being **37.0.0**, well ahead of the 35.0.0 AGP builds against, and
its output having moved: `aapt2` emits `minSdkVersion:'26'` where older versions
emit `sdkVersion:'26'`, and `apksigner` prints `V2 Signer: certificate DN: C=US,
O=Android, CN=Android Debug` — scheme-prefixed, and with the RDN components in
the opposite order. Both spellings are now matched on the parts that do not vary
and both are pinned by tests, rather than pinning a build-tools version in the
script: the verifier runs on whatever SDK the machine has, and pinning would
only move the failure somewhere less visible than CI. Both parse paths now dump
the tool output they were reading, so the next format change says what it
changed to in the run that catches it.

Known gaps carried into Phase 1: the APK is never installed on an emulator — an
`adb install` is the literal reading of "installable", but it puts a slow,
KVM-dependent, historically flaky job on a merge-on-green gate — and alignment
is checked at 4 bytes rather than the 16 KB page alignment Android 15+ wants for
native libraries, which MLKit ships. `zipalign -c -P 16` needs build-tools 35+
and is worth its own item. Raising AGP off 8.7.3 (with `lifecycle` restored to
2.9.x) remains the largest open item from PR #19, and the wrapper still has no
`distributionSha256Sum`.

## Phase 1 — Foundation
- [x] Kotlin 2.1 + Gradle 8 (KTS) + Android API 26+ min, API 35 target
- [x] Jetpack Compose + Material3 scaffold
- [x] Hilt dependency injection setup (Application + Activity)
- [x] Navigation Compose with typed routes (Kotlin Serialization)
- [x] `build.gradle.kts` with version catalog (`libs.versions.toml`)

## Phase 2 — Architecture
- [x] MVVM + UiState sealed class pattern per screen
- [x] Repository pattern: `UserRepository` interface + `UserRepositoryImpl`
- [x] Kotlin Coroutines + Flow for reactive data
- [x] Room 2.7 database + DAO + Entity pattern

## Phase 3 — Network
- [x] Retrofit 3 + OkHttp 5 with JWT interceptor + token refresh
- [x] Kotlin Serialization (`kotlinx.serialization`) for JSON
- [x] Result<T> wrapper for error handling in repositories

## Phase 4 — Auth & ML
- [x] DataStore Preferences for token persistence
- [x] Google Sign-In + Credential Manager API
- [x] MLKit barcode scanning screen example
- [x] CameraX integration with MLKit text recognition

## Phase 5 — UI Components
- [x] Reusable Compose components: `AppButton`, `AppTextField`, `LoadingIndicator`
- [x] Dark/light theme with `MaterialTheme` tokens
- [x] Adaptive layout (phone + tablet)

## Phase 6 — Testing & DevOps
- [x] JUnit 5 + MockK unit tests for ViewModels
- [x] Compose UI tests with `createComposeRule`
- [x] GitHub Actions: lint → test → build APK

## Phase 7 — Coroutines & Concurrency
- [x] Structured concurrency: `supervisorScope`, `coroutineScope`, and cancellation-safe cleanup — three places turned cancellation into a failure, so a coroutine cancelled mid-call completed *successfully* and its parent never saw the cancellation it was waiting for (PR #22)
- [x] Custom `CoroutineExceptionHandler` + a `Result`-returning `safeCall` wrapper — nothing installed a handler anywhere, so the `supervisorScope` footgun item 1 documented was still live: a failing `launch` child had nowhere to report to and reached the thread's uncaught handler (PR #23)
- [x] Flow operators in anger: `flatMapLatest`, `debounce`, `distinctUntilChanged`, `retryWhen` — two of the four were used nowhere: no `retryWhen`, so one dropped connection stranded a screen on an error until the user tapped retry, and no `debounce`, so every keystroke in the search field rebuilt the whole `HomeUiState` (PR #24)
- [x] `StateFlow` vs `SharedFlow` decision guide with `WhileSubscribed(5000)` and config-change survival — the sign-in screen drove navigation *and* its snackbar from `LaunchedEffect(uiState)`, so a rotation mid-snackbar cancelled the `clearError()` that was meant to follow it and the next composition showed the same failure again (PR #25)
- [x] `callbackFlow` + `awaitClose` wrapping a legacy listener API — neither builder was used anywhere and nothing observed connectivity at all, so `retryWithBackoff` retried a dropped connection blind and the Home screen showed a cached list with no sign it was stale; the wrapper handles the three things a naive one gets wrong — a leaked registration, no initial value, and a wifi→cellular hand-off read as an outage (PR #26)
- [x] Dispatcher injection for testability + `runTest` with a `TestDispatcher` — the qualifiers and their module had existed since Phase 3, so the gap was ownership, not wiring: `UserRepositoryImpl` declared no threading contract, and because `Flow` operators are context-preserving its row mapping ran in the collector's context — the main thread — while three view models each appended `.flowOn(ioDispatcher)` to compensate and nothing could test any of the three (PR #27)
- [x] Concurrent request fan-out with `async`/`awaitAll` and partial-failure handling — `syncCurrentUser` and `syncUser` had no caller outside their own tests, so the app could display a list of users indefinitely without ever refetching one: there was no refresh to write concurrently (PR #28)
- [x] Immutability: `data class` + `@Immutable`/`@Stable` annotations and persistent collections — nothing in the app carried either annotation, and the two state classes holding collections held them as `List`, which is a read-only interface over what is usually an `ArrayList` and so makes the class unstable; strong skipping then falls back to instance comparison, and both producers allocate a fresh list per emission (Room invalidates per table, so any row write rebuilds the whole home list; the ML Kit analyzer rebuilds its blocks once per camera frame), so neither ever compared equal and both panes recomposed regardless. `StabilityContractTest` is what keeps it fixed: `@Immutable` is an unchecked promise the compiler skips recomposition on, so the audit discovers every ViewModel from the compiled output, walks the graph reachable from each `StateFlow`, and fails the build on a missing annotation, a `var`, or a `kotlin.collections` property type (PR #29)

Item 1 complete as of PR #22 (2026-08-03). Nothing in the repo had used
`coroutineScope`, `supervisorScope` or `NonCancellable`, and looking for where they
should have been turned up the same defect in three places: `safeCall` and both
`GoogleAuthRepositoryImpl` methods wrapped `CancellationException` into a
`Result.failure` via `runCatching`, so a coroutine cancelled mid-call completed
successfully, its parent stopped waiting for a cancellation that never arrived, and
the screen the user had already left was handed an error to render.
`UserRepositoryImpl` separately dropped an already-fetched user when the caller was
cancelled mid-write; the request stays cancellable, the write now completes under
`NonCancellable`.

`core/coroutines/StructuredConcurrency.kt` carries what the language does not
enforce — a cancelled coroutine cannot suspend, so a suspending cleanup in a
`finally` block dies at its first suspension point, exactly in the case it exists
for. The two scope builders are deliberately **not** wrapped: they are designed to
be used inline, so their contrast lives in `docs/structured-concurrency.md` with
every claim it makes pinned by a test in `StructuredConcurrencyTest`.

Deliberately left to later items: fan-out with `async`/`awaitAll` and
partial-failure aggregation is item 7, and the `CoroutineExceptionHandler` that
answers the documented `supervisorScope` footgun — a failing `launch` child has
nowhere to report to and reaches the thread's uncaught handler, which on Android is
a crash — is item 2.

The environment constraint from PR #19 still holds: `dl.google.com` is 403 on
CONNECT for the scheduled agent, so four of the five gates in CLAUDE.md ran only in
CI. Maven Central *is* reachable, which is enough to compile and run the pure-Kotlin
subset of the sources locally against the real Kotlin 2.1.0 / coroutines 1.9.0 with
stub `androidx.room` annotations, and to run detekt 1.23.8 against the repo config.
Future runs on this repo should do the same rather than pushing unverified Kotlin:
41 tests and a detekt finding were caught before the first push. Test-only gotcha
worth remembering: kotlinx.coroutines copies a throwable as it crosses a coroutine
boundary, so `assertSame` is wrong for anything caught outside the coroutine that
threw it.

Item 2 complete as of PR #23 (2026-08-04). The `safeCall` half of this item had
already landed in PR #22, so what this added is the handler, its wiring, and
`docs/coroutine-errors.md` — which frames the pair as one question: *is there still
a caller?* `safeCall` when there is one and the failure should become a value, the
handler for what is left over. `AppCoroutineExceptionHandler` reports and stops; by
the time it runs the coroutine has failed and its scope is cancelled, so anything
more would pretend the work can still finish. It escalates only `VirtualMachineError`
and `LinkageError` to the thread's uncaught handler — absorbing an `OutOfMemoryError`
buys a second, more confusing crash — and deliberately not `AssertionError`, which is
an ordinary bug. Where a failure *goes* is a `CoroutineFailureReporter` behind one
`@Binds`; Logcat is the default and is not production-grade on purpose.
`@ApplicationScope` is where the handler is actually installed, and is the first
process-lifetime scope in the repo.

The two tests worth having are the ones for when a handler is silently *not*
consulted: `launch(handler)` nested inside another coroutine, where the parent
handles the failure, and `async`, where the `Deferred` holds it. Both read as error
handling and neither is.

The PR #22 environment constraint is unchanged — `dl.google.com` still 403 on
CONNECT, four of five gates CI-only — and the local-verification habit it recommends
paid off again: the handler, the reporter and all 12 tests were compiled with
kotlinc 2.1.0 against coroutines 1.9.0 in a standalone JVM and run green before the
first push, with an `android.util.Log` stub standing in for the platform. detekt
1.23.8 also runs fully offline against the repo config. What that harness cannot
reach is Hilt/KSP codegen, so `CoroutineErrorModule` is still CI's to prove.

Item 3 complete as of PR #24 (2026-08-05). `flatMapLatest` was already in use and
correct; the other three were the gap. **`retryWhen` was absent entirely**, and a
`Flow` is over the moment it throws — there is no resuming, so for a screen backed
by `stateIn` one dropped connection left an error on screen until the user found the
retry button, for a failure that would have cleared on its own.
`core/coroutines/FlowRetry.kt` wraps it as `retryWithBackoff` around the three
checks that make a retry loop safe rather than harmful: not cancellation, transient
only, capped. The first is the same defect PR #22 found in three other places —
`catch` and `retryWhen` rethrow a `CancellationException` that is the *current* job's
cancellation cause, but one raised by upstream code is an ordinary throwable to that
check and would be retried, swallowing the cancellation its parent waits for.
`isTransientFailure` draws the second line at `IOException` plus 408/429/5xx: every
other 4xx is a statement about the request, and retrying it four times only makes
the user watch a spinner before getting the same answer.

**`debounce` was absent from the one search field in the app** — `_searchQuery` fed
`combine` directly, so typing `alice` filtered and re-mapped the list five times and
rendered five states. `asSearchQueries()` is trim → debounce → distinct, applied to
the *derived* query and never to `searchQuery` itself, because a debounced text
field reads as a broken keyboard. Its timeout is computed per value so an empty
query is not rate-limited: a plain `debounce(300)` delays the initial `""` a
`MutableStateFlow("")` replays on subscription, putting a loading flash in front of
every cold start.

**`distinctUntilChanged` now sits upstream of the expensive part** in all three
flow-backed ViewModels. Room invalidates per table, not per row, so any write to
`users` re-delivers a byte-identical list, and retrying replays the prefix for the
same reason. The trailing `stateIn` conflates the resulting state either way — but
only after the filter and the full item mapping had run again, which is the work
this drops. At the *end* of a pipeline the operator is genuinely redundant, and
`docs/flow-operators.md` says so rather than implying it always earns its place.

Deliberately left to later items: `WhileSubscribed(5000)` is used here but its
`StateFlow`/`SharedFlow` trade-off is item 4, and dispatcher injection — which these
tests lean on heavily to keep the backoff on virtual time — is item 5.

The environment constraint is unchanged again, and the recommended habit caught
things again: kotlinc found a missing `kotlinx.coroutines.test.currentTime` import
before the first push, and the 21 new operator tests plus a standalone harness
reproducing the `HomeViewModel` pipeline verbatim (13 timing assertions — debounce
collapsing, retry counts, backoff schedule, `flatMapLatest` cancellation) all ran
green locally. The one gap in that habit is what went red: detekt was *not* run
before the first push, and CI failed on two `UseCheckOrError` findings that the
offline detekt reproduces in seconds. Run all three — kotlinc, the JUnit console
launcher, and detekt — not two of them.

Item 4 complete as of PR #25 (2026-08-05). `WhileSubscribed(5_000)` was already
correct in all three flow-backed ViewModels; what was wrong was the other half of the
item. The sign-in screen drove **both** of its side effects from
`LaunchedEffect(uiState)`, and a `LaunchedEffect` keyed on state runs again every
time the composition is rebuilt — once per configuration change. The error path is
where that bites: `showSnackbar` suspends until dismissal, so the `clearError()`
meant to follow it never ran when a rotation cancelled the effect, `uiState` was
still `Error`, and the new composition showed the same snackbar from the top. Rotate
five times, see it five times. The navigation callback had the identical shape and
escaped only because the destination pops the sign-in entry immediately — correct by
accident.

`clearError()` was the tell: state that has to be cleared by hand once it has been
read is an event wearing state's clothes. `GoogleSignInUiState` therefore has no
`Error` case at all — a failed sign-in leaves the screen at `Idle`, offering the
button — and the reason is a `GoogleSignInEvent.SignInFailed` delivered once through
a `Channel(BUFFERED).receiveAsFlow()`. Not the `SharedFlow` the item's title names,
and `docs/state-and-events.md` argues the choice rather than asserting it:
`MutableSharedFlow(replay = 0)` drops what it is given while nobody is subscribed and
`tryEmit` still returns `true` when it does, which on this screen is the ordinary
path — the credential picker is another Activity, so the screen is stopped for the
whole time sign-in is happening. `receiveAsFlow` and not `consumeAsFlow`, which would
close the channel with its first collector and leave a dead event stream after one
rotation. `core/ui/event/ObserveAsEvents.kt` is the reading half.

`StateAndEventSemanticsTest` pins every claim the guide makes against
kotlinx.coroutines itself, including the two `WhileSubscribed(5_000)` properties that
had never been asserted anywhere: a rotation-sized gap does not restart the upstream,
a longer one does, and the cached value survives either way because
`replayExpirationMillis` defaults to infinite — which is the property that keeps a
returning screen from flashing `Loading`, and it is one argument away from being lost.

Deliberately left to later items: the app-wide `SharedFlow` event bus is Phase 8's
observer-pattern item, so the guide states the rule for it and the code does not
implement it.

Environment note, and a correction to the habit PR #24 recommended: this run verified
**only** detekt locally (1.23.8 CLI, repo config, `--build-upon-default-config`, 0
findings) and did not stand up the kotlinc harness the previous three runs used. It
cost a round trip — `Flow<*>.collect()` is a top-level extension, not the member
overload, and the missing import was caught by re-reading rather than by a compiler.
Detekt runs without type resolution and cannot catch that class of error. Run all
three: kotlinc, the JUnit console launcher, detekt.

Known gaps carried forward: nothing yet *uses* `@ApplicationScope`;
`LogcatCoroutineFailureReporter` has no unit test, because `android.util.Log` throws
"not mocked" on the JVM and enabling `returnDefaultValues` for one thin adapter would
weaken every other test in the module; and the merged branch could not be deleted —
the git proxy rejects deletes, which is why every merged branch in this repo is still
on the remote.

Item 5 complete as of PR #26 (2026-08-07). Neither `callbackFlow` nor `awaitClose`
appeared anywhere in the repo, and nothing observed connectivity at all — which is
what left `retryWithBackoff` (item 3) retrying a dropped connection on a blind
schedule with no idea whether there was a network to retry over, and `HomeScreen`
rendering a cached list with no indication that it might be stale.

`ConnectivityManagerNetworkMonitor` is the worked example, and the value is in the
three things a naive wrapper gets wrong, each pinned by a test rather than only by a
comment. **The leaked registration**: the system holds a strong reference to the
callback and keeps waking the process on every network change until it is
unregistered, which nothing about a cancelled coroutine does — `awaitClose` is the
only place it can go. **No initial value**: a `NetworkCallback` reports transitions
only, so a collector subscribing while already offline waits for a change that may
never come; the flow seeds itself, and seeds *after* registering so no transition
falls into the gap. **The hand-off**: on wifi → cellular the platform announces the
new default with `onAvailable` *before* the old one with `onLost`, so treating every
`onLost` as offline goes offline immediately after coming back online and stays
there.

`NetworkStatus` is not a Boolean on purpose. An unvalidated network is a captive
portal — requests connect and come back with a login page, which no retry fixes — so
`HomeViewModel.isOffline` reports it as online and a test says so.

**Every gate ran in CI only, and the environment is why.** `dl.google.com` is still
403 on CONNECT for the scheduled agent, so Google Maven is unreachable and AGP 8.7.3
itself does not resolve: `./gradlew compileDebugKotlin` dies at configuration time
with "Plugin [id: 'com.android.application', version: '8.7.3'] was not found" before
touching any project code. No Android SDK can be installed either. This run also did
not stand up the kotlinc/JUnit-console harness the item-1–3 runs used; unlike the
item-4 run that skipped it, this one cost no round trip — all four gates plus
`assembleDebug` and the APK verification passed on the first CI attempt.

Known gaps carried forward: `retryWithBackoff` still does not consult the monitor,
because gating a resubscription on connectivity changes when a flow retries and is a
design decision worth its own change; the monitor is cold, so two collectors mean two
platform registrations, and only `HomeViewModel` currently shares one via
`stateIn(WhileSubscribed(5_000))`; and `BarcodeScannerScreen` still leaks a
`newSingleThreadExecutor` per composition from inside its `AndroidView` factory — the
other listener-shaped defect in this repo, left alone here because converting a
CameraX analyzer means restructuring camera binding and lands in code CI cannot
exercise, since there is no emulator and `androidTest` never runs.

Item 6 complete as of PR #27 (2026-08-08). `@IoDispatcher`, `@DefaultDispatcher` and
`@MainDispatcher` plus `CoroutineDispatchersModule` had been in place since Phase 3,
so this item was never about adding wiring. It was that the layer doing the work did
not own the decision, and that nothing could test the decision it did not own.

`UserRepositoryImpl` declared no threading contract at all, and the reason that
survived five phases is worth writing down: **Room's generated suspend DAOs and
Retrofit's suspend calls dispatch their own work**, so a repository with no `flowOn`
and no `withContext` still returns entirely correct results. Nothing about the
results distinguishes the confined version from the unconfined one. What is left on
the caller's thread is the code *between* the library calls — `toDomain()` over every
row of a query result, `toEntity()` on every write — and `Flow` operators are
context-preserving, so `.map { }` runs in the **collector's** context. For a view
model collecting into `viewModelScope`, that is the main thread.

Three view models compensated by appending `.flowOn(ioDispatcher)` to the repository
call and injecting a dispatcher in order to: one decision written three times, and a
fourth caller that forgot would have had nothing to fail. The repository now confines
itself and the callers stop compensating. Both profile view models take no dispatcher
at all — what is left in them is a null check and one allocation per emission, and the
thread hand-off would cost more than the work it protects. `HomeViewModel` keeps one
and it becomes `@DefaultDispatcher`: its remaining work is a scan of the whole user
list, which is CPU-bound, and the IO pool is sized for threads that are parked
waiting. Nested `flowOn` resolves this correctly — the innermost wins for the section
it encloses, so the row mapping stays on IO.

The dispatcher parameter deliberately has **no default value**. A default would let a
caller construct the repository without one, and every test that did would silently
run against the real IO pool.

`UserRepositoryImplDispatcherTest` is what the injection is *for*, and the reason this
is a fix rather than a tidy-up: a `CoroutineDispatcher` **is** the
`ContinuationInterceptor` in a coroutine's context, so a fake DAO recording
`currentCoroutineContext()[ContinuationInterceptor]` reports which dispatcher its
caller had installed. Two `StandardTestDispatcher`s over one `TestCoroutineScheduler`
give one virtual clock where the only difference is identity. `StandardTestDispatcher`
and **not** `UnconfinedTestDispatcher`: unconfined never actually dispatches, so a
missing `flowOn` would pass exactly as happily as a present one — which is the trap
this whole item is about. The suite carries the control that the caller was not
already on the io dispatcher, without which a bug making the two the same object would
turn the file green. One case pins that `withContext(NonCancellable)` **inherits** the
dispatcher rather than replacing it: `NonCancellable` is a `Job` and nothing else, and
if it were a full context switch the cache write would land back on the caller's
thread with nothing else noticing.

**Process note, and this run got it wrong.** The environment constraint is unchanged —
`dl.google.com` is still 403 on CONNECT, AGP 8.7.3 does not resolve, and
`./gradlew compileDebugKotlin` dies at plugin resolution before reading any source, so
all five gates were CI-only again. But the item-1–3 runs left explicit advice above:
Maven Central *is* reachable, which is enough to compile and run the pure-Kotlin
subset locally under kotlinc against the real Kotlin 2.1.0 / coroutines 1.9.0 with
stub `androidx.room` annotations, and to run detekt offline. **This run did not stand
that harness up and pushed unverified Kotlin.** It passed on the first CI attempt, but
that was luck rather than method, and every gate here was reachable by that harness
except the Hilt/KSP codegen. Future runs should build it before the first push.

Known gaps carried forward: `GoogleAuthRepositoryImpl` and `ThemePreferencesRepository`
still take no dispatcher, deliberately — Credential Manager and DataStore dispatch
their own work and neither has meaningful mapping around the call, so a dispatcher
there would be cargo cult. `ObserveAsEvents` still hardcodes `Dispatchers.Main.immediate`,
which is correct for a composable. `ProfileViewModel` still has no test of its own, a
pre-existing gap this item did not close. And there is still no `@TestInstallIn` module
replacing the dispatchers for Hilt instrumented tests, which belongs with Phase 12's
"Hilt test modules with fake bindings" rather than here.

Item 7 complete as of PR #28 (2026-08-09). The pattern had no representation in the
repo, and the gap it left was not stylistic: **`syncCurrentUser` and `syncUser` had no
caller anywhere outside their own tests.** Home served its list from Room, and its
"Retry" button resubscribed to that query — the fix for a failed *read*, which cannot
make data newer. So the app could display a list indefinitely without asking the
network for it again, and there was no refresh to write concurrently because there was
no refresh.

`core/coroutines/FanOut.kt` carries both halves. `mapConcurrently` is all-or-nothing:
`awaitAll` inside `coroutineScope`, so the first failure cancels the siblings still
working towards a result nobody can use, and does not return until they have finished
cancelling. `mapConcurrentlyCatching` returns a `FanOutResult` with each failure
carrying the *input* that produced it, so a caller can retry exactly the ones that did
not land — a bare list of throwables answers "how badly did that go?" but not "which
ones do I retry?".

Three decisions are the substance, and each has a wrong answer that still compiles:

1. **Concurrency is bounded by a `Semaphore`, and the permit is taken inside each
   child.** `map { async { } }` starts as many requests as the list happens to be long
   — a herd sized by data rather than by design, with the tail queued behind OkHttp's
   five-per-host limit anyway. Taking the permit inside the child is what makes
   `withPermit`'s `finally` release it on the failure path; a permit released only on
   success runs at full speed until the first outage and then deadlocks.
2. **`supervisorScope` is not what delivers a partial result**, which is the trap this
   item exists to document, because the obvious reading of item 1's own doc says it is.
   Scope policy does not help when `awaitAll` rethrows the first failure regardless —
   and once it throws, every `Deferred` left un-awaited takes its exception to the
   grave. Catching *inside each child, as a value*, is what works: no child ever fails,
   so there is nothing for a scope policy to arbitrate.
3. **Cancellation stays cancellation**, via the `rethrowIfCancellation` from item 1.
   `runCatching` would record it as element N's failure and hand the caller a tidy
   report of a fan-out that had been told to stop — completing *normally*, with a
   partial result, for a screen the user has already left.

`UserRepositoryImpl.syncUsers` is the first caller (the API has no bulk endpoint, so a
list refresh is genuinely N independent requests) and dedupes its ids first: the same
id twice is the same request twice, and duplicates would make "8 refreshed" mean either
eight users or five. `HomeViewModel.refresh()` wires it to the app bar. The outcome
lives beside `HomeUiState` rather than inside it, for the same reason the offline
banner does — a failed refresh should not replace a readable list with an error page —
and a CAS on that state doubles as the in-flight lock, so a double tap is one fan-out.
Successes get no UI path at all: they land in Room and the list observing it re-renders,
so the only thing the fan-out reports is the shortfall.

**Process note: the harness item 6 asked for was built, but only partway.**
`dl.google.com` is still 403 on CONNECT and AGP 8.7.3 still does not resolve, so all
five gates were CI-only again. This run did stand up kotlinc 2.1.0 from Maven Central
and compiled `FanOut.kt` + `StructuredConcurrency.kt` + `FanOutTest.kt` against the
real coroutines 1.9.0 — clean, and all 20 tests passed locally before the first push.
What it did *not* do is add the stub `androidx.room`/`androidx.lifecycle` annotations
that would have covered `UserRepositoryImplSyncUsersTest` and `HomeViewModelRefreshTest`,
or run detekt offline. Those three gates were still pushed unverified. They passed
first time, but the next run should extend the stub set rather than repeat the gamble.
Where an API could not be exercised it was at least confirmed by `javap` against the
exact pinned jar rather than from memory — `MockWebServer.dispatcher`/`requestCount`,
`RecordedRequest.path`, `Semaphore`/`withPermit`, and `MockKStubScope.coAnswers`, the
last of which decided whether an import was required or a compile error.

Known gaps carried forward: nothing uses `mapConcurrently` (the all-or-nothing half) in
production yet — it is tested but not called, which is the same shape of gap this item
found in `syncUser`, and the first screen needing two independent fetches should be its
caller. `syncCurrentUser` still has no caller. The refresh covers only the users
currently on screen, so refreshing under an active search leaves the rest untouched by
design; a pull-to-refresh gesture and a "refresh all" affordance are both unbuilt.


## Phase 8 — Architecture & Patterns
- [x] SOLID audit of the repository/use-case layers documented in `docs/solid.md` — the headline is what is missing: there is no use-case layer, so application policy sits in the ViewModels and `ProfileViewModel`/`ProfileDetailPaneViewModel` already hold the same observe-retry-map-or-not-found policy verbatim; eight findings recorded, three of them with no later item to pick them up, and `SolidContractTest` pins the structural half as equalities so a *fix* fails it too and the page cannot describe a problem that is gone (PR #30)
- [x] Clean Architecture layering: domain use-cases with no Android imports, enforced by a lint rule — `ObserveUserProfileUseCase` and `RefreshVisibleUsersUseCase` take the policy finding 1 found duplicated verbatim across the two profile view models; enforced twice, by a `ForbiddenImport` rule scoped to `**/core/domain/**` and by `DomainLayerContractTest` reading the compiled constant pool, because the linter reads import directives and a fully-qualified reference has none. The interesting failure: the Compose compiler plugin stamps `@StabilityInferred` on *every* class in the module, so "no androidx in the domain layer" is not literally achievable in a single-module Compose app — exempted by full name, and modularisation is what deletes the exemption (PR #31)
- [x] Factory + Strategy: pluggable `SyncStrategy` resolved by Hilt multibinding — `VisibleUsersSyncStrategy` is the old `RefreshVisibleUsersUseCase` body moved down (its three decisions turned out to be about *that* way of syncing; `CURRENT_USER` answers all three differently), and the use case keeps the one decision that is genuinely policy: which mode a user-initiated list refresh means. The interesting part is what Dagger cannot check — a `SyncMode` with **no** binding compiles and throws at first use, and a `@SyncModeKey` naming a different mode than the strategy it sits on is a well-typed lie — so `SyncStrategy.mode` exists to be compared against the key on every resolution, and `SyncStrategyModuleContractTest` reads the module's annotations to pin both. `CURRENT_USER` is bound and tested but nothing selects it yet; it is the first caller `syncCurrentUser` has ever had, and WorkManager is its intended one (PR #32)
- [ ] Decorator pattern: repository wrappers adding cache, retry, and telemetry
- [ ] Observer pattern: app-wide event bus on `SharedFlow`
- [ ] Unidirectional data flow: single `UiState` + `UiEvent` + `UiEffect` contract per screen
- [ ] Modularisation: `:core`, `:data`, `:feature:*` Gradle modules with dependency rules

## Phase 9 — Offline-First & Data
- [ ] WorkManager background sync with constraints, backoff, and unique work
- [ ] Offline-first repository: single source of truth in Room with a `NetworkBoundResource`
- [ ] Conflict resolution: last-write-wins vs merge, with a version column
- [ ] Paging 3 with `RemoteMediator` over Room + network
- [ ] Room migrations with exported schemas and a migration test suite
- [ ] Idempotent sync requests with client-generated keys
- [ ] DataStore Proto for typed structured preferences

## Phase 10 — Compose Performance & UI
- [ ] Recomposition profiling: Layout Inspector counts + a fix for an unstable-lambda hotspot
- [ ] Stability: `@Immutable`, `@Stable`, and the compiler-metrics report checked into CI
- [ ] `derivedStateOf` and `remember` keying to cut redundant work
- [ ] `LazyColumn` performance: keys, `contentType`, and item prefetch
- [ ] Custom layouts and `SubcomposeLayout` for a measure-dependent component
- [ ] Shared-element transitions + predictive back gesture
- [ ] Material3 dynamic colour, edge-to-edge, and full accessibility semantics

## Phase 11 — Security & Release
- [ ] EncryptedSharedPreferences / Keystore-backed token storage, never plaintext
- [ ] Certificate pinning in OkHttp with a documented rotation plan
- [ ] Root/tamper detection with Play Integrity API
- [ ] R8 full mode with keep rules + a mapping-file upload step
- [ ] Play Store signing via CI with secrets from the GitHub secret store only
- [ ] Baseline Profiles + Macrobenchmark startup test with a CI budget
- [ ] Dependency and licence scanning gate in CI

## Phase 12 — TDD & Advanced Testing
- [ ] TDD kata: one use-case built red→green→refactor, one commit per step
- [ ] Turbine tests for Flow emissions
- [ ] Screenshot tests with Paparazzi + a CI diff gate
- [ ] Instrumented tests on an emulator matrix in CI
- [ ] Hilt test modules with fake bindings replacing network and DB

Phase 7 item 8 complete as of PR #29 (2026-08-10). All four checks green:
dependency resolution, `compileDebugKotlin` / `lintDebug` / `detekt` /
`testDebugUnitTest`, `assembleDebug` + APK verification, and GitGuardian.

`kotlinx-collections-immutable` is pinned at **0.3.8**, not 0.4.0 or 0.5.1.
0.5.x is built on kotlin-stdlib 2.3.0, whose metadata Kotlin 2.1.0 cannot read;
0.4.0 is on 2.1.20, which reads but drags the stdlib ahead of the compiler and
makes every compile task warn that the runtime JARs are newer than the API
version. The same pin question that broke Phase 0 item 1 elsewhere, answered by
reading the POMs rather than by picking the newest number.

The environment constraint from PR #19 still holds and should be assumed by
every future run: `dl.google.com` is 403 on CONNECT for the scheduled agent, so
AGP itself is unfetchable and Gradle cannot even configure the project — zero of
the five gates in CLAUDE.md can run locally. What *was* run locally this time,
and is worth repeating: the Kotlin 2.1.0 compiler is on **Maven Central**, which
is reachable, so `app/src` can be parse-checked for syntax errors, and a
self-contained test like `StabilityContractTest` can be compiled and executed
against stand-ins — here against a deliberately broken hierarchy (it reported all
four defect classes) and against the real state types (clean). That is not a
substitute for CI, but it is a great deal better than pushing blind.

Known gaps carried forward: instrumented tests still run nowhere, so
`HomeScreenTest`'s move to `persistentListOf` is compile-checked only. The
Compose compiler's own stability *metrics* are not wired into the build — the
audit checks the contract at the source level, which is where the fix lives, but
it does not read back what the compiler actually inferred. `ImageVector` is
trusted to carry its upstream `@Immutable` rather than being listed by hand;
CI confirms that trust is well placed today.
