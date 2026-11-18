# Hilt test modules: fake bindings for the network and the database

How an instrumented test in this repository gets a Hilt graph, what `@TestInstallIn` replaces in
it, and the four things that make the difference between a replacement that works and one that
compiles and does nothing.

## The three pieces, and why none of them is optional

A `@HiltAndroidTest` needs all three. Leaving any one out fails in a different way, and only one
of the three failures is loud.

| Piece | Where | What it does |
|---|---|---|
| `HiltTestRunner` | `app/src/androidTest/kotlin/com/kojo/boilerplate/HiltTestRunner.kt` | substitutes `HiltTestApplication` for `BoilerplateApp` |
| `hilt-android-testing` | `androidTestImplementation` in `app/build.gradle.kts` | `@HiltAndroidTest`, `HiltAndroidRule`, `@TestInstallIn`, `HiltTestApplication` |
| the KSP processor | `kspAndroidTest(libs.hilt.android.compiler)` | generates the test component **and** reads `@TestInstallIn` |

**Without the runner** the test fails loudly: Hilt reports that the application is not a
`HiltTestApplication` and nothing runs. `testInstrumentationRunner` in `app/build.gradle.kts` is
what names it, overriding the `BoilerplateBuild.TEST_RUNNER` the convention plugin sets for every
other module.

**Without the processor in `kspAndroidTest`** nothing fails. This is the one worth knowing about,
and it is the same failure `HiltConventionPlugin`'s KDoc describes for `@InstallIn`: a
replacement module is discovered through `@AggregatedDeps` metadata the processor writes *next to
the module it processes*, so a `@TestInstallIn` compiled without it contributes nothing. The test
then runs against the real `NetworkModule` and the real `DatabaseModule` — against a socket and a
file — and passes or fails on whether the emulator happened to have a route to
`BuildConfig.BASE_URL`. `FakeBindingsTest` has two tests whose only job is to catch that:
`theApiBindingsAreTheInMemoryDoubles` and `theDatabaseBindingHasNoFileBehindIt`.

## What is replaced

Two modules, both declared in `:data`, both `@InstallIn(SingletonComponent::class)`.

### `NetworkModule` → `TestNetworkModule`

`NetworkModule` provides eight things: a `Json`, an `HttpLoggingInterceptor`, a
`PinningDecision`, the authenticated `OkHttpClient`, the `@AuthOkHttpClient` one, a `Retrofit`,
and `UserApi` and `AuthApi`. The replacement provides **only the two APIs**, which is the
interesting part of the design rather than an omission: nothing outside `NetworkModule` injects
any of the other six, so what is left is a graph with no `OkHttpClient` binding at all. A test
cannot reach the network by accident, and a provider added later that wants a client fails at
compile time here instead of at run time on a device.

Three things the production graph does on first use therefore do not happen, and each is covered
somewhere that reads `src/main` instead:

- `providePinningDecision` parses `BuildConfig.CERTIFICATE_PINS` — `CertificatePinningContractTest`.
- `provideLoggingInterceptor` redacts `Authorization` and the integrity header —
  `IntegrityAttestationContractTest`.
- `TokenAuthenticator`, `AuthInterceptor` and `IntegrityInterceptor` become unreachable, because
  the two client providers were their only consumers. Dagger constructs what a component's entry
  points reach, so they are never built.

`NetworkBindsModule` — the other module in the same file, binding `TokenProvider` to
`DataStoreTokenProvider` — is deliberately **not** replaced. Its only consumers were the two
interceptors above, so nothing reachable asks for it and the encrypted token store is never
opened. A test whose subject is token storage should replace it and say so; replacing a binding
no test can reach is how a test module becomes hard to read.

### `DatabaseModule` → `TestDatabaseModule`

What makes the production binding unusable is the **file**, not the SQL. `boilerplate.db`
outlives the test that wrote it, so the order a suite happens to run in decides what each test
sees and a failure reproduces only after an uninstall. So the replacement swaps the file and keeps
everything else: the same `@Database`, entities, `UserFieldSetConverter` and generated DAO
implementations, running the same statements, through `Room.inMemoryDatabaseBuilder`.

