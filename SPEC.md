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
- [ ] Custom `CoroutineExceptionHandler` + a `Result`-returning `safeCall` wrapper
- [ ] Flow operators in anger: `flatMapLatest`, `debounce`, `distinctUntilChanged`, `retryWhen`
- [ ] `StateFlow` vs `SharedFlow` decision guide with `WhileSubscribed(5000)` and config-change survival
- [ ] `callbackFlow` + `awaitClose` wrapping a legacy listener API
- [ ] Dispatcher injection for testability + `runTest` with a `TestDispatcher`
- [ ] Concurrent request fan-out with `async`/`awaitAll` and partial-failure handling
- [ ] Immutability: `data class` + `@Immutable`/`@Stable` annotations and persistent collections

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

## Phase 8 — Architecture & Patterns
- [ ] SOLID audit of the repository/use-case layers documented in `docs/solid.md`
- [ ] Clean Architecture layering: domain use-cases with no Android imports, enforced by a lint rule
- [ ] Factory + Strategy: pluggable `SyncStrategy` resolved by Hilt multibinding
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
