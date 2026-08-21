# Offline JVM harness

```
scripts/jvm-harness/run.sh            # compile + test + detekt
scripts/jvm-harness/run.sh --skip-detekt
JVM_HARNESS_JARS=/path/to/jars scripts/jvm-harness/run.sh
```

Compiles, tests and lints as much of `:app` as can be done **without the Android SDK**. It is
a pre-flight check, not a replacement for CI.

## Why it exists

All five gates in `CLAUDE.md` go through Gradle, and Gradle cannot configure this project
without the Android Gradle Plugin, which lives on Google Maven. In the scheduled agent's
environment `dl.google.com` answers 403 on CONNECT — confirmed against
`$HTTPS_PROXY/__agentproxy/status`, which names the denial — so AGP does not resolve, no
Android SDK can be installed, and none of the five gates run at all.

Maven Central *is* reachable, and most of this module is ordinary JVM Kotlin. This runs the
Kotlin compiler, the JUnit console and detekt over that part directly. It was rebuilt from
prose in `SPEC.md` twice before being checked in; that is why it is here.

## What it covers

At the time of writing: 76 of 117 `src/main` files, 39 of 53 `src/test` files, 257 tests, and
detekt over `src/main`, `src/test` **and** `src/androidTest` with the repo's own config — the
same three source sets `app/build.gradle.kts` gives the Gradle `detekt` task, so that gate is
covered in full rather than in part.

Which files join is computed from their imports, not from a list: a file is in when every
import resolves against the jars the script fetches or against a stub in `stubs/`, and when
every app symbol it imports is itself in. A new file is therefore picked up, or skipped, on
its own merits.

**Every skipped test file is printed by name on each run**, under `not run here:`. That is not
decoration. A skipped test is a gate this run did not apply, and a bare count reads as
coverage: `StabilityContractTest` was excluded for a single unresolvable import
(`ImageVector`, via `AdaptiveNavItem`), the count said "37 of 53", and a regression it would
have caught reached CI instead. Anything in that list is CI's job, and shortening it — by
adding a stub, as `ImageVector` now is — is usually cheap.

## What it cannot cover

- **Anything touching Room, DataStore, CameraX, MLKit, Credential Manager, Navigation or
  Compose UI.** Those are Google Maven artifacts. That is most of `feature/*/…Screen.kt`,
  `core/database`, `core/datastore` and the Hilt modules.
- **Hilt/KSP code generation.** The `@HiltViewModel` annotation is stubbed so that the
  declarations compile; the component Hilt would generate from them is not produced, so a
  binding that does not exist still compiles here and fails in CI.
- **Android Lint.** `lintDebug` needs AGP.
- **`assembleDebug` and the APK checks.** `scripts/verify-apk.sh` needs a built APK.
- **`SolidContractTest`.** It asserts over the whole app's compiled output — every repository
  interface and every implementation — so a partial compilation fails it on the harness's own
  coverage rather than on anything about the code. It is the one entry in `HARD_EXCLUDES` in
  `run.sh`, with that reason next to it. `StabilityContractTest`, `DomainLayerContractTest` and
  `UnidirectionalDataFlowContractTest` read compiled output too and *do* run: they walk out
  from the view models, which the harness compiles in full.
- Whatever the run prints under `not run here:` — mostly the Room- and DataStore-backed
  repository and DAO tests.

## The stubs

`stubs/` holds hand-written stand-ins for the handful of `androidx`/`android` declarations the
JVM-compilable subset needs: `ViewModel` and `viewModelScope`, `SavedStateHandle` and
`toRoute`, `@Immutable`/`@Stable`, `ImageVector`, `Log`, `Context`, `NetworkCapabilities`, the
two Credential Manager exceptions, and `@HiltViewModel`. Each one says in its own KDoc what it
reproduces and what it deliberately does not.

Three are worth knowing about because getting them wrong would make a test pass — or fail —
for the wrong reason:

- `@Immutable`/`@Stable` are declared `BINARY` retention, matching the real ones.
  `StabilityContractTest` finds them by searching the class file's constant pool *because* the
  JVM drops binary-retention annotations before reflection can see them; a `RUNTIME` stub would
  hide that.
- `viewModelScope` is `SupervisorJob() + Dispatchers.Main.immediate`, exactly as the real one
  builds it, so a test that swaps the Main dispatcher behaves as it would on device.
- `ImageVector` carries `@Immutable` because the real class does. Without it the stability
  audit reports `AdaptiveNavItem` as holding an unstable property — a failure about the stub
  rather than about the app, which is the other way a stub can waste an afternoon.

Everything else — dagger, hilt-core, Retrofit, OkHttp, kotlinx, mockk, JUnit — is the real
artifact from Maven Central at the version `gradle/libs.versions.toml` pins.

## Invoking the Kotlin compiler as a library

`kotlinc` is not published to Maven, so `run.sh` calls `K2JVMCompiler` directly. Four things
are needed that no error message names, and all four have been rediscovered more than once:

| Missing | Failure |
|---|---|
| `org.jetbrains:annotations` on the *compiler's* classpath | "Backend Internal error" — `NoClassDefFoundError: …Nullable` inside `AnnotationCodegen` |
| `kotlin-stdlib` + `kotlinx-coroutines-core`, same place | dies in `CoreApplicationEnvironment.createApplication` before reading a source file |
| `org.jetbrains.intellij.deps:trove4j` | `NoClassDefFoundError: gnu/trove/TObjectHashingStrategy` from source collection |
| `-jvm-target 17` | the 1.8 default crashes the backend generating `safeCall`, a suspend function returning `Result` |

Maven Central answers 429 to a burst, so the fetch backs off rather than looping.
