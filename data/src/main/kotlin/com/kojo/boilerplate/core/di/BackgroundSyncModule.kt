package com.kojo.boilerplate.core.di

import android.content.Context
import androidx.work.WorkManager
import com.kojo.boilerplate.core.domain.sync.BackgroundSyncScheduler
import com.kojo.boilerplate.core.work.WorkManagerBackgroundSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the background sync: the scheduler abstraction the app calls, and the [WorkManager]
 * behind it.
 *
 * Both live in `:data` for the same reason every other implementation does — `:app` is not
 * allowed to name a type from this module, only to carry it so its bindings reach the
 * component. `BoilerplateApp` injects [BackgroundSyncScheduler] and never learns what
 * schedules it.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackgroundSyncModule {

    @Binds
    @Singleton
    abstract fun bindBackgroundSyncScheduler(
        impl: WorkManagerBackgroundSyncScheduler,
    ): BackgroundSyncScheduler

    companion object {

        /**
         * `@Provides` because [WorkManager] is not this app's class to `@Inject`: it is
         * obtained from a static factory that returns the process-wide instance.
         *
         * `getInstance` is the reason this needs a module at all, and it carries one
         * requirement worth stating next to the call. It throws unless WorkManager has been
         * initialised — either by the `androidx.startup` provider in its own manifest, or, as
         * here, by the `Configuration.Provider` on `BoilerplateApp` that supplies the
         * `HiltWorkerFactory`. Nothing may ask for this binding before `Application.onCreate`
         * has run, which in practice nothing can: Hilt builds the component inside that
         * method.
         *
         * `@Singleton` matches what `getInstance` already guarantees. It is here so the
         * lookup happens once rather than on every injection, not to make something a
         * singleton that was not one.
         */
        @Provides
        @Singleton
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
            WorkManager.getInstance(context)
    }
}
