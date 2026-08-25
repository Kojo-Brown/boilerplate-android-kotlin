package com.kojo.boilerplate.core.domain.sync

/**
 * Keeps the app's periodic background sync registered with whatever schedules work on this
 * platform.
 *
 * ## Why this is an interface in the domain layer
 *
 * The caller is `BoilerplateApp.onCreate`, and what it wants to say is "this app keeps the
 * signed-in account fresh in the background". That sentence contains no WorkManager. Every
 * noun that does — `Constraints`, `BackoffPolicy`, `PeriodicWorkRequest`,
 * `ExistingPeriodicWorkPolicy` — belongs to the mechanism and lives with the implementation
 * in `:data`, the same way `UserRepository` keeps Room and Retrofit out of the layers above
 * it.
 *
 * That split is not decoration here. `:app` may not depend on `:data` for anything it names
 * — `checkModuleDependencies` allows the edge only so `:data`'s Hilt modules reach the
 * component, and the module comment says so — so an `Application` that built a
 * `PeriodicWorkRequest` itself would either need that rule relaxed or would put scheduling
 * policy in the module least able to test it.
 *
 * ## What an implementation owes the caller
 *
 * **Idempotence.** [ensurePeriodicSyncScheduled] is called on every process start, which on
 * Android is not once per install but every time the system decides to bring the app back.
 * Calling it twice must leave one piece of work scheduled, not two — that is what *unique*
 * work means and why the method is named for the state it guarantees rather than for the act
 * of enqueuing.
 *
 * **Not blocking.** It is called from `Application.onCreate`, on the main thread, before the
 * first frame. Enqueuing is a database write that WorkManager performs on its own executor;
 * an implementation that waited on the result would be doing disk I/O in app startup.
 */
interface BackgroundSyncScheduler {

    /**
     * Registers the periodic sync if it is not registered, and brings it up to date with the
     * schedule this build declares if it is.
     *
     * Safe to call repeatedly and from every process start; see the class KDoc.
     */
    fun ensurePeriodicSyncScheduled()

    /**
     * Removes the periodic sync, cancelling a run already in flight.
     *
     * The counterpart to [ensurePeriodicSyncScheduled], for the two moments an app genuinely
     * has to stop: sign-out, and a user turning background refresh off. Neither exists in
     * this boilerplate yet — nothing calls this outside its tests, and
     * `docs/background-sync.md` records that rather than leaving it to be discovered. It is
     * declared because a scheduler that can only ever add work is one half of an interface,
     * and because the alternative at sign-out is a worker that keeps waking up to refresh an
     * account nobody is signed in to.
     */
    fun cancelPeriodicSync()
}
