package com.kojo.boilerplate.core.paging

import androidx.paging.PagingData
import com.kojo.boilerplate.core.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * The user list as an endless, offline-first stream of pages.
 *
 * ## Why this is not a method on `UserRepository`
 *
 * `UserRepository.getUsers()` is `Flow<List<User>>` — every row Room holds, in one list, on
 * every change. That is the right shape for a screen that shows the handful of users this app
 * has synced by name, and the wrong one for a list that grows without a bound: the whole table
 * is materialised, mapped and re-emitted for a single edited row, and nothing in the signature
 * can say "the next page has not been fetched yet".
 *
 * Both shapes are legitimate and neither replaces the other, so this is an interface of its own
 * rather than a seventh method on `UserRepository`. It is also in a module of its own, for a
 * reason that has nothing to do with paging and everything to do with the layer boundary — see
 * this module's build file and `docs/paging.md`.
 *
 * ## What the implementation guarantees
 *
 * Room is the single source of truth. The returned stream is backed by a Room `PagingSource`,
 * so it serves whatever is cached the moment it is collected, with no network at all; a
 * `RemoteMediator` fills the cache in behind it as the reader scrolls, and every page it
 * fetches lands through the same conflict resolver every other write goes through. A collector
 * offline sees the cached pages and a `LoadState.Error` on the append, not an empty list.
 */
interface PagedUserRepository {

    /**
     * A stream of pages over the cached users matching [query], ordered as
     * `UserRepository.getUsers()` orders them.
     *
     * Cold, and safe to collect more than once — each collection gets its own `PagingData`
     * generation. Collect it in a scope that survives configuration change (`cachedIn`) if the
     * consumer is a view model; a `PagingData` is a one-shot stream of load events and
     * re-collecting it re-fetches from page one.
     *
     * ## Why the search is a parameter here rather than a filter on the result
     *
     * Because `PagingData` carries the pages that have been loaded, not the rows that exist. A
     * caller filtering the returned stream would be searching its own scroll history: the
     * matches on page seven are invisible until something has already fetched page seven, so the
     * list comes back short rather than filtered, and shorter the sooner the reader types. The
     * predicate has to reach the query that decides which rows the list is made of, and this
     * parameter is how a caller that must not see a DAO says so.
     *
     * ## What a query changes about fetching
     *
     * A search covers **what has been downloaded**, not what the server holds. `GET /users` takes
     * a page and a page size and nothing else — there is no query parameter to forward — so the
     * only honest options are to search the cache or to walk the whole remote list one page at a
     * time hoping for a match. The second is not a search; it is a table scan wearing a scroll
     * bar, and on a list of any size it never terminates before the reader gives up. So an
     * implementation must not let a query drive remote loading: a searching list pages through
     * the cache alone, and filling the cache is what scrolling the unsearched list does.
     *
     * @param query what the user typed, already trimmed. Blank means everything, and is the
     *   default state of a screen rather than a special case.
     */
    fun users(query: String): Flow<PagingData<User>>
}
