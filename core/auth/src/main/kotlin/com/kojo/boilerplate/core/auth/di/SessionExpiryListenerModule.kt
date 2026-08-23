package com.kojo.boilerplate.core.auth.di

import com.kojo.boilerplate.core.auth.SessionExpiryCredentialListener
import com.kojo.boilerplate.core.event.AppEventListener
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Puts this module's reaction to session expiry into the app-wide listener set.
 *
 * It is here rather than in `:core:common` next to `AppEventModule` because the listener it
 * binds needs Credential Manager, and `:core:common` — which owns the bus, the dispatcher and
 * the event type — has no Android dependencies at all. The `@IntoSet` multibinding is what lets
 * the two live apart: `AppEventDispatcher` asks for a `Set<AppEventListener>` and Dagger
 * assembles it from every module that contributed one, so a listener joins from whichever
 * module can actually build it.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionExpiryListenerModule {

    @Binds
    @IntoSet
    abstract fun bindSessionExpiryCredentialListener(
        impl: SessionExpiryCredentialListener,
    ): AppEventListener
}
