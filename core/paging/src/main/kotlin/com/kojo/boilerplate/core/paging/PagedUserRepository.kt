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
     * A stream of pages over every cached user, ordered as `UserRepository.getUsers()` orders
     * them.
     *
     * Cold, and safe to collect more than once — each collection gets its own `PagingData`
     * generation. Collect it in a scope that survives configuration change (`cachedIn`) if the
     * consumer is a view model; a `PagingData` is a one-shot stream of load events and
     * re-collecting it re-fetches from page one.
     */
    fun users(): Flow<PagingData<User>>
}
