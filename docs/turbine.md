# Testing a Flow's emissions with Turbine

When a test's subject is a **sequence** rather than a value, and what to use when it is not.

Almost every Flow test in this repository collects into a list:

```kotlin
val queries = mutableListOf<String>()
backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
    field.asSearchQueries(debounce).collect { queries += it }
}
runCurrent()

field.value = "alice"
advanceTimeBy(debounce + 1.milliseconds)

assertEquals(listOf("", "alice"), queries)
```

That is the right idiom for what it is asking, and it stays. The question it answers is
*what had arrived by the time the assertion ran* — which for a debounce, where the test is
driving a clock it owns, is exactly the question.

[Turbine](https://github.com/cashapp/turbine) answers a different one. It collects into a
channel and hands the test a cursor over it: `awaitItem()` takes the next emission and
suspends until there is one, `expectNoEvents()` asserts that there is not one right now,
`awaitComplete()` and `awaitError()` assert the terminal event, and an emission left
unconsumed at the end of the block **fails the test**. The sequence becomes the subject
instead of a list that happens to be the shape of one.

## What the list cannot say

Three things, and the third is the one that bites.

**"And nothing else."** `assertEquals(listOf("", "alice"), queries)` holds for a flow that
emits a third value one dispatch later. The list is read at an instant; a late emission is
behind it. Turbine's `expectNoEvents()` says it, and the unconsumed-event check says it again
for anything that arrives before the block ends.

**Which of several collectors got it.** A `Channel` fans out to exactly one receiver, and
"exactly one" cannot be written as an assertion about one list. Two turbines in a
`turbineScope` can be asked separately, which is how
[`UdfViewModelEffectsTest`](../core/ui/src/test/kotlin/com/kojo/boilerplate/core/ui/udf/UdfViewModelEffectsTest.kt)
pins the property every navigation effect in this app depends on.

**The difference between "not yet" and "never".** An empty list is what you get when the
upstream emitted nothing *and* when the collector never ran — a `launch` that was never
dispatched, a `stateIn` nobody subscribed to, a `runCurrent()` left out. Both read as
`assertEquals(emptyList(), received)` passing. That is an absence assertion that cannot fail,
and this repository has shipped the shape before: three gates that could never have gone red
are catalogued in [`tdd.md`](./tdd.md). Turbine fails instead — `awaitItem()` on a stream
nothing is feeding times out and says which flow it was waiting on, by the name you gave it.

It is not a timing fix. Turbine does not pump the scheduler for you: the view-model test above
shares one `TestDispatcher` with `Dispatchers.Main` precisely so that an `expectNoEvents()`
cannot pass because a send had not been dispatched yet.

## The rule here

Reach for Turbine when the assertion is about **order, cardinality, absence, or termination**:

- a sequence of emissions, in order, with nothing extra — `UdfViewModel.effects`
- an event that must be delivered once, to one collector
- a flow that must complete, or fail with a particular throwable
- a stream that must still be alive after its collector went away

Stay with the list collector, `first()`, or `state.value` when the assertion is about a value:

- what the screen renders now — `state.value`, with a collector alive if it is a `stateIn`
- the first value matching a predicate — `state.first { it is Success }`
- the values a finite flow produced — `flowOf(…).map(…).toList()`
- anything whose subject is a clock the test drives, where `advanceTimeBy` and a list read
  more directly than an `awaitItem()` per tick

Converting a passing list assertion to Turbine buys nothing and costs a diff, so the existing
suites were left alone. [`SearchQueryFlowTest`](../core/common/src/test/kotlin/com/kojo/boilerplate/core/coroutines/SearchQueryFlowTest.kt),
[`AppEventBusTest`](../core/common/src/test/kotlin/com/kojo/boilerplate/core/event/AppEventBusTest.kt)
and [`ConnectivityManagerNetworkMonitorTest`](../data/src/test/kotlin/com/kojo/boilerplate/core/network/connectivity/ConnectivityManagerNetworkMonitorTest.kt)
each assert a sequence the test itself produced, one callback or one keystroke at a time, with
a `runCurrent()` between — and that pump is what makes their lists complete rather than
instantaneous.

## What it was pointed at first

`UdfViewModel`, the base class all six screens are written against, and the one part of the
UDF contract with no behavioural test of its own — `UnidirectionalDataFlowContractTest` in
`:app` pins the *shape* of every view model, that it exposes `state` and `effects` and nothing
else and that only the first is a `StateFlow`, and nothing asserted what the stream then does.
Its `effects` stream is a
`Channel(BUFFERED).receiveAsFlow()`, and the KDoc explaining that choice makes five claims
about emissions:

| Claim | Why it is there | What a regression looks like |
|---|---|---|
| Order survives the `launch` inside `emitEffect` | `emitEffect` is called from a non-suspending handler, so the `send` needs a coroutine | "saved" shown after "left the screen" |
| An effect raised with nothing collecting is buffered | the credential picker is another Activity, so sign-in completes with the screen stopped | a sign-in that navigates nowhere |
| A full buffer makes the producer wait | `send`, not `trySend`, which fails into a return value nobody reads | one effect missing from a burst |
| The stream survives its collector leaving | `receiveAsFlow`, not `consumeAsFlow`, which closes the channel | dead effect stream after the first rotation |
| An effect reaches exactly one collector | two collectors of a navigation effect navigate twice | the same screen pushed twice |

All five were prose. The only coverage any of them had was `GoogleSignInViewModelTest`
asserting one screen's behaviour *through* them, which means a regression in the base class
would have been found as a double navigation in whichever feature noticed first, and debugged
there.

## Running them

Through Gradle, with the rest of the unit tests:

```
./gradlew testDebugUnitTest
```

Turbine is on every module's test classpath — it is in `sharedTestDependencies()` in
`build-logic/convention`, beside JUnit 5, `kotlinx-coroutines-test` and mockk — so a test in
any module can reach for it without a line in that module's build file.

The offline harness runs them too, which is worth knowing because `:core:ui` is a Compose
module and most of it cannot compile without the Android SDK:

```
scripts/jvm-harness/run.sh --skip-detekt
```

`UdfViewModel` itself needs only the stubbed `ViewModel` and `viewModelScope`, so the effects
suite is inside the harness's subset rather than left to CI. See
[`scripts/jvm-harness/README.md`](../scripts/jvm-harness/README.md).

## The version pin

Turbine is held at **1.2.0**, not the newer 1.2.1, and the reason is in
`gradle/libs.versions.toml` next to the pin: 1.2.1 declares `kotlin-stdlib` 2.1.21 and
`kotlinx-coroutines-core` 1.10.2, Gradle resolves to the highest version anyone asks for, and
moving coroutines off 1.9.0 would leave `kotlinx-coroutines-play-services` — deliberately
versioned with it — bridging `Task.await()` on one release against a core from another. A test
library is not worth a change to what the app runs on.
