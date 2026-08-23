# Modularisation

The app is thirteen Gradle modules. This page is what each one is for, what it may depend on,
and — the part worth reading — what the split actually bought, because most of the reasons
usually given for modularising an Android app did not apply here.

## The graph

```
                                    :app
                                      │
        ┌──────────┬─────────────┬────┴────┬──────────────┬───────────────┐
        │          │             │         │              │               │
  :feature:home :feature:profile :feature:signin :feature:scanner :feature:textrecognition
        │          │             │         │              │
        └──────────┴──────┬──────┴─────────┴──────────────┘
                          │
              ┌───────────┼─────────────┬───────────────┐
              │           │             │               │
         :core:ui    :core:domain  :core:navigation  :core:auth
              │           │                             │
              └───────────┴──────────┬──────────────────┘
                                     │
                                :core:common

  :data ──▶ :core:domain, :core:auth, :core:common      (and nothing depends on :data but :app)
  :core:testing ──▶ :core:auth, :core:common, :core:domain   (test configurations only)
```

| Module | Compose plugin | What it holds |
| --- | --- | --- |
| `:core:common` | no | `Result`/`safeCall`, the coroutine utilities and dispatcher qualifiers, the app event bus, the telemetry seam, the `NetworkMonitor` interface. Depends on nothing. |
| `:core:navigation` | no | `AppDestination` — the route contract, and nothing that draws one. |
| `:core:domain` | **no, deliberately** | Use cases, sync strategies, the domain models and `UserRepository`. No Android reference at all. |
| `:core:auth` | no | `GoogleAuthRepository` and its Credential Manager implementation. |
| `:core:ui` | yes | The theme, the shared widgets, the adaptive scaffold, and the `UdfViewModel`/`UiState`/`ObserveAsEvents` vocabulary. |
| `:core:testing` | no | Fakes and JUnit rules, in `src/main` so other modules' tests can see them. Test configurations only. |
| `:data` | no | Room, DataStore, Retrofit/OkHttp, the connectivity monitor, and the Hilt modules that bind them. |
| `:feature:*` | yes | One screen family each. Siblings; none may depend on another. |
| `:app` | yes | `MainActivity`, `BoilerplateApp`, `AppNavHost`. Depends on everything; nothing depends on it. |

## The rules, and what enforces them

The rules are declared in the root [`build.gradle.kts`](../build.gradle.kts) as a map from
module to the set it may depend on, and `./gradlew checkModuleDependencies` fails the build on
anything outside it. CI runs it first, before compiling, because it is seconds of work and it
catches a class of change every other gate would happily compile.

Two rules are worth stating on their own:

- **A feature may never depend on a feature.** `HomeTwoPaneScreen` shows a profile in its
  detail pane and does not know `:feature:profile` exists: it takes
  `detailPane: @Composable (userId: String) -> Unit` and `AppNavHost` passes `ProfileDetailPane`
  in. Knowing about both screens is navigation's job.
- **`:core:testing` is test-only.** Its fakes are real, shippable classes in a `main` source
  set, which is the price of them being visible from another module at all. The check fails if
  any module depends on it from a configuration that ships.

A module missing from the map fails the check rather than defaulting to permissive. Adding a
module is a decision about where it sits in the graph, and that map is where the decision is
written down.

`resolveAllDependencies` — the Phase 0 gate that proves every declared version exists — is
registered by the same convention plugins, and it collects its classpaths in `afterEvaluate`
for two reasons worth knowing before moving that line. Collecting them while the build file is
still being read sees none of AGP's variant classpaths, so the gate passes having checked
almost nothing: every module reported the same "5 configurations, 39 module nodes", `:app`
included. And asking a configuration for its resolution result marks it observed, so a
dependency the build file declares afterwards is not reliably picked up — that is how
`:core:domain` lost `api(project(":core:common"))` and failed to compile against a module its
own build file names. The task asserts it collected `debugCompileClasspath` before it does
anything else, so that failure cannot come back quietly.

The offline harness in [`scripts/jvm-harness`](../scripts/jvm-harness/README.md) checks the
same thing a level lower and without Gradle: it compiles each module separately, against only
the modules its build file declares — `api` travelling transitively, `implementation` not — so
a file reaching into a module the build does not name fails locally in seconds instead of in
CI in twenty minutes.

