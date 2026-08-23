package com.kojo.boilerplate.core.telemetry

import android.util.Log
import javax.inject.Inject

/**
 * The default [RepositoryTelemetry]: writes each operation to Logcat.
 *
 * Enough to watch the data layer work during development, and deliberately not enough for
 * production — a metric that only ever reaches Logcat is a metric nobody aggregates. Replace
 * the `@Binds` in `RepositoryModule` with an adapter over the app's analytics or APM SDK; that
 * binding is the only place this class is named.
 *
 * Failures log at error level with the throwable attached so the stack trace survives; a
 * partial fan-out logs at warning, because the screen still rendered; everything else is debug,
 * so a successful refresh does not push anything out of a bug report's log buffer.
 *
 * Like `LogcatCoroutineFailureReporter`, this has no unit test: `android.util.Log` throws
 * "not mocked" under a plain JVM test, and turning on `returnDefaultValues` for one thin
 * adapter would weaken every other test in the module. What the tests cover is the decorator
 * that decides *what* to record — see `TelemetryUserRepositoryTest`.
 */
class LogcatRepositoryTelemetry @Inject constructor() : RepositoryTelemetry {

    override fun record(event: RepositoryOperationEvent) {
        val prefix = "${event.operation} took ${event.duration.inWholeMilliseconds}ms"
        when (val outcome = event.outcome) {
            is RepositoryOutcome.Succeeded -> Log.d(TAG, "$prefix: succeeded")
            is RepositoryOutcome.Cancelled -> Log.d(TAG, "$prefix: cancelled")
            is RepositoryOutcome.PartiallyFailed ->
                Log.w(TAG, "$prefix: ${outcome.succeeded} succeeded, ${outcome.failed} failed")
            is RepositoryOutcome.Failed -> Log.e(TAG, "$prefix: failed", outcome.cause)
        }
    }

    private companion object {
        // Under the 23-character limit the platform enforces on tags below API 24.
        const val TAG = "RepositoryTelemetry"
    }
}
