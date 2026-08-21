# boilerplate-android-kotlin

> Kotlin 2.1 · Jetpack Compose · Hilt · Room · Retrofit · MLKit

Modern Android app starter following Google's recommended architecture.

## Stack

| Layer | Tech |
|-------|------|
| Language | Kotlin 2.1 |
| UI | Jetpack Compose + Material3 |
| DI | Hilt |
| Navigation | Navigation Compose (typed routes) |
| DB | Room 2.7 |
| Network | Retrofit 3 + OkHttp 5 |
| Async | Kotlin Coroutines + Flow |
| ML | MLKit (text, barcode) |
| Camera | CameraX |
| Testing | JUnit 5 + MockK + Compose UI Test |

## Docs

- [Structured concurrency](./docs/structured-concurrency.md) — `coroutineScope` vs
  `supervisorScope`, and the cancellation rules the compiler does not enforce.
- [Concurrent fan-out](./docs/fan-out.md) — running one operation over many inputs at
  once: bounding the concurrency, and choosing between all-or-nothing and a partial result
  that says which inputs did not land.
- [Coroutine error handling](./docs/coroutine-errors.md) — `safeCall` for work with a
  caller, `AppCoroutineExceptionHandler` for work without one, and when a handler is
  silently not consulted.
- [Flow operators](./docs/flow-operators.md) — `flatMapLatest`, `debounce`,
  `distinctUntilChanged` and `retryWhen`: where each one belongs in a ViewModel pipeline
  and what goes wrong when it is left out or put in the wrong place.
- [State and events](./docs/state-and-events.md) — `StateFlow` vs `SharedFlow` vs
  `Channel`, what `WhileSubscribed(5_000)` buys, and why a one-shot event held as state
  fires again on every configuration change.
- [Dispatchers](./docs/dispatchers.md) — which layer owns the thread its work runs on,
  why Room and Retrofit dispatching internally is what makes this easy to get wrong, and
  how `runTest` plus an injected `TestDispatcher` turns "does it confine?" into an
  assertion.
- [Immutability and Compose stability](./docs/immutability.md) — why a `List` property
  costs a screen its skipping, when `ImmutableList` and `PersistentList` differ, and what
  `@Immutable` promises the compiler that nothing verifies.
- [SOLID in the repository layer](./docs/solid.md) — an audit of the abstractions the data
  flows through: where each principle holds, the eight places it does not, and why the
  application policy that was duplicated across two ViewModels is the finding the rest of
  Phase 8 is built around.
- [The domain layer](./docs/clean-architecture.md) — what earns a place in `core.domain`
  (duplicated policy, not "business logic"), why the framework ban is enforced twice, and
  what a lint rule scoped to one package still cannot tell you.
- [Sync strategies](./docs/sync-strategy.md) — Factory + Strategy over a Dagger
  multibinding: why the map beats a `when`, why it holds `Provider`s, and the two wiring
  mistakes Dagger cannot catch that a contract test and a runtime check do.
- [Repository decorators](./docs/decorator.md) — cache, retry and telemetry as layers around
  an unchanged `UserRepositoryImpl`: what each position in the stack buys, why a retry that
  `runCatching`s a `Result` never retries, and why a shared in-flight request has to be owned
  by nobody.
- [The app-wide event bus](./docs/event-bus.md) — Observer on a `SharedFlow`: the three
  questions an event has to answer to get on it, why a `Channel` cannot do this job and a
  `replay` cannot fix it, and why the subscription that matters is started in `Application`
  rather than in a screen.
- [Unidirectional data flow](./docs/unidirectional-data-flow.md) — the one `UiState` /
  `UiEvent` / `UiEffect` contract every screen is written against: when a field beats a sealed
  case, why two flows for one screen is a bug waiting for a race, and what `Nothing` says that
  a comment cannot.

## Quick Start

1. Open in Android Studio Meerkat+
2. Sync Gradle
3. Create `local.properties` with `API_URL=https://your-api.com`
4. Run on emulator (API 26+) or device

## CI

Two workflows run on every push to `main` and every pull request.

### `ci.yml` — gates → APK

| Job | Runs |
|-----|------|
| **compile · lint · detekt · test** | `compileDebugKotlin`, `lintDebug`, `detekt`, `testDebugUnitTest` |
| **Build + verify APK** (needs the gates) | `assembleDebug`, then `scripts/verify-apk.sh` |

All four gates run even when an earlier one fails, so a single run reports
every result rather than stopping at the first.

Artifacts: `gate-reports` (lint HTML, detekt, test reports and JUnit XML,
7-day retention) and `app-debug` (`app-debug.apk`, 14-day retention). The APK
is uploaded even when verification fails, so the artifact is available to
inspect without re-running CI.

### Verifying the APK

`assembleDebug` exiting 0 means the build ran; it does not mean the artifact is
installable. `scripts/verify-apk.sh` checks the properties Android's package
installer would otherwise reject the APK for:

- the archive is readable and carries `AndroidManifest.xml`, at least one
  `classes*.dex` and `resources.arsc`
- uncompressed entries are 4-byte aligned (`zipalign -c 4`)
- `apksigner verify` passes across the app's own API 26–35 range, under an APK
  Signature Scheme v2 or newer, with the Android debug certificate
- the package, `versionCode`, `versionName`, `minSdk` and `targetSdk` match what
  `app/build.gradle.kts` declares
- there is a launchable activity, and the APK is marked debuggable

The expected identity is not a second copy of those values: `./gradlew
:app:writeDebugApkIdentity` writes them out of the build DSL to
`app/build/apk-identity/debug.properties`, which the script reads. Changing
`defaultConfig` moves the expectation with it, and the check keeps proving that
AGP propagated the declared values into the artifact.

Run it locally with:

```bash
./gradlew :app:assembleDebug :app:writeDebugApkIdentity
./scripts/verify-apk.sh
```

`scripts/verify-apk.test.sh` drives the script against stub build-tools to prove
each check fails when it should. It needs only bash — no SDK, no APK, no network
— and runs in CI ahead of the build.

Not covered: the APK is never installed on an emulator, and alignment is checked
at 4 bytes rather than the 16 KB page alignment Android 15+ wants for native
libraries.

The debug build needs no secrets. A signed release build would add
`KEYSTORE_FILE`, `KEY_ALIAS`, `KEY_PASSWORD` and `STORE_PASSWORD` to the
repository secret store and extend the `build` job — none of those belong in
the repository.

### `dependency-resolution.yml` — resolution only

Resolves every version declared in `gradle/libs.versions.toml` without
compiling, so a version that does not exist fails fast and on its own.

## Spec Progress
See [SPEC.md](./SPEC.md).
