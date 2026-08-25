# SOLID in the repository layer

An audit of the abstractions the app's data flows through — what each SOLID principle asks
of them, where this code answers and where it does not, and which of the remaining Phase 8
items is the fix for each gap.

Every structural claim on this page is pinned by
[`SolidContractTest`](../app/src/test/kotlin/com/kojo/boilerplate/architecture/SolidContractTest.kt),
which reads the compiled output rather than the source and fails `testDebugUnitTest` when
the shape it finds stops matching the shape described here — including when a finding is
*fixed*, so a repair cannot quietly leave this page describing a problem that is gone. That
is not hypothetical: finding 1 has since been repaired, the assertion that the use-case layer
did not exist failed exactly as its own KDoc predicted, and this page was edited in the same
change. The domain layer that repair introduced has its own pinned page,
[`clean-architecture.md`](./clean-architecture.md).

## The surface that was audited

Eight types carry the repository role, and the audit is the whole set:

| Type | Abstraction | Implementation | Bound in | Module |
| --- | --- | --- | --- | --- |
| `UserRepository` | interface, framework-free | `UserRepositoryImpl`, wrapped by three decorators | `RepositoryModule` | `:core:domain` / `:data` |
| `GoogleAuthRepository` | interface, takes an `android.content.Context` | `GoogleAuthRepositoryImpl` | `GoogleAuthModule` | `:core:auth` |
| `ThemePreferencesRepository` | **none — a concrete class** | itself | injected directly | `:data` |

The last column is what [modularisation](./modularisation.md) added, and it changed one thing
on this page rather than none. `UserRepository` moved from `core.data.repository` to
`core.domain.repository`: it is the interface the domain layer is written against, and leaving
it in the package its implementation lives in would have meant every feature depending on
`:data` to see it. The implementations did not move.

**`GoogleAuthRepository` is why `:core:auth` exists**, and finding 2 below is the reason. Its
`signIn` takes an `android.content.Context`, so it cannot live in `:core:domain` — that module
is checked to have no framework reference at all — and it cannot live in `:data`, because
`:feature:signin` injects it and no feature may see `:data`. A module of its own is the honest
answer while that parameter is on the interface. Inverting the parameter would let the
abstraction move down to `:core:domain` and `:core:auth` disappear into `:data`, which is a
second reason to fix finding 2 beyond the one the finding itself gives.

`CachingUserRepository`, `RetryingUserRepository` and `TelemetryUserRepository` are counted as
implementations of `UserRepository`, because that is what they are: each one is a repository a
caller could be handed. Only the assembled stack is bound, and
[`decorator.md`](./decorator.md) is where the composition is argued.

`TokenProvider`/`DataStoreTokenProvider` and `NetworkMonitor`/`ConnectivityManagerNetworkMonitor`
are the same shape one package over and both get the pattern right: a framework-free
interface, one implementation, `@Binds` in a module, a hand-written fake in the test source
set. They are named here because they are the standard the table above is measured against,
but they sit outside the pinned set, which keys on the `Repository`/`UseCase` suffix. A data
abstraction introduced under some third name — `…DataSource`, `…Client`, `…Store` — is
invisible to the check and would need this page revisited by hand.

## The use-case layer — the finding, and its repair

**This section described a gap when it was written. Finding 1 has since been fixed, and the
original text is kept below the line because the argument for *why* the layer was worth adding
is the part worth keeping.**

`core.domain.usecase` now holds three use cases, and
[`clean-architecture.md`](./clean-architecture.md) is the page about them:

| Use case | The policy it took out of the `ViewModel`s |
| --- | --- |
| `ObserveUserProfileUseCase` | Retry, dedupe, and "a missing row is a failed load, not an empty one" |
| `RefreshVisibleUsersUseCase` | Which sync a list refresh performs — the three decisions it originally took (empty selection, dedupe, partial failure) moved on into `VisibleUsersSyncStrategy`, see [`sync-strategy.md`](./sync-strategy.md) |
| `PerformBackgroundSyncUseCase` | Which sync a background worker performs, what counts as done, and when to stop retrying. Not taken out of a `ViewModel` — out of a `Worker`, which is a class no JVM test can construct. See [`background-sync.md`](./background-sync.md) |

