# Repository decorators: cache, retry, telemetry

`UserRepositoryImpl` talks to Room and Retrofit. It does not retry, does not suppress a
redundant request, and reports nothing about how long any of it took — and adding those three
things to it would be three more reasons for one class to change, in a class that already has
one. The decorator pattern is the alternative: each behaviour is its own `UserRepository` that
wraps another one, and the app gets the stack without knowing it exists.

```
             ┌─────────────────────────────┐
 injected →  │ TelemetryUserRepository     │  how long, and how did it end
             │  ┌──────────────────────────┴──┐
             │  │ CachingUserRepository       │  is this request necessary at all
             │  │  ┌──────────────────────────┴──┐
             │  │  │ RetryingUserRepository      │  is this failure worth another attempt
             │  │  │  ┌──────────────────────────┴──┐
             │  │  │  │ UserRepositoryImpl          │  Room + Retrofit
             └──┴──┴──┴─────────────────────────────┘
```

Everything in the app injects `UserRepository`. `RepositoryModule` is the only file that knows
there are four objects behind it, and `decorateUserRepository` is the only place the order is
written down.

## What each layer adds

| Layer | Adds | Leaves alone |
| --- | --- | --- |
| `RetryingUserRepository` | Re-attempts a transient `sync*` failure on a `BackoffPolicy` schedule; retries only the failed ids of a fan-out | The `Flow` reads and `saveUser` |
| `CachingUserRepository` | A freshness window per key, and one shared request when callers overlap | The `Flow` reads — Room is the cache for those |
| `TelemetryUserRepository` | One event per `sync*` call: operation, duration, outcome | The `Flow` reads and `saveUser` |

## The order, and what each swap would cost

The order is not a matter of taste. Each pair has a right way round and a wrong one that still
compiles and still passes every test that does not look for it — which is why
[`UserRepositoryDecoratorTest`](../data/src/test/kotlin/com/kojo/boilerplate/core/data/repository/decorator/UserRepositoryDecoratorTest.kt)
asserts the assembled chain.

**Retry innermost, because a retry is a property of the request.** From anywhere above it, one
call is one operation regardless of how many attempts it took: one cache entry, one telemetry
event, one duration that includes the backoff. Put retry *outside* the cache and every attempt
re-enters the cache — which either serves the caller the value it is retrying past, or, since
failures are not cached, simply repeats the lookup for nothing.

**Cache above retry, because a hit should cost nothing.** A fresh result should not pay for a
retry schedule it will never use. Below retry, the cache would only ever see calls the retry
loop had already decided to make.

**Telemetry outermost, because the caller's experience is the metric.** A duration measured out
here is what the user waited for: retries and backoff included, and near-zero when the cache
answered.

### What the chosen order gives up

From outside the cache, telemetry cannot distinguish a hit from a fast request, so **hit rate is
not derivable from this event stream**. That is a real cost and the fix is not to give
`TelemetryUserRepository` a way to ask — a decorator that knows what it wraps is not a decorator
any more. It is to wrap twice:

```kotlin
TelemetryUserRepository(          // what callers waited for
    CachingUserRepository(
        TelemetryUserRepository(  // what was actually requested
            RetryingUserRepository(impl),
            telemetry,
        ),
        scope,
    ),
    telemetry,
)
```

Two streams for the same operation, and the difference between their counts is the hit rate.
This app does not do it, because one Logcat line per sync is enough for a boilerplate and two
would be confusing. The point is that the question is answered by *composition* rather than by
an extra flag on an event.

## The three things that are easy to get wrong

### 1. A failure that is a value is not caught by a `catch`

`syncCurrentUser`, `syncUser` and `syncUsers` do not throw. `safeCall` turns a failed request
into `Result.failure`, and `mapConcurrentlyCatching` turns a failed fan-out child into a
`FanOutFailure`. So the retry every codebase writes first,

```kotlin
repeat(maxRetries) {
    runCatching { delegate.syncUser(id) }.onSuccess { return it }   // never retries
}
```

is a no-op: `runCatching` sees a returned `Result.failure` as a successful call and returns on
the first attempt. `RetryingUserRepository` inspects the returned value instead —
`result.exceptionOrNull()` — which is the only thing that works against a repository whose
failures are data. `RetryingUserRepositoryTest` pins it with a delegate that fails the way the
real one does.

### 2. A shared in-flight request must not belong to a caller

Coalescing means the second caller for a key awaits the first caller's request instead of making
its own. Written the obvious way — `async` in the calling coroutine — the second caller is now
awaiting a `Deferred` whose job is a child of the first caller's, and closing the first screen
mid-flight cancels a request the second screen is still waiting for. The failure needs two
callers and a cancellation between them to appear at all, so it survives a lot of manual
testing.

`CachingUserRepository` hosts the request in the process-lifetime `@ApplicationScope` instead.
Nobody who awaits it owns it, so a caller going away just stops awaiting. This is the first use
of that scope in the app, and it is what the scope was declared for: short, bounded work that
must outlive the screen that asked for it.

The registration and deregistration are ordered by the same mutex — the entry is added while
the lock is held, and the request's own `finally` needs that lock to remove it — so a request
that completes instantly cannot deregister before it was registered. The `finally` runs under
`NonCancellable`, because a suspension in the cleanup path of a cancelled coroutine throws
rather than running, and an in-flight entry that is never removed is a key that can never be
fetched again.

### 3. Cancellation is not failure, at any layer

Every layer has its own way to get this wrong, and each is written down where it is handled:

- **Retry**: nothing is caught, so a `CancellationException` cannot be mistaken for a failure
  worth another attempt. `delay` is cancellable, so a cancelled caller stops immediately rather
  than sleeping out its backoff first.
- **Cache**: a cancelled *caller* leaves the shared request alone; a cancelled *request*
  deregisters itself so the next caller starts a new one instead of joining a dead `Deferred`.
- **Telemetry**: cancellation is recorded as `RepositoryOutcome.Cancelled` and rethrown
  unchanged. Recording it as a failure is how an error-rate metric ends up tracking how fast
  users navigate.

## Where the cache does *not* go

Not over `getUsers` and `getUser`. Those observe Room, which is already the cache and is the one
that notifies. A memory copy in front of an observable query is a second source of truth that no
`Flow` emits from, and it would go stale the moment anything else wrote a row. What
`CachingUserRepository` caches is the *decision to make a request*, not the data — the data goes
where it always went, into the database, and the screens re-render from there.

The one thing it must do because it holds that decision: **invalidate on a local write.**
`saveUser` drops the entry for the user it wrote, or a `syncUser` moments later would answer
with the copy that predates the edit.

## Adding a layer

1. Implement `UserRepositoryDecorator` — all six methods, including the ones that just forward.
   There is no abstract base class on purpose: a method added to `UserRepository` should fail to
   compile in every decorator until someone decides what that layer does with it, and a base
   class turns that decision into a silent default.
2. Add it to `decorateUserRepository`, in the position its argument justifies.
3. Update `UserRepositoryDecoratorTest`'s expected chain — it fails until you do, which is the
   prompt to write the argument down here.
4. Add its class to `SolidContractTest.AUDITED_REPOSITORIES` and `AUDITED_IMPLEMENTATIONS`; a
   decorator is a `UserRepository` implementation and the audit counts it as one.
