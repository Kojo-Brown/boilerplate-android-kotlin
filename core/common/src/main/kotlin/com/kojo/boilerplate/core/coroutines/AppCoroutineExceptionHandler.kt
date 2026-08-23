package com.kojo.boilerplate.core.coroutines

import javax.inject.Inject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName

/**
 * True for throwables after which the process cannot be trusted to keep running.
 *
 * A [VirtualMachineError] means the VM itself is out of a resource it needs — heap, stack,
 * or a class it failed to load — and a [LinkageError] means the code that is running is not
 * the code that was compiled. Neither is a failure the app can report and carry on from, so
 * they are the one case [AppCoroutineExceptionHandler] refuses to absorb.
 *
 * This is deliberately narrower than "is an [Error]": `AssertionError` is an ordinary bug
 * and stopping the process over one would be worse than reporting it.
 */
val Throwable.isFatal: Boolean
    get() = this is VirtualMachineError || this is LinkageError

/**
 * The app's [CoroutineExceptionHandler]: the last thing that runs when a coroutine fails and
 * there is nobody left to hand the failure to.
 *
 * ## When it is consulted
 *
 * A handler is a *last resort*, not an error-handling strategy, and the coroutines machinery
 * only reaches it once every other route has been ruled out:
 *
 * | Situation | Handler consulted? |
 * |---|---|
 * | `scope.launch { }` fails, handler in the scope's context | yes |
 * | `launch { }` inside a `supervisorScope` fails | yes |
 * | `scope.async { }` fails | no — the failure is held by the `Deferred` until `await()` |
 * | `launch(handler) { }` nested in another coroutine | no — the parent handles it, and this handler is ignored |
 * | The coroutine is cancelled | no — cancellation is not a failure |
 *
 * The third and fourth rows are the two mistakes worth knowing. A handler installed on a
 * child of a coroutine has no effect at all: the child's failure goes to its parent, and it
 * is the *root* coroutine's context that is consulted at the end of that chain. Install it
 * on the [kotlinx.coroutines.CoroutineScope], which is what `CoroutineErrorModule` does for
 * the application scope.
 *
 * ## What it does with the failure
 *
 * It reports it and stops. It cannot recover — by the time it runs, the coroutine has
 * already failed and its scope has already been cancelled — so anything beyond reporting
 * would be pretending the work can still be finished.
 *
 * ## Its relationship to `safeCall`
 *
 * [com.kojo.boilerplate.core.common.safeCall] is the other half of the answer and covers the
 * common case: work whose caller is still there to be handed a `Result`. This handler covers
 * what is left — fire-and-forget work on the application scope, and bugs nobody wrapped —
 * which without it reaches the thread's uncaught handler and crashes the app. See
 * `docs/coroutine-errors.md` for which one to reach for.
 */
class AppCoroutineExceptionHandler @Inject constructor(
    private val reporter: CoroutineFailureReporter,
) : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {

    @Suppress("TooGenericExceptionCaught")
    // Catching Throwable is the point: a handler that throws is worse than one that does
    // nothing, because kotlinx replaces the original failure with a wrapper describing the
    // handler, and the crash report then describes the reporter instead of the bug. Nothing
    // is swallowed — the reporter's own failure is attached to the exception it was trying
    // to report, and escalates the same way if it is fatal.
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        // Defensive rather than reachable: the machinery treats a CancellationException as
        // the coroutine completing as cancelled and never routes it here. It matters for a
        // handler invoked directly, which is how a scope's owner reports its own teardown.
        if (exception is CancellationException) return

        val reportingFailure = try {
            reporter.report(context[CoroutineName]?.name, exception)
            null
        } catch (failure: Throwable) {
            exception.addSuppressed(failure)
            failure
        }

        if (exception.isFatal || reportingFailure?.isFatal == true) {
            escalate(exception)
        }
    }

    /**
     * Hands [exception] to the uncaught-exception handler of the thread the coroutine failed
     * on, which is where it would have gone had no [CoroutineExceptionHandler] been installed
     * at all — on Android, the platform's crash handler.
     *
     * Absorbing a fatal error would leave the app running on a VM that has already told us it
     * cannot continue, producing a second, more confusing crash somewhere unrelated. Reporting
     * happens first, so the report is filed before the process goes.
     */
    private fun escalate(exception: Throwable) {
        val thread = Thread.currentThread()
        thread.uncaughtExceptionHandler.uncaughtException(thread, exception)
    }
}
