package com.kojo.boilerplate.core.di

import com.kojo.boilerplate.core.event.AppEventBus
import com.kojo.boilerplate.core.event.AppEventListener
import com.kojo.boilerplate.core.event.SessionExpiryCredentialListener
import com.kojo.boilerplate.core.event.SharedFlowAppEventBus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Wires the app-wide event bus and the listeners that must never miss what it carries.
 *
 * The listener set is a Dagger multibinding rather than a list assembled somewhere, so adding a
 * reaction to an [com.kojo.boilerplate.core.event.AppEvent] is one `@Binds @IntoSet` and no
 * edit to [com.kojo.boilerplate.core.event.AppEventDispatcher]. The same shape as
 * [SyncStrategyModule], and it has the same blind spot: Dagger checks that everything in the
 * set is an `AppEventListener` and nothing else. That a listener is *registered* is not
 * something a JVM unit test can see — a `@Binds` deleted here compiles, passes every test, and
 * silently stops that reaction — which is why the dispatcher's own contract (deliver to every
 * listener it is given, survive one that throws) is tested against an explicit set instead.
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

    @Binds
    @IntoSet
    abstract fun bindSessionExpiryCredentialListener(
        impl: SessionExpiryCredentialListener,
    ): AppEventListener
}
