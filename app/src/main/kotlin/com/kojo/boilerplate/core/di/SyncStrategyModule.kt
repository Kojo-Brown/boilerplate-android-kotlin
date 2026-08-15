package com.kojo.boilerplate.core.di

import com.kojo.boilerplate.core.domain.sync.CurrentUserSyncStrategy
import com.kojo.boilerplate.core.domain.sync.SyncMode
import com.kojo.boilerplate.core.domain.sync.SyncStrategy
import com.kojo.boilerplate.core.domain.sync.VisibleUsersSyncStrategy
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/**
 * The `SyncStrategy` map, assembled by Dagger multibinding.
 *
 * Every `@Binds @IntoMap` method here contributes one entry to the
 * `Map<SyncMode, Provider<SyncStrategy>>` that `SyncStrategyFactory` injects. Nothing
 * enumerates the strategies anywhere else: adding a mode is this file plus a class, and no
 * `when`, registry or factory body has to be edited to reach it.
 *
 * ### What the compiler will and will not catch here
 *
 * Dagger catches a *duplicate* key — two methods claiming the same [SyncMode] fail the build
 * — and it catches a bound type that is not a `SyncStrategy`. It cannot catch the two
 * mistakes that matter most:
 *
 * - **A missing key.** A [SyncMode] with no method here compiles perfectly and fails when
 *   something first asks for it. `SyncStrategyModuleContractTest` reads this class's
 *   annotations and asserts that every enum constant is bound exactly once, which is what
 *   turns "the mode nobody wired up" from a runtime surprise into a red test.
 * - **A key on the wrong strategy.** `@SyncModeKey(VISIBLE_USERS)` over a method binding
 *   [CurrentUserSyncStrategy] is a well-typed lie. `SyncStrategyFactory` compares the key it
 *   resolved against [SyncStrategy.mode] on every resolution, and the same contract test
 *   pins the class-to-key pairing.
 *
 * The strategies are unscoped. Each is a couple of fields over the repository, constructed
 * per resolution and thrown away — cheaper than the memory a `@Singleton` on each would hold
 * for the life of the process, and there is no state in them worth sharing.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncStrategyModule {

    @Binds
    @IntoMap
    @SyncModeKey(SyncMode.VISIBLE_USERS)
    abstract fun bindVisibleUsersSyncStrategy(strategy: VisibleUsersSyncStrategy): SyncStrategy

    @Binds
    @IntoMap
    @SyncModeKey(SyncMode.CURRENT_USER)
    abstract fun bindCurrentUserSyncStrategy(strategy: CurrentUserSyncStrategy): SyncStrategy
}
