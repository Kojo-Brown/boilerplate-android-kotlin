package com.kojo.boilerplate.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import com.kojo.boilerplate.core.datastore.proto.UserPreferencesProto
import com.kojo.boilerplate.core.datastore.userPreferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The typed preferences `DataStore`.
 *
 * No qualifier, unlike `DataStoreModule` and `ThemeModule`. Those two both provide a
 * `DataStore<Preferences>` — the same type twice — so one of them needs a `@ThemeDataStore` to
 * tell Dagger which is which, and a mistake there is a screen reading the auth-token file. Here
 * the type argument does that job: `DataStore<UserPreferencesProto>` is a different binding key
 * from `DataStore<Preferences>`, and a second typed store would be a third distinct one. Not
 * needing a qualifier is the schema paying for itself in the DI graph as well as at the call
 * site.
 */
@Module
@InstallIn(SingletonComponent::class)
object UserPreferencesModule {

    @Provides
    @Singleton
    fun provideUserPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<UserPreferencesProto> = context.userPreferencesDataStore
}
