package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.common.safeCall
import com.kojo.boilerplate.core.domain.model.RefreshOutcome
import com.kojo.boilerplate.core.domain.sync.BackgroundSyncOutcome
import com.kojo.boilerplate.core.domain.sync.SyncMode
import com.kojo.boilerplate.core.domain.sync.SyncStrategyFactory
import javax.inject.Inject

/**
 * The body of the background sync: what a worker that woke up on its own actually does, and
 * whether it is worth coming back.
 *
 * `UserSyncWorker` is the Android half of this — a `CoroutineWorker` that hands over its
 * attempt count and turns the answer into a `ListenableWorker.Result`. Everything that is a
 * decision rather than a framework hand-off is here, for the reason
 * `docs/clean-architecture.md` gives: a `Worker` cannot be constructed off-device, so policy
 * left inside one is policy no JVM test can reach.
 *
 * ### Decision 1: a background sync covers the signed-in user
 *
 * The mode is not a parameter. It is the policy this use case exists to own, and it is the
 * mirror image of the one [RefreshVisibleUsersUseCase] owns:
 *
 * > **A person tapping refresh covers what the screen is showing. A worker that woke up on a
 * > timer covers the account, and nothing else.**
 *
 * A worker has no screen. Whatever list was last open is not what the user is looking at —
 * they are not looking at anything — so sizing a background fetch by it means spending a
 * metered connection and a wakelock on rows chosen by an app state that expired hours ago.
 * [SyncMode.CURRENT_USER] is one request whatever happened before, which is why
 * `CurrentUserSyncStrategy` was written for this item and has been bound, tested and
 * unreached since. This is its caller.
 *
 * ### Decision 2: a partial failure is a retry, and an exception is too
 *
 * [RefreshOutcome] reports a shortfall rather than a pass/fail, so "did this work" needs
 * answering. It is answered strictly: anything that did not arrive is worth another attempt.
 * A screen showing eight of ten users is mostly right and the tenth can wait for the pull to
 * refresh; a background sync has no one waiting and no cheaper moment to try again in, so the
 * bar for "done" is everything.
 *
 * A *thrown* failure is treated the same way, and that is the part worth being deliberate
 * about. WorkManager reads an exception escaping `doWork()` as `Result.failure()` — the
 * occurrence is dropped and never retried, whatever the backoff policy says — so an offline
 * moment surfacing as an `IOException` would silently cost a sync cycle. [safeCall] catches
 * it and this maps it to the same budget-aware retry as a counted shortfall.
 *
 * `safeCall` rethrows `CancellationException` rather than reporting it as a failure, which
 * matters more here than in a `ViewModel`: WorkManager cancels a worker's coroutine when a
 * constraint stops being met mid-run, and a cancelled run reported as a failure would burn an
 * attempt for something that was not a failure at all. See `docs/structured-concurrency.md`.
 *
 * What is lost with the exception is the exception. Nothing in this app reports it anywhere —
 * `CoroutineFailureReporter` is scoped to uncaught coroutine failures, which this is not, and
 * the crash-reporting seam that would take it is a Phase 11 item. So a background sync that
 * fails for a novel reason is visible as a retried worker and nothing more.
 * `docs/background-sync.md` carries that as a known gap rather than leaving it to be found.
 *
 * ### Decision 3: the attempt budget is policy; the delay curve is not
 *
 * [MAX_ATTEMPTS] lives here because "how many times is this worth trying" is a product
 * question — it trades freshness against battery — and because it is the half of the retry
 * behaviour a test can assert on. *How long* to wait between those attempts is the
 * scheduler's mechanism, expressed as `setBackoffCriteria` in `UserSyncSchedule`, and is not
 * something this layer could implement even if it wanted to: the process is not running
 * between attempts.
 *
 * The two are described together in `docs/background-sync.md`, because reading either alone
 * gives the wrong picture of how long a failing sync keeps trying for.
 */
class PerformBackgroundSyncUseCase @Inject constructor(
    private val syncStrategies: SyncStrategyFactory,
) {

    /**
     * Runs one attempt and says what should happen next.
     *
     * @param attempt how many times this occurrence has already been retried, which is
     *   `ListenableWorker.runAttemptCount` — **zero on the first run**, not one. Passing the
     *   count as a parameter rather than reading it from anywhere is what keeps this class
     *   free of the framework that owns it.
     * @throws IllegalArgumentException if [attempt] is negative, which would mean the caller
     *   is not passing `runAttemptCount`.
     */
    suspend operator fun invoke(attempt: Int): BackgroundSyncOutcome {
        require(attempt >= 0) {
            "attempt is ListenableWorker.runAttemptCount and starts at 0, was $attempt"
        }

        val outcome = safeCall {
            syncStrategies.create(BACKGROUND_SYNC_MODE).sync(NO_CALLER_SELECTION)
        }

        return outcome.fold(
            onSuccess = { refresh ->
                if (refresh.failed == 0) BackgroundSyncOutcome.SUCCESS else retryOrGiveUp(attempt)
            },
            onFailure = { retryOrGiveUp(attempt) },
        )
    }

    /**
     * Whether the attempt budget has anything left in it.
     *
     * [attempt] counts the runs *before* this one, so the run that just finished is
     * `attempt + 1` of [MAX_ATTEMPTS]. Getting that off by one is the difference between
     * three attempts and four, and it is invisible without a test that names the boundary —
     * `PerformBackgroundSyncUseCaseTest` names both sides of it.
     */
    private fun retryOrGiveUp(attempt: Int): BackgroundSyncOutcome =
        if (attempt + 1 >= MAX_ATTEMPTS) BackgroundSyncOutcome.FAILURE else BackgroundSyncOutcome.RETRY

    private companion object {

        /**
         * The mode a background sync always uses. See "Decision 1" in the class KDoc.
         */
        val BACKGROUND_SYNC_MODE = SyncMode.CURRENT_USER

        /**
         * [com.kojo.boilerplate.core.domain.sync.SyncStrategy.sync] takes the ids the caller
         * is interested in, and a worker is
         * interested in none: [SyncMode.CURRENT_USER] does not read the parameter, and
         * handing it a stale list would be a lie about what this asked for even though
         * nothing would act on it.
         */
        val NO_CALLER_SELECTION = emptyList<String>()

        /**
         * How many runs one occurrence gets before the sync is abandoned until the next
         * period.
         *
         * Four, paired with the 30-second exponential backoff in `UserSyncSchedule`: the
         * attempts land at roughly 0s, 30s, 60s and 120s, so a failing sync stops within
         * about four minutes rather than being woken on a doubling delay for the rest of the
         * day. Against a six-hour period that costs almost nothing in freshness — the next
         * scheduled run is never far off, and it starts with a fresh budget.
         */
        const val MAX_ATTEMPTS = 4
    }
}
