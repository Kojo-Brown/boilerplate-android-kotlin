# Factory + Strategy, resolved by Hilt multibinding

There is more than one right way to pull users from the network into the cache, the right one
depends on what the caller is doing, and the caller cannot know at construction time which it
will want. That is the shape Strategy is for. This page is about the version of it that
survives contact with a DI graph: what each piece buys, what Dagger checks for you, and the
two mistakes it cannot check that the code has to catch itself.

## The pieces

```
RefreshVisibleUsersUseCase          picks a SyncMode  ─────────────┐
                                                                   ▼
SyncStrategyFactory      Map<SyncMode, Provider<SyncStrategy>> ── create(mode)
                                    ▲                              │
SyncStrategyModule       @Binds @IntoMap @SyncModeKey(…)           ▼
                                                            SyncStrategy.sync(ids)
                                                                   │
VisibleUsersSyncStrategy · CurrentUserSyncStrategy ◀───────────────┘
```

| Type | What it is for |
| --- | --- |
| `SyncMode` | The key. A closed enum, so "every mode has a binding" is a question with an answer |
| `SyncStrategy` | One way of syncing: fetch, cache, report the shortfall as a `RefreshOutcome` |
| `SyncStrategyModule` | The `@Binds @IntoMap` methods that assemble the map |
| `SyncModeKey` | The `@MapKey` annotation that files a binding under a mode |
| `SyncStrategyFactory` | Resolves a mode to a strategy, lazily, and checks the two agree |

## Why multibinding rather than a `when`

The obvious implementation of "pick a strategy by mode" is a `when` in a factory:

```kotlin
fun create(mode: SyncMode) = when (mode) {
    SyncMode.VISIBLE_USERS -> VisibleUsersSyncStrategy(userRepository)
    SyncMode.CURRENT_USER -> CurrentUserSyncStrategy(userRepository)
}
```

It works, and it is exhaustive — the compiler will even make you handle a new mode. What it
costs is that the factory now depends on **every dependency of every strategy**: the moment
one of them needs a `WorkManager`, a `Clock` or a second data source, that parameter appears
on the factory's constructor, and the class whose job is to choose is transitively coupled to
everything it might choose. Adding a strategy means editing the factory, its constructor, and
every test that builds one.

With `@Binds @IntoMap`, each strategy declares its own dependencies through its own `@Inject`
constructor and the factory takes a map. Adding a strategy is a new file and a new binding;
nothing that already exists is edited. That is the property that makes this worth the
indirection in a boilerplate, where the whole point is that the next person adds the third,
fourth and fifth strategy.

The trade is real and worth stating: `when` is checked by the compiler, and a map is not.
See "what Dagger cannot check" below.

## Why the map holds `Provider`s

`Map<SyncMode, SyncStrategy>` compiles and constructs **every** strategy in order to answer a
question about one of them. With two strategies over one repository that is free. It stops
being free exactly when the strategies stop being uniform, which is the situation this design
exists to support — so `Map<SyncMode, Provider<SyncStrategy>>` it is, and resolving a mode
builds only that mode's strategy. `SyncStrategyFactoryTest` asserts that, with a counter per
entry, because it is the kind of property that quietly stops holding.

One Kotlin detail that is not decoration:

```kotlin
private val strategies: Map<SyncMode, @JvmSuppressWildcards Provider<SyncStrategy>>
```

Without `@JvmSuppressWildcards`, Kotlin compiles the value type to `? extends
Provider<SyncStrategy>`, Dagger provides it without the wildcard, and the two do not match.
The failure is a KSP-time "cannot be provided" error naming a type that looks identical to the
one that is bound, which is a bad hour if you have not seen it before.

## What Dagger checks, and what it cannot

Dagger catches:

- **A duplicate key.** Two bindings claiming `VISIBLE_USERS` fail the build.
- **A wrong bound type.** `@Binds` from something that is not a `SyncStrategy` fails the build.

Dagger cannot catch:

- **A missing key.** A `SyncMode` with no binding produces a map that is simply missing an
  entry. Everything compiles; `create(mode)` throws the first time anything asks — which, for
  a mode only a background worker uses, could be long after release.
  `SyncStrategyModuleContractTest` reads the module's annotations and asserts that every enum
  constant is bound exactly once.
