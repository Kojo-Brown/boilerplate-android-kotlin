# SOLID in the repository layer

An audit of the abstractions the app's data flows through — what each SOLID principle asks
of them, where this code answers and where it does not, and which of the remaining Phase 8
items is the fix for each gap.

Every structural claim on this page is pinned by
[`SolidContractTest`](../app/src/test/kotlin/com/kojo/boilerplate/core/architecture/SolidContractTest.kt),
which reads the compiled output rather than the source and fails `testDebugUnitTest` when
the shape it finds stops matching the shape described here — including when a finding is
*fixed*, so a repair cannot quietly leave this page describing a problem that is gone.

## The surface that was audited

Five types carry the repository role, and the audit is the whole set:

| Type | Abstraction | Implementation | Bound in |
| --- | --- | --- | --- |
| `UserRepository` | interface, framework-free | `UserRepositoryImpl` | `RepositoryModule` |
| `GoogleAuthRepository` | interface, takes an `android.content.Context` | `GoogleAuthRepositoryImpl` | `GoogleAuthModule` |
| `ThemePreferencesRepository` | **none — a concrete class** | itself | injected directly |

`TokenProvider`/`DataStoreTokenProvider` and `NetworkMonitor`/`ConnectivityManagerNetworkMonitor`
are the same shape one package over and both get the pattern right: a framework-free
interface, one implementation, `@Binds` in a module, a hand-written fake in the test source
set. They are named here because they are the standard the table above is measured against,
but they sit outside the pinned set, which keys on the `Repository`/`UseCase` suffix. A data
abstraction introduced under some third name — `…DataSource`, `…Client`, `…Store` — is
invisible to the check and would need this page revisited by hand.

## The use-case layer does not exist

There is no type in this app whose name ends in `UseCase`, and nothing that plays the part
under another name. Every `ViewModel` depends on a repository directly.

That is a defensible choice at this size and is not, by itself, a finding — a use case that
only forwards to one repository method is a file that costs a hop and buys nothing. It
matters because of what the missing layer is currently doing instead: **application policy
has settled in the `ViewModel`s, and it has already been copied.**

`ProfileViewModel` and `ProfileDetailPaneViewModel` hold the same policy — observe one user,
retry with backoff, drop duplicate emissions, map to `ProfileData`, and render a
`"User $userId not found"` error when the row is absent — in two verbatim copies. They differ
only in where the id comes from: a `SavedStateHandle` route for the full screen, an
`@Assisted` parameter for the two-pane detail. The decision that a missing row is an error
rather than an empty state is a *product* decision, and it is currently written down twice,
in the layer furthest from the data. A third caller — the same detail pane inside a different
scaffold — makes it three.

`HomeViewModel.refresh()` is the other side of the same gap. Which users get refreshed
(the ones currently on screen, so a refresh under an active search covers what the user is
looking at), what a partial failure means, and how the outcome is counted are all policy,
and they live in a `ViewModel` that also owns search debouncing, the offline banner and the
in-flight CAS lock.

Extracting `ObserveUserProfile` and `RefreshVisibleUsers` is the fix, and it is the next
item in this phase — where a lint rule keeps those use cases free of Android imports, which
is also what would let them be tested without a `ViewModel` at all.

## Single responsibility

**`UserRepositoryImpl` — holds.** It coordinates network and cache, maps at both edges and
owns the dispatcher its work runs on. That reads like three jobs and is one: every part of
it changes for the same reason, which is a change to how a user is fetched or stored.
Dispatcher ownership in particular was moved *into* it deliberately, from three callers that
each appended their own `flowOn` — see [dispatchers](./dispatchers.md).

**`DataStoreTokenProvider` — two audiences.** It implements `TokenProvider`, whose four
methods are synchronous, *and* exposes a `tokensFlow` that is not on the interface and has no
production caller. The synchronous half exists because OkHttp's `Interceptor` and
`Authenticator` are synchronous, which is why the class carries a `runBlocking` and an
`AtomicReference` cache; the flow half is what a reactive consumer would want. One class
answering to both is the reason a token-storage change has to be reasoned about twice.

