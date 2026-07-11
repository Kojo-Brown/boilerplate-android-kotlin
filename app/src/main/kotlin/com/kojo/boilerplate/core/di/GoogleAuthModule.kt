package com.kojo.boilerplate.core.di

import android.content.Context
import androidx.credentials.CredentialManager
import com.kojo.boilerplate.core.auth.GoogleAuthRepository
import com.kojo.boilerplate.core.auth.GoogleAuthRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleAuthBindsModule {

    @Binds
    @Singleton
    abstract fun bindGoogleAuthRepository(impl: GoogleAuthRepositoryImpl): GoogleAuthRepository
}

@Module
@InstallIn(SingletonComponent::class)
object GoogleAuthModule {

    @Provides
    @Singleton
    fun provideCredentialManager(
        @ApplicationContext context: Context,
    ): CredentialManager = CredentialManager.create(context)
}
