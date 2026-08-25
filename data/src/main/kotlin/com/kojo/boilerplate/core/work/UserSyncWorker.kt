package com.kojo.boilerplate.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kojo.boilerplate.core.domain.sync.BackgroundSyncOutcome
import com.kojo.boilerplate.core.domain.usecase.PerformBackgroundSyncUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The Android half of the background sync: a worker that WorkManager wakes on the schedule in
 * [UserSyncSchedule], hands the run to `PerformBackgroundSyncUseCase`, and translates the
 * answer back into WorkManager's vocabulary.
 *
 * There is deliberately nothing else in it. Everything a test would want to assert — which
 * users a background sync covers, what a shortfall means, when to stop retrying — is in the
 * use case, because a `ListenableWorker` cannot be constructed on a JVM: it needs a `Context`
 * and a `WorkerParameters` that only the framework builds. A decision left in here is a
 * decision that can only be checked on an emulator.
 *
 * ### Why `CoroutineWorker`
 *
 * The work is a `suspend` call. `Worker` would mean blocking a thread from WorkManager's pool
 * until the request returned, and would give cancellation nowhere to go — WorkManager
 * cancels a run whose constraints stop being met, and a blocked thread cannot notice.
 * `CoroutineWorker.doWork` runs in a scope the framework cancels for exactly that, which is
 * what makes the cancellation path in `PerformBackgroundSyncUseCase` reachable rather than
 * theoretical.
 *
 * ### Why `@HiltWorker` and not a `WorkerFactory` by hand
 *
 * A worker is constructed by WorkManager, not by the DI graph, so it cannot take an
 * `@Inject` constructor — the two extra parameters come from the framework and the rest from
 * Hilt. `@AssistedInject` is what expresses that split, and `@HiltWorker` is what generates
 * the entry in the `HiltWorkerFactory` that `BoilerplateApp` hands to WorkManager. Both
 * annotations are required and neither implies the other: without `@HiltWorker` the class
 * compiles and then fails at runtime with "Could not instantiate", because nothing told the
 * factory this worker exists.
 */
@HiltWorker
class UserSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val performBackgroundSync: PerformBackgroundSyncUseCase,
) : CoroutineWorker(appContext, workerParameters) {

    /**
     * `runAttemptCount` is WorkManager's own count of how many times this occurrence has
     * already run, and it is the only piece of scheduler state the decision needs. Passing it
     * down rather than letting the use case ask for it is what keeps the use case free of the
     * framework — see its KDoc.
     *
     * The `when` is exhaustive over a closed enum on purpose: a fourth outcome added to
     * [BackgroundSyncOutcome] fails this compile rather than falling through to a default and
     * being silently treated as one of the other three.
     */
    override suspend fun doWork(): Result = when (performBackgroundSync(runAttemptCount)) {
        BackgroundSyncOutcome.SUCCESS -> Result.success()
        BackgroundSyncOutcome.RETRY -> Result.retry()
        BackgroundSyncOutcome.FAILURE -> Result.failure()
    }
}