Both profile view models are now one `flatMapLatest` over `ObserveUserProfileUseCase` plus a
`when` that turns the outcome into strings, and the `when` itself is shared as
`UserProfile.toUiState()` rather than written twice. `HomeViewModel` keeps its direct
`userRepository.getUsers()` call, for the reason the original text gives: a use case that
forwards one method to one repository buys nothing.

The layer is held framework-free by a `ForbiddenImport` rule scoped to `**/core/domain/**` and
by `DomainLayerContractTest`, which reads the compiled constant pool and so also catches the
fully-qualified references an import rule cannot see.

**What has not changed:** the two divergences in `FakeUserRepository` (finding 5) are still
there, and `ObserveUserProfileUseCaseTest` uses a hand-written double rather than that fake.
`syncUser` and `syncCurrentUser` still have no production caller — extracting the use cases
moved the callers of `syncUsers`, it did not create any (finding 6). `syncCurrentUser` gained
one later in this phase, in `CurrentUserSyncStrategy`, though nothing in the UI selects that
strategy yet.

---

*Original text, as written for the audit:*

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

**The `ViewModel`s — now hold, for the two that did not.** The profile pair and
`HomeViewModel.refresh()` owned presentation *and* application policy; the policy moved to
`core.domain.usecase` and what is left in each is a state pipeline and a mapping to strings.
`HomeViewModel` is still the largest class here — search debouncing, the offline banner and
the in-flight CAS lock are all genuinely presentation, and all genuinely its.

## Open/closed

`UserRepositoryImpl` hard-codes one synchronisation strategy: fetch from the network, write
through to Room, return the fetched value. Every alternative a real app grows — cache-first
with a staleness window, network-only for a manual pull, write-behind for an offline edit —
is an edit to that class rather than a new type alongside it. The `cache()` helper is
`private` and `syncUsers` names `mapConcurrentlyCatching` directly, so there is no seam to
extend through even from a subclass.

The same closure applied to the cross-cutting behaviour the class does *not* have. Retry
lived in `retryWithBackoff` at the `ViewModel`'s call site, so a repository call made anywhere
else was unretried; there was no caching policy, no telemetry, and nowhere to add either
without opening the class.

**Both halves have since landed, and neither one edited `UserRepositoryImpl`.** The strategy
half is `SyncStrategy` over a Dagger multibinding ([sync-strategy.md](./sync-strategy.md)); the
cross-cutting half is three decorators around the same unchanged implementation
([decorator.md](./decorator.md)). That the class did not have to be reopened for either is the
whole claim of the principle, and it is the reason this section is kept rather than deleted.

What remains closed is the fetch-and-write body itself: a cache-first read or a write-behind
edit is still an edit to `UserRepositoryImpl` rather than a type beside it. A decorator can
suppress a request or repeat one, but it cannot change what the request *does*.

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
for its count and lets the state pick the new rows up out of the database — sees the write
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
| `getUser(id)` | `ObserveUserProfileUseCase` |
| `syncUsers(ids)` | `VisibleUsersSyncStrategy` |
| `saveUser(user)` | none |
| `syncCurrentUser()` | `CurrentUserSyncStrategy`, selected by `PerformBackgroundSyncUseCase` |
| `syncUser(id)` | none |

