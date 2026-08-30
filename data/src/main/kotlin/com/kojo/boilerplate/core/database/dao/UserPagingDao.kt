package com.kojo.boilerplate.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.database.entity.UserPageKeyEntity

/**
 * The reads and writes Paging needs, kept apart from [UserDao].
 *
 * ## Why a second DAO over the same table
 *
 * Because a page is committed as one unit: the rows and the cursor that says where they came
 * from have to land together, or a crash between the two leaves the app either re-fetching a
 * page it already has or skipping one it does not. Room gives that guarantee to a `@Transaction`
 * method, and a `@Transaction` method can only call the DAO it is declared on — so the write
 * spanning `users` and `user_page_keys` has to live on a DAO that can reach both.
 *
 * Putting it on [UserDao] instead would mean the DAO every screen's repository already injects
 * also carrying the pagination cursor, which is the opposite of what splitting the contract
 * into [com.kojo.boilerplate.core.paging.PagedUserRepository] was for. The cost of the split is
 * [findUser] and [upsertUser], two one-line queries that restate what [UserDao] already
 * declares. That is the whole duplication, it is over a table shape Room checks at build time
 * either way, and it buys a transaction boundary that could not otherwise exist.
 *
 * An abstract class rather than an interface, for the same reason [UserDao] is one: only a
 * concrete method can carry `@Transaction` over more than one statement.
 */
@Dao
abstract class UserPagingDao {

    /**
     * Every cached user as a `PagingSource`, ordered exactly as [UserDao.observeAll] orders
     * them — the two views of the same table must not disagree about order, or a user appears
     * in a different place depending on which screen is showing it.
     *
     * Room generates the `LIMIT`/`OFFSET` and, more importantly, the invalidation: any write to
     * `users` invalidates this source and Paging re-presents from the database. That is what
     * makes a locally edited row show up in a paged list without a refresh.
     */
    @Query("SELECT * FROM users ORDER BY displayName ASC")
    abstract fun pagingSource(): PagingSource<Int, UserEntity>

    /**
     * The pagination cursor, or `null` if no page has ever been stored.
     *
     * `LIMIT 1` rather than a lookup by [UserPageKeyEntity.SINGLETON_ID], because the table
     * holding more than one row would be a bug in [commitPage] rather than a case to select
     * between — and a query with no parameter cannot be called with the wrong one.
     */
    @Query("SELECT * FROM user_page_keys LIMIT 1")
    abstract suspend fun pageKey(): UserPageKeyEntity?

    @Query("SELECT * FROM users WHERE id = :id")
    abstract suspend fun findUser(id: String): UserEntity?

    @Upsert
    abstract suspend fun upsertUser(entity: UserEntity)

    @Upsert
    abstract suspend fun upsertPageKey(key: UserPageKeyEntity)

    /**
     * Stores one page of users and moves the cursor, atomically.
     *
     * ## Why [resolve] is a lambda, and not a list of rows
     *
     * For the reason [UserDao.upsertResolving] takes one: what to write for a given id is a
     * decision about the row that is in the database *at the moment of writing*, and a caller
     * that read it first would be resolving against a value that a concurrent sync may already
     * have replaced. Handing the decision in keeps this method to what it contributes, which is
     * atomicity, and keeps the conflict policy where it belongs — in `:core:domain`, reached
     * through the repository.
     *
     * A `null` from [resolve] means the policy declined the write: the stored row wins and this
     * id is skipped. The cursor still moves, because whether a *row* was worth overwriting says
     * nothing about whether the page was delivered.
     *
     * @param nextPage the cursor to store, `null` once the server has run out of pages.
     * @param userIds the ids on this page, in the order the server returned them.
     * @param resolve given an id and the row currently stored under it, returns the row to
     *   write, or `null` to leave it alone. Called on the transaction's thread, so it must not
     *   block or suspend — every `ConflictResolver` is a pure function for exactly this reason.
     */
    @Transaction
    open suspend fun commitPage(
        nextPage: Int?,
        userIds: List<String>,
        resolve: (id: String, local: UserEntity?) -> UserEntity?,
    ) {
        upsertPageKey(UserPageKeyEntity(id = UserPageKeyEntity.SINGLETON_ID, nextPage = nextPage))
        userIds.forEach { id ->
            val resolved = resolve(id, findUser(id))
            if (resolved != null) {
                upsertUser(resolved)
            }
        }
    }
}