A hand-written fake `UserDao` is the other option and the wrong one in this configuration.
`upsertResolving` and `commitPage` are `@Transaction` methods whose whole contribution is
atomicity, and a fake over a `MutableMap` has no transaction to serialise — it accepts every
interleaving, including the one the method exists to rule out. `:data`'s unit tests use
`FakeUserDao` where the subject is the *caller's* logic; this rig is for when the subject is the
caller **and** the statement it issues. Both are worth having.

Two decisions inside it are easy to get wrong:

- **`allowMainThreadQueries()` is absent.** It is the first line people add to a Room test. Every
  method on `UserDao` and `UserPagingDao` is `suspend` or returns a `Flow`, so Room already runs
  every query off the main thread; a blocking DAO method added later should throw on a device, and
  allowing main-thread queries in the rig is exactly how it would stop throwing.
- **No migrations.** An in-memory database is created at the current version, so a migration
  cannot run even if registered. `AppDatabaseMigrationTest` is the only thing here that executes
  one, against `MigrationTestHelper` and the exported schemas.

## What the doubles are, and why they are not mocks

`TestUserApi` and `TestAuthApi` are real implementations with in-memory state. The reason is
that every caller — `UserRepositoryImpl`, `PendingUserChangeRepositoryImpl`,
`UsersRemoteMediator` — is a read-modify-write against the database: a fetch is committed through
conflict resolution, a push is acknowledged against the key the row holds. A mock returning a
canned value per method cannot express that the server's copy *changed because of the request*,
so an acknowledgement would carry whatever version the fixture was written with and the resolver
would be exercised against a state that cannot occur. `TestUserApi.updateUser` applies the patch
and bumps `UserDto.version`, which is the smallest amount of server this repository's own code
reasons about.

Four details in them are load-bearing:

1. **They are thread-safe.** `syncUsers` and `pushPendingChanges` both go through
   `mapConcurrentlyCatching`, so several coroutines call in at once on `Dispatchers.IO`. With a
   `mutableListOf` the recorded calls are a race, and a lost record reads as "the request never
   reached the network" — the one conclusion the recording exists to support.
2. **A call is recorded before the failure is thrown.** A test asserting that a terminal failure
   was not retried is asserting on the number of requests that arrived, and an unrecorded request
   cannot be counted.
3. **The patch is applied by field name, not by nullability.** `avatarUrl` is nullable on the
   wire, so a cleared avatar and an unsent one are both `null`; only
   `UpdateUserRequest.changedFields` distinguishes them.
4. **`TestAuthApi` records the email and drops the password.** A failed assertion prints the
   recorded calls and an instrumented test report is a CI artifact, so a double that keeps a
   password in a list is a credential in a build log. The tokens are `mock-access-token` and
   `mock-refresh-token`: a real-looking JWT in a fixture is indistinguishable from a leaked one.

## Which exception a failing double should throw

`RetryingUserRepository` sits between the test and `UserRepositoryImpl`, and `isTransientFailure`
reads an `IOException` or a retryable `HttpException` as worth another attempt and everything else
as terminal. So `TestUserApi.failure` is not a free choice:

- A **terminal** exception (`IllegalStateException` and the like) produces exactly one request.
  That is what an assertion on the number of calls needs.
- A **transient** one produces up to `BackoffPolicy.DEFAULT.maxRetries` further requests with real
  `delay` between them. Useful when retry *is* the subject; misleading otherwise, and slow.

## Isolation

Hilt builds a fresh component for each test method, so each one gets its own empty in-memory
database and its own `TestUserApi`. That is pinned rather than assumed: several tests assert the
*exact* list of calls that reached the double, and a shared component would make those lists
accumulate across methods and fail whichever ran second. `tearDown` closes the database, which is
what keeps the number of open in-memory databases from tracking the number of test methods.

## `runBlocking`, not `runTest`

`runTest` controls virtual time for the coroutine it starts, and nothing in this rig is confined
to that coroutine: `CachingUserRepository` dispatches the fetch into the injected
`@ApplicationScope`, the repository hops to `@IoDispatcher`, and Room runs its queries on its own
executor. Virtual time buys nothing over work it does not schedule, and `advanceUntilIdle` on a
scheduler none of it uses is the kind of line that makes a test look deterministic without being
so. The JVM suites in `:data` are the place for virtual time, because there the dispatcher is
injected and the test owns it.

