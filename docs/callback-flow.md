# `callbackFlow` — turning a listener into a stream

Android is full of APIs that predate coroutines and are shaped the same way: you hand the
system an object, the system calls you back on a thread of its choosing, and you are expected
to hand the object back when you are done. `ConnectivityManager.NetworkCallback`,
`SensorEventListener`, `LocationCallback`, `ContentObserver`, `BroadcastReceiver` and
`OnSharedPreferenceChangeListener` are all this API.

`callbackFlow` is the adapter for that shape, and
[`ConnectivityManagerNetworkMonitor`](../data/src/main/kotlin/com/kojo/boilerplate/core/network/connectivity/ConnectivityManagerNetworkMonitor.kt)
is this repo's worked example of it. Every claim below is pinned by a test in
[`ConnectivityManagerNetworkMonitorTest`](../data/src/test/kotlin/com/kojo/boilerplate/core/network/connectivity/ConnectivityManagerNetworkMonitorTest.kt).

## The shape

```kotlin
override val networkStatus: Flow<NetworkStatus> = callbackFlow {
    val callback = defaultNetworkCallback { status -> trySend(status) }

    connectivityManager.registerDefaultNetworkCallback(callback)   // 1. subscribe
    trySend(currentStatus())                                       // 2. seed

    awaitClose { connectivityManager.unregisterNetworkCallback(callback) }  // 3. unsubscribe
}
    .conflate()
    .distinctUntilChanged()
```

## Which builder

| Builder | Emit from | Use when |
|---|---|---|
| `flow { }` | the collecting coroutine only | you produce values yourself, suspending as you go |
| `channelFlow { }` | any coroutine | you fan work out and merge the results |
| `callbackFlow { }` | any **thread** | a foreign API calls you back |

The distinction is not stylistic. A plain `flow { }` enforces its invariant at runtime: emitting
from a thread that is not the collector's fails with `IllegalStateException: Flow invariant is
violated`. A network callback is delivered on the platform's own thread, so the plain builder
is not available here — it is not that `callbackFlow` is nicer, it is that the alternative
does not work.

`callbackFlow` buys that by putting a channel in the middle, which is also what makes
`trySend` available: non-suspending, thread-safe, and returning a result rather than blocking.
That matters because `onLost` is a `void` method — there is no way for it to wait on a slow
collector even if you wanted it to.

## `awaitClose` is the point, not boilerplate

Two things go wrong without it, and only one of them is loud.

The loud one: `callbackFlow` throws `IllegalStateException` if its block returns without
calling `awaitClose`. The producer block runs once and returns, so without a suspension at the
end the channel closes immediately and the collector sees an empty stream. The check exists
because that mistake is otherwise silent.

The quiet one is the leak. A registered `NetworkCallback` is held by the system, not by you.
Nothing about a cancelled coroutine unregisters it, and the process keeps being woken for every
network change on the device for as long as it lives. `awaitClose` runs its block when the
collector goes away for *any* reason — cancellation, an exception downstream, a
`WhileSubscribed` window expiring — which makes the registration's lifetime exactly the
collection's lifetime and nothing else.

> `the callback is unregistered when the collector goes away` is the test for this, and it
> asserts the negative first: no unregister while collection is live.

## Three things the callback does not give you

**A current value.** A `NetworkCallback` reports *transitions*. Subscribe while already
offline and nothing arrives until connectivity changes — which for a phone in a lift may be
never. The flow seeds itself, and the seed is read *after* registering: registering first
means no change can be missed in the gap, and the snapshot is then strictly newer than
anything the callback could have delivered in the meantime. Reading before registering opens a
window in which a transition is lost permanently.

**Survival across a hand-off.** When wifi drops to cellular, the platform reports the *new*
default with `onAvailable` before it reports the old one with `onLost`. Code that treats every
`onLost` as "offline" therefore goes offline immediately after coming back online, and stays
there until something else changes. The monitor tracks which network it currently believes is
the default and ignores the loss of any other.

**Backpressure.** `trySend` cannot suspend, so on a full buffer it drops — and with
`callbackFlow`'s default 64-element buffer, what it drops is the *newest* value while the
collector is still working through stale ones. `conflate()` fuses with that channel to hold
exactly one value, which inverts it: what gets dropped is what has already been superseded.
For a "what is the state right now" stream that is the only correct thing to drop.

## Cold, and why

Each collector registers its own callback. That is the right default — the registration lasts
exactly as long as the collection, with no shared lifetime to reason about — but it means N
collectors mean N registrations. A consumer that wants one registration shared says so:

```kotlin
val isOffline: StateFlow<Boolean> = networkMonitor.networkStatus
    .map { !it.isOnline }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)
```

`WhileSubscribed(5_000)` is what makes this safe on Android: a rotation does not tear the
registration down and put it back up a frame later, and a backgrounded screen releases it
after five seconds rather than holding it forever. `HomeViewModel` does exactly this, and the
5-second window is the same one its repository subscription uses.

## Applying it to another listener

| Step | `NetworkCallback` | `SensorEventListener` | `ContentObserver` |
|---|---|---|---|
| subscribe | `registerDefaultNetworkCallback` | `registerListener` | `registerContentObserver` |
| seed | `activeNetwork` + capabilities | usually none available | query once |
| unsubscribe in `awaitClose` | `unregisterNetworkCallback` | `unregisterListener` | `unregisterContentObserver` |
| buffer | `conflate()` — latest wins | `conflate()` at high sample rates | `conflate()` |

The one that varies is the seed: some APIs can be asked for their current value and some
cannot. When one cannot, say so in the flow's type — a `Flow<T?>` or a dedicated "unknown"
state — rather than inventing a default the caller will mistake for a reading.
