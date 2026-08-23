package com.kojo.boilerplate.core.domain.sync

import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Resolves the [SyncStrategy] for a [SyncMode].
 *
 * ## Why a factory rather than injecting the strategy
 *
 * The mode is not known at construction time. `RefreshVisibleUsersUseCase` picks it per call,
 * and a background worker will pick a different one from the same process — so a caller
 * cannot declare "give me the visible-users strategy" in its constructor without also
 * deciding, permanently, that it will never want another. Asking for the map and looking up
 * the mode is the indirection that makes the choice a runtime one.
 *
 * ## Why the map holds [Provider]s
 *
 * `Map<SyncMode, SyncStrategy>` would work and would construct **every** strategy to answer
 * a question about one of them. That is free today, with two strategies over one repository,
 * and stops being free the moment a strategy takes a `WorkManager`, a `Clock` or a second
 * data source: a refresh of the visible users would build the machinery of every sync the app
 * knows how to do. `Provider` makes each entry lazy, so resolving a mode constructs exactly
 * the strategy that mode names.
 *
 * The map itself is filled by Dagger multibinding — `@Binds @IntoMap @SyncModeKey(…)` in
 * `SyncStrategyModule` — which is what makes a new strategy a new file plus a binding rather
 * than an edit to this class. Nothing here enumerates the modes.
 *
 * `@JvmSuppressWildcards` on the value type is load-bearing and not decoration. Kotlin
 * compiles `Map<SyncMode, Provider<SyncStrategy>>` to a Java signature carrying
 * `? extends Provider<SyncStrategy>`, Dagger provides the map without the wildcard, and the
 * two do not match — the failure is a "cannot be provided" error at KSP time naming a type
 * that looks identical to the one that is bound.
 *
 * ## Why the mode is checked against the strategy
 *
 * A map key is an annotation on a `@Binds` method in another file, and Dagger does not — and
 * cannot — check that the key names the same thing the implementation does. Binding
 * [CurrentUserSyncStrategy] under [SyncMode.VISIBLE_USERS] compiles cleanly and yields an app
 * that refreshes the wrong users, with nothing in the type system objecting. [SyncStrategy.mode]
 * is the implementation's own statement of what it is, and comparing it here turns that class
 * of mistake into a loud failure on first use instead of a subtle one at runtime.
 *
 * Both failures are [IllegalStateException] and both are programmer errors rather than
 * conditions a caller could recover from: they mean the graph is wired wrong, and every
 * resolution of that mode will fail identically for as long as the app is installed. Nothing
 * catches them, on purpose.
 */
@Singleton
class SyncStrategyFactory @Inject constructor(
    private val strategies: Map<SyncMode, @JvmSuppressWildcards Provider<SyncStrategy>>,
) {

    /**
     * The strategy bound under [mode].
     *
     * @throws IllegalStateException if no strategy is bound for [mode], or if the one that is
     *   reports a different [SyncStrategy.mode].
     */
    fun create(mode: SyncMode): SyncStrategy {
        val provider = checkNotNull(strategies[mode]) {
            "No SyncStrategy is bound for $mode. Add a @Binds @IntoMap @SyncModeKey($mode) " +
                "method to SyncStrategyModule — SyncStrategyModuleContractTest asserts that " +
                "every SyncMode has exactly one."
        }

        val strategy = provider.get()
        check(strategy.mode == mode) {
            "${strategy.javaClass.name} is bound under $mode but reports ${strategy.mode}. " +
                "One of the two is wrong: fix the @SyncModeKey in SyncStrategyModule or the " +
                "`mode` the strategy declares."
        }
        return strategy
    }
}
