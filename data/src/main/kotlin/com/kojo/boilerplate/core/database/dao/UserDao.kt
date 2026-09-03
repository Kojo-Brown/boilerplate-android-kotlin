package com.kojo.boilerplate.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.kojo.boilerplate.core.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow

/**
 * An abstract class rather than an interface, so that [upsertResolving] can have a body Room
 * wraps in a transaction. Room generates a subclass either way; only a concrete method can
 * carry `@Transaction` over more than one statement.
 */
@Dao
abstract class UserDao {

    @Query("SELECT * FROM users ORDER BY displayName ASC")
    abstract fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :id")
    abstract fun observeById(id: String): Flow<UserEntity?>

    /**
     * The row as it stands right now, or `null`.
     *
     * A one-shot read, where [observeById] is a stream. Conflict resolution needs the former:
     * it compares a fetched row against what is stored *at the moment of writing*, inside the
     * transaction that then writes it, and a `Flow` cannot be read that way without opening a
     * second subscription whose value is stale as soon as it is delivered.
     */
    @Query("SELECT * FROM users WHERE id = :id")
    abstract suspend fun findById(id: String): UserEntity?

    /**
     * Every row holding an edit the server has not acknowledged.
     *
     * The push queue, and it is the `users` table itself rather than a table beside it — see
     * `PendingUserChangeRepository` for why one unsent mutation per row is the right bound for
     * a profile edit and the wrong one for anything that accumulates.
     *
     * `locallyChanged != ''` is the emptiness test in the storage format's own terms:
     * `UserFieldSetConverter` joins the set with commas, so the empty set is the empty string
     * and nothing else can be. Comparing against `''` rather than reading every row and
     * filtering in Kotlin keeps the scan in SQLite, which matters on the only caller there is —
     * a background worker over a table of every user the app has ever cached, almost none of
     * which have anything pending.
     *
     * Ordered by [UserEntity.id] so a push covers the rows in a stable order. Nothing depends on
     * *which* order, but an unordered query lets the same set of rows come back differently
     * between runs, and a flaky ordering is a flaky test the day someone asserts on the first
     * request.
     */
    @Query("SELECT * FROM users WHERE locallyChanged != '' ORDER BY id ASC")
    abstract suspend fun findPendingChanges(): List<UserEntity>

    @Upsert
    abstract suspend fun upsert(entity: UserEntity)

    @Delete
    abstract suspend fun delete(entity: UserEntity)

    /**
     * Reads the row under [id], asks [resolve] what should be there instead, and writes the
     * answer — all inside one transaction.
     *
     * ## Why the transaction is the point
     *
     * Conflict resolution is a read-modify-write, and `syncUsers` runs several concurrently
     * against the same database. Without a transaction two syncs of one id can both read the
     * pre-write row, both decide they are newer than it, and both write — and the one that
     * writes second wins regardless of which carried the higher version, which is precisely
     * the ordering bug the resolver exists to remove. Room serialises transactions, so the
     * second call reads what the first wrote and resolves against it.
     *
     * ## Why [resolve] is a lambda over entities
     *
     * Because the DAO has no business knowing what a conflict is. The policy lives in
     * `:core:domain` and the mapping between entities and domain records lives in the
     * repository; passing the decision in keeps this method to what it actually contributes,
     * which is atomicity. It also keeps the DAO free of injected collaborators, which a Room
     * DAO cannot have.
     *
     * @param resolve given the stored row, or `null` if there is none, returns the row to
     *   write — or `null` to write nothing. Called on the transaction's thread, so it must not
     *   block or suspend; every `ConflictResolver` is a pure function for this reason.
     * @return the row that is in the database when the transaction commits: what [resolve]
     *   returned, or the untouched existing row when it declined to write.
     */
    @Transaction
    open suspend fun upsertResolving(
        id: String,
        resolve: (local: UserEntity?) -> UserEntity?,
    ): UserEntity? {
        val local = findById(id)
        val resolved = resolve(local) ?: return local
        upsert(resolved)
        return resolved
    }
}
