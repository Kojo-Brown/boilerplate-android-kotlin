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
- [x] Decorator pattern: repository wrappers adding cache, retry, and telemetry — three layers around an unchanged `UserRepositoryImpl`, which is what closes `docs/solid.md` finding 7. The order is the design (`retry` innermost, so everything above sees one logical operation; caching above it, so a hit costs no retry schedule; telemetry outermost, so durations are what the caller waited for) and `UserRepositoryDecoratorTest` walks the assembled chain, because nothing else fails when it is reversed. Three traps, each of which still compiles: the sync methods return failures as **values**, so `runCatching { delegate.syncUser(id) }` never retries anything; a shared in-flight request started with the first caller's `async` is cancelled when that caller leaves, taking down a request a second screen is still awaiting — it is hosted in `@ApplicationScope` instead, the first use that scope has had; and cancellation is not failure at any layer, so it is unretried, uncached and recorded as `Cancelled`. The retry decorator retries only the failed ids of a fan-out and restores request order, and `BackoffPolicy` is extracted so the flow operator and the suspend retry share one schedule (PR #33)
- [x] Observer pattern: app-wide event bus on `SharedFlow` — implements rule 4 of `docs/state-and-events.md`, which had been written without anything behind it because nothing in the app had two independent listeners for one thing. Session expiry does: `TokenAuthenticator` is the only code that can tell a session has died, and it reacted by clearing the tokens and failing one request while nothing else found out — so Credential Manager kept its record of who was authorised and could answer the next sign-in by silently re-authorising the account the server had just ejected. The design is that its two reactions have different lifetimes: clearing the credential state is an `AppEventListener` under `AppEventDispatcher`'s process-lifetime subscription, because a session usually dies with the app backgrounded, and navigating is a composition-scoped `ObserveAsEvents` collector that accepts the miss. A `SharedFlow` reaches both; a `Channel` would have given the event to whichever asked first. Three traps, each verified by mutation rather than by a passing build: `start()` must launch `UNDISPATCHED` or the subscription registers a dispatch late and everything published in between is dropped while `tryEmit` reports success (removing it fails 6 of 16 tests); the dispatcher is a single `collect`, so a listener that throws would cancel it and silently stop *every* reaction for the life of the process (removing the per-listener catch fails 3); and the buffer is `SUSPEND` rather than `DROP_OLDEST`, so a full buffer is a `false` a caller can act on instead of a pending event that evaporates. `AppEvent` has exactly one member on purpose — the admission test is in `docs/event-bus.md`, and connectivity is the near miss it rejects, being state with a current answer that `NetworkMonitor` already publishes (PR #34)
- [x] Unidirectional data flow: single `UiState` + `UiEvent` + `UiEffect` contract per screen — `UdfViewModel<S, E, F>` gives every screen `state`, `onEvent` and `effects` and nothing else public, which `UnidirectionalDataFlowContractTest` enforces against the compiled output. The convention had been in `CLAUDE.md` since the repo existed and nothing was checking, so `HomeViewModel` had reached four public flows and four public methods. Two of the three costs were real bugs rather than untidiness: `TextRecognitionViewModel` guarded detection with `uiState is Scanning && !isPaused` because neither flag could be trusted alone, and `isPaused` was true in exactly the cases where the scan state was `TextDetected` — a second copy of one value kept in step by hand, which folding the screen into one state deleted; and `RefreshState.Finished` was state cleared by a Dismiss button, the exact tell rule 2 of `docs/state-and-events.md` names, surviving every rotation until pressed and losing the press if the rotation came first. `HomeUiState` is a data class with the mutually-exclusive part as a sealed `content` field rather than one sealed state, because an `Error` case would exclude the search text and a `Loading` case the offline banner. Four of six screens bind `Nothing` as their effect type, which has no instances, so "this screen decides no one-shot" is compiler-enforced and `emitEffect` is uncallable there (PR #35)
- [x] Modularisation: `:core`, `:data`, `:feature:*` Gradle modules with dependency rules — thirteen modules with the layering declared in the root build file and enforced by `checkModuleDependencies`, which CI runs before compiling. The payoff is not build times at 180 files: `:core:domain` is compiled without the Compose plugin, so `@StabilityInferred` is no longer stamped on it and `DomainLayerContractTest` lost the one exemption it carried; two `@ApplicationScope` qualifiers turned out to exist, with `DataStoreTokenProvider` silently bound to the undocumented one; and `syncStrategyFactoryOver` was already being imported across what is now a module boundary. `HomeTwoPaneScreen` takes its detail pane as a slot so no feature imports a feature (PR #36)

Item 4 complete as of PR #33 (2026-08-18). **The local harness the last three runs kept
recommending was finally built in full, and it caught things.** The environment is unchanged —
`dl.google.com` is 403 on CONNECT for the scheduled agent, so Google Maven is unreachable, AGP
8.7.3 does not resolve and no Android SDK can be installed — but Maven Central is reachable,
and everything in this change except Hilt/KSP codegen was verifiable without Gradle:

- `kotlin-compiler-2.1.0.jar` run directly as `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler`.
  Two things are needed that are not obvious: `org.jetbrains:annotations` must be on the
  *compiler's* classpath (without it the JVM backend dies in `AnnotationCodegen` with a
  `NoClassDefFoundError` for `Nullable`, reported as "Backend Internal error"), and
  `-jvm-target 17` is required — the 1.8 default crashes the backend while generating
  `safeCall`, which is a suspend function returning `Result`.
- `junit-platform-console-standalone-1.11.4.jar` to run the JUnit 5 suite: 53 tests, including
  the existing `FlowRetryTest` against the refactored `BackoffPolicy`.
- `detekt-cli-1.23.8-all.jar` with the repo config and `--build-upon-default-config`: 0 findings.

Only one gate was genuinely out of reach: `SolidContractTest` compiles under the harness but
cannot run, because it reads the compiled output of the *whole* app and the decorators change
two of its recorded lists. That, plus Hilt codegen and Lint, was what CI proved. All five
Gradle gates passed on the first attempt.

Item 5 complete as of PR #34 (2026-08-19). All three checks green on the first attempt —
dependency resolution, the gates job (`compileDebugKotlin`, `lintDebug`, `detekt`,
`testDebugUnitTest`), and `assembleDebug` + APK verification. The environment is unchanged:
`dl.google.com` is still 403 on CONNECT (confirmed against
`$HTTPS_PROXY/__agentproxy/status`, which now names the denial explicitly), so AGP and the
Android SDK remain unfetchable and none of the five gates in CLAUDE.md can run locally.

**The harness from PR #33 was rebuilt from these notes in about ten minutes, and the notes
were what made that possible** — the two non-obvious compiler arguments were exactly as
recorded. Two additions worth carrying forward: `kotlin-stdlib` *and*
`kotlinx-coroutines-core` must be on the compiler's own `-cp` alongside `annotations`, or the
compiler dies in `CoreApplicationEnvironment.createApplication` before it reads a single
source file; and `kotlin-compiler-2.1.0.jar` needs `org.jetbrains.intellij.deps:trove4j` at
runtime, which is not obvious from the failure (`NoClassDefFoundError:
gnu/trove/TObjectHashingStrategy`, thrown from source collection). Maven Central answers 429
under a burst — fetch the jars with backoff rather than in one loop.

What the harness covered this time: 23 JVM-only sources compiled against hand-written
stand-ins for `android.util.Log`, `android.content.Context` and
`androidx.compose.runtime.Immutable`; 16 tests through
`junit-platform-console-standalone`; detekt 1.23.8 with the repo config, 0 findings. What it
could not: the three Compose/Hilt entry points (`AppNavHost`, `MainActivity`,
`BoilerplateApp`), Hilt/KSP codegen, Lint, and the contract tests that read the whole app's
compiled output — all of which CI proved. **The harness is still not checked in**, and the
argument for doing so is now that it has been rebuilt from prose twice; `scripts/` is where
it belongs, next to `verify-apk.sh`.

**Process note for the next run: the GitHub API served ~45 minutes of stale job status.** The
gates job finished at 01:20:39 and the API reported it `in_progress` until well past 02:00,
including reporting `lintDebug` as running 45 minutes after it had passed in 56 seconds. It
looks exactly like a hung runner and is not one. Read the *steps* array from
`get_workflow_job` rather than the check-run summary, notice when a step's elapsed time exceeds
the job's own `timeout-minutes` (30 here) without the job being killed, and treat that as stale
data — not as a reason to push an empty commit or re-run.

Item 6 complete as of PR #35 (2026-08-21). **The harness is checked in** — `scripts/jvm-harness/`
— so the next run starts from a working one instead of rebuilding it from these notes for a
third time. `run.sh --skip-detekt` for the fast loop; jars land in `~/.jvm-harness/jars` or
wherever `JVM_HARNESS_JARS` points. It grew beyond what PR #33 and #34 described: dagger,
hilt-core, Retrofit, OkHttp, mockwebserver and mockk are all real artifacts from Maven Central,
so 76 of 117 main sources, 39 of 53 test sources and 257 tests run locally, plus detekt over
all three source sets. The environment is unchanged: `dl.google.com` still 403s on CONNECT.

**The one thing worth carrying forward is how it failed.** The first push went red on
`StabilityContractTest`, which walks the compiled output for everything assignable to
`ViewModel` and found the new `UdfViewModel` base class as a seventh entry. The harness had
compiled and run 245 tests and reported itself green — because `StabilityContractTest` was
being *silently skipped*, excluded by one unresolvable import (`ImageVector`, via
`AdaptiveNavItem`), and so was `GoogleSignInViewModelTest`, the most heavily rewritten test in
the change, because the import-closure check only recorded top-level names and could not
satisfy `FakeGoogleAuthRepository.Companion.fakeGoogleUser`. A count of "37 of 53" reads as
coverage and is not. `run.sh` now prints every skipped test file by name under `not run here:`
— read that list, and treat shortening it (usually one stub) as cheaper than a red CI run.

Two smaller traps for whoever adds a screen: a new `ViewModel` has to be added to
`EXPECTED_VIEW_MODELS` in *two* contract tests, both of which assert the whole list; and a stub
must reproduce the real declaration's annotations, not just its shape — `ImageVector` carries
`@Immutable`, and without it the stability audit fails on the stub rather than on the app.

Still open from earlier items and untouched here: `README.md` advertises "Retrofit 3 + OkHttp
5" while the catalog pins Retrofit 2.11.0 / OkHttp 4.12.0, and the wrapper has no
`distributionSha256Sum`. Modularisation is the next item, and it is also what deletes the
`@StabilityInferred` exemption `docs/clean-architecture.md` records.

Item 7 complete as of PR #36 (2026-08-23). **Five CI rounds, and every one of them found
something no offline check could have.** The environment is unchanged — `dl.google.com` still
403s on CONNECT, so AGP does not resolve and none of the five Gradle gates run here — and the
harness in `scripts/jvm-harness/` was rewritten to be module-aware: it reads the module graph out
of the build files and compiles each module against only the classpath its build file entitles it
to, `api` transitive and `implementation` not. That found the two real defects above plus a stale
`BuildConfig` import before anything was pushed. What it could not find is the interesting part.

**Round 1 — `build-logic` did not compile.** Three errors, and the first is worth remembering: a
Kotlin block comment ends at the first `*/`, and `**/core/domain/**` quoted inside one contains
exactly that, so the comment closed mid-sentence and the rest of the glob became source. The
reported failure was "Expecting a top level declaration" on a line that looks fine. Also
`Plugin.apply` returns `Unit`, so an expression body ending in `dependencies.apply { … }` does not
override it; and `platform(…)` is a member of Gradle's `DependencyHandler`, not a Kotlin DSL
extension, so `import org.gradle.kotlin.dsl.platform` does not resolve. Separately,
`hilt-android-gradle-plugin` 2.57.2 carries a Kotlin 2.x `.kotlin_module` the `kotlin-dsl`
compiler refuses to read — keep it off `build-logic`'s classpath; nothing there names a Hilt type.

**Round 2 — a convention plugin collected `configurations` at apply time**, i.e. before the
module's own `dependencies { }` block had run. Two consequences, and the second is the one to
watch for. AGP has created no variant classpaths that early, so `resolveAllDependencies` — the
Phase 0 gate that proves every declared version exists — was resolving a handful of
plugin-internal classpaths and passing: all thirteen modules reported the identical "5
configurations, 39 module nodes", `:app` alongside `:core:navigation`. And asking a configuration
for its `resolutionResult` marks it observed, so `api(project(":core:common"))` declared afterwards
was silently dropped and `:core:domain` failed to compile against a module its own build file
names. `afterEvaluate` fixes both; the task now asserts it collected `debugCompileClasspath`
before doing anything, and the gate reports 20–31 configurations and 234–1906 module nodes per
module. **If a verification task's numbers are identical across modules, it is not verifying.**

**Round 3 — `javax.inject` was not declared anywhere.** `SyncStrategyFactory`'s constructor takes
a `Map<SyncMode, Provider<SyncStrategy>>`, so `Provider` is on `:core:domain`'s public surface and
needs `api`, not the `implementation` the Hilt convention supplies. The harness had missed it
because it hands every module the same jars; it now checks `javax.inject` and `dagger` against the
build files instead.

**Rounds 4 and 5 — the whole-app discovery, twice.** `EXPECTED_MODULE_PACKAGES` in `CompiledApp`
still named the theme's old package one commit after the theme moved, and then
`getResources("com/kojo/boilerplate")` turned out to return every *dependency's* output but not
`:app`'s own, so `com.kojo.boilerplate.navigation` went missing. The module the contract tests live
in is now reached through a class in it named as a string — no import, so the file stays
compilable by the harness. Roots are compared by equality rather than path prefix, because
`…/kotlin-classes/debugUnitTest` starts with `…/kotlin-classes/debug`.

**Every one of those five fixes carries the check that would have caught it**, which is the only
reason the count is not higher: the harness now parses `build-logic`, cross-checks
`EXPECTED_MODULE_PACKAGES` against the packages modules actually declare, and scans every source
for a comment that closes inside a code span or for invisible characters. That last one is not
theoretical — a zero-width space was the only thing keeping the round-1 comment alive, and
deleting it looked like deleting nothing.

Still open and untouched here: `README.md` advertises "Retrofit 3 + OkHttp 5" while the catalog
pins Retrofit 2.11.0 / OkHttp 4.12.0, and the wrapper has no `distributionSha256Sum`. New with
this change: `:data` still holds `core.data`, `core.database`, `core.datastore`, `core.network` and
`core.di` — packages were left alone except where one would straddle two modules, and aligning
them is a mechanical follow-up. `:core:auth` exists only because `GoogleAuthRepository.signIn`
takes a `Context`, which is `docs/solid.md` finding 2 showing up as a module; inverting that
parameter folds the module into `:data`.

## Phase 9 — Offline-First & Data
- [x] WorkManager background sync with constraints, backoff, and unique work — a six-hourly periodic sync of the signed-in user, under CONNECTED + battery-not-low, exponential backoff from 30s against a four-attempt budget, enqueued as unique work under `UPDATE` (PR #37)
- [x] Offline-first repository: single source of truth in Room with a `NetworkBoundResource` — `networkBoundResource` in `:core:common` joins the two halves that nothing obliged a caller to connect, and `ObserveUserProfileUseCase` is the first to use it, so the profile screen refreshes at all for the first time; `refresh` returns `Unit`, which is what makes "the network only ever writes to Room" a property of the signature (PR #38)
- [x] Conflict resolution: last-write-wins vs merge, with a version column — every fetch used to upsert straight over whatever Room held, so the stored row was decided by arrival order; `syncUsers` fans out concurrently and `CachingUserRepository` hands one response to every joined caller, which is what makes that a real ordering bug rather than a theoretical one. `UserEntity` gains a server-assigned `version` and a `locallyChanged` field set, and `VersionOrderedConflictResolver` settles the four cases both policies agree on — the fifth, a newer server row over an unpushed edit, is the only thing a policy decides. Deliberately a counter and not a timestamp: a wall-clock tiebreaker lets a device ten minutes fast win every conflict it takes part in, which is an acceptable price for a freshness decision and not for one that costs data, and it is why the `updatedAt` column `docs/offline-first.md` expected here was not added. Deliberately a set and not a boolean, because with one flag a merge can only keep the whole local row or none of it, and both of those are last-write-wins. `MERGE` is the default — under the other policy the six-hourly background sync would silently undo a profile edit. Applied inside a Room `@Transaction`, since without one two concurrent syncs both read the pre-write row and the second writer wins whatever its version. Against a server that sends no version it degrades to what the repo already did, with the unpushed edit still protected (PR #39)
- [x] Paging 3 with `RemoteMediator` over Room + network — Room is the only source of data, so the list renders offline from the first frame and `MediatorResult.Error` shows as a `LoadState.Error` beside the cached pages rather than emptying them. The contract is a new module, `:core:paging`: `Flow<PagingData<User>>` cannot be a seventh method on `UserRepository` because `PagingData` is an `androidx` type and `:core:domain` carries none, and it cannot sit in `:data` because a feature will consume it — the same bind `:core:auth` is in, except that a fix deletes `:core:auth` and nothing deletes this one. Three deliberate departures from the codelab, each because its answer breaks something already settled here: the cursor is one row rather than a key per user, because `syncUser` and `syncCurrentUser` write rows that were never on a page and an `APPEND` reading a null key off one truncates the list silently; `REFRESH` refills rather than clearing, because `deleteAll()` would take the profile screen's cached rows and any unpushed local edit with them, making scrolling the way to destroy a change; and pages go through the `ConflictResolver` like every other fetched write, since it is the write that happens most often. End of pagination is a short page, not an envelope field — with the one server behaviour that defeats that (a capped `per_page`) written down rather than left to be found. Fourteen modules now. The offline harness was red on `main` and had been since PR #39: it selects files by their imports, and `UserFieldSetConverterTest` names its Room `@TypeConverter` from the same package, so it was compiled against nothing and aborted the run before eight modules' tests — a failure reported where a skip belonged. `missing_sibling` closes it, matching over code with comments and strings stripped and against column-zero declarations only, both narrowings found by watching the first version drop `:feature:home` over a local `val scope`. Not done: no screen consumes it yet (Phase 10's `LazyColumn` item), and there is no instrumented test — atomicity and the generated `PagingSource` need a real database (PR #40)
- [x] Room migrations with exported schemas and a migration test suite — `MIGRATION_1_2` and `MIGRATION_2_3` had existed since PRs #39 and #40 and neither had ever been executed, which is the one place where "written but never run" costs a user's data rather than a red build: a fresh install builds the schema from the entities and is always correct, so a broken migration is invisible on the machine that wrote it and only appears on a device that had the previous version installed. `exportSchema = true` and `room { schemaDirectory(…) }` had both been set since PR #36, so Room's compiler had been writing a schema file on every build for months and nobody ever committed one — not ignored, just never added, so CI generated the artifact into a working tree and threw the tree away. Versions 1 and 2 were regenerated by building the commits that declared them (`5aa38e0`, `b723ff2`), since Room only ever exports the version of the code in front of it; a temporary bootstrap workflow did that and was deleted again, because the Android SDK and Google Maven are unreachable from the scheduled agent's environment. The suite is a **unit** test, not an instrumented one: `androidTest` runs nowhere here (Phase 12's emulator matrix is still unchecked), so an instrumented suite would have joined `UserDaoTest` in never executing, and Robolectric puts it under `testDebugUnitTest` — a gate CI already enforces. `MigrationTestHelper` is not used because it loads its bundles through an instrumentation context's assets, the same source set that never runs; `ExportedSchema` reads the files off disk instead, which is also what makes `data/schemas` load-bearing rather than decorative. Validation is Room's own — `RoomOpenHelper.onUpgrade` compares the migrated tables against the schema compiled into `AppDatabase_Impl` — with `everyExportedVersionMigratesToTheCurrentSchema` as an independent second check against a fresh install, covering the column defaults and undeclared indices Room's validator does not look at, and growing on its own as versions are added because it reads the directory rather than a list. `AppDatabase.ALL_MIGRATIONS` is one list for `DatabaseModule` and the test, so the app cannot ship without a migration the suite proved. Not done: no auto-migrations, no downgrade path (`fallbackToDestructiveMigrationOnDowngrade` deliberately unset, so an older build over a newer database throws), and Robolectric's SQLite is the host's build of the library — an OEM-specific difference is still Phase 12's job (PR #41)
- [x] Idempotent sync requests with client-generated keys — the write half of the sync, and the thing that makes retrying one safe. A mutating request has three outcomes and the client can only tell two of them apart: never received, applied-but-the-response-was-lost, and applied-and-answered. The first two look identical from here and call for opposite responses, and no timeout tuning closes the gap — so the two attempts have to be *recognisable as one*, which is a property of the request rather than of the retry. `users` gains a `pendingChangeKey`, `saveUser` names each mutation, and `PendingUserChangeRepository` sends every pending row under the key its row holds. Three rules, each with a wrong version that compiles: the key is minted only where the mutation is created (generating one at send time reads perfectly well and deletes the entire guarantee — every attempt would introduce itself as a new change); a new key when, and only when, the payload changes (reusing a name for a different edit lets a server drop the second as a duplicate, *only when* because a re-submitted identical save must keep its name or a push in flight is renamed underneath itself); and the key is non-null exactly when the pending set is, which `toEntity` enforces by taking it as a required parameter. Stored rather than derived from the change, because a hash makes edit→revert→edit-back collide with the first key and drop the user's change; a counter repeats after a reinstall. `commitAcknowledging` clears a row only while it still holds the key that was sent, so a `saveUser` landing mid-flight is not silently discarded. The push runs before the fetch in `PerformBackgroundSyncUseCase`: under `LAST_WRITE_WINS` a pull that ran first would destroy the unsent edit before anything tried to send it. It is its own interface rather than a seventh `UserRepository` method because all three decorators would have had to answer for it and all three answers would be wrong — which is also why the key is a column, since the retry that matters arrives hours later in a new process. CI caught two things the local harness could not: a fourth `toEntity` call site in `UsersRemoteMediator` (the required parameter working exactly as intended — a defaulted key would have compiled and let a paged write keep an unpushed edit while dropping the name for it), now fixed by collapsing three copies of the resolution into one `resolvedEntity`; and that the `Room schemas are committed` gate does not verify schemas at all. It passed over a `4.json` whose `identityHash` was 32 zeros typed by hand, on a run where `:data:kspDebugKotlin` executed and `:data:copyRoomSchemas` still reported NO-SOURCE. Two attempted fixes both failed — reading Room's intermediates compared an empty directory, and forcing `--rerun-tasks` produced no schema at all and would have failed every future merge — so `ci.yml` is left exactly as found and the finding, the evidence and the working recipe are in `docs/room-migrations.md` instead. The real hash came from a temporary workflow that built on a runner with the SDK, which is evidence independent of that gate. Not done: no base version on the wire, no telemetry on the push, no general outbox (one unsent mutation per row, right for a renamed profile and wrong for two payments), and `saveUser` still has no production caller, so nothing is queued on a device nobody has edited anything on (PR #42)
- [x] DataStore Proto for typed structured preferences — `user_preferences.pb` behind a `.proto` schema, read and written through `UserPreferencesDataSource`. The app already had two DataStores and both are the untyped kind, which is the right shape for the auth tokens and the theme and the wrong one for anything with structure: a misspelled key reads back the default with no diagnostic anywhere, a wrong type throws at read time, and two settings that must change together cannot. The schema carries a nested `SyncPreferencesProto` and an enum because those are exactly the two things the untyped store cannot express. The nesting is not decoration — three sibling keys are three writes, and a crash between two of them leaves a combination on disk that was never on screen; one nested message is one write. Its mirror image is `recordSuccessfulSync`, a narrow write that must not disturb the block around it, done as a read-modify-write *inside* `updateData` rather than from a value the caller observed earlier: the version that reads better silently reverts any policy change that landed since that read, and the background sync is both the most frequent writer here and the one holding the stalest copy of everything else. Fifteen modules now. `:core:datastore-proto` is a module for a reason that is about the build before it is about the architecture — protoc emits Java source during the build and `:data` runs two KSP processors, so a generated-source directory KSP reads without depending on the task that writes it is a Gradle implicit-dependency failure on a good day and a race on a bad one; a module with no annotation processor removes the question instead of answering it. The layering it also buys is the second reason and still real: it is an `implementation` dependency, so nothing above `:data` can name a generated type. Three schema facts stop at the mapping to `:core:common`'s Kotlin models: `0` epoch millis means "never synced" and is also a real instant, so it becomes `Instant?`; proto3 has no defaults, so `UNSPECIFIED` resolves to `SyncPolicy.Default` in one named place; and `UNRECOGNIZED` — a constant written by a newer build on the same device — resolves rather than throwing, because it is one unreadable field and not an unreadable file. The serializer maps `InvalidProtocolBufferException` to `CorruptionException` and *only* that, since it is the only exception DataStore gives its corruption handler a chance to answer; an `IOException` is deliberately left alone, because answering it by discarding the user's settings would turn "the device is out of space" into "the app forgot your preferences". protoc and `protobuf-javalite` share one catalog key, which is load-bearing: 4.x gencode validates the runtime version in its static initialiser. The Gradle plugin is held at 0.9.6, the last of the line that drives AGP through the `BaseVariant` API 8.7.3 still ships; 0.10.0's move to the new sources API is a change to make when AGP moves. None of the six Gradle gates ran locally — `dl.google.com` answers 403 on CONNECT from the agent's environment — so what was verified before pushing was the offline harness (15 modules, 0 boundary violations, detekt 0 findings), protoc 4.36.1 over the schema, and both new test classes compiled and executed against the real protobuf runtime with a file-backed stand-in for `DataStoreFactory`: 11 tests, all passing. CI proved the rest first time — twelve gate steps plus the APK build, green on the first run, which is the protobuf plugin's AGP wiring, KSP codegen, lint and `assembleDebug` all confirmed. Not done: nothing consumes it yet (the sync policy's first real reader is `WorkManagerBackgroundSyncScheduler`, and wiring it means re-enqueuing the periodic work when the preference changes), the theme is still in a Preferences DataStore, the store is single-process, and there is no instrumented test (PR #43)

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

Phase 9 item 1 complete as of PR #37 (2026-08-25). All four checks green:
dependency resolution, `modules · compile · lint · detekt · test`, `Build +
verify APK`, and GitGuardian.

`SyncMode.CURRENT_USER` finally has the caller it was written for. Adding it was
a use case naming a mode — no edit to `SyncStrategyFactory`, to the multibinding
map, or to the other strategy — which is the property the Phase 7 indirection
was bought for and the first time it has been paid out.

The layer split is worth restating because it is the decision in the change:
WorkManager's vocabulary stays in `:data`, and `:core:domain` takes only what
survives without it. That is not tidiness — a `ListenableWorker` needs a
`Context` and a `WorkerParameters` that only the framework builds, so anything
left inside one is checkable on an emulator and nowhere else. What crossed:
which users a background run covers, what counts as done, that a throw is a
retry rather than a dropped occurrence, and that cancellation is neither.

Two pins are load-bearing and neither is the newest release. `androidx.work` is
**2.10.0** because Gradle resolves to the highest version anyone asks for and
`lifecycleRuntimeKtx` is held at 2.8.7 for the AGP 8.7.3 lint crash recorded
above; 2.10.0 predates lifecycle 2.9.0 entirely, so it cannot undo that pin,
while a later WorkManager could — and the failure would surface as a lint crash
naming nothing about WorkManager. `androidx.hilt` stays at **1.2.0**, with the
version key renamed `hiltNavigationCompose` -> `androidxHilt` now that three
artifacts from one release wave share it.

The environment constraint holds unchanged: `dl.google.com` is still 403 on
CONNECT, so zero of the five CLAUDE.md gates ran locally and CI was the gate.
What has improved since the note above is what stands in for them —
`scripts/jvm-harness/run.sh` is now checked in and ran green end to end
(13 modules / 0 boundary violations, 23 build scripts, per-module compile,
every runnable suite, detekt 0 findings). It caught nothing this time, which is
the outcome to want from a pre-flight check.

Known gaps carried forward, all four written into `docs/background-sync.md`
rather than left here: nothing proves Hilt can actually construct
`UserSyncWorker` or that a run happens — that needs `WorkManagerTestInitHelper`
and the emulator matrix of Phase 12, with `compileDebugKotlin` standing in for
the binding half since KSP fails on an unsatisfied one. A failure's cause is
reported nowhere, and belongs with Phase 11's crash reporting.
`cancelPeriodicSync` has no production caller, because sign-out does not exist
yet. And the sync runs whether or not anyone is signed in, so an install that
has never signed in will fail, retry and give up four times a day — gating the
schedule on auth state is the natural follow-up and pairs with the sign-out
cancellation.

Phase 9 item 2 complete as of PR #38 (2026-08-27). All four checks green:
dependency resolution, `checkModuleDependencies` / `compileDebugKotlin` /
`lintDebug` / `detekt` / `testDebugUnitTest`, `assembleDebug` + APK
verification, and GitGuardian.

The item asked for a `NetworkBoundResource` and the interesting part was
deciding **where** to compose one. Inside `UserRepositoryImpl` is the obvious
reading of "offline-first *repository*", and it is wrong here for a reason
specific to this codebase: retry, caching and telemetry are decorators around
the `UserRepository` interface, so a resource built inside the implementation
would reach the DAO and the API underneath all three — no backoff, no
telemetry, and no coalescing, meaning a two-pane profile layout would fire two
identical requests every time it subscribed. Composed above the repository, in
the use case, the refresh is an ordinary `syncUser` and inherits the whole
stack. `docs/offline-first.md` is the argument; `docs/decorator.md` carries the
other half of it.

Two departures from the canonical implementations, both deliberate and both
tested. `Resource` has no arm without data — the payload is non-null on
`Loading`, `Success` and `Failure` alike, because every emission comes from
reading the store, and a nullable payload makes every call site's
`if (data != null)` a stand-in for "has the store been read yet?". And there is
no `shouldFetch`: freshness is `CachingUserRepository`'s existing 30-second
window, and a second copy of that decision here is a second place for it to
disagree with the first.

The product decision worth knowing about: a refresh that fails over a cached
row renders the cached row rather than an error. `UserProfile.Unavailable` is
kept for having genuinely nothing to show.

Known gaps, carried forward and written into `docs/offline-first.md`. A screen
showing a cached row after a failed refresh gets **no staleness signal** —
`UserProfile` has nowhere to put one, and adding a fourth arm belongs with the
Phase 10 UI items. Nothing in Room records when a row was written, so "stale"
means "the refresh this subscription ran did not land", never "this row is four
days old"; the column that changes that arrives with the conflict-resolution
item immediately below. And lists are deliberately not covered: a resource over
`getUsers` needs a `refresh` that fetches the whole queried set, and this API
has no bulk endpoint, which is the same constraint that made the list refresh a
fan-out in the first place.

The environment constraint holds unchanged: `dl.google.com` is still 403 on
CONNECT, so zero of the five CLAUDE.md gates ran locally and CI was the gate.
`scripts/jvm-harness/run.sh` ran green end to end beforehand (13 modules / 0
boundary violations, 23 build scripts, per-module compile, 299 tests across
eleven modules, detekt 0 findings). It earned its place this time: it caught
that `ProfileDetailPaneViewModelTest` had been passing only because MockK's
"no answer found for syncUser(nonexistent-id)" message happens to contain the
id the assertion was looking for. One note for whoever runs it next — its jar
fetch retries `URLError` and `TimeoutError` but not `http.client.IncompleteRead`,
so a truncated download of the 60 MB Kotlin compiler aborts the whole run;
pre-fetching with `curl --retry` into `~/.jvm-harness/jars` is the workaround,
and widening that `except` clause is the fix.