## What `BoilerplateApp` does and `HiltTestApplication` does not

`BoilerplateApp.onCreate` starts the app-wide event dispatcher and calls
`ensurePeriodicSyncScheduled`, and the class implements `Configuration.Provider` to hand
WorkManager the `HiltWorkerFactory`. Under `HiltTestApplication` none of that runs.

For this suite that is a feature: a test asserting on a database would otherwise be racing a
worker that syncs it, and `AndroidManifest.xml` removes WorkManager's own `androidx.startup`
initialiser, so nothing initialises WorkManager unless a test asks for it.

A suite whose subject **is** the scheduling needs an application that provides a WorkManager
`Configuration`. The route is `@CustomTestApplication` on an interface naming such a class, which
generates a test application to point `HiltTestRunner` at, plus
`WorkManagerTestInitHelper` for a synchronous executor. That is a different rig and belongs with
the item that needs it.

## Where this lives, and when it should move

All of it is in `:app`'s `androidTest` source set. `:app` is the only module that may depend on
`:data` — the rule map in the root `build.gradle.kts` says so — so it is the only module that can
name `NetworkModule` and `DatabaseModule` in a `replaces` list, which settles where the two test
modules go.

The runner is less forced, and sits beside them for now rather than in `:core:testing`. Putting it
there would make it reusable by a feature module's own Hilt suite, at the cost of
`hilt-android-testing` on the `api` configuration of a module whose classes are real and
shippable, plus an `:app` → `:core:testing` edge in the rule map for a single class. The second
module that wants a Hilt suite is the point at which that trade changes; this file is the note to
whoever gets there.

Three artifacts beside the Hilt ones are declared `androidTestImplementation` in
`app/build.gradle.kts` — `room-runtime`, `retrofit` and `kotlinx-serialization-json`. They are
`implementation` dependencies of `:data`, so they reach `:data`'s own sources and stop there;
`:app` names none of them in `src/main` and should not start. `src/androidTest` needs them only to
stand in for what `:data` binds: `Room.inMemoryDatabaseBuilder`, and the annotations on
`UserApi`/`AuthApi` and the DTOs the doubles return.

## Running it

```
./gradlew connectedDebugAndroidTest        # needs an SDK and a device
./gradlew compileDebugAndroidTestKotlin    # the half that is only a compile
```

Both are gates — see `CLAUDE.md` — and neither runs in the scheduled agent's environment:
`dl.google.com` answers 403 on CONNECT there, so there is no SDK and no emulator.
`scripts/jvm-harness` covers detekt over `src/androidTest` and nothing else of it, because the
Hilt component is generated by KSP inside a real Gradle build. CI is therefore the first thing
that type-checks anything in this file's subject, and `docs/instrumented-testing.md` says what
follows from that.

## What is still not covered

- **A screen.** Nothing here launches a composable that injects a view model. That needs an
  `@AndroidEntryPoint` activity in the androidTest manifest — `createAndroidComposeRule` cannot
  use the `ComponentActivity` from `ui-test-manifest`, which is not an entry point — and
  `ProfileViewModel` and `HomeViewModel` both read their route from a `SavedStateHandle`, so a
  test has to navigate rather than compose. That is the `:app` navigation item's subject.
- **Paging.** `UserPagingDao` is bound and asserted to be a view of the same database, but
  nothing drives `UsersRemoteMediator` against `TestUserApi.getUsers`, which is implemented and
  unused. `androidx.paging:paging-testing` is what that needs; `docs/paging.md` has the
  assertions.
- **DataStore.** `DataStoreModule`, `UserPreferencesModule` and `ThemeModule` are untouched, so a
  test that reads a preference reads a real file in the app's data directory and leaves it there
  for the next test. Nothing reachable from this suite does, but a suite about preferences or
  tokens needs its own replacement and should write down what it replaced.
- **`syncUsers` and the fan-out.** `mapConcurrentlyCatching` over several ids through one
  database is the configuration `TestUserApi`'s thread safety is for, and no test here exercises
  it.
