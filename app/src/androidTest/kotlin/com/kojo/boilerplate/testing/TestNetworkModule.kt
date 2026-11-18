package com.kojo.boilerplate.testing

import com.kojo.boilerplate.core.di.NetworkModule
import com.kojo.boilerplate.core.network.api.AuthApi
import com.kojo.boilerplate.core.network.api.UserApi
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces the whole of [NetworkModule] with two in-memory doubles, so that no instrumented test
 * in this module can open a socket.
 *
 * ## What `replaces` actually removes
 *
 * [NetworkModule] provides eight things: a `Json`, an `HttpLoggingInterceptor`, a
 * `PinningDecision`, the two `OkHttpClient`s — the authenticated one and the `@AuthOkHttpClient`
 * one that `AuthApi` uses — a `Retrofit`, and the two APIs. Only the APIs are provided here, and
 * that is the point rather than an omission: nothing outside [NetworkModule] injects any of the
 * other six, so the replacement leaves a graph in which **there is no `OkHttpClient` binding at
 * all**. A test cannot accidentally reach the network through a client it did not mean to use,
 * and a future provider that needs one fails at compile time here rather than at run time on a
 * device with no route to `BuildConfig.BASE_URL`.
 *
 * Three side effects of that are worth naming, because each is a thing the production graph does
 * on first use and this one does not:
 *
 * * `providePinningDecision` parses `BuildConfig.CERTIFICATE_PINS` and logs the outcome. Pinning
 *   is asserted by `CertificatePinningContractTest` over `src/main`, which is the right place for
 *   it: a pin set is a property of the build, not of a test run.
 * * `provideLoggingInterceptor` redacts `Authorization` and the integrity header. There is no
 *   request to log, so there is nothing to redact — and `IntegrityAttestationContractTest`
 *   likewise reads `src/main`.
 * * `TokenAuthenticator`, `AuthInterceptor` and `IntegrityInterceptor` become unreachable, since
 *   their only consumers were the two client providers. Dagger validates what is reachable from
 *   a component's entry points, so they are simply never constructed.
 *
 * ## What is deliberately *not* replaced
 *
 * `NetworkBindsModule` — the other module in the same file — binds `TokenProvider` to
 * `DataStoreTokenProvider`, and it stays. Its only consumers were `AuthInterceptor` and
 * `TokenAuthenticator`, so nothing reachable asks for it and the encrypted token store is never
 * opened. Replacing it would be replacing a binding no test can reach, which is the kind of
 * entry that makes a test module hard to read later. A test whose subject *is* token storage
 * should replace it then, and say so.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NetworkModule::class])
abstract class TestNetworkModule {

    /**
     * No `@Singleton` on the binding: [TestUserApi] carries the scope itself, which is what lets
     * a test inject the concrete type and the interface and know they are the same object.
     */
    @Binds
    abstract fun bindUserApi(impl: TestUserApi): UserApi

    @Binds
    abstract fun bindAuthApi(impl: TestAuthApi): AuthApi
}
