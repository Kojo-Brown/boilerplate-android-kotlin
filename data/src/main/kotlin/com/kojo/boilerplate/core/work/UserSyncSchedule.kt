package com.kojo.boilerplate.core.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import java.util.concurrent.TimeUnit

/**
 * The schedule the background sync runs on: how often, under what conditions, and how it
 * behaves when a run does not land.
 *
 * Every value here is a WorkManager decision with a WorkManager consequence, which is why it
 * is in `:data` next to the worker rather than mirrored into `:core:domain` as a set of
 * app-owned enums. `Constraints`, `BackoffPolicy` and the flex window do not mean anything
 * without the scheduler that interprets them, and a translation layer whose only purpose was
 * to keep them off the domain's classpath would be a second vocabulary to keep in step with
 * this one. What *is* in the domain is the part that survives without WorkManager: which
 * users a background sync covers, and how many attempts it gets — see
 * `PerformBackgroundSyncUseCase`.
 *
 * `internal` because the request is not a value anything outside this package should be
 * building. `WorkManagerBackgroundSyncScheduler` is the one caller and
 * `BackgroundSyncScheduler` is what the rest of the app sees.
 */
internal object UserSyncSchedule {

    /**
     * The name the periodic sync is enqueued under, and the whole of what makes it *unique*
     * work.
     *
     * WorkManager keys unique work on this string and nothing else, so two things follow.
     * It must be stable across app versions — changing it does not rename the existing work,
     * it enqueues a second, unrelated chain beside it and leaves the old one running forever.
     * And it must not collide with another feature's unique work, since a collision silently
     * replaces one schedule with the other under `UPDATE`. Prefixing with the package is the
     * cheap insurance against a library doing the latter.
     */
    const val UNIQUE_WORK_NAME = "com.kojo.boilerplate.work.user-sync"

    /**
     * How often the sync runs.
     *
     * Six hours is a deliberate midpoint rather than a default: this app's background sync
     * refreshes one row so that a returning user sees a current account rather than a stale
     * one, which is worth a handful of wakeups a day and is not worth more. WorkManager's
     * floor is 15 minutes and anything near it should be a push message instead — a server
     * that knows when the account changed can say so, and polling four times an hour to find
     * out that it did not is the pattern this interval is chosen against.
     */
    private const val REPEAT_INTERVAL_HOURS = 6L

    /**
     * The tail of each period the run may be placed in.
     *
     * Without a flex window WorkManager may run the job at any point in the six hours, so two
     * runs can land nearly twelve hours apart (end of one period, start of the next) or
     * minutes apart. Restricting it to the last hour bounds that drift and — the reason it is
     * worth having at all — lets the system batch this wakeup with other work due in the same
     * window rather than waking the device on its own for it.
     *
     * Under an hour is not worth setting: `PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS` is
     * five minutes, but Doze and app-standby buckets already move a run by far more than
     * that, so a tighter window describes a precision the platform does not offer.
     */
    private const val FLEX_INTERVAL_HOURS = 1L

    /**
     * The first backoff delay after a run returns `retry`, doubling from there.
     *
     * With the four-attempt budget in `PerformBackgroundSyncUseCase` the attempts land at
     * roughly 0s, 30s, 60s and 120s, so a sync that is failing because the device is offline
     * gives up inside about four minutes and waits for the next period instead of climbing an
     * exponential curve for hours.
     *
     * Thirty seconds rather than WorkManager's ten-second minimum
     * (`WorkRequest.MIN_BACKOFF_MILLIS`): the failure this retry exists for is a connection
     * that is not there, and a device that was offline ten seconds ago is usually still
     * offline. The network constraint below means a retry does not even run until
     * connectivity returns, so the delay is the floor on how soon it may run, not a promise
     * that it will.
     */
    private const val INITIAL_BACKOFF_SECONDS = 30L

    /**
     * The conditions under which a run is allowed to happen at all.
     *
     * Both are the same kind of decision — do not spend the user's resources on work nobody
     * is waiting for — and both are enforced by the system rather than by the worker, which
     * is the point of expressing them as constraints: a worker that checked connectivity
     * itself would already have been woken to do the checking.
     *
     * - **[NetworkType.CONNECTED], not [NetworkType.UNMETERED].** One user row is a few
     *   hundred bytes; refusing to fetch it off Wi-Fi would mean a phone on mobile data all
     *   week never syncs at all. Unmetered is the right constraint for a payload whose size
     *   the user would notice, which this is not.
     * - **`setRequiresBatteryNotLow`.** Below the low-battery threshold the user is rationing
     *   what is left for things they asked for. This is not one of them, and it will still be
     *   there after a charge.
     *
     * Deliberately absent: `setRequiresCharging` and `setRequiresDeviceIdle`. Either would
     * mean a device that is never plugged in overnight — or never idle — silently stops
     * syncing, which is a failure mode with no signal attached. `Constraints` are a promise
     * about *when* work runs, and every one added is another way for it to run never.
     */
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * The request as it should be enqueued today.
     *
     * A function rather than a `val`: `PeriodicWorkRequest` carries a generated id, and a
     * shared instance would mean every enqueue after the first re-using the first one's — the
     * kind of state that behaves correctly until something enqueues twice.
     */
    fun request(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<UserSyncWorker>(
            repeatInterval = REPEAT_INTERVAL_HOURS,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = FLEX_INTERVAL_HOURS,
            flexTimeIntervalUnit = TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                INITIAL_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(TAG)
            .build()

    /**
     * A tag to query and cancel by, alongside the unique name.
     *
     * The two are not interchangeable and both are worth having: the name identifies *this*
     * schedule and is what makes the work unique, while a tag groups every piece of
     * background sync the app might come to have. `cancelAllWorkByTag(TAG)` at sign-out stays
     * correct when a second sync is added; `cancelUniqueWork(UNIQUE_WORK_NAME)` does not.
     */
    const val TAG = "background-sync"
}
