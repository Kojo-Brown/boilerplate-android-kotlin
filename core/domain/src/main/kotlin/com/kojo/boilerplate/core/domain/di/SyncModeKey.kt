package com.kojo.boilerplate.core.domain.di

import com.kojo.boilerplate.core.domain.sync.SyncMode
import dagger.MapKey

/**
 * The Dagger map key that files a `SyncStrategy` under the [SyncMode] it implements.
 *
 * Dagger needs an annotation, not a value, to key a multibinding — the map is assembled at
 * compile time and the key has to be readable from the source. `@MapKey` on an annotation
 * with a single enum parameter is the standard spelling of "the key is this enum constant",
 * and Dagger unwraps it so the injected map is `Map<SyncMode, …>` rather than
 * `Map<SyncModeKey, …>`.
 *
 * `RUNTIME` retention is stated rather than left to Kotlin's default because Dagger requires
 * it — a map key that is discarded after compilation cannot be read by the generated
 * component.
 *
 * It lives in `core.di` and not next to [SyncMode] in the domain layer on purpose:
 * `docs/clean-architecture.md` lists what that layer may depend on, and a DI framework's
 * annotations are not on it. The strategies carry `javax.inject.Inject` and nothing else;
 * everything that names Dagger sits here.
 */
@MapKey
@Retention(AnnotationRetention.RUNTIME)
annotation class SyncModeKey(val value: SyncMode)
