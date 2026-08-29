package com.kojo.boilerplate.core.domain.di

import com.kojo.boilerplate.core.domain.sync.conflict.ConflictPolicy
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.LastWriteWinsConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.MergeConflictResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Binds the one [ConflictResolver] the app runs with.
 *
 * ## Why a `when` and not a multibound map
 *
 * `SyncStrategyModule` next door assembles a `Map<SyncMode, Provider<SyncStrategy>>`, and that
 * indirection is bought by a real requirement: the mode is chosen per call, so no caller can
 * name its strategy at construction time. Nothing chooses a conflict policy per call — see
 * [ConflictPolicy] for why it would be wrong to — so the same machinery here would be a
 * runtime lookup for a decision made once, and a lookup that can miss.
 *
 * The `when` is worth more than the map for exactly that reason. Dagger cannot tell that a
 * [ConflictPolicy] has no binding; the compiler can tell that a `when` over an enum has no
 * branch for one, and it says so where the policy is added rather than where it is first
 * asked for. Adding a third policy does not compile until this file decides about it.
 *
 * ## Why [Provider], with only one implementation ever constructed
 *
 * The branch not taken is never built. Both resolvers are trivial today — no state, no
 * dependencies — so this saves nothing measurable, and it is here because the shape is what
 * stops that from mattering later: a resolver that grows a dependency should not have it
 * constructed by an app configured to use the other policy. The alternative, taking both
 * resolvers as instances, makes the unused half's dependency graph part of every startup.
 */
@Module
@InstallIn(SingletonComponent::class)
object ConflictResolverModule {

    /**
     * `@Singleton` because the resolvers are stateless and pure: one instance answers every
     * caller identically, and there is nothing to keep them apart. This is the opposite call
     * from the sync strategies, which are unscoped precisely because each resolution wants a
     * fresh one — the difference is that a strategy is chosen per call and a policy is not.
     */
    @Provides
    @Singleton
    fun provideConflictResolver(
        lastWriteWins: Provider<LastWriteWinsConflictResolver>,
        merge: Provider<MergeConflictResolver>,
    ): ConflictResolver = when (ConflictPolicy.DEFAULT) {
        ConflictPolicy.LAST_WRITE_WINS -> lastWriteWins.get()
        ConflictPolicy.MERGE -> merge.get()
    }
}
