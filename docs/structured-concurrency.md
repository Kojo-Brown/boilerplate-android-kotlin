# Structured concurrency

Which scope builder to reach for, and what to do about cancellation.

Every claim on this page is pinned by a test in
[`StructuredConcurrencyTest`](../core/common/src/test/kotlin/com/kojo/boilerplate/core/coroutines/StructuredConcurrencyTest.kt),
so a coroutines upgrade that changes one of these semantics fails the build rather than
quietly invalidating the guidance.

## The two scope builders

Both suspend until every child they started has completed. They differ only in what a
failing child does to its siblings.

| | `coroutineScope` | `supervisorScope` |
|---|---|---|
| A child fails | its siblings are cancelled, and the builder rethrows the failure to the caller | its siblings keep running, and the caller is not failed |
| Waits for all children | yes | yes |
| Caller is cancelled | every child is cancelled | every child is cancelled |
| Use it for | work that is only meaningful as a whole | independent work whose parts are individually useful |

`coroutineScope` is the default. Reach for it whenever a partial result is not a result:
if a screen needs both the profile and its permissions, the profile arriving alone is not
half an answer, and continuing to fetch it after permissions failed only wastes battery.

`supervisorScope` is for the case where the parts stand alone — refreshing four independent
dashboard cards, or uploading a batch where three successes and one failure is a real
outcome you intend to report. For the specific shape of running one operation over many
inputs, [concurrent fan-out](./fan-out.md) covers why `supervisorScope` on its own still
does not hand you those three successes.

Neither is a way to *avoid* waiting. `supervisorScope` isolates failures; it does not
abandon children. It still does not return until the slowest of them has finished.

### The `supervisorScope` footgun

Failure isolation means a failing child has nowhere to report to. A child started with
`launch` inside a `supervisorScope` does not fail its parent, so its exception goes to the
context's `CoroutineExceptionHandler` — and with no handler installed, to the thread's
uncaught handler, which on Android is a crash.

Start children you need results from with `async` and `await()` them, which turns the
failure back into something the caller can act on. Installing a handler is the other half
of the answer: see [coroutine error handling](./coroutine-errors.md).

## Cancellation

Cancellation travels as a `CancellationException`. It is not an error: it is the parent
telling this coroutine to stop, and the machinery upstream is waiting to see it. Three
rules follow.

### 1. Never convert cancellation into a failure

`runCatching`, and any `catch (e: Exception)` broad enough to reach it, swallows
cancellation along with everything else. The coroutine then completes *successfully*, its
parent stops waiting for a cancellation that never arrives, and the UI renders an error
for a screen the user has already left.

```kotlin
suspend fun load(): Result<User> = runCatching { api.getUser() }   // wrong
```

Use [`safeCall`](../core/common/src/main/kotlin/com/kojo/boilerplate/core/common/Result.kt), which
is that same wrapper with the cancellation put back on its way, or call
`Throwable.rethrowIfCancellation()` as the first line of your own broad `catch`.

### 2. A cancelled coroutine cannot suspend

Once a job is cancelling, every suspension point inside it throws immediately. So this
does not do what it looks like:

```kotlin
try {
    session.stream()
} finally {
    session.close()      // suspends → throws → the session is never closed
}
```

The `finally` block runs; the suspending call inside it does not. Cleanup that suspends has
to run in `NonCancellable`, which is what
[`withCleanup`](../core/common/src/main/kotlin/com/kojo/boilerplate/core/coroutines/StructuredConcurrency.kt)
and `useCancellationSafe` do:

```kotlin
session.useCancellationSafe(release = { it.close() }) { it.stream() }
```

`NonCancellable` is for short, bounded steps that have already started — a commit, a
release, a flush. Wrapping a whole operation in it produces work the user cannot stop,
which is the bug it is meant to prevent, inverted.

`UserRepositoryImpl.cache` is the example in this codebase: the request stays cancellable,
but once the response is in hand the local write completes rather than being abandoned
half-way, because dropping it wastes the round trip and leaves the cache holding data the
app has just proved stale.

### 3. Cleanup wants to know why

`withCleanup` hands the throwable that ended the block to the cleanup — the
`CancellationException` when the caller went away, the failure when the work failed, `null`
when it succeeded — so one cleanup path can distinguish committing from rolling back
without a second flag.

If both the block and the cleanup fail, the block's failure is the one that propagates and
the cleanup's is attached to it with `addSuppressed`, following `AutoCloseable.use`: the
first failure is usually the one that explains the second.

## Identity is not preserved across a coroutine boundary

kotlinx.coroutines copies a throwable as it crosses `coroutineScope`, `supervisorScope`,
`withContext` or `await` so it can attach the launching stack trace. The exception a caller
catches on the far side is equal to, but not the same instance as, the one that was thrown.
Assert on type and message in tests, not on identity.
