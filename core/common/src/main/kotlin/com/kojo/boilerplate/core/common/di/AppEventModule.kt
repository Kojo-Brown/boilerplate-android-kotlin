package com.kojo.boilerplate.core.common.di

import com.kojo.boilerplate.core.event.AppEventBus
import com.kojo.boilerplate.core.event.SharedFlowAppEventBus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the app-wide event bus itself. The reactions to what it carries are contributed by the
 * modules that own them — `SessionExpiryListenerModule` in `:core:auth` is the first — because
 * a listener's dependencies belong to its own layer and not to this one.
 *
 * The listener set is a Dagger multibinding rather than a list assembled somewhere, so adding a
 * reaction to an [com.kojo.boilerplate.core.event.AppEvent] is one `@Binds @IntoSet` in any
 * module and no edit to [com.kojo.boilerplate.core.event.AppEventDispatcher] or to this file.
 * That is also what makes the split work: `SessionExpiryCredentialListener` needs Credential
 * Manager, which `:core:common` must not depend on, and the multibinding lets it join the set
 * from a module that can.
 *
 * The same shape as `SyncStrategyModule`, and it has the same blind spot: Dagger checks that
 * everything in the set is an `AppEventListener` and nothing else. That a listener is
 * *registered* is not something a JVM unit test can see — a `@Binds` deleted anywhere compiles,
 * passes every test, and silently stops that reaction — which is why the dispatcher's own
 * contract (deliver to every listener it is given, survive one that throws) is tested against
 * an explicit set instead.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppEventModule {

    /**
     * `@Singleton` is load-bearing: the bus *is* its subscriber list and its buffer, so a
     * second instance would be a second bus, with the publisher on one and every listener on
     * the other.
     */
    @Binds
    @Singleton
    abstract fun bindAppEventBus(impl: SharedFlowAppEventBus): AppEventBus
}
