package com.kojo.boilerplate.core.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.kojo.boilerplate.core.common.safeCall
import com.kojo.boilerplate.core.database.dao.UserPagingDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.database.entity.toEntity
import com.kojo.boilerplate.core.database.entity.toVersioned
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolution
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.VersionedUser
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.toVersioned
import javax.inject.Inject

/**
 * Fills the local `users` table from the network as a reader scrolls, and nothing else.
 *
 * A `RemoteMediator` is the half of Paging 3 that has no opinion about what is shown. The
 * `PagingSource` — Room's, from [UserPagingDao.pagingSource] — is the only thing that ever
 * feeds the UI, so the list works fully offline and every row on screen came out of the
 * database. This is called when that source runs out of cached rows in some direction, and its
 * only job is to put more of them in and report whether there are any left to fetch. It never
 * returns data.
 *
 * That separation is why an error here does not empty the screen: [MediatorResult.Error]
 * surfaces as `LoadState.Error` on the append while the cached pages stay exactly where they
 * were.
 *
 * ## Every write still goes through the conflict resolver
 *
 * A page is a batch of fetched rows, so it is the same kind of write as `syncUser`'s and gets
 * the same treatment — see `docs/conflict-resolution.md`. Skipping the resolver here would
 * make scrolling past a user the one way to silently overwrite an unpushed local edit, which
 * is precisely the bug the resolver exists to prevent, arriving through a door nobody thought
 * to lock.
 *
 * ## What it deliberately does not do
 *
 * **It does not clear the table on `REFRESH`.** The codelab's mediator does, so that the local
 * order and the server's page order cannot drift apart. Here that would delete the user rows
 * `syncCurrentUser` and `syncUser` cached for the profile screen, and — worse — any row
 * carrying an edit the server has not acknowledged. A stale row that the server no longer
 * returns lingering in the list is the price, and it is a much smaller one than losing a user's
 * unsent change because they scrolled.
 *
 * **It does not `PREPEND`.** Paging starts at page one and the cursor only moves forward, so
 * there is never anything before what is loaded. The `PREPEND` case is answered as
 * end-of-pagination without a database read or a request.
 *
 * @see com.kojo.boilerplate.core.database.entity.UserPageKeyEntity for why the cursor is one
 *   row rather than a key per user.
 */
@OptIn(ExperimentalPagingApi::class)
class UsersRemoteMediator @Inject constructor(
    private val userApi: UserApi,
    private val pagingDao: UserPagingDao,
    private val conflictResolver: ConflictResolver,
) : RemoteMediator<Int, UserEntity>() {

    /**
     * Always refresh on the first collection.
     *
     * The alternative, `SKIP_INITIAL_REFRESH`, needs a "when was this last fetched?" timestamp
     * to decide against, and this app does not keep one — `UserEntity` carries a server version
     * rather than a fetch time, deliberately, because a wall clock is not something a
     * correctness decision should rest on. Refreshing is the answer that cannot be wrong: the
     * cached pages are already on screen by the time the request is made, so the cost is a
     * round trip and never a blank list.
     */
    override suspend fun initialize(): InitializeAction = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, UserEntity>,
    ): MediatorResult {
        val page = pageToLoad(loadType)
            ?: return MediatorResult.Success(endOfPaginationReached = true)

        return safeCall { fetchAndStore(page, state.config.pageSize) }.fold(
            onSuccess = { MediatorResult.Success(endOfPaginationReached = it) },
            // Every failure, not a chosen few. Paging is the one deciding what to do with it —
            // it becomes `LoadState.Error` and the reader gets a retry — so narrowing this to
            // `IOException` and `HttpException` would mean a serialization failure crashed the
            // collector instead of showing up as a failed append. `safeCall` still lets
            // cancellation through, which Paging requires: a cancelled load must not be
            // reported as an error the reader can retry.
            onFailure = { MediatorResult.Error(it) },
        )
    }

    /**
     * Which page this load wants, or `null` when there is nothing to fetch in that direction.
     *
     * `APPEND` with no cursor row at all means no page has ever been stored — an initial
     * refresh that failed, most often — and starting over at page one is the recovery. That is
     * a different case from a stored cursor whose `nextPage` is `null`, which means the server
     * has run out, and telling the two apart is why the cursor is an entity rather than a
     * nullable column read on its own.
     */
    private suspend fun pageToLoad(loadType: LoadType): Int? = when (loadType) {
        LoadType.REFRESH -> FIRST_PAGE
        LoadType.PREPEND -> null
        LoadType.APPEND -> appendPage()
    }

    private suspend fun appendPage(): Int? {
        val cursor = pagingDao.pageKey() ?: return FIRST_PAGE
        return cursor.nextPage
    }

    /**
     * Fetches [page] and commits it, returning whether the server has run out of users.
     *
     * A page shorter than [perPage] is the end — see [UserApi.getUsers] for why that is the
     * signal rather than an envelope field.
     *
     * The rows are keyed by id before the write, which both deduplicates a page that repeated a
     * user and preserves the order the server sent them in. The lambda handed to
     * [UserPagingDao.commitPage] is pure and non-suspending, as that method requires: it runs
     * on the transaction's thread.
     */
    private suspend fun fetchAndStore(page: Int, perPage: Int): Boolean {
        val response = userApi.getUsers(page = page, perPage = perPage)
        val endOfPaginationReached = response.size < perPage
        val remote = response.associate { it.id to it.toVersioned() }

        pagingDao.commitPage(
            nextPage = if (endOfPaginationReached) null else page + 1,
            userIds = remote.keys.toList(),
            resolve = { id, local -> resolveRow(remote.getValue(id), local) },
        )

        return endOfPaginationReached
    }

    private fun resolveRow(remote: VersionedUser, local: UserEntity?): UserEntity? =
        when (val resolution = conflictResolver.resolve(local?.toVersioned(), remote)) {
            ConflictResolution.KeepLocal -> null
            is ConflictResolution.Write -> resolution.record.toEntity()
        }

    private companion object {
        /** The API is 1-indexed; page 1 is the beginning of the list. */
        const val FIRST_PAGE = 1
    }
}
