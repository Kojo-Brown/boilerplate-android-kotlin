package com.kojo.boilerplate.core.work

import android.annotation.SuppressLint
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three things the spec item names — constraints, backoff, unique work — asserted on the
 * request that is actually enqueued.
 *
 * ### Why a mock rather than `WorkManagerTestInitHelper`
 *
 * WorkManager's own test harness needs a `Context` and initialises a real database, so it
 * belongs to the instrumented source set, which this repository has no CI runner for yet
 * (Phase 12, "Instrumented tests on an emulator matrix"). What *can* be checked on a JVM is
 * the part that has actually been wrong before in this kind of code: the request handed to
 * `enqueueUniquePeriodicWork`, and the policy it is handed under. `WorkSpec` is plain data
 * and readable without any of the framework running, so capturing the argument gets at every
 * value in `UserSyncSchedule` without pretending to run a worker.
 *
 * What this therefore does not cover, and the emulator would: that a run actually happens,
 * that Hilt can construct `UserSyncWorker`, and that the constraints are honoured by the
 * platform. `docs/background-sync.md` keeps that division written down.
 *
 * ### Why the values are asserted literally
 *
 * Reading them back out of `UserSyncSchedule` would make this test pass for any schedule at
 * all — including an empty `Constraints` — since both sides would move together. The numbers
 * are duplicated on purpose: changing the interval should fail here and make someone confirm
 * the change was meant, in the same way `SolidContractTest` fails on a repaired finding.
 *
 * ### The suppression
 *
 * `WorkRequest.workSpec` and `WorkRequest.tags` are `@RestrictTo(LIBRARY_GROUP)`: WorkManager
 * does not want app code depending on the shape of a request it built, and for production
 * code that is right — there is nothing an app should do with a `WorkSpec`. A test asserting
 * that the request carries the schedule it was configured with is the case the restriction is
 * not aimed at, and WorkManager publishes no other way to read one back. Lint does not analyse
 * unit test sources at AGP's default (`checkTestSources` is off, and this repository sets no
 * lint configuration), so this annotation is belt and braces rather than the thing making the
 * build pass — it is here so that turning `checkTestSources` on stays a one-line change
 * instead of a red build with a puzzling cause.
 */
@SuppressLint("RestrictedApi")
class WorkManagerBackgroundSyncSchedulerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val scheduler = WorkManagerBackgroundSyncScheduler(workManager)

    private fun enqueuedRequest(): PeriodicWorkRequest {
        val request = slot<PeriodicWorkRequest>()
        every {
            workManager.enqueueUniquePeriodicWork(
                any<String>(),
                any<ExistingPeriodicWorkPolicy>(),
                capture(request),
            )
        } returns mockk(relaxed = true)

        scheduler.ensurePeriodicSyncScheduled()

        return request.captured
    }

    /**
     * Unique work, in both halves: the name it is keyed on, and the policy that decides what
     * happens when it is already there.
     *
     * `UPDATE` is the assertion with teeth. `KEEP` would freeze the schedule on every install
     * that has ever run the sync — see the KDoc on the scheduler — and the two constants are
     * a single token apart.
     */
    @Test
    fun `it enqueues under a stable unique name, updating an existing schedule`() {
        val name = slot<String>()
        val policy = slot<ExistingPeriodicWorkPolicy>()
        every {
            workManager.enqueueUniquePeriodicWork(capture(name), capture(policy), any<PeriodicWorkRequest>())
        } returns mockk(relaxed = true)

        scheduler.ensurePeriodicSyncScheduled()

        assertEquals("com.kojo.boilerplate.work.user-sync", name.captured)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, policy.captured)
    }

    /** Called on every process start, so enqueuing twice must still describe one schedule. */
    @Test
    fun `enqueuing twice sends the same unique name both times`() {
        val names = mutableListOf<String>()
        every {
            workManager.enqueueUniquePeriodicWork(
                capture(names),
                any<ExistingPeriodicWorkPolicy>(),
                any<PeriodicWorkRequest>(),
            )
        } returns mockk(relaxed = true)

        scheduler.ensurePeriodicSyncScheduled()
        scheduler.ensurePeriodicSyncScheduled()

        assertEquals(listOf(UserSyncSchedule.UNIQUE_WORK_NAME, UserSyncSchedule.UNIQUE_WORK_NAME), names)
    }

    @Test
    fun `it repeats every six hours with a one-hour flex window`() {
        val spec = enqueuedRequest().workSpec

        assertTrue(spec.isPeriodic, "the request must be periodic, not one-time")
        assertEquals(TimeUnit.HOURS.toMillis(6), spec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(1), spec.flexDuration)
    }

    /**
     * Connected rather than unmetered, and battery-not-low rather than charging. Both are
     * choices with a "syncs never" failure mode on the other side of them — the scheduler's
     * KDoc says which and why — so both are pinned rather than left to whatever the builder
     * defaults to.
     */
    @Test
    fun `it runs only on a connection and not on a low battery`() {
        val constraints = enqueuedRequest().workSpec.constraints

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow(), "a background refresh must not run on a low battery")
        assertFalse(constraints.requiresCharging(), "requiring a charger means a phone never plugged in never syncs")
        assertFalse(constraints.requiresDeviceIdle(), "requiring idle means a phone in constant use never syncs")
    }

    /**
     * Exponential from thirty seconds. Paired with the four-attempt budget in
     * `PerformBackgroundSyncUseCase`, which is the half of the retry behaviour this request
     * does not carry — neither number means much read on its own.
     */
    @Test
    fun `it backs off exponentially from thirty seconds`() {
        val spec = enqueuedRequest().workSpec

        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), spec.backoffDelayDuration)
    }

    /** The tag is what makes a group-wide cancel possible; the request has to carry it. */
    @Test
    fun `it is tagged as background sync`() {
        assertTrue(
            UserSyncSchedule.TAG in enqueuedRequest().tags,
            "the request must carry ${UserSyncSchedule.TAG}, which is what cancelPeriodicSync cancels by",
        )
    }

    /**
     * Cancelling by tag rather than by name, so that a second background sync added later is
     * also stopped. Asserted as "the tag call happened", not merely "some cancel happened".
     */
    @Test
    fun `cancelling stops every background sync rather than just this one`() {
        scheduler.cancelPeriodicSync()

        verify(exactly = 1) { workManager.cancelAllWorkByTag(UserSyncSchedule.TAG) }
    }

    /** Each enqueue must carry its own request id; a shared instance would reuse the first. */
    @Test
    fun `each enqueue builds a fresh request`() {
        val requests = mutableListOf<PeriodicWorkRequest>()
        every {
            workManager.enqueueUniquePeriodicWork(
                any<String>(),
                any<ExistingPeriodicWorkPolicy>(),
                capture(requests),
            )
        } returns mockk(relaxed = true)

        scheduler.ensurePeriodicSyncScheduled()
        scheduler.ensurePeriodicSyncScheduled()

        assertEquals(2, requests.map { it.id }.distinct().size, "two enqueues produced the same request id")
    }

    /** The schedule must name the worker that performs it, not some other `ListenableWorker`. */
    @Test
    fun `it schedules the user sync worker`() {
        assertEquals(UserSyncWorker::class.java.name, enqueuedRequest().workSpec.workerClassName)
    }
}
