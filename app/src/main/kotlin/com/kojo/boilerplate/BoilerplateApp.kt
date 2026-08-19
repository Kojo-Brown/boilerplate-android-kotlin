package com.kojo.boilerplate

import android.app.Application
import com.kojo.boilerplate.core.event.AppEventDispatcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BoilerplateApp : Application() {

    @Inject
    lateinit var appEventDispatcher: AppEventDispatcher

    /**
     * Subscribes the app-wide event listeners before anything can publish.
     *
     * Two orderings matter here and both are easy to get backwards:
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
     */
    override fun onCreate() {
        super.onCreate()
        appEventDispatcher.start()
    }
}
