# Offline JVM harness

```
scripts/jvm-harness/run.sh            # boundaries + compile + test + detekt
scripts/jvm-harness/run.sh --skip-detekt
scripts/jvm-harness/run.sh --skip-tests
JVM_HARNESS_JARS=/path/to/jars scripts/jvm-harness/run.sh
```

Compiles, tests and lints as much of this repository as can be done **without the Android
SDK**. It is a pre-flight check, not a replacement for CI.

## Why it exists

All five gates in `CLAUDE.md` go through Gradle, and Gradle cannot configure this project
without the Android Gradle Plugin, which lives on Google Maven. In the scheduled agent's
environment `dl.google.com` answers 403 on CONNECT — confirmed against
`$HTTPS_PROXY/__agentproxy/status`, which names the denial — so AGP does not resolve, no
Android SDK can be installed, and none of the five gates run at all.

Maven Central *is* reachable, and most of this repository is ordinary JVM Kotlin. This runs the
Kotlin compiler, the JUnit console and detekt over that part directly. It was rebuilt from prose
in `SPEC.md` twice before being checked in; that is why it is here.

## What it covers

**Build scripts, before anything else.** Two checks that cost two seconds between them and each
one has already paid for itself:

- **A block comment that ends inside a `` ` ``-quoted span.** A Kotlin block comment ends at the
  first `*/`, and a path glob quoted inside one — a `core/domain` glob with stars on both sides —
  contains exactly that sequence. The comment closes early, the rest of the glob becomes source,
  and the file stops parsing with "Expecting a top level declaration" pointing at a line that
  looks fine. This is checked across every Kotlin source and every build script.
- **No invisible characters.** A zero-width space once sat between the stars and the slash of a
  path glob quoted inside a block comment, and was the only thing stopping that comment from
  terminating early. Deleting it looked like deleting nothing and broke the build. Nothing here
  has a legitimate use for one.
- **`build-logic` parses.** Nothing offline can *type-check* those files — they compile against
  AGP, which is what this environment cannot fetch — but a syntax error in them fails the very
  first Gradle invocation, so it costs a whole CI round trip to learn something the parser knows
  immediately. Only the parser's own diagnostics are reported; every semantic one is expected
  and discarded.

**The module graph, next and still cheap.** Before compiling anything it reads
`settings.gradle.kts` and every module's `build.gradle.kts`, and checks that each source file's
imports stay inside the modules that file's own module declares a dependency on — with `api`
edges travelling transitively and `implementation` edges not, as Gradle does it. That makes this
the one place outside CI where the layering is enforced rather than described, and it reports in
seconds. Two failures it is specifically shaped to catch:

- **A package declared in two modules.** A split package breaks `internal` visibility and
  confuses every path-scoped rule in the repository. The run stops and names both modules.
- **An import of `javax.inject` or `dagger` with nothing declaring it.** The harness hands every
  module the same jars, so a module that leans on what the `boilerplate.hilt` convention supplies
  elsewhere compiles here and fails in CI — which is what `:core:testing` did the moment
  `syncStrategyFactoryOver` moved into it. Checked against the build files rather than against
  the compiler, and only for these two: the rest of the external classpath is androidx, which
  cannot be fetched here and so cannot be modelled per module without inventing failures.
- **`EXPECTED_MODULE_PACKAGES` drifting.** That list in `CompiledApp` is what makes the
  whole-app contract tests fail loudly when a module drops off `:app`'s classpath, rather than
  quietly auditing less. It is hand-written, so it drifts: the theme's package stayed in it for
  a full CI run after the theme moved into `:core:ui`. Both directions are checked — an entry
  no module declares, and a module no entry covers.
- **A reference to a same-package declaration the selection dropped.** Which files join is
  computed from *imports*, and a declaration in the file's own package needs none — so a test
  sitting beside the class it instantiates was selected however unbuildable that class is here.
  `UserFieldSetConverterTest` is in the Room `@TypeConverter`'s own package, so when the
  converter was left to CI the test still compiled against nothing and took `:data`'s entire
  test compilation down with it. The run reported a *failure* where it should have reported a
  skip, which is the one way this script can be actively misleading. Matched by simple name
  against the file's text, which is coarse on purpose: a false positive costs a line under
  `not run here:`, a false negative costs the run.
- **An import that resolves only to another module's `test` source set.** A test source set is
  invisible from anywhere else, so this always fails in CI and is easy to miss locally — the
  file is right there in the repository. `syncStrategyFactoryOver` was exactly this, and the
  message says to move it to `:core:testing`.

**Then compilation, per module, in dependency order**, each against only the classpath its build
file entitles it to. At the time of writing: 102 of 149 `src/main` files, 44 of 61 `src/test`
files, 327 tests, and detekt over `src/main`, `src/test` **and** `src/androidTest` in every
module with the repo's own config — the same three source sets the `detekt` convention gives the
Gradle task, so that gate is covered in full rather than in part.

Which files join is computed from their imports, not from a list: a file is in when every import
resolves against the jars the script fetches or against a stub in `stubs/`, and when every app
symbol it imports is itself in. A new file is therefore picked up, or skipped, on its own
merits.

**Tests run once per module**, each with only that module's own test classpath — the isolation
`testDebugUnitTest` gives it. Running them together would put every module's test classes on one
classpath, and the contract tests in `:app` walk the classpath: they would audit
`FakeUserRepository` and `ResultTest` as if those were application classes.

**Every skipped test file is printed by name on each run**, under `not run here:`, with its
reason. That is not decoration. A skipped test is a gate this run did not apply, and a bare count
reads as coverage: `StabilityContractTest` was once excluded for a single unresolvable import
(`ImageVector`, via `AdaptiveNavItem`), the count said "37 of 53", and a regression it would have
caught reached CI instead. Anything in that list is CI's job, and shortening it — usually by
adding a stub — is cheap.

## What it cannot cover

- **Anything touching Room, DataStore, CameraX, ML Kit, Credential Manager, Navigation or
  Compose UI.** Those are Google Maven artifacts. That is most of `feature/*/…Screen.kt`,
  `core/database`, `core/datastore` and the Hilt modules that bind them.
- **Hilt/KSP code generation.** The `@HiltViewModel` annotation is stubbed so the declarations
  compile; the component Hilt would generate is not produced, so a binding that does not exist
  still compiles here and fails in CI. Nothing here checks that a module reaches the component
  at all — that is what `:app` depending on `:data` is for, and only a real build proves it.
- **Android Lint.** `lintDebug` needs AGP.
- **`assembleDebug` and the APK checks.** `scripts/verify-apk.sh` needs a built APK.
- **`build-logic`, beyond parsing.** The convention plugins compile against AGP, so nothing here
  type-checks or evaluates them. Two mistakes that got through to CI are worth knowing about:
  `Plugin.apply` returns `Unit`, so an expression body ending in `dependencies.apply { … }`
  does not override it; and `platform(…)` is a member of Gradle's `DependencyHandler` rather
  than a Kotlin DSL extension, so `import org.gradle.kotlin.dsl.platform` does not resolve.
  A third is a dependency rather than a mistake in the code: `hilt-android-gradle-plugin` 2.57.2
  carries a Kotlin 2.x `.kotlin_module` that the compiler behind `kotlin-dsl` refuses to read,
  so it must stay off `build-logic`'s compile classpath — which is fine, because nothing there
  names a Hilt type.
- **`SolidContractTest` and `CompiledAppTest`.** Both assert over the whole app's compiled
  output — every repository type, and at least one class per module. The harness compiles a
  subset by construction, so they would fail on its coverage rather than on the code. They are
  the two entries in `HARD_EXCLUDES` in `harness.py`, each with that reason next to it.
  `StabilityContractTest`, `DomainLayerContractTest` and `UnidirectionalDataFlowContractTest`
  read compiled output too and *do* run: they walk out from the view models, which the harness
  compiles in full.
- Whatever the run prints under `not run here:` — mostly the Room- and DataStore-backed
  repository and DAO tests.

## The stubs

`stubs/` holds hand-written stand-ins for the handful of `androidx`/`android` declarations the
JVM-compilable subset needs: `ViewModel` and `viewModelScope`, `SavedStateHandle` and `toRoute`,
`@Immutable`/`@Stable`, `ImageVector`, `Log`, `Context`, `NetworkCapabilities`, the two
Credential Manager exceptions, and `@HiltViewModel`. Each says in its own KDoc what it reproduces
and what it deliberately does not. They are compiled once and put on every module's classpath.

Three are worth knowing about because getting them wrong would make a test pass — or fail — for
the wrong reason:

- `@Immutable`/`@Stable` are declared `BINARY` retention, matching the real ones.
  `StabilityContractTest` finds them by searching the class file's constant pool *because* the
  JVM drops binary-retention annotations before reflection can see them; a `RUNTIME` stub would
  hide that.
- `viewModelScope` is `SupervisorJob() + Dispatchers.Main.immediate`, exactly as the real one
  builds it, so a test that swaps the Main dispatcher behaves as it would on device.
- `ImageVector` carries `@Immutable` because the real class does. Without it the stability audit
  reports `AdaptiveNavItem` as holding an unstable property — a failure about the stub rather
  than about the app, which is the other way a stub can waste an afternoon.

Everything else — dagger, hilt-core, Retrofit, OkHttp, kotlinx, mockk, JUnit — is the real
artifact from Maven Central at the version `gradle/libs.versions.toml` pins.

## Invoking the Kotlin compiler as a library

`kotlinc` is not published to Maven, so the harness calls `K2JVMCompiler` directly. Four things
are needed that no error message names, and all four have been rediscovered more than once:

| Missing | Failure |
|---|---|
| `org.jetbrains:annotations` on the *compiler's* classpath | "Backend Internal error" — `NoClassDefFoundError: …Nullable` inside `AnnotationCodegen` |
| `kotlin-stdlib` + `kotlinx-coroutines-core`, same place | dies in `CoreApplicationEnvironment.createApplication` before reading a source file |
| `org.jetbrains.intellij.deps:trove4j` | `NoClassDefFoundError: gnu/trove/TObjectHashingStrategy` from source collection |
| `-jvm-target 17` | the 1.8 default crashes the backend generating `safeCall`, a suspend function returning `Result` |

Maven Central answers 429 to a burst, so the fetch backs off rather than looping.

## Why Python

It was bash until the app became thirteen Gradle modules. The work stopped being "compile a
directory" and became "read a dependency graph, order it, and give each module exactly the
classpath its build file entitles it to", which is the part that makes this a check on the
architecture and not just on the code. `run.sh` is still the entry point and still takes the
same flags.
