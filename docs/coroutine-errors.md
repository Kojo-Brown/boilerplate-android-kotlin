# Coroutine error handling

Two mechanisms, and which one a given failure belongs to.

Every claim on this page is pinned by a test in
[`AppCoroutineExceptionHandlerTest`](../app/src/test/kotlin/com/kojo/boilerplate/core/coroutines/AppCoroutineExceptionHandlerTest.kt)
and [`ResultTest`](../app/src/test/kotlin/com/kojo/boilerplate/core/common/ResultTest.kt),
so a coroutines upgrade that changes one of these semantics fails the build rather than
quietly invalidating the guidance.

## The split

The question is not "how do I handle errors in coroutines" but **is there still a caller?**

| | Someone is waiting for the result | Nobody is |
|---|---|---|
| Mechanism | [`safeCall`](../app/src/main/kotlin/com/kojo/boilerplate/core/common/Result.kt) | [`AppCoroutineExceptionHandler`](../app/src/main/kotlin/com/kojo/boilerplate/core/coroutines/AppCoroutineExceptionHandler.kt) |
| Failure becomes | a `Result.failure` the caller renders | a report, and nothing else |
| Typical caller | a repository method a ViewModel awaits | fire-and-forget work on the application scope |
| Can it recover? | yes — that is the point | no — the coroutine has already failed |

`safeCall` is the one you should be reaching for. The handler exists for what is left over:
work with no caller, and bugs nobody wrapped. A codebase where the handler fires often has a
`safeCall` missing somewhere.

## `safeCall` — turn a failure into a value

```kotlin
suspend fun user(id: String): Result<User> = safeCall { api.getUser(id) }
```

It is `runCatching` with the cancellation put back on its way. Plain `runCatching` catches
`CancellationException` like any other throwable, so a coroutine cancelled mid-call completes
*successfully*, its parent stops waiting for a cancellation that never arrives, and the UI
renders an error for a screen the user has already left. See
[structured concurrency](./structured-concurrency.md) for the full set of cancellation rules.

`Result` then flows to the UI through `toUiState()` and `toUiStateFlow()`.

## `AppCoroutineExceptionHandler` — the last resort

A handler is reached only after every other route has been ruled out, and the rules for when
that happens are the part worth knowing:

| Situation | Handler consulted? |
|---|---|
| `scope.launch { }` fails, handler in the scope's context | yes |
| `launch { }` inside a `supervisorScope` fails | yes |
| `scope.async { }` fails | no — the `Deferred` holds it until `await()` |
| `launch(handler) { }` nested in another coroutine | no — the parent handles it |
| The coroutine is cancelled | no — cancellation is not a failure |

Rows three and four are the two mistakes. Both look like error handling and neither is:

```kotlin
scope.launch {
    launch(handler) { risky() }   // handler ignored: not a root coroutine
}
scope.async { risky() }           // nothing reports this unless someone await()s it
```

Install the handler on the **scope**, which is what `CoroutineErrorModule` does, and start
work you need a result from with `async` + `await()` so the failure has somewhere to go.

### What it does

1. Ignores cancellation.
2. Hands the failure and the coroutine's name to a
   [`CoroutineFailureReporter`](../app/src/main/kotlin/com/kojo/boilerplate/core/coroutines/CoroutineFailureReporter.kt).
3. For a **fatal** error — `VirtualMachineError` or `LinkageError` — passes it on to the
   thread's uncaught-exception handler after reporting, so the process still dies. Absorbing
   an `OutOfMemoryError` only buys a second, more confusing crash somewhere unrelated. An
   `AssertionError` is not in that set: it is an ordinary bug, and killing the process over
   one would be worse than reporting it.

It never throws. A handler that throws is worse than one that does nothing, because
kotlinx.coroutines replaces the original failure with a wrapper about the handler and the
crash report then describes the reporter instead of the bug. A reporter that fails has its
own failure attached to the original with `addSuppressed`.

### Sending failures somewhere real

`LogcatCoroutineFailureReporter` is the default and is deliberately not production-grade — a
crash that only ever reaches Logcat is a crash nobody sees. Point it at a crash reporter by
changing one binding:

```kotlin
@Binds
@Singleton
abstract fun bindCoroutineFailureReporter(impl: CrashlyticsReporter): CoroutineFailureReporter
```

## The application scope

`@ApplicationScope CoroutineScope` is a `SupervisorJob` carrying the handler, on
`Dispatchers.Default`, that lives as long as the process:

```kotlin
class SyncTrigger @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun onLogout() = scope.launch { tokenStore.clear() }
}
```

It is for work that must outlive the screen that started it, where `viewModelScope` would
cancel it mid-flight. The supervisor is what makes the handler necessary: under it a failing
child has no parent to report to, so without a handler in the context its exception would go
straight to the thread's uncaught handler and take the app down.

It is not a general escape from lifecycle scoping. Work started there has no owner watching
it, so keep it short, bounded, and something the user will not wait on. Anything long or
retryable belongs in WorkManager, which survives process death.

## In a ViewModel

Neither mechanism is what a ViewModel normally needs. `viewModelScope` is cancelled with the
screen, and the failure has a caller — the UI — so it belongs in state:

```kotlin
fun load() = viewModelScope.launch {
    _uiState.value = safeCall { repository.user(id) }.toUiState()
}
```

Reach for the handler in a ViewModel only for work you deliberately do not want cancelled
when the screen goes away — and then start it on `@ApplicationScope`, not `viewModelScope`,
because the point is to outlive the screen.
