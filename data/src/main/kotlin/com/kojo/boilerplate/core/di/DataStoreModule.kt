package com.kojo.boilerplate.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.kojo.boilerplate.core.datastore.authTokenDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The auth-token `DataStore`.
 *
 * This module used to declare a second `@ApplicationScope` qualifier of its own, next to the
 * one in `core.coroutines`, and bind it to a bare `CoroutineScope(SupervisorJob())`.
 * `DataStoreTokenProvider` was its only consumer, so it silently got a scope with no
 * `CoroutineExceptionHandler` and no dispatcher of its own while the KDoc on
 * `@ApplicationScope` described the handler-carrying one. Two same-named qualifiers in one
 * module are legal and invisible; splitting the modules is what made them collide, and the
 * duplicate went rather than the original.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideAuthTokenDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.authTokenDataStore
}