**The `ViewModel`s — do not hold**, for the reason above: they own presentation *and* the
policy a use case should own.

## Open/closed

`UserRepositoryImpl` hard-codes one synchronisation strategy: fetch from the network, write
through to Room, return the fetched value. Every alternative a real app grows — cache-first
with a staleness window, network-only for a manual pull, write-behind for an offline edit —
is an edit to that class rather than a new type alongside it. The `cache()` helper is
`private` and `syncUsers` names `mapConcurrentlyCatching` directly, so there is no seam to
extend through even from a subclass.

The same closure applies to the cross-cutting behaviour the class does *not* have. Retry
lives in `retryWithBackoff` at the `ViewModel`'s call site, so a repository call made
anywhere else is unretried; there is no caching policy, no telemetry, and nowhere to add
either without opening the class.

Both are named items in this phase — a `SyncStrategy` resolved by Hilt multibinding, and
repository decorators for cache, retry and telemetry. This section is the argument for why
they are worth building rather than a description of something broken today.

One smaller instance, outside the repositories: `Result.toUiState()` maps every failure to
`throwable.message`. There is no error taxonomy, so distinguishing "you are offline" from
"that user is gone" from "the server broke" means editing the mapper rather than adding a
case to a sealed type.

## Liskov substitution

`FakeUserRepository` is the only substitute for `UserRepository` in the codebase, and it is
what every `ViewModel` test runs against. It diverges from the contract the interface
documents in two places:

```kotlin
// UserRepository.syncUser — "Fetches a user by [id] from the network, caches locally,
// and returns the outcome wrapped in [Result]."
override suspend fun syncUser(id: String): Result<User> = syncUserResult
```

The fake returns the configured result and writes nothing. A caller that syncs and then
observes — which is exactly what `HomeViewModel.refresh()` does, since it reads the outcome
for its count and lets `uiState` pick the new rows up out of the database — sees the write
in production and not under test. The behaviour that makes `refresh()` work is the behaviour
the fake omits.

```kotlin
successes += _users.value.firstOrNull { it.id == id }
    ?: User(id = id, displayName = "User $id", email = "user$id@example.com")
```

The second divergence is stronger: an id the fake has never heard of *succeeds*, with a
fabricated user. Against `UserRepositoryImpl` the same id is a 404 and a `FanOutFailure`. A
test can therefore assert a successful refresh over ids that would fail against every real
implementation, which is a substitute that is not merely incomplete but permissive in the
one direction that hides bugs.

Neither is hard to fix — write through on sync, and fail unknown ids unless the test says
otherwise — but both are behavioural, so `SolidContractTest` cannot pin them; only a
contract test run against both implementations could, and that needs Room, which puts it in
`androidTest`. Recorded here as the open finding it is.

## Interface segregation

`UserRepository` declares six methods. Its production callers use three:

| Method | Production callers |
| --- | --- |
| `getUsers()` | `HomeViewModel` |
| `getUser(id)` | `ProfileViewModel`, `ProfileDetailPaneViewModel` |
| `syncUsers(ids)` | `HomeViewModel` |
| `saveUser(user)` | none |
| `syncCurrentUser()` | none |
| `syncUser(id)` | none |

Half the interface has no caller at all, and neither of the sync methods ever had one:
`HomeViewModel.refresh()` was written to give `syncUser` a caller and ended up needing the
fan-out instead, so `syncUsers` is the only one of the three that is reachable. That
surface is not free: `FakeUserRepository` implements all six regardless, so the two profile
view models — which between them call exactly one method — are tested through a double that
has to model the whole thing, and a seventh method would enlarge every one of those tests
without any of them wanting it.

The profile view models are the sharpest case. Each needs `(String) -> Flow<User?>` and
nothing else, and today each depends on a six-method interface to get it. A single-method
use case is the segregation, which is the same conclusion the section on the missing layer
reaches from the other direction.

`UserDao` has the milder version of this: `delete` is called from `UserDaoTest` and nowhere
else. A DAO is generated surface rather than a hand-designed abstraction, so the cost is
lower, but it is the same unused method.

