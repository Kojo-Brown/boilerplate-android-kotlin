package com.kojo.boilerplate

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kojo.boilerplate.core.domain.sync.BackgroundSyncScheduler
import com.kojo.boilerplate.core.event.AppEventDispatcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BoilerplateApp : Application(), Configuration.Provider {

    @Inject
    lateinit var appEventDispatcher: AppEventDispatcher

    /**
     * The factory that knows how to build a worker with constructor dependencies.
     *
     * WorkManager instantiates workers itself, long after the graph was built and often in a
     * process the user never opened, so a worker cannot be `@Inject`-constructed like anything
     * else. `@HiltWorker` generates an entry in this factory per worker; handing it over below
     * is what connects the two.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * Registers the periodic background sync, and does not care what performs it.
     *
     * `:app` may not name a type from `:data` — see the dependency comment in this module's
     * build file — so this is the domain-layer interface, with the WorkManager request behind
     * it in `:data`. That is also what keeps this class testable in principle: the only
     * scheduling detail here is *when* it is called.
     */
    @Inject
    lateinit var backgroundSyncScheduler: BackgroundSyncScheduler

    /**
     * The configuration WorkManager reads when it initialises.
     *
     * This is a `val`, not a function, and it is read *before* [onCreate] runs: WorkManager's
     * on-demand initialisation asks the `Application` for its configuration from inside
     * `WorkManager.getInstance`, which can happen the first time anything touches WorkManager
     * in the process. Hilt injects an `Application`'s fields during `super.onCreate()`, so
     * [workerFactory] is set by then in every path the app itself takes.
     *
     * On-demand initialisation is not optional here, and it is the half of this that is easy
     * to leave undone. WorkManager's own manifest contributes an `androidx.startup` provider
     * that initialises it with the *default* worker factory, which cannot build a worker that
     * takes dependencies. This class only gets asked for a configuration once that provider is
     * removed — which `AndroidManifest.xml` does, with the reasoning next to it.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Subscribes the app-wide event listeners before anything can publish, and makes sure the
     * background sync is on the schedule this build declares.
     *
     * Three orderings matter here and each is easy to get backwards:
     *
     * - `super.onCreate()` first. Hilt injects an `Application`'s fields from inside it, so
     *   touching [appEventDispatcher] above this line throws
     *   `UninitializedPropertyAccessException` — and only on a device, since nothing in a JVM
     *   unit test runs this method.
     * - The subscription before the first publisher exists. The only publisher is
     *   [com.kojo.boilerplate.core.network.TokenAuthenticator], which cannot run until
     *   something makes a request, which cannot happen before the first Activity — well after
     *   this. Starting here rather than lazily from the UI is what closes the window in which
     *   a `SharedFlow` with no subscriber silently discards what it is given.
     * - The sync scheduled from `Application.onCreate` rather than from an Activity. The
     *   process is started for reasons that never show a screen — a broadcast, a content
     *   provider, WorkManager itself — and scheduling from a screen would mean an app whose
     *   background refresh depends on having been opened recently, which is the opposite of
     *   what it is for. `ensurePeriodicSyncScheduled` is idempotent precisely so that it can
     *   live on a path that runs this often; see its KDoc.
     */
    override fun onCreate() {
        super.onCreate()
        appEventDispatcher.start()
        backgroundSyncScheduler.ensurePeriodicSyncScheduled()
    }
}
