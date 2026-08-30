package com.kojo.boilerplate.core.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.kojo.boilerplate.core.database.dao.UserPagingDao
import com.kojo.boilerplate.core.database.entity.toDomain
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.paging.PagedUserRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Assembles the three pieces Paging 3 needs and maps rows to the domain model.
 *
 * There is no threading to own here, which is the one way this repository differs from
 * `UserRepositoryImpl` and its injected `@IoDispatcher`. Paging does its own confinement: the
 * `PagingSource` Room generates runs its queries on the database's own executor, and
 * `RemoteMediator.load` is called on the fetcher's dispatcher. Adding a `flowOn` here would
 * move only the `map` below — and `PagingData.map` is applied lazily, per item, as the
 * presenter reaches it, so it would not even move that.
 */
class PagedUserRepositoryImpl @Inject constructor(
    private val pagingDao: UserPagingDao,
    private val remoteMediator: UsersRemoteMediator,
) : PagedUserRepository {

    /**
     * A new `Pager` per collection, on purpose. A `Pager` is a factory for one stream of load
     * events, and two collectors sharing one would share a scroll position; the caller that
     * wants a shared, configuration-change-surviving stream is a view model, and `cachedIn` is
     * how it says so.
     *
     * `pagingSourceFactory` is a lambda rather than a captured instance because a
     * `PagingSource` is single-use: Room invalidates it on every write to `users`, and Paging
     * calls the factory again for a fresh one. Passing an instance would leave the list frozen
     * at the first invalidation.
     */
    @OptIn(ExperimentalPagingApi::class)
    override fun users(): Flow<PagingData<User>> = Pager(
        config = PAGING_CONFIG,
        remoteMediator = remoteMediator,
        pagingSourceFactory = { pagingDao.pagingSource() },
    ).flow.map { pagingData -> pagingData.map { it.toDomain() } }

    private companion object {
        /**
         * `enablePlaceholders = false` because nothing knows how many users there are. The
         * count would have to come from the server, this endpoint deliberately does not report
         * one (see `UserApi.getUsers`), and a placeholder list sized from a guess scrolls
         * wrongly.
         *
         * `initialLoadSize` is left at its default of three pages: the first load is the one
         * the reader waits on, and filling more than a screen with it is what stops an
         * immediate scroll from hitting an empty append.
         *
         * `pageSize` is the number the mediator passes to the API as `per_page`, so this
         * constant is also the request size — see `UsersRemoteMediator.fetchAndStore`.
         */
        val PAGING_CONFIG = PagingConfig(
            pageSize = 20,
            enablePlaceholders = false,
        )
    }
}
