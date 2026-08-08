# Dispatchers — who owns the thread, and how a test checks

A `CoroutineDispatcher` decides which thread a coroutine resumes on. Two questions follow
from that, and this repo answers them in two different places:

1. **Which layer decides?** The one that does the work — not its callers.
2. **How do you know it decided correctly?** By injecting the dispatcher and asserting on it.

The second question is the reason for the first. A dispatcher reached through
`Dispatchers.IO` is a global, and a test cannot substitute a global; a dispatcher taken as a
constructor parameter is one a test can hand a `TestDispatcher` to and then check.

## The qualifiers

[`AppDispatchers.kt`](../app/src/main/kotlin/com/kojo/boilerplate/core/coroutines/AppDispatchers.kt)
declares three Hilt qualifiers, bound in
[`CoroutineDispatchersModule`](../app/src/main/kotlin/com/kojo/boilerplate/core/di/CoroutineDispatchersModule.kt):

| Qualifier | Backed by | For |
|---|---|---|
| `@IoDispatcher` | `Dispatchers.IO` | work that **waits** — disk, network, database |
| `@DefaultDispatcher` | `Dispatchers.Default` | work that **computes** — parsing, sorting, filtering a list |
| `@MainDispatcher` | `Dispatchers.Main` | work that must touch the UI thread |

The IO/Default split is a sizing decision, not a naming one. `Dispatchers.IO` is sized for
threads that are parked on a syscall and allows far more of them than there are cores; filling
it with work that actually wants a core starves the waiting calls it exists for.
`Dispatchers.Default` is capped at the core count for the opposite reason.

## Rule 1 — the layer that does the work owns the dispatcher

Not the caller. [`UserRepositoryImpl`](../app/src/main/kotlin/com/kojo/boilerplate/core/data/repository/UserRepositoryImpl.kt)
takes `@IoDispatcher` and confines itself:

```kotlin
override fun getUsers(): Flow<List<User>> =
    userDao.observeAll()
        .map { entities -> entities.map { it.toDomain() } }
        .flowOn(ioDispatcher)

override suspend fun saveUser(user: User) {
    withContext(ioDispatcher) { userDao.upsert(user.toEntity()) }
}
```

`flowOn` for the cold flows, `withContext` for the suspend functions. Both are *upstream*
operators: `flowOn` governs everything between it and the source, `withContext` everything
inside its block, and neither affects what the caller does with the result.

This used to live in the callers — three view models each appending `.flowOn(ioDispatcher)`
to a repository call and injecting a dispatcher in order to. That is one decision written
three times, no test can see any of the three, and a fourth caller that forgets has nothing
to fail.

### Why the libraries do not already handle it

They handle their own part, which is what makes this easy to get wrong. Room's generated
suspend DAOs and Retrofit's suspend calls both dispatch internally, so a repository with no
threading contract at all still returns correct results. What is left on the caller's thread
is the code *between* those calls — `toDomain()` over every row of a query result,
`toEntity()` on every write. And `Flow` operators are context-preserving: `.map { }` runs in
the **collector's** context, so an unconfined `getUsers()` mapped the entire list on whatever
collected it. For a view model collecting into `viewModelScope`, that is the main thread.

Nothing about the *results* distinguishes the confined version from the unconfined one. Only
the thread differs, which is why the contract went unstated for as long as it did.

## Rule 2 — do not add a dispatcher that buys nothing

A `flowOn` is a thread hand-off, and a hand-off costs more than the work it is protecting
when that work is small. `ProfileViewModel` and `ProfileDetailPaneViewModel` take **no**
dispatcher: now that the repository confines its own I/O, all that is left in them is a null
check and one `ProfileData` allocation per emission. Running that on the main thread is the
correct answer, not a tolerated one.

`HomeViewModel` does keep one, and it is `@DefaultDispatcher` rather than `@IoDispatcher`:

```kotlin
combine(userRepository.getUsers()…, searchQueries) { users, query -> /* filter + map */ }
    .flowOn(defaultDispatcher)
```

