# Flow operators

Four operators that decide how a screen behaves under real conditions — a user typing, a
connection dropping, a database invalidating a table — and the mistakes each one exists to
prevent.

Every claim here is pinned by a test in
[`FlowRetryTest`](../app/src/test/kotlin/com/kojo/boilerplate/core/coroutines/FlowRetryTest.kt),
[`SearchQueryFlowTest`](../app/src/test/kotlin/com/kojo/boilerplate/core/coroutines/SearchQueryFlowTest.kt)
and [`HomeViewModelTest`](../app/src/test/kotlin/com/kojo/boilerplate/feature/home/HomeViewModelTest.kt),
so a coroutines upgrade that changes one of these semantics fails the build rather than
quietly invalidating the guidance.

## Where they sit

`HomeViewModel` uses all four, and the order they appear in is the whole argument:

```kotlin
private val content: Flow<HomeContent> = retrySignal
    .flatMapLatest {                                  // manual retry replaces the subscription
        combine(
            userRepository.getUsers()
                .retryWithBackoff()                   // transient failures never reach the UI
                .distinctUntilChanged(),              // an unchanged list does no work
            searchQuery.asSearchQueries(),            // debounce + trim + distinct
        ) { users, query -> /* … */ }
            .catch { /* … */ }
    }
    .flowOn(defaultDispatcher)
    .onStart { emit(HomeContent.Loading) }            // combine waits for every input

override val state: StateFlow<HomeUiState> = combine(content, searchQuery, offline, refreshing)
    { /* … */ }
    .stateIn(viewModelScope, WhileSubscribed(5_000), HomeUiState())
```

The pipeline produces the *content* of the screen rather than the whole state — the search
text, the offline flag and the refresh spinner are independent of it and are combined in at
the end. [`unidirectional-data-flow.md`](./unidirectional-data-flow.md) has why, including
the `onStart` above, which is load-bearing: `combine` emits nothing until every input has.

## `flatMapLatest` — switch, do not accumulate

| Operator | On a new upstream value |
|---|---|
| `flatMapLatest` | cancels the inner flow, starts the new one |
| `flatMapConcat` | waits for the inner flow to finish first |
| `flatMapMerge` | runs both |

For anything that maps "the current selection" to "the data for it" — a retry signal, a
selected id, a search query — only the first is correct. The other two leave the previous
subscription alive, and every one of them keeps writing to the same state: tapping retry three
times gives you three live collections racing to set the state, and the winner is whichever
happens to emit last.

`flatMapConcat` is worse than it looks here, because an inner flow backed by Room or a
`StateFlow` never completes — so it never gets to the next value at all.

`flatMapLatest` is still `@ExperimentalCoroutinesApi` in kotlinx-coroutines 1.9.0. The opt-in
is written at the class declaration rather than left as a warning, so the experimental surface
a class depends on is visible where the class is defined.

## `debounce` — rate-limit the work, never the field

[`asSearchQueries()`](../app/src/main/kotlin/com/kojo/boilerplate/core/coroutines/SearchQueryFlow.kt)
is `trim` → `debounce` → `distinctUntilChanged`, and it goes on the *derived* flow:

```kotlin
private val _searchQuery = MutableStateFlow("")
val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()   // the field reads this
// … and the work reads _searchQuery.asSearchQueries()
```

Binding the text field to a debounced flow is the version of this that ships: the character
appears 300ms after it is typed, the cursor stutters, and it reads as a broken keyboard. The
field must be immediate. What gets rate-limited is the query, the request, the filter.

Two details that are easy to leave out:

- **The timeout is per value, and it is zero for an empty query.** A plain `debounce(300)`
  delays the initial `""` that a `MutableStateFlow("")` replays on subscription, so every cold
  start renders 300ms of loading state before the first result. It also makes clearing the
  field feel slower than filling it, which is backwards.
- **Trim before, distinct after.** A soft keyboard adds a space after a word; `"alice "` is a
  different value to the upstream `StateFlow` but the same query, and only the
  `distinctUntilChanged` *after* the trim collapses the two.

## `distinctUntilChanged` — and where it is already free

Room invalidates per **table**, not per row: any write to `users` re-runs every query
observing it, and a query whose result did not change re-emits a byte-identical list. Same for
`retryWithBackoff`, which resubscribes and therefore replays whatever the source had already
emitted.

A `StateFlow` conflates equal consecutive values on its own, so at the *end* of a pipeline
this operator is redundant. It earns its place **upstream of the expensive part** — the filter
and the mapping to `HomeItem` in `HomeViewModel` — where it stops the work from running rather
than discarding the result afterwards.

It compares with `equals`, which is why the models are `data class`es. A model with identity
equality makes this operator silently do nothing.

## `retryWhen` — the only way back

A `Flow` is over the moment it throws. There is no resuming: the collector's `catch` runs and
the subscription is gone. For a screen backed by `stateIn`, one dropped connection therefore
leaves an error on screen until the user finds the retry button —
[`retryWithBackoff`](../app/src/main/kotlin/com/kojo/boilerplate/core/coroutines/FlowRetry.kt)
is `retryWhen` wrapped so that does not happen for failures that would have cleared on their
own.

Three questions, in order, and each one is a way to get this wrong:

| Question | Wrong answer looks like |
|---|---|
| Is it cancellation? | The user leaves the screen; the retry loop keeps working, and the parent waiting on the cancellation never sees it. |
| Is it transient? | A 404 is retried four times, so the user watches a spinner for four seconds to be told the same thing. |
| Are there attempts left? | No cap: a server that is down is retried forever, by every client at once. |

`isTransientFailure` answers the second one. `IOException` covers the transport layer — no
route to host, connection reset, read timeout — which is exactly what clears by itself. An
`HttpException` means the server answered, so the status decides: 408 and 429 are the server
asking to be asked again, 5xx is its own fault, and every other 4xx is a statement about the
request that sending it again unchanged will not change.

On cancellation: `catch` and `retryWhen` already rethrow a `CancellationException` that is the
*current* job's cancellation cause, but one raised by upstream code for its own reasons is an
ordinary throwable to that check and would be retried. The guard is explicit for that case.
Note the consequence: a `withTimeout` around the source throws `TimeoutCancellationException`,
which is a `CancellationException`, so it is not retried. On Android that is usually the right
outcome anyway — a network timeout comes out of OkHttp's own call/read timeouts as a
`SocketTimeoutException`, which is an `IOException` and is retried.

### Backoff and jitter

`initialDelay * factor^attempt`, capped at `maxDelay`: 0.5s, 1s, 2s by default, four
subscriptions in total. A fixed delay is not a backoff — it is the same load, spread slightly.

Up to `jitterRatio` of each delay is then subtracted at random, so a delay lands in
`[d × 0.75, d]`. Without it, every client that lost the same server reconnects on the same
schedule and the retry storm finishes what the outage started. `random` is a parameter so
tests assert an exact schedule instead of a range.

### Retrying replays

Resubscribing re-runs the flow from the start, so anything already emitted is emitted again:

```kotlin
flow { emit("first"); if (firstAttempt) throw IOException(); emit("second") }
// collector sees: first, first, second
```

That is why `.retryWithBackoff().distinctUntilChanged()` is the pair, in that order, wherever
the collector cannot tolerate a repeat.
