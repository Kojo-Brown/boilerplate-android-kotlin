# The app-wide event bus

The Observer pattern, at the one scale where it earns its keep: something happened to the
application, and several unrelated parts of it have to react.

`docs/state-and-events.md` ends with six rules, and rule 4 is the one this implements —
"broadcasts to several independent listeners: `SharedFlow`". The guide stops there because
until now nothing in the app had two independent listeners for the same thing. Session expiry
does, and it is also the case that shows why the obvious implementations of a bus are wrong.

## What is on it

One event. That is not a placeholder.

```kotlin
sealed interface AppEvent {
    data object SessionExpired : AppEvent
}
```

An event bus is the easiest abstraction in an application to ruin, because everything can be
phrased as an event and nothing about adding one is visibly expensive. What it costs is the
ability to answer "what happens when X?" by reading — a direct call has a caller you can jump
to; a publish has a set of subscribers you have to go looking for. So membership is gated on
three questions, all of which have to answer yes.

**1. Is it an event at all?** The test from `docs/state-and-events.md`: if the screen were
destroyed and rebuilt right now, should it happen again? Connectivity is the instructive near
miss. "The network came back" reads like news, but "is there a network" is a value with a
current answer that a newly built screen needs, and `NetworkMonitor` already exposes it as a
`Flow<NetworkStatus>` that anyone can observe. Putting it on the bus as well would give the
app two answers to the same question, one of which is only correct for whoever was listening
at the time.

**2. Do two or more independent things react?** One listener means the publisher should call
it. A bus with a single subscriber is indirection with no fan-out to pay for it.

**3. Would a direct call couple layers that should not know each other?** `SessionExpired` is
published by an OkHttp `Authenticator` on a request thread. Its consequences belong to the
auth layer and to navigation. Wiring those up directly gives the network layer a reference to
the navigation graph.

A per-screen one-shot — a snackbar, "navigate on success" — fails question 2. It belongs in
the screen's own `Channel`, which is what a `UiEffect` is, and the two mechanisms
coexist deliberately.

## Why `SharedFlow` here and `Channel` there

They are not interchangeable, and the difference is exactly the property this needs:

| | `Channel(BUFFERED).receiveAsFlow()` | `MutableSharedFlow(replay = 0)` |
|---|---|---|
| Delivery | exactly one collector | every collector |
| Emitted with nobody collecting | buffered until someone receives | **dropped** |

A screen's own event wants the first row's *left* column — two collectors of a navigation
event navigate twice — and can live with buffering, because the collector always comes back.
An app-wide broadcast wants the right column, because the whole point is that the credential
listener and the navigation graph both react. Handing `SessionExpired` to a `Channel` would
deliver it to whichever of them happened to ask first, non-deterministically, and the other
would never know.

The price is the second row, and it is not a small one.

## The dropped-event problem, and what actually solves it

`MutableSharedFlow(replay = 0)` throws away anything published while it has no subscribers, and
`tryEmit` returns `true` while doing it. There is no signal at the call site at all.

For `SessionExpired` that is not a hypothetical. A session dies when a token refresh is
rejected, which happens in the middle of a request, which is very often a request made while
the app is in the background — precisely when no composition is collecting anything.

Three answers, two of them wrong:

- **`replay = 1`.** Now the event survives the gap, and every composition that starts
  afterwards handles it again. A rotation re-navigates. This is the bug
  `docs/state-and-events.md` works through at length under "what this replaced"; adding replay
  moves it from a screen's events to the whole application's.
- **`BufferOverflow.DROP_OLDEST`.** Solves a different problem — it makes `tryEmit` always
  succeed — by silently discarding a *pending* event when a subscriber is slow. For "the
  session is gone", losing one without a word is the worst available outcome. The bus uses
  `SUSPEND`, so a full buffer becomes a `false` return that a caller can act on rather than an
  event that quietly evaporates.
- **A subscriber that is always there.** `AppEventDispatcher` holds one subscription for the
  life of the process, started from `BoilerplateApp.onCreate`, and fans out in-process to every
  `AppEventListener` in the Dagger multibinding. Reactions that must never be missed are
  listeners. Reactions that only make sense with a screen present collect
  `AppEventBus.events` from the composition and accept that they miss what happens while the
  screen is stopped.

