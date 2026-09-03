package com.kojo.boilerplate.core.di

import com.kojo.boilerplate.core.coroutines.ApplicationScope
import com.kojo.boilerplate.core.data.repository.PendingUserChangeRepositoryImpl
import com.kojo.boilerplate.core.data.repository.UserRepositoryImpl
import com.kojo.boilerplate.core.data.repository.decorator.decorateUserRepository
import com.kojo.boilerplate.core.data.sync.UuidIdempotencyKeyGenerator
import com.kojo.boilerplate.core.domain.repository.PendingUserChangeRepository
import com.kojo.boilerplate.core.domain.repository.UserRepository
import com.kojo.boilerplate.core.domain.sync.IdempotencyKeyGenerator
import com.kojo.boilerplate.core.telemetry.LogcatRepositoryTelemetry
import com.kojo.boilerplate.core.telemetry.RepositoryTelemetry
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

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

    /**
     * Swap this binding to change how a mutation is named — but read
     * [UuidIdempotencyKeyGenerator]'s KDoc first: the two obvious alternatives, a hash of the
     * change and a counter, are each broken in a way that only shows up as a silently dropped
     * edit.
     */
    @Binds
    @Singleton
    abstract fun bindIdempotencyKeyGenerator(
        impl: UuidIdempotencyKeyGenerator,
    ): IdempotencyKeyGenerator

    /**
     * Bound undecorated, unlike [UserRepository] above, and
     * `PendingUserChangeRepository`'s KDoc gives the reason for each of the three layers it does
     * without: a cache would suppress a write, in-process retry is the wrong scale for a push
     * that has to survive the process, and telemetry is the one real loss.
     */
    @Binds
    @Singleton
    abstract fun bindPendingUserChangeRepository(
        impl: PendingUserChangeRepositoryImpl,
    ): PendingUserChangeRepository

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