It scans the whole user list and allocates per surviving row. That is CPU work with no I/O
in it, so it belongs on `Default` — and because the repository's own `flowOn` sits closer to
the source, the row mapping still runs on IO. When `flowOn` operators nest, the innermost one
wins for the section it encloses.

## Rule 3 — `NonCancellable` is a `Job`, and nothing else

```kotlin
withContext(NonCancellable) { userDao.upsert(user.toEntity()) }
```

This reads like "run this part somewhere safe" and does not mean that. `NonCancellable` is a
single context element — a `Job` — so `withContext` replaces the job and **inherits the
dispatcher already installed**. Inside `UserRepositoryImpl.cache` that is `ioDispatcher`,
because of the `withContext(ioDispatcher)` at the call site above it. Before that call site
existed, it was the caller's thread. Pinned by
`syncUser writes through NonCancellable on the injected dispatcher`.

## Testing: `runTest` and which `TestDispatcher`

`runTest` runs the test body on a `TestDispatcher` backed by a `TestCoroutineScheduler` —
a virtual clock, so a `delay(5.seconds)` returns immediately while still ordering everything
around it. Every dispatcher in a test must share **one** scheduler or there is more than one
clock, and `advanceUntilIdle()` on one of them will not move the others.

There are two ways to get that sharing, and both are used here:

- Pass the dispatcher to `runTest`: `runTest(testDispatcher) { … }` adopts its scheduler.
- Construct from the test's own: `UnconfinedTestDispatcher(testScheduler)`.

`Dispatchers.Main` is a third case: it does not exist off-device, so a view model test must
install one. [`MainDispatcherExtension`](../app/src/test/kotlin/com/kojo/boilerplate/core/coroutines/MainDispatcherExtension.kt)
(JUnit 5) does the `setMain`/`resetMain` pair. `runTest` then picks up *its* scheduler
automatically, so no explicit wiring is needed as long as nothing else invents a scheduler.

### Standard or Unconfined

| | `StandardTestDispatcher` | `UnconfinedTestDispatcher` |
|---|---|---|
| A new coroutine | queues until the scheduler runs it | runs eagerly, on the calling thread |
| Good for | asserting ordering, and that a hop happened | getting a flow collector hot before the assertions |
| Hides | nothing | missing dispatch — every hop appears to be a no-op |

`UnconfinedTestDispatcher` is the convenient default and the reason to be careful:
because it never actually dispatches, a **missing** `flowOn` behaves exactly like a present
one. Anything asserting *where* code runs has to use `StandardTestDispatcher`, which is what
[`UserRepositoryImplDispatcherTest`](../app/src/test/kotlin/com/kojo/boilerplate/core/data/repository/UserRepositoryImplDispatcherTest.kt)
does.

### Asserting where the code ran

A `CoroutineDispatcher` *is* the `ContinuationInterceptor` in a coroutine's context, so a fake
can report which dispatcher its caller had installed:

```kotlin
override suspend fun upsert(entity: UserEntity) {
    upsertContext = currentCoroutineContext()[ContinuationInterceptor]
    written += entity
}
```

```kotlin
private val scheduler = TestCoroutineScheduler()
private val ioDispatcher = StandardTestDispatcher(scheduler, name = "repository-io")
private val callerDispatcher = StandardTestDispatcher(scheduler, name = "caller")

@Test
fun `saveUser runs its write on the injected dispatcher`() = runTest(callerDispatcher) {
    UserRepositoryImpl(dao, api, ioDispatcher).saveUser(user)

    assertSame(ioDispatcher, dao.upsertContext)
}
```

Two dispatchers, one scheduler: one clock, and the only difference between them is identity —
which is the whole assertion. The suite also carries the control that the caller was not
already on `ioDispatcher`, without which a bug that made the two the same object would turn
every case in the file green.

## Checklist

- Suspend functions and cold flows confine themselves; callers never add `flowOn` to fix a
  layer below them.
- `@IoDispatcher` for waiting, `@DefaultDispatcher` for computing.
- No dispatcher parameter for work that is a field read and an allocation.
- Dispatchers arrive by constructor injection with **no default value** — a default is a way
  for a test to silently run against the real pool.
- One scheduler per test.
- `StandardTestDispatcher` whenever the assertion is about *where* something ran.