- **A key on the wrong strategy.** `@SyncModeKey(VISIBLE_USERS)` over a method binding
  `CurrentUserSyncStrategy` is a well-typed lie: the key is an annotation in one file and the
  behaviour is a class in another, and nothing relates them. This is why `SyncStrategy` has a
  `mode` property at all — it is the implementation's own statement of what it is, and
  `SyncStrategyFactory` compares the two on every resolution. The same contract test pins the
  class-to-key pairing in CI.

The `mode` property looks redundant with the map key, and that redundancy is the check.

## Who decides the mode

The selection is policy and lives in the domain layer, not at the call site.
`RefreshVisibleUsersUseCase` holds one sentence of it:

> A user-initiated refresh from a list screen covers what that screen is showing.

A `ViewModel` injecting `SyncStrategyFactory` directly would re-decide that at every call
site, and the second list screen could decide differently — which is exactly the test
[`clean-architecture.md`](./clean-architecture.md) sets for what belongs in `core.domain`.
The strategies own *how* their mode syncs; the use case owns *which* mode a refresh means.

## The strategies today

| Mode | Strategy | What it does | Selected by |
| --- | --- | --- | --- |
| `VISIBLE_USERS` | `VisibleUsersSyncStrategy` | Dedupes the caller's ids and fans out over them, reporting successes and failures side by side | `RefreshVisibleUsersUseCase`, from `HomeViewModel.refresh()` |
| `CURRENT_USER` | `CurrentUserSyncStrategy` | One request for the signed-in user, whatever the screen holds | `PerformBackgroundSyncUseCase`, from `UserSyncWorker` |

**`CURRENT_USER` now has the caller it was written for.** It was bound, tested and unreached
for one item — written because a Strategy with a single implementation demonstrates nothing,
and because `UserRepository.syncCurrentUser` had no caller at all before it, half of finding 6
in [`solid.md`](./solid.md). The WorkManager background-sync item that opens Phase 9 is what
selects it: a worker that wakes on a timer has no screen, so it wants the smallest useful
refresh rather than one sized by whatever list happened to be open hours ago. See
[`background-sync.md`](./background-sync.md).

That is worth noting as evidence about the pattern rather than as a status update. Adding the
caller was a use case naming a mode — no edit to the factory, the map, or the other strategy —
which is the property the indirection was bought for.

## Testing without a Hilt runtime

The unit source set has no `hilt-android-testing` and runs on a plain JVM, so no component can
be built to ask for the real map. Two things stand in for it, and each has a guard:

- `TestSyncStrategies.syncStrategyFactoryOver(repository)` builds the same map by hand, so use
  case and `ViewModel` tests run against real strategies over a fake repository.
- `SyncStrategyModuleContractTest` reads `SyncStrategyModule`'s annotations by reflection —
  `@Binds` carries `RUNTIME` retention — and asserts that the bindings are exactly the classes
  the hand-written map contains, under exactly the keys those classes report.

So the hand-written copy cannot drift from the module without CI saying which file to edit.
The one thing neither covers is whether Hilt actually assembles the graph at runtime; that is
what `compileDebugKotlin` (KSP fails the build on an unsatisfied binding) and the app itself
answer.

## Adding a strategy

1. Add the constant to `SyncMode`, with a KDoc saying what it covers.
2. Write the strategy: `@Inject` constructor, `override val mode`, `sync(userIds)` returning a
   `RefreshOutcome`. Do not catch `CancellationException` — see
   [`structured-concurrency.md`](./structured-concurrency.md).
3. Bind it: `@Binds @IntoMap @SyncModeKey(SyncMode.YOUR_MODE)` in `SyncStrategyModule`.
4. Add it to `documentedStrategies` in `SyncStrategyModuleContractTest` and to
   `syncStrategyFactoryOver`. If you forget either, the contract test tells you which.
5. Give it a caller, or record in this file that it has none and what its intended one is.
   `CURRENT_USER` spent one item in that state and the entry above is what tracked it.