## Dependency inversion

**`GoogleAuthRepository` does not invert.** The interface is framework-free everywhere except
the one place that matters:

```kotlin
interface GoogleAuthRepository {
    suspend fun signIn(activityContext: Context): Result<GoogleUser>
```

`android.content.Context` in the abstraction means the abstraction is as Android-bound as the
implementation it was extracted from. It cannot move to a platform-independent module, it
cannot be implemented by anything that is not running on Android, and a fake needs a stubbed
`Context` to satisfy a parameter it will not read. The leak also travels *up*:
`GoogleSignInViewModel.signIn(activityContext: Context)` takes the same parameter and imports
the same type, so the framework reaches the presentation layer through a hole in the interface
that was supposed to stop it.

The parameter is real — Credential Manager needs an `Activity` context to show its UI, so
this cannot be deleted. It can be inverted: an interface owned by this app that yields the
presentation context, implemented near the Activity, injected into the implementation, and
absent from the repository's own signature.

**`ThemePreferencesRepository` has no abstraction to depend on.** It is a concrete
`@Singleton` class, and `MainActivity` injects it by its concrete type — so the app's entry
point depends directly on DataStore's key/value storage with nothing in between. Its
constructor takes `DataStore<Preferences>`, which makes it testable, and
`ThemePreferencesRepositoryTest` uses that; testability is not the gap. The gap is that
nothing can be substituted for it in the graph and the dependency points at a detail.

It also inverts the layering: it lives in `core.datastore` and imports
`com.kojo.boilerplate.ui.theme.ThemeMode`, so the data layer depends on the UI layer. `ThemeMode`
is a three-constant enum with no Compose in it — it is a domain concept filed under `ui`
because that is where the theme code lives, and moving it is most of the fix.

**Everything else inverts correctly.** `UserRepository`, `TokenProvider` and `NetworkMonitor`
are framework-free interfaces with exactly one implementation each, bound through `@Binds`,
and each has a hand-written fake. `UserRepositoryImpl` taking `@IoDispatcher CoroutineDispatcher`
with no default is the same principle applied to the thread pool.

## Findings

| # | Principle | Finding | Fixed by |
| --- | --- | --- | --- |
| 1 | SRP / DRY | The profile-loading policy is duplicated across `ProfileViewModel` and `ProfileDetailPaneViewModel` | Clean Architecture use-cases (next item) |
| 2 | DIP | `GoogleAuthRepository.signIn` takes an `android.content.Context`, and the leak reaches `GoogleSignInViewModel` | Clean Architecture use-cases + the layering lint rule |
| 3 | DIP | `ThemePreferencesRepository` has no interface and `MainActivity` depends on the concrete type | Open |
| 4 | DIP | `core.datastore` imports `ui.theme.ThemeMode`; data depends on UI | The layering lint rule |
| 5 | LSP | `FakeUserRepository.syncUser`/`syncCurrentUser` do not cache, and unknown ids succeed with a fabricated user | Open |
| 6 | ISP | Three of `UserRepository`'s six methods have no production caller | Use-cases; the dead methods go with the decorators |
| 7 | OCP | Sync strategy and cross-cutting behaviour are closed inside `UserRepositoryImpl` | `SyncStrategy` multibinding + repository decorators |
| 8 | SRP | `DataStoreTokenProvider` serves a synchronous interface and an unused flow | Open |

Findings 3, 5 and 8 have no item in this phase that will pick them up. They are recorded
rather than fixed because an audit that quietly repaired what it found would leave nothing
to read, and because each is a design change with choices in it — what `ThemePreferences`
should be called once it is an interface, whether the fake should fail unknown ids by default
or by opt-in — that belongs in its own change.

## What this page cannot tell you

The pinned checks are structural: names, implementation counts, method sets, and which
framework types appear in a signature. They say nothing about whether a method *does* what
its KDoc claims, which is why finding 5 is prose. They also key on the `Repository` and
`UseCase` suffixes, so an abstraction introduced under a different name joins the data layer
without this audit noticing.
