# Offline-first reads with `NetworkBoundResource`

A screen reads the database. Only the database. The network's job is to write to it.

That sentence is the whole pattern, and it is worth stating as a rule because the alternative
is not a different architecture — it is no architecture, arrived at one screen at a time. A
screen that renders "whatever the last request returned, and a spinner until it does" is
offline-hostile by construction: it has nothing to show on a cold start without a connection,
it blanks itself when a refresh fails, and every one of its states is a decision that screen
made alone.

## What this app had before

Two halves, and nothing obliging anyone to connect them.

```
UserRepository.getUser(id) : Flow<User?>        observes Room. Never asks the network.
UserRepository.syncUser(id): Result<User>       asks the network, writes Room, returns once.
```

Both are correct. The gap is that using one without the other compiles, and the failure it
produces is invisible in the place you would look for it:

- `ObserveUserProfileUseCase` used only the first. The profile screen therefore **never
  refreshed** — it rendered whatever row some *other* screen's sync had left in Room, for as
  long as that row existed. A user opened from a deep link, or one last fetched a week ago,
  looked exactly like one fetched a second ago. There is no error state for this, no log line,
  and no test that could have caught it without knowing to ask.
- `HomeViewModel` used both, correctly, by hand: it observes `getUsers()` and calls
  `RefreshVisibleUsersUseCase` from a button. That is about fifteen lines of `flatMapLatest`,
  a CAS-guarded in-flight flag and a `finally`, and it is per-screen.

`networkBoundResource` is the second of those written once.

## The shape

```kotlin
fun <T> networkBoundResource(
    query: () -> Flow<T>,
    refresh: suspend () -> Unit,
): Flow<Resource<T>>
```

```
subscribe
   │
   ├─▶ query().first()          ──▶  emit Resource.Loading(local)
   │
   ├─▶ refresh()                     fetches and commits to the store
   │        │
   │        ├── returned  ──▶  emitAll(query()) as Resource.Success
   │        └── threw     ──▶  emitAll(query()) as Resource.Failure(cause)
   │
   └─▶ …and the store keeps emitting for as long as the collector is there.
```

The value the collector sees always came out of `query`. `refresh` returns `Unit`, so there is
no path by which a network response reaches a screen without going through the database first.
That is the guarantee, and it is enforced by the signature rather than by review.

## `Resource`, and why every arm carries data

```kotlin
sealed interface Resource<out T> {
    val data: T
    data class Loading<out T>(override val data: T) : Resource<T>
    data class Success<out T>(override val data: T) : Resource<T>
    data class Failure<out T>(override val data: T, val cause: Throwable) : Resource<T>
}
```

The arm describes **the refresh**. The data is the store's, and it is present on all three
arms because every emission this builder makes came from reading the store.

That is the one place this differs from the canonical implementations of the pattern, which
make the payload nullable. A nullable payload is load-bearing at every call site — the
`if (data != null)` is really asking *"has the store been read yet?"* — and it is a question
this builder answers before it emits anything. Making it non-null moves the answer into the
type, and the cost is a documented edge: a `query` that never emits leaves the resource silent
rather than emitting a data-less loading state. Room always answers, so the app never reaches
it, and `NetworkBoundResourceTest` pins it so the behaviour is not a surprise.

`Resource` is deliberately not `Result`. `Result` has two arms and this has three, and the
third — *here is the data, and the attempt to make it newer failed* — is the entire point.
Collapsed into `Result.failure` it loses the data the screen should still be showing;
collapsed into `Result.success` it loses the fact that the screen is out of date.

## Four decisions, each with a wrong answer that still compiles

### 1. A failing `query` is not a `Resource.Failure`

Only `refresh` is caught. If the store itself cannot be read there is no local value, so there
is no resource to describe, and the flow terminates with the failure for a collector's `catch`
to handle. `Resource.Failure` then means something narrower and more useful than "something
went wrong": the store was read, and it may be stale.

Catching both would produce a `Failure` arm that sometimes carries a real row and sometimes
carries whatever the caller was forced to invent for a store it could not read.

### 2. A failed refresh stays failed

Once `refresh` has failed, every later emission from the store is `Resource.Failure`, including
writes that some other part of the app makes. The arm reports what *this* subscription's
refresh did, and nothing that happens afterwards changes that.

The alternative — promoting the next emission back to `Success` — reads as "self-healing" and
is worse: an unrelated write to the same table would clear a stale-data warning without
anything having been refreshed. Recovery is a new subscription, which in a ViewModel is
`flatMapLatest` over a retry signal, the shape `HomeViewModel` already uses for its retry
button.

### 3. Cancellation is not failure

`refresh` runs inside `safeCall`, which is the one wrapper in this codebase that rethrows a
`CancellationException` instead of reporting it. A collector that leaves mid-refresh cancels;
it is not handed a `Resource.Failure` for a screen that is already gone. See
[structured concurrency](./structured-concurrency.md).

### 4. Freshness belongs to the repository, not to this builder

There is no `shouldFetch` parameter, which is the other thing the canonical form has. This
refreshes on every subscription, and *whether that costs a request* is decided one layer down
by `CachingUserRepository`: a 30-second freshness window, plus coalescing of concurrent callers
onto a single in-flight request. See [repository decorators](./decorator.md).

Putting the decision here as well would be a second place to make it and a second place for it
to disagree with the first — a two-pane layout subscribing twice would have to agree with
itself about how old is too old. When a row gains an `updatedAt` (the next item in `SPEC.md`), a
per-row age policy still belongs next to the column that carries it.

