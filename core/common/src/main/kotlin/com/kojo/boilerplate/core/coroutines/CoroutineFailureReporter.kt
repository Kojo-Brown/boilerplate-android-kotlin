package com.kojo.boilerplate.core.coroutines

import android.util.Log
import javax.inject.Inject

/**
 * Where an uncaught coroutine failure is sent once [AppCoroutineExceptionHandler] has caught it.
 *
 * This is the seam between the handler — whose behaviour is a coroutines question and is
 * pinned by tests — and the destination, which is a product decision that changes per app:
 * Logcat in this boilerplate, Crashlytics or Sentry in a real one. Keeping them apart means
 * swapping the destination is one `@Binds` and needs no change to the handler, and that the
 * handler's tests do not need a logging framework standing behind them.
 *
 * The coroutine's name is passed rather than its whole [kotlin.coroutines.CoroutineContext]:
 * it is the only part of the context a crash report can use, and a narrow parameter keeps a
 * reporting SDK adapter from depending on kotlinx.coroutines at all.
 *
 * Implementations must not throw. If one does anyway, the handler attaches the failure to
 * the original exception rather than letting it escape — but it will not be reported
 * anywhere, because the thing that reports has just failed.
 */
fun interface CoroutineFailureReporter {

    /**
     * Records [failure], which ended a coroutine that had no caller left to return it to.
     *
     * @param coroutineName the [kotlinx.coroutines.CoroutineName] of the failing coroutine,
     *   or `null` when it was started without one.
     */
    fun report(coroutineName: String?, failure: Throwable)
}

/**
 * The default [CoroutineFailureReporter]: writes the failure to Logcat at error level.
 *
 * Enough for a boilerplate and for local debugging, and deliberately not enough for
 * production — a crash that only ever reaches Logcat is a crash nobody sees. Replace the
 * `@Binds` in `CoroutineErrorModule` with an adapter over the app's crash reporter.
 */
class LogcatCoroutineFailureReporter @Inject constructor() : CoroutineFailureReporter {

    override fun report(coroutineName: String?, failure: Throwable) {
        Log.e(TAG, "Uncaught failure in coroutine ${coroutineName ?: "<unnamed>"}", failure)
    }

    private companion object {
        // Under the 23-character limit the platform enforces on tags below API 24.
        const val TAG = "CoroutineFailure"
    }
}