That last split is the design. `SessionExpired` has one of each:

```
TokenAuthenticator ──tryPublish──▶ AppEventBus (SharedFlow, replay = 0)
                                        │
                    ┌───────────────────┴───────────────────┐
                    ▼                                       ▼
        AppEventDispatcher                          AppNavHost
        (process lifetime)                          (STARTED only)
                    │                                       │
                    ▼                                       ▼
    SessionExpiryCredentialListener              navigate to SignIn,
    clears Credential Manager state              clearing the back stack
```

Clearing the credential state has to happen whether or not anything is on screen: left alone,
Credential Manager can answer the next sign-in from its own record of who was authorised, so a
user whose session was revoked is silently re-authorised as the same account. Navigating, by
contrast, is meaningless without a composition — and a screen that starts after the event still
lands in the right place, because it has no tokens and the sign-in screen is the start
destination.

## Three things that are easy to get wrong

**`start()` launches undispatched.** A default `launch` returns before its body runs, so the
subscription is registered on some later dispatch and anything published in between is dropped
and reported as accepted. `CoroutineStart.UNDISPATCHED` runs the body on the calling thread up
to its first real suspension, and `SharedFlow.collect` registers before it suspends — so the
bus has a subscriber by the time `start()` returns. `AppEventDispatcherTest` publishes on the
line after `start()`, and that test fails without the argument.

**A listener that throws would unsubscribe everyone.** The dispatcher is a single `collect`, so
an exception escaping a listener does not fail that listener — it cancels the collection, and
every listener stops receiving events for the rest of the process. Nothing crashes; the app
just stops reacting. `AppEventDispatcher.deliver` contains the failure per listener and reports
it through `CoroutineFailureReporter`, rethrowing only `CancellationException` (the scope being
torn down) and fatal throwables (which the application scope's handler escalates, as everywhere
else — see `docs/coroutine-errors.md`).

**`tryPublish`'s return value is not a delivery receipt.** `false` means a subscriber is far
enough behind to have filled the buffer. `true` means the emission was accepted, which with no
subscribers means it was accepted and thrown away. Anything that needs delivery needs a
listener, not a truthy return.

## Adding an event

1. Answer the three questions above. If any is no, it is not an app event.
2. Add the case to `AppEvent`.
3. Publish it from the one place that can tell it has happened — `publish` from a coroutine,
   `tryPublish` from a callback thread.
4. For a reaction that must not be missed: implement `AppEventListener` and add one
   `@Binds @IntoSet` to `AppEventModule`. For a reaction that needs a screen: collect
   `AppEventBus.events` with `ObserveAsEvents`.

Step 4's blind spot is worth knowing: Dagger checks that everything in the set is an
`AppEventListener` and nothing more. A `@Binds` deleted from `AppEventModule` compiles, passes
every test, and silently stops that reaction. Nothing in a JVM unit test can see the graph,
which is why `AppEventDispatcherTest` tests the dispatcher's contract against an explicit set
instead, and why a new listener is worth one instrumented check that it actually ran.

## Known gaps

- **The user cache is not invalidated on expiry.** `CachingUserRepository` keeps freshness marks
  for up to 30 seconds, so a sign-in as a different account inside that window can be served
  one stale record. Fixing it properly means reaching the cache through the decorator chain,
  which is a change to how `RepositoryModule` assembles it — a separate item, not a rider on
  this one. Room's own rows outlive a sign-out entirely, which is the larger version of the
  same question and belongs with the offline-first work in Phase 9.
- **Nothing yet publishes on user-initiated sign-out.** `GoogleSignInViewModel.signOut` clears
  the Google credential state but not this app's tokens, so the two paths are not symmetric.
  Making them symmetric is a change to what sign-out means, not to the bus.
- **The navigation listener is unverified in CI.** It lives in a composable, so it is
  `androidTest` territory, and the emulator matrix is a Phase 12 item. Everything on the
  process-lifetime side of the diagram is covered by JVM tests.