## Where it is composed, and why not in the repository

`ObserveUserProfileUseCase` builds the resource over the injected `UserRepository`:

```kotlin
networkBoundResource(
    query = { userRepository.getUser(userId).retryWithBackoff() },
    refresh = { userRepository.syncUser(userId).getOrThrow() },
)
```

Putting an `observeUser(id): Flow<Resource<User?>>` on `UserRepository` itself is the obvious
alternative and it is the wrong one *in this app*, for a reason specific to how the repository
is assembled: retry, caching and telemetry are **decorators around the interface**
(`docs/decorator.md`). A `networkBoundResource` built inside `UserRepositoryImpl` would call
the DAO and the API directly, underneath all three — so the profile screen's refresh would get
no backoff, no telemetry, and, worst of the three, no coalescing, meaning a two-pane layout
would fire two identical requests every time it subscribed.

Composed *above* the repository, the refresh is an ordinary `syncUser` call and inherits the
whole stack. The use case is also where the choice already lived: it is the layer that exists
to hold policy the ViewModels were duplicating ([the domain layer](./clean-architecture.md)).

`getOrThrow()` turns the repository's `Result` back into a throw so the builder can catch it.
The round trip is deliberate — the builder is generic over stores, and a throw is the only
failure channel every `refresh` can share.

## Mapping `Resource` onto what the screen renders

`UserProfile` still has three arms and still has no `Loading`, because loading is the absence
of an emission — `stateIn`'s initial value already expresses it. The mapping is where the
product decisions are:

| `Resource` | store | `UserProfile` |
| --- | --- | --- |
| `Loading` | has the row | `Loaded` — a row being refreshed is still a row worth showing |
| `Loading` | empty | *nothing is emitted* — this is what "loading" is |
| `Success` | has the row | `Loaded` |
| `Success` | empty | `Missing(userId)` — the refresh landed and there is genuinely no such user |
| `Failure` | has the row | **`Loaded`** — see below |
| `Failure` | empty | `Unavailable(cause)` — nothing to show, so the failure is the news |

The row worth arguing about is the fifth. **A failed refresh over a cached row renders the
cached row**, not an error. Blanking a profile the app already has because the network went
away is precisely the failure mode offline-first exists to remove, and it is also strictly what
this screen did before — it never refreshed, so it never had a refresh failure to render.

**Known gap, and it is a UI one.** A screen showing a cached row after a failed refresh gets no
signal that it is stale, because `UserProfile` has nowhere to put one. `HomeUiState.isOffline`
is how the list screen says it; giving the profile screen the equivalent means a fourth arm
here and belongs with the Phase 10 UI items.

## Operator order in the use case

```kotlin
networkBoundResource(query = { getUser(id).retryWithBackoff() }, refresh = { … })
    .mapNotNull { it.toProfile(id) }
    .distinctUntilChanged()
    .catch { emit(UserProfile.Unavailable(it)) }
```

- **`retryWithBackoff` inside `query`, not around the resource.** What it fixes is a *store
  read* that threw. Resubscribing the resource would re-run the refresh as well — a second
  network request to recover from a database error.
- **`distinctUntilChanged` after the mapping, which is where it moved to.** It used to sit on
  the `User`, on the argument that comparing a row is cheaper than allocating a `UserProfile`
  to discard. Still true, and now outweighed: the same unchanged row arrives under two
  different arms, once as `Loading` while the refresh is in flight and once as `Success` when
  it lands, so a dedupe upstream of the mapping compares two values that differ and lets both
  through — and the screen sees `Loaded(alice)` twice. Comparing the profile is the comparison
  that answers *"did anything the user can see change?"*.
- **`catch` last, and it now catches one thing.** Only a store read that failed every attempt
  still travels as a throw; a failed refresh arrives as a value.

## What this does not cover

- **Lists.** `observeUsers()` as a network-bound resource needs a `refresh` that fetches the
  whole queried set, and this API has no bulk endpoint — `users/{id}` is the only way to read a
  user. That is exactly why the list refresh is a fan-out reporting `FanOutResult` rather than
  a resource ([concurrent fan-out](./fan-out.md)), and why `HomeViewModel` keeps its explicit
  refresh button. A `users?ids=` endpoint would change the answer.
- **Writes.** This is a read pattern. Reconciling a local edit with a row that changed on the
  server is [conflict resolution](./conflict-resolution.md), which is the item that followed
  this one. Getting the edit *to* the server is [idempotency](./idempotency.md), which is the
  item after that: the background sync pushes every row with pending fields before it fetches,
  each under a client-generated key that survives the process, so a retry that arrives hours
  later applies the change once rather than again.
- **Paging.** `RemoteMediator` is the same idea over a windowed query and is its own item.
- **Row age.** Nothing in Room records when a row was written, so "stale" here means "the
  refresh this subscription ran did not land", never "this row is four days old". This said
  the column would arrive with conflict resolution, and it did not:
  [conflict resolution](./conflict-resolution.md) added a server-assigned `version` instead,
  and deliberately no wall-clock column, because a timestamp sitting next to a conflict
  resolver gets read as a tiebreaker and a skewed device clock then wins conflicts it should
  lose. Row age belongs with the freshness policy that would read it — today an in-memory
  window in `CachingUserRepository` — where being wrong costs a redundant request rather than
  a lost edit.
