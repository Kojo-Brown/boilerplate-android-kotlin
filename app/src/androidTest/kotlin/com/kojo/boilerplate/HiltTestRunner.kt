package com.kojo.boilerplate

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * The instrumentation runner every suite in this module runs under, and the one thing that makes
 * a Hilt test possible at all.
 *
 * ## What it replaces, and why nothing else can
 *
 * `am instrument` starts the application the merged manifest names, which is `BoilerplateApp` —
 * `@HiltAndroidApp`, so its component is the *production* graph: the real `NetworkModule` with
 * its `OkHttpClient` and its certificate pinner, and the real `DatabaseModule` with a SQLite
 * file at `boilerplate.db`. `@TestInstallIn` has no effect on that graph; replacements are read
 * when the component is built, and `BoilerplateApp` builds the one Hilt generated for the
 * *application*. A `@HiltAndroidTest` running under it fails outright, with Hilt reporting that
 * the application is not a `HiltTestApplication`.
 *
 * [HiltTestApplication] is the one that builds a test component, and the only hook for
 * substituting an `Application` is this method: it is called before `onCreate`, with the class
 * name taken from the manifest, and returning a different name is what gets a different class
 * instantiated. There is no Gradle setting and no manifest merge that does it, which is why this
 * file exists rather than a line in `build.gradle.kts`. The runner is named there —
 * `testInstrumentationRunner` in `app/build.gradle.kts`, with the reasoning next to it.
 *
 * ## What is lost with `BoilerplateApp`, and why that is the right trade
 *
 * `BoilerplateApp.onCreate` starts the app-wide event dispatcher and schedules the periodic
 * background sync, and it implements `Configuration.Provider` to hand WorkManager the
 * `HiltWorkerFactory`. None of that happens here. That is a feature rather than a gap: a suite
 * that asserts on a database would otherwise be racing a worker that syncs it, and the manifest
 * removes WorkManager's own initialiser, so nothing initialises WorkManager unless a test asks
 * for it. A suite whose subject *is* the scheduling needs `@CustomTestApplication` on an
 * application that implements `Configuration.Provider` — see docs/hilt-testing.md.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(
        classLoader,
        HiltTestApplication::class.java.name,
        context,
    )
}
