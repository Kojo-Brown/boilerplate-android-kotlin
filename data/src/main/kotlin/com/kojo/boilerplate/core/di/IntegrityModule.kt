package com.kojo.boilerplate.core.di

import com.kojo.boilerplate.core.security.integrity.IntegrityAttestation
import com.kojo.boilerplate.core.security.integrity.IntegrityConfiguration
import com.kojo.boilerplate.core.security.integrity.PlayIntegrityAttestation
import com.kojo.boilerplate.data.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The two bindings behind Play Integrity attestation.
 *
 * Separate from [SecurityModule] — which binds the cipher and key source behind token storage —
 * because the two answer different questions: how a credential is kept at rest, and how a
 * request proves which device and which binary it came from. This is the file to look at when
 * the answer to the second one changes.
 *
 * `@Singleton` on the implementation is load-bearing rather than an optimisation.
 * `PlayIntegrityAttestation` caches the warmed-up token provider, and a second instance would
 * warm up a second one: two round trips to Play where one would do, and two providers to
 * invalidate when one goes stale.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class IntegrityModule {

    @Binds
    @Singleton
    abstract fun bindIntegrityAttestation(impl: PlayIntegrityAttestation): IntegrityAttestation

    companion object {

        /**
         * The Cloud project this build's tokens are encrypted to, read from the `BuildConfig`
         * field `data/build.gradle.kts` writes from `gradle/play-integrity.properties`.
         *
         * Parsed once, at injection, so that a malformed value fails while the graph is being
         * built rather than on the first attested request — which, on the unmodified checkout
         * of this repository, would be never.
         */
        @Provides
        @Singleton
        fun provideIntegrityConfiguration(): IntegrityConfiguration =
            IntegrityConfiguration.parse(BuildConfig.INTEGRITY_CLOUD_PROJECT_NUMBER)
    }
}
