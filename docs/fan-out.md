# Concurrent fan-out

Running one suspending operation over many inputs at once, and deciding what a single
failure means.

Every claim on this page is pinned by a test in
[`FanOutTest`](../core/common/src/test/kotlin/com/kojo/boilerplate/core/coroutines/FanOutTest.kt),
so a coroutines upgrade that changes one of these semantics fails the build rather than
quietly invalidating the guidance.

## The sequential version is the bug

```kotlin
val users = ids.map { repository.syncUser(it) }   // N round trips, one after another
```

Ten users on a 200ms link is two seconds of spinner for work that could have taken 200
milliseconds. Nothing about it looks wrong — `map` over a suspending call is the most
natural line of Kotlin there is — which is why it survives review.

Rewriting it as a fan-out is small and introduces three decisions the sequential form never
had to make. Each has a wrong answer that still compiles.

## Decision 1: how many at once?

```kotlin
ids.map { async { repository.syncUser(it) } }.awaitAll()   // don't
```

This starts *every* element immediately. The count is whatever the list happens to hold, so
a list that grew from 10 rows to 10,000 turns a refresh into ten thousand in-flight
requests — a thundering herd the client aimed at one server, sized by data rather than by
design. On the client side each one holds a coroutine and a connection-pool slot; OkHttp
will run five per host and queue the other 9,995, so the last request cannot start any
sooner than it would have anyway.

Both functions in `FanOut.kt` take a `concurrency` bound and hold it with a `Semaphore`:

```kotlin
val users = ids.mapConcurrently(concurrency = 8) { repository.syncUser(it) }
```

The permit is acquired *inside* each child, not around the loop that launches them, so a
child that throws still gives its permit back — `withPermit` releases in a `finally`. A
bound that leaked a permit per failure would run at full speed until the first outage and
then deadlock.

The default of 8 is chosen against the transport rather than the data: it sits just above
OkHttp's five-per-host limit, which keeps the pipe full without accumulating a long tail of
coroutines that exist only to wait for a connection. CPU-bound work wants a different number
— see [dispatchers](./dispatchers.md).

## Decision 2: what does one failure mean?

This is a product decision, and it is the entire difference between the two functions.

| | `mapConcurrently` | `mapConcurrentlyCatching` |
|---|---|---|
| Returns | `List<R>` | `FanOutResult<T, R>` |
| One element fails | the call throws | that element is a `FanOutFailure`, the rest come back |
| The siblings | are cancelled | run to completion |
| Use it for | work that is only meaningful whole | parts that stand alone |

`mapConcurrently` is the right default. If a screen needs both the profile and its
permissions, the profile arriving alone is not half an answer, and continuing to fetch it
after permissions failed only spends battery on a result nobody can use.

`mapConcurrentlyCatching` is for the case where a partial result is a real outcome you
intend to show: refreshing a visible list, where eight rows of ten is a better screen than
an error page, or a batch upload where "three sent, one to retry" is the status.

`FanOutResult` keeps each failure next to the input that produced it. A bare list of
throwables answers "how badly did that go?" but not "which ones do I retry?", and
reconstructing that by position only works until the first time the two lists are filtered
apart.

```kotlin
val outcome = ids.mapConcurrentlyCatching { repository.syncUser(it) }
if (outcome.isPartial) {
    report("Refreshed ${outcome.successes.size} of ${outcome.attempted}")
    retryLater(outcome.failures.map { it.input })
}
```

`isCompleteSuccess` is true for an empty fan-out. That is the answer that composes — a
refresh of an empty list has nothing to tell the user — so a caller for which "nothing to
do" is itself notable should test `attempted` instead.

### `supervisorScope` is not the tool here

The obvious reading of [structured concurrency](./structured-concurrency.md) is that
partial failure is what `supervisorScope` is for: it stops a failing child from cancelling
its siblings. It does — and it still does not give you a partial result:

```kotlin
supervisorScope {
    ids.map { async { repository.syncUser(it) } }.awaitAll()   // still throws
}
```

`awaitAll` rethrows the first failure it is handed regardless of scope policy, so the
fan-out fails at the await. Worse, once it throws, every `Deferred` left un-awaited takes
its exception to the grave: a failure nobody asked for is a failure nobody sees.

Isolating the failure **inside each child, as a value** is what actually delivers a partial
result. No child ever fails, so there is nothing for a scope policy to arbitrate, and every
failure arrives attached to its input. That is what `mapConcurrentlyCatching` does, and it
is why it is built on `coroutineScope`.

## Decision 3: cancellation is not a failure

`runCatching` inside each child would happily record a `CancellationException` as element
3's failure and hand back a tidy report of a fan-out that had been told to stop — the caller
completes *normally*, with a partial result, for a screen the user has already left.

Both functions rethrow it, via the same `rethrowIfCancellation` that `safeCall` uses. A
cancelled caller is cancelled, never partially served. The rule and its consequences are in
[structured concurrency](./structured-concurrency.md#cancellation).

Fail-fast cancellation runs the other way too: when `mapConcurrently` rethrows, its
siblings have already been cancelled *and* have finished cancelling, because `coroutineScope`
does not return until every child has completed. Whatever those transforms were holding is
released by the time the caller sees the exception.

## Where this is used

`UserRepositoryImpl.syncUsers` refreshes N users concurrently. The API has no bulk endpoint
— `users/{id}` is the only way to read a user — so a list refresh is genuinely N independent
requests, and one of them 500ing says nothing about the other N-1.

It deduplicates the ids first: the same id twice is the same request twice, and nothing
upstream guarantees the caller deduplicated. It also keeps the count honest, since with
duplicates in, "8 refreshed" could mean eight users or five.

`HomeViewModel.refresh()` is the caller. Note what it does *not* do with the successes:
nothing. They were written to the database, and the state observes the database, so the rows
update themselves. The only thing the fan-out has to report to the UI is the shortfall — see
[state and events](./state-and-events.md) for why that lives beside `HomeUiState` rather
than inside it.
