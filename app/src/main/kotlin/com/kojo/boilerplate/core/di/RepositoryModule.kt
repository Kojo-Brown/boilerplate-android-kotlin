package com.kojo.boilerplate.core.di

import com.kojo.boilerplate.core.coroutines.ApplicationScope
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.data.repository.UserRepositoryImpl
import com.kojo.boilerplate.core.data.repository.decorator.decorateUserRepository
import com.kojo.boilerplate.core.telemetry.LogcatRepositoryTelemetry
import com.kojo.boilerplate.core.telemetry.RepositoryTelemetry
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Binds the data layer, and the only place in the app that knows the repository is decorated.
 *
 * Everything else injects `UserRepository` and gets retry, caching and telemetry without being
 * able to tell — which is the property that makes the stack changeable. Removing a layer, or
 * adding one, is an edit to `decorateUserRepository` and nothing else.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * Swap this binding to send repository metrics to an analytics or APM SDK instead of
     * Logcat. Nothing else changes: the decorator depends on the interface.
     */
    @Binds
    @Singleton
    abstract fun bindRepositoryTelemetry(impl: LogcatRepositoryTelemetry): RepositoryTelemetry

    companion object {

        /**
         * `@Provides` rather than `@Binds`, because what is bound is a composition rather than
         * an implementation: `UserRepositoryImpl` is still the thing that talks to Room and
         * Retrofit, and the decorators around it are constructed here.
         *
         * `@Singleton` is load-bearing now in a way it was not when this bound the
         * implementation directly. The cache holds freshness marks and in-flight requests, so a
         * second instance would be a second cache that shares neither — every screen coalescing
         * only with itself. One instance is the whole point of the layer.
         *
         * The decorators take plain constructors and are assembled by hand rather than being
         * `@Inject`-annotated: each one needs a `UserRepository` to wrap, and `UserRepository`
         * is what this method provides, so letting Dagger construct them would be a dependency
         * cycle broken only by a qualifier per layer — three annotations whose whole job is to
         * encode an order that reads perfectly well as nesting.
         */
        @Provides
        @Singleton
        fun provideUserRepository(
            impl: UserRepositoryImpl,
            telemetry: RepositoryTelemetry,
            @ApplicationScope applicationScope: CoroutineScope,
        ): UserRepository = decorateUserRepository(
            base = impl,
            telemetry = telemetry,
            scope = applicationScope,
        )
    }
}
