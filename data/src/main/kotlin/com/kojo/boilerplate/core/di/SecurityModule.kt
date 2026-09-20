package com.kojo.boilerplate.core.di

import com.kojo.boilerplate.core.security.AesGcmTokenCipher
import com.kojo.boilerplate.core.security.AndroidKeystoreSecretKeySource
import com.kojo.boilerplate.core.security.SecretKeySource
import com.kojo.boilerplate.core.security.TokenCipher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The two bindings behind encrypted token storage.
 *
 * Both are `@Binds` rather than `@Provides` because both implementations are constructor
 * injectable and neither needs anything Hilt cannot already build — in particular neither needs
 * a `Context`, which is worth noticing: the `AndroidKeyStore` is reached through the JCA
 * provider registry rather than through a system service, so there is no `getSystemService` call
 * and nothing to scope to the application.
 *
 * `@Singleton` on both for the reason spelled out in `AndroidKeystoreSecretKeySource`: the key
 * is cached per process, and two instances would each hold their own cache and, on a fresh
 * install, could each decide to generate. A second instance is not merely wasteful here, it is
 * the race that class is written to avoid.
 *
 * Kept in its own module rather than added to `NetworkBindsModule` because the two answer
 * different questions — how a request is authenticated, and how the credential is kept at rest —
 * and because this is the file to look at when the answer to the second one changes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindTokenCipher(impl: AesGcmTokenCipher): TokenCipher

    @Binds
    @Singleton
    abstract fun bindSecretKeySource(impl: AndroidKeystoreSecretKeySource): SecretKeySource
}
