package com.kojo.boilerplate.core.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.kojo.boilerplate.core.database.dao.UserPagingDao
import com.kojo.boilerplate.core.database.dao.likePattern
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
     *
     * ## Why a search turns the mediator off
     *
     * `remoteMediator` is `null` for every non-blank [query], and that one `takeIf` is the whole
     * of what makes search terminate.
     *
     * Paging asks the mediator to `APPEND` when the `PagingSource` is running out of rows near
     * the reader's position — it has no idea the query is filtered, and no way to ask the server
     * for matches, because `GET /users` takes a page number and a page size and nothing else. So
     * a searching list with the mediator attached does this: three matches come back, the
     * prefetch window is nowhere near satisfied, an `APPEND` fetches page one, the rows land, the
     * filtered source is invalidated, three matches come back again. It is a loop that walks the
     * entire remote list — every page, through the conflict resolver, on a keystroke — and ends
     * only when the server runs out. A query matching nothing pays the full cost for an empty
     * screen.
     *
     * With it off, a search covers what has been downloaded, which is a promise that can be kept
     * offline and in constant time. Filling the cache is what scrolling the unsearched list does,
     * and it is unaffected: clearing the search restores the mediator for the next generation,
     * which is the same generation boundary the new `PagingSource` already forces.
     *
     * The trade is that a user searching for someone on a page nobody has scrolled to sees no
     * result. That is visible — the list is empty and says what it searched — where the
     * alternative's cost is not, and closing it properly means a `?q=` on the endpoint rather
     * than a client-side workaround pretending to be one. See `docs/paging.md`.
     */
    @OptIn(ExperimentalPagingApi::class)
    override fun users(query: String): Flow<PagingData<User>> = Pager(
        config = PAGING_CONFIG,
        remoteMediator = remoteMediator.takeIf { query.isBlank() },
        pagingSourceFactory = { pagingDao.pagingSource(likePattern(query)) },
    ).flow.map { pagingData -> pagingData.map { it.toDomain() } }

    private companion object {

        /**
         * Rows per page, and therefore the `per_page` the mediator sends — it reads it back off
         * `state.config.pageSize`, so this is the request size as well. Named because
         * [PAGING_CONFIG] now uses it twice and the two uses have to move together; the
         * `prefetchDistance` note below is why.
         */
        const val PAGE_SIZE = 20

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
         * `prefetchDistance` is how far from the loaded edge an access has to be before the
         * next page is requested, and it is **item prefetch for this list** — Compose's own
         * lazy-layout prefetch composes one item ahead of the viewport out of data it already
         * has, and cannot ask for data that has not been fetched. So the choice of whether the
         * reader ever waits at the bottom of the list is made here and not in the
         * `LazyColumn`; `docs/lazy-lists.md` is where the two halves are set beside each other.
         *
         * One page ahead is the value, written as [PAGE_SIZE] rather than as `20`. That is
         * also `PagingConfig`'s default — the default *is* `pageSize`, and restating it is the
         * point: as a defaulted parameter, halving the page size to cut request cost would
         * silently halve the prefetch window too, which is a scroll-smoothness regression
         * arriving from a line that says nothing about scrolling. Written out, the coupling is
         * deliberate and a reviewer sees both numbers move.
         */
        val PAGING_CONFIG = PagingConfig(
            pageSize = PAGE_SIZE,
            prefetchDistance = PAGE_SIZE,
            enablePlaceholders = false,
        )
    }
}
