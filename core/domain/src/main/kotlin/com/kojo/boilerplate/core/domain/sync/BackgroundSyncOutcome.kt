package com.kojo.boilerplate.core.domain.sync

/**
 * How a background sync ended, in the three answers a scheduler can act on.
 *
 * This is `androidx.work.ListenableWorker.Result` with the framework taken out of it. The
 * three constants map one-to-one onto `Result.success()`, `Result.retry()` and
 * `Result.failure()`, and `UserSyncWorker` is the single place that translation happens.
 *
 * The indirection buys one specific thing: the *decision* — did enough of the sync land, and
 * if not is another attempt worth making — is application policy, and policy belongs in a
 * layer that compiles without the Android framework and can be tested on a plain JVM.
 * `ListenableWorker.Result` cannot be constructed off-device, its instances have no usable
 * `equals`, and `DomainLayerContractTest` fails the build on any `androidx/` reference in this
 * package anyway. An enum is what is left, and it is enough: a worker's contract with
 * WorkManager really is a three-way choice.
 *
 * It deliberately carries no payload. What was fetched is already in the database — see
 * `UserRepository.syncUsers` — and what failed is not something the scheduler can do anything
 * with beyond deciding whether to come back.
 */
enum class BackgroundSyncOutcome {

    /**
     * Everything the sync asked for arrived, or there was nothing to ask for.
     *
     * The periodic work stays on its schedule and runs again at the next interval.
     */
    SUCCESS,

    /**
     * Some or all of it did not arrive, and the failure is the kind another attempt could
     * fix — an offline moment, a 5xx, a timeout.
     *
     * The scheduler backs off and re-runs this occurrence. Note what this is *not*: it is not
     * the next periodic run. A retried occurrence runs on the backoff delay, ahead of the
     * schedule, and the periodic cadence resumes once it settles.
     */
    RETRY,

    /**
     * It did not arrive and retrying is no longer worth the battery.
     *
     * Today that means only "the attempt budget is spent" — see
     * [com.kojo.boilerplate.core.domain.usecase.PerformBackgroundSyncUseCase]. A permanently
     * failing sync that keeps returning [RETRY] would be woken on an exponential backoff
     * forever, which is how a background job turns into a battery complaint.
     *
     * Giving up drops *this occurrence*, not the schedule: periodic work whose run returns
     * failure is still enqueued and still runs at the next interval, on a fresh attempt
     * count. So this is "stop hammering now", not "stop syncing".
     */
    FAILURE,
}
