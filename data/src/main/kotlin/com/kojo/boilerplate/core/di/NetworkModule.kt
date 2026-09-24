package com.kojo.boilerplate.core.di

import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.kojo.boilerplate.core.datastore.DataStoreTokenProvider
import com.kojo.boilerplate.core.network.AuthInterceptor
import com.kojo.boilerplate.core.network.TokenAuthenticator
import com.kojo.boilerplate.core.network.TokenProvider
import com.kojo.boilerplate.core.network.api.AuthApi
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.pinning.PinningDecision
import com.kojo.boilerplate.core.network.pinning.PinningPolicy
import com.kojo.boilerplate.core.security.integrity.IntegrityInterceptor
import com.kojo.boilerplate.data.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

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

    /**
     * Request/response logging, full-bodied in debug and silent in release.
     *
     * ### The two redactions
     *
     * `Level.BODY` logs every header, and two of the ones this app sends are credentials in
     * everything but name. Logcat is readable by `adb` from any machine the device is plugged
     * into and, on a debug build, is exactly what gets pasted into a bug report.
     *
     * * `Authorization` carries the bearer token. Printing it turns a stack trace attached to a
     *   ticket into a live session.
     * * [IntegrityInterceptor.INTEGRITY_TOKEN] carries a Play Integrity token. It is encrypted
     *   to a Cloud project and so grants nothing by itself — but it is bound to the request it
     *   accompanies, and a log at `BODY` level prints both. That pair, replayed while the token
     *   is fresh, is a request the backend will accept as attested.
     *
     * `redactHeader` keeps the header's *name* in the log and replaces its value, which is what
     * makes a missing token still debuggable: the line says the header was there.
     */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader(IntegrityInterceptor.INTEGRITY_TOKEN)
        }

    /**
     * What this build does about certificate pinning, decided once.
     *
     * The host is `BASE_URL`'s rather than a constant, because the thing that has to be pinned
     * is wherever this build actually sends its requests — see [PinningPolicy.decideFor], which
     * refuses a pin set that does not cover it. `Instant.now()` is read here, at the one place
     * the decision is taken; everything that depends on the time is a parameter below it, which
     * is what makes the expiry testable without a clock.
     *
     * The summary is logged because none of the three outcomes is visible in the app's
     * behaviour: a build with pinning off, a build whose pins expired and a build enforcing them
     * against a server that matches all behave identically, right up until the one day they do
     * not.
     */
    @Provides
    @Singleton
    fun providePinningDecision(): PinningDecision =
        PinningPolicy.parse(BuildConfig.CERTIFICATE_PINS, BuildConfig.CERTIFICATE_PIN_EXPIRY)
            .decideFor(host = BuildConfig.BASE_URL.toHttpUrl().host, now = Instant.now())
            .also { Log.i(PINNING_TAG, it.summary) }

    /**
     * Unauthenticated OkHttpClient used exclusively by [AuthApi] (login, token refresh).
     * Must NOT include [AuthInterceptor] or [TokenAuthenticator] to avoid circular calls.
     *
     * Pinned, and this is the client where forgetting it costs the most. It carries the
     * password on the way in and the refresh token on every rotation — the two credentials the
     * other client never sends — so an interception here yields a session rather than one
     * response. It is also the easy one to miss, because it is defined away from the main
     * client and shares none of its configuration. `CertificatePinningContractTest` is what
     * stops the next `OkHttpClient.Builder()` in this repository from being unpinned.
     */
    @Provides
    @Singleton
    @AuthOkHttpClient
    fun provideAuthOkHttpClient(
        integrityInterceptor: IntegrityInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
        pinningDecision: PinningDecision,
    ): OkHttpClient = OkHttpClient.Builder()
        .certificatePinner(pinningDecision.certificatePinner)
        .addInterceptor(integrityInterceptor)
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
     *
     * The interceptor order is the one thing here that is not arbitrary. Application
     * interceptors run in the order they are added, each wrapping the next, so
     * [IntegrityInterceptor] sits between the auth header and the log: it attests the request
     * as it will actually be sent, and the logging interceptor below it sees — and redacts —
     * what went out rather than the marker header that asked for it.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        integrityInterceptor: IntegrityInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        loggingInterceptor: HttpLoggingInterceptor,
        pinningDecision: PinningDecision,
    ): OkHttpClient = OkHttpClient.Builder()
        .certificatePinner(pinningDecision.certificatePinner)
        .addInterceptor(authInterceptor)
        .addInterceptor(integrityInterceptor)
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

    private const val PINNING_TAG = "CertificatePinning"
}
