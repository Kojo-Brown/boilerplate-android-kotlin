package com.kojo.boilerplate.core.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.kojo.boilerplate.core.domain.sync.BackgroundSyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BackgroundSyncScheduler] over WorkManager.
 *
 * The whole of the implementation is choosing an existing-work policy, and that choice is the
 * only interesting decision in the file.
 *
 * ### Why `UPDATE` rather than `KEEP`
 *
 * Both make the enqueue idempotent, which is what the interface promises and what makes it
 * safe to call from every process start. They differ on what happens when the *schedule*
 * changes — a different interval, an added constraint, a new backoff — in a later version of
 * the app:
 *
 * - `KEEP` leaves the enqueued work exactly as it was. An install that has run the sync once
 *   keeps the old schedule for the life of the install, so a fix shipped in an update reaches
 *   new installs only. That failure is completely silent: the code says six hours, the device
 *   does whatever the version it first ran said, and nothing anywhere disagrees.
 * - `UPDATE` replaces the work's specification while keeping its identity — same id, same
 *   run history, no cancel-and-re-enqueue. The next occurrence uses the new schedule.
 *
 * `UPDATE` also avoids the trap that `REPLACE` (its predecessor, deprecated for periodic
 * work) had: replacing cancelled the existing work and enqueued fresh, which reset the
 * period. On an app that calls this every process start, that meant a device relaunching the
 * app often enough could restart the six-hour clock before the sync ever came due — a
 * schedule that never fires, from a call site that looks entirely reasonable.
 *
 * The cost is real and small: an update is a database write on every process start rather
 * than a lookup. WorkManager performs it on its own executor — see the interface's note about
 * not blocking — and it is one row.
 *
 * ### Why `@Singleton`
 *
 * Not for state — there is none here beyond the injected [WorkManager], which is a singleton
 * in its own right. It is so that the binding is not re-created per injection point, and so
 * that the scheduler and the thing it schedules through have the same lifetime as the process
 * that owns them.
 */
@Singleton
class WorkManagerBackgroundSyncScheduler @Inject constructor(
    private val workManager: WorkManager,
) : BackgroundSyncScheduler {

    override fun ensurePeriodicSyncScheduled() {
        workManager.enqueueUniquePeriodicWork(
            UserSyncSchedule.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            UserSyncSchedule.request(),
        )
    }

    /**
     * Cancels by tag rather than by unique name.
     *
     * The two would do the same thing today — one schedule carries the tag — and they stop
     * doing the same thing the moment a second background sync is added. "Stop syncing in the
     * background" is a statement about the group, so cancelling the group is the version of
     * it that stays true; cancelling the one name would quietly leave the new sync running.
     */
    override fun cancelPeriodicSync() {
        workManager.cancelAllWorkByTag(UserSyncSchedule.TAG)
    }
}
