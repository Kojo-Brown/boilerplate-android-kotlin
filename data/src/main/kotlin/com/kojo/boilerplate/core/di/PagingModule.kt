package com.kojo.boilerplate.core.di

import com.kojo.boilerplate.core.data.paging.PagedUserRepositoryImpl
import com.kojo.boilerplate.core.paging.PagedUserRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the paged half of the user contract.
 *
 * Separate from `RepositoryModule` because that module's whole subject is the decorator stack —
 * retry, caching and telemetry wrapped around `UserRepositoryImpl` — and none of it applies
 * here. Paging has its own retry (`LoadState.Error` plus `retry()`), its own cache (Room, which
 * is also the source of truth), and its own load-state stream for a telemetry decorator to read
 * instead. Folding this in would put a `@Binds` with nothing to say next to a `@Provides` whose
 * documentation is entirely about a composition it is not part of.
 *
 * `@Binds` rather than `@Provides` for the same reason: what is bound *is* the implementation,
 * with nothing assembled around it.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PagingModule {

    /**
     * `@Singleton` so that the implementation — and through it the one `UsersRemoteMediator` —
     * is shared. It is not a correctness requirement the way `RepositoryModule`'s is: the
     * mediator holds no state between calls, and the pagination cursor it reads lives in Room
     * where a second instance would see the same row. It is here because a second instance
     * would be pure waste, and because the scope is what a reader would otherwise have to
     * check the mediator's fields to rule out.
     */
    @Binds
    @Singleton
    abstract fun bindPagedUserRepository(impl: PagedUserRepositoryImpl): PagedUserRepository
}
