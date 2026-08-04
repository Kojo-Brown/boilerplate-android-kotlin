package com.kojo.boilerplate.core.di

import com.kojo.boilerplate.core.coroutines.AppCoroutineExceptionHandler
import com.kojo.boilerplate.core.coroutines.ApplicationScope
import com.kojo.boilerplate.core.coroutines.CoroutineFailureReporter
import com.kojo.boilerplate.core.coroutines.DefaultDispatcher
import com.kojo.boilerplate.core.coroutines.LogcatCoroutineFailureReporter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Wires the uncaught-failure path: what reports a failure, what catches it, and the one
 * scope in the app that has the handler installed.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CoroutineErrorModule {

    /**
     * Swap this binding to send uncaught failures to a crash reporter instead of Logcat.
     * Nothing else has to change: the handler depends on the interface.
     */
    @Binds
    @Singleton
    abstract fun bindCoroutineFailureReporter(
        impl: LogcatCoroutineFailureReporter,
    ): CoroutineFailureReporter

    @Binds
    @Singleton
    abstract fun bindCoroutineExceptionHandler(
        impl: AppCoroutineExceptionHandler,
    ): CoroutineExceptionHandler

    companion object {

        /**
         * The process-lifetime scope described by [ApplicationScope].
         *
         * [SupervisorJob] so that one failed task does not cancel unrelated ones — the tasks
         * started here have nothing to do with each other. That isolation is exactly what
         * makes the handler necessary: under a supervisor a failing child has no parent to
         * report to, so without a handler in this context its exception would go straight to
         * the thread's uncaught handler and take the app down.
         *
         * [DefaultDispatcher] rather than IO: work here is fire-and-forget, and defaulting to
         * the unbounded-ish IO pool makes it easy to start enough of it to matter. A task
         * that really is I/O-bound should say so with its own `withContext`.
         *
         * The scope is deliberately never cancelled. Its lifetime is the process, and the
         * platform reclaims it; a `close()` on it would only invite a caller to cancel work
         * that other parts of the app are relying on.
         */
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(
            @DefaultDispatcher dispatcher: CoroutineDispatcher,
            exceptionHandler: CoroutineExceptionHandler,
        ): CoroutineScope = CoroutineScope(
            SupervisorJob() + dispatcher + exceptionHandler + CoroutineName("application"),
        )
    }
}