The count above is as of the Factory + Strategy item. Before it, half the interface had no
caller at all and neither of the sync methods ever had one: `HomeViewModel.refresh()` was
written to give `syncUser` a caller and ended up needing the fan-out instead, so `syncUsers`
was the only one of the three that was reachable. `CurrentUserSyncStrategy` is the first
caller `syncCurrentUser` has had, and as of the WorkManager background-sync item it is
selected as well as bound: `PerformBackgroundSyncUseCase` asks for `SyncMode.CURRENT_USER` on
every periodic run. So the method is now reachable end to end — from a scheduler rather than
from a screen, which is the shape the strategy was written for. That
surface is not free: `FakeUserRepository` implements all six regardless, so the two profile
view models — which between them call exactly one method — are tested through a double that
has to model the whole thing, and a seventh method would enlarge every one of those tests
without any of them wanting it.

The profile view models were the sharpest case: each needed `(String) -> Flow<User?>` and
nothing else, and each depended on a six-method interface to get it. Both now depend on
`ObserveUserProfileUseCase`, which is the single-method surface they wanted.

The finding is *moved rather than closed*, and it is worth being exact about how much was
bought. There is now one type depending on the six-method interface for `getUser` instead of
two, and `ObserveUserProfileUseCaseTest` has to hand-write a double declaring all six to test
a use case that calls one — the same cost, one layer down and paid once. What did change is
that the two `ViewModel` tests no longer pay it at all, and a seventh method would now
enlarge one double instead of three.

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
are framework-free interfaces with exactly one implementation that *does* anything — the
decorators around `UserRepositoryImpl` are implementations too, and each is defined entirely in
terms of another one — bound through `@Binds` or, where a composition is bound, `@Provides`,
and each has a hand-written fake. `UserRepositoryImpl` taking `@IoDispatcher CoroutineDispatcher`
with no default is the same principle applied to the thread pool.

## Findings

| # | Principle | Finding | Status |
| --- | --- | --- | --- |
| 1 | SRP / DRY | The profile-loading policy is duplicated across `ProfileViewModel` and `ProfileDetailPaneViewModel` | **Fixed** — `ObserveUserProfileUseCase` / `RefreshVisibleUsersUseCase` |
| 2 | DIP | `GoogleAuthRepository.signIn` takes an `android.content.Context`, and the leak reaches `GoogleSignInViewModel` | Open |
| 3 | DIP | `ThemePreferencesRepository` has no interface and `MainActivity` depends on the concrete type | Open |
| 4 | DIP | `core.datastore` imports `ui.theme.ThemeMode`; data depends on UI | Open |
| 5 | LSP | `FakeUserRepository.syncUser`/`syncCurrentUser` do not cache, and unknown ids succeed with a fabricated user | Open |
| 6 | ISP | Three of `UserRepository`'s six methods have no production caller | Open — still two (`saveUser`, `syncUser`). The decorators wrap all six, and wrapping is not calling: they added no caller |
| 7 | OCP | Sync strategy and cross-cutting behaviour are closed inside `UserRepositoryImpl` | **Fixed** for both — `SyncStrategy` multibinding, and `decorateUserRepository` for cache/retry/telemetry. The fetch-and-write body itself is still closed |
| 8 | SRP | `DataStoreTokenProvider` serves a synchronous interface and an unused flow | Open |

**Findings 2 and 4 were originally attributed to the layering lint rule, and that was
optimistic.** The rule as landed is scoped to `**/core/domain/**`, which is the scope the item
asked for and the only one that does not need a long exemption list — so neither finding is in
its path:

- **Finding 2** is about `core.auth`. The `Context` on `GoogleAuthRepository.signIn` is
  untouched, and `GoogleSignInViewModel` still takes the same parameter. The fix is the
  inversion the dependency-inversion section describes — an interface this app owns that
  yields the presentation context — not a lint rule, which can only report the leak.
- **Finding 4** is about `core.datastore`, and closing it means moving `ThemeMode` out of
  `ui.theme` first. Only once it has somewhere framework-free to live does a rule have
  anything to enforce. See the closing section of
  [`clean-architecture.md`](./clean-architecture.md).

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
