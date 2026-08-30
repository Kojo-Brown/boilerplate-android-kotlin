package com.kojo.boilerplate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Where the paged user list has got to on the server, as one row.
 *
 * ## Why one row and not a key per user
 *
 * The shape the Paging codelab reaches for is a `remote_keys` table with a row per item,
 * carrying the previous and next page each item arrived on. That exists to answer "which page
 * do I resume from, given the item the reader is looking at?" — the question a
 * `RemoteMediator` has to answer when a `REFRESH` can start anywhere in the middle of the list.
 *
 * Two things about this app make it the wrong shape here, and both are about the `users` table
 * having writers other than the mediator:
 *
 *  - `syncCurrentUser` and `syncUser` cache single rows fetched by id. Those rows have never
 *    been on a page, so a per-item table has no key for them — and if one of them happens to
 *    sort last, `APPEND` reads a null key off it and reports end-of-pagination while the
 *    server still has users. The list silently truncates, and only for users whose profile has
 *    been opened.
 *  - The local order is `displayName`, chosen by the DAO rather than by the endpoint, so
 *    "the page this item came from" is not a property of where it sits locally anyway.
 *
 * A single cursor sidesteps both: the mediator asks the server for the page after the last one
 * it stored, whatever else has been written to `users` in between, and `PREPEND` is answered
 * without a table read at all — page one is the beginning, so there is never anything before
 * what is loaded. What is given up is resuming a `REFRESH` from the middle, which this
 * mediator does not do: a refresh restarts at page one, and the reader's position is held by
 * the local Room `PagingSource`, not by the mediator.
 *
 * @property id always [SINGLETON_ID]. The primary key exists so that `@Upsert` replaces the
 *   cursor rather than appending a second one; the table is deliberately never more than one
 *   row.
 * @property nextPage the page to request on the next `APPEND`, or `null` once the server has
 *   returned a short page — meaning there is nothing left to fetch. Distinguishing "no row"
 *   from "row with a null `nextPage`" is exactly why this is an entity rather than a nullable
 *   `Int` column read on its own: the first means nothing has ever been loaded, the second
 *   means everything has.
 */
@Entity(tableName = "user_page_keys")
data class UserPageKeyEntity(
    @PrimaryKey val id: Int,
    val nextPage: Int?,
) {
    companion object {
        /** The only primary key this table ever holds. */
        const val SINGLETON_ID: Int = 0
    }
}
