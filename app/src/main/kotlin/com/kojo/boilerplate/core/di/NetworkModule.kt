package com.kojo.boilerplate.core.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.kojo.boilerplate.BuildConfig
import com.kojo.boilerplate.core.datastore.DataStoreTokenProvider
import com.kojo.boilerplate.core.network.AuthInterceptor
import com.kojo.boilerplate.core.network.TokenAuthenticator
import com.kojo.boilerplate.core.network.TokenProvider
import com.kojo.boilerplate.core.network.api.AuthApi
import com.kojo.boilerplate.core.network.api.UserApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthOkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindsModule {

    @Binds
    @Singleton
    abstract fun bindTokenProvider(impl: DataStoreTokenProvider): TokenProvider
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    /**
     * Unauthenticated OkHttpClient used exclusively by [AuthApi] (login, token refresh).
     * Must NOT include [AuthInterceptor] or [TokenAuthenticator] to avoid circular calls.
     */
    @Provides
    @Singleton
    @AuthOkHttpClient
    fun provideAuthOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .build()

    @Provides
    @Singleton
    fun provideAuthApi(
        @AuthOkHttpClient authOkHttpClient: OkHttpClient,
        json: Json,
    ): AuthApi = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(authOkHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AuthApi::class.java)

    /**
     * Main authenticated OkHttpClient — adds the JWT Bearer header on every request
     * and handles 401 responses via [TokenAuthenticator].
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        loggingInterceptor: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .addInterceptor(loggingInterceptor)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi = retrofit.create(UserApi::class.java)
}