## What the split actually bought

**The domain layer became framework-free for real.** This is the concrete one, and it is the
reason `:core:domain` does not apply the Compose plugin. The Compose compiler stamps
`@StabilityInferred` onto *every* class in a module it is applied to, so while the app was one
module the domain layer's compiled output carried an `androidx` annotation that no edit to
those files could remove. `DomainLayerContractTest` had to carry a named exemption for it, with
a note saying to delete the exemption the day modularisation landed. It is deleted; that scan
now forgives nothing. See [`clean-architecture.md`](./clean-architecture.md).

**Two `@ApplicationScope` qualifiers turned out to be one too many.** `core.coroutines` declared
one — documented as carrying `AppCoroutineExceptionHandler` on a supervisor scope — and
`DataStoreModule` declared a second with the same simple name, bound to a bare
`CoroutineScope(SupervisorJob())`. `DataStoreTokenProvider` was the only consumer of the second
and had been getting the undocumented scope: no exception handler, no dispatcher of its own.
Two same-named qualifiers in one module are legal and invisible. Splitting the modules is what
made them collide, and the duplicate went.

**A test helper was being shared across what are now module boundaries.**
`syncStrategyFactoryOver` lived in `:core:domain`'s test source set and `:feature:home`'s tests
imported it. In one module that works; across two it cannot, because a test source set is not
visible from anywhere else. It moved to `:core:testing`.

**A screen can no longer reach an implementation.** Nothing depends on `:data` except `:app`,
so `UserDao`, `Retrofit` and `DataStore` are not on any feature's compile classpath. That was
already the convention and it was already true; it is now a fact about the build rather than
something a reviewer has to notice.

**Manifest permissions moved to the modules that need them.** `CAMERA` is declared by the two
camera features and `ACCESS_NETWORK_STATE` by `:data`, and the merger assembles the set. The
permissions an APK asks for are now a consequence of the modules it includes.

## What it did not buy, and the costs

**Build times are not the argument here.** This is a boilerplate with about 180 source files.
Parallel module compilation will not be measurable at this size, and anyone reading this page
for a large app should know that the wins above are structural, not performance.

**Thirteen build files where there was one.** That cost is paid down by `build-logic`: the
convention plugins in [`build-logic/convention`](../build-logic/convention) hold the compile
SDK, the Java and Kotlin targets, the unit-test setup, detekt and the Compose dependency floor,
so a module's own build file is its namespace and its dependencies and nothing else. The
version catalog is shared with the main build, so a convention plugin and a module script
cannot disagree about a version.

**Some packages no longer match their module.** `:data` holds `com.kojo.boilerplate.core.data`,
`core.database`, `core.datastore`, `core.network` and `core.di`. Packages were left alone except
where one would otherwise straddle two modules, which kept the change reviewable: renaming every
package in the data layer would have added several hundred lines of import churn to a change
that is already large, and it can be done on its own later. What was *not* left alone is any
package that would have been declared in two modules at once — the harness fails on a split
package, because a split package breaks `internal` visibility and confuses every path-scoped
rule in the repository.

**`:core:auth` is a module that a fix would delete.** It exists because
`GoogleAuthRepository.signIn` takes an `android.content.Context`, which keeps it out of
`:core:domain`, while `:feature:signin` injects it, which keeps it out of `:data`. That is
`docs/solid.md` finding 2 showing up as a module. When the parameter is inverted, the interface
belongs in `:core:domain` and this module folds into `:data`.

## Adding a module

1. `include(":your:module")` in [`settings.gradle.kts`](../settings.gradle.kts).
2. A `build.gradle.kts` applying `boilerplate.android.library` (add `.compose` if it draws
   anything, and `boilerplate.hilt` if it declares a binding), with a `namespace`.
3. An entry in `moduleDependencyRules` in the root build file saying what it may depend on.
   Without one, `checkModuleDependencies` fails and names it.
4. If `:app` should see it, add it to `:app`'s dependencies **and** to
   `EXPECTED_MODULE_PACKAGES` in `CompiledApp` — the contract tests in `app/src/test` audit the
   assembled app, and a module they cannot see is a module they silently stop covering.
