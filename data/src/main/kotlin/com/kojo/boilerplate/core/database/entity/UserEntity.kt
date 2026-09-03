package com.kojo.boilerplate.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import com.kojo.boilerplate.core.domain.sync.conflict.VersionedUser

/**
 * @property version the server's version of this row, `0` for a row that has never been
 *   synced. Written only from a fetched response — see `ConflictResolver`.
 * @property locallyChanged the fields carrying an edit the server has not acknowledged, stored
 *   through `UserFieldSetConverter`. Empty for every row that came off the network.
 * @property pendingChangeKey the idempotency key naming the mutation [locallyChanged]
 *   describes, or `null` when there is nothing pending. **Non-null exactly when
 *   [locallyChanged] is non-empty** — the two are one fact stored in two columns, and
 *   [toEntity] is the only place allowed to decide either, which is why it takes the key as a
 *   parameter rather than defaulting it.
 *
 *   It is a plain `String` and not a value class because it crosses Room, Retrofit and a
 *   `@Header` in that order, and each boundary would need its own converter to unwrap what the
 *   type system had wrapped. What a value class would buy — not passing a user id where a key
 *   belongs — is bought instead by the key never being constructed anywhere but
 *   `IdempotencyKeyGenerator`.
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String?,
    // The defaults are declared to Room and not only to Kotlin. A Kotlin default fills the
    // column for a row this code constructs; `defaultValue` is what fills it for the rows
    // already in a version-1 database when `MIGRATION_1_2` adds the column, and what makes
    // the schema Room generates match the SQL that migration runs. Without them the migration
    // and the entity describe two different tables and Room fails validation at open time.
    @ColumnInfo(defaultValue = "0") val version: Long = 0L,
    @ColumnInfo(defaultValue = "") val locallyChanged: Set<UserField> = emptySet(),
    // No `defaultValue`, unlike the two above, and that is the correct difference rather than
    // an oversight: a nullable column added by `ALTER TABLE … ADD COLUMN … TEXT` gets SQL NULL
    // for the existing rows without a DEFAULT clause, and declaring one here would make Room's
    // generated schema disagree with the migration that MIGRATION_3_4 actually runs.
    val pendingChangeKey: String? = null,
)

fun UserEntity.toDomain(): User = User(
    id = id,
    displayName = displayName,
    email = email,
    avatarUrl = avatarUrl,
)

/** The row as the conflict resolvers see it: the fields, plus the sync bookkeeping. */
fun UserEntity.toVersioned(): VersionedUser = VersionedUser(
    user = toDomain(),
    version = version,
    locallyChanged = locallyChanged,
)

/**
 * The row to store, with the idempotency key that names its pending edit.
 *
 * ## Why the key is a parameter and not carried on [VersionedUser]
 *
 * Because it is bookkeeping *about* the pending set rather than part of it, and because
 * `ConflictResolver` is the one thing that must not have an opinion on it. The resolvers are
 * pure functions over the fields and the version — that is what lets them run inside a Room
 * transaction — and a key threaded through them would have to be decided three times, once per
 * policy, to arrive at the same answer each time.
 *
 * The answer, made once, at this boundary: **carry the existing key while anything is still
 * pending, and drop it when nothing is.** Callers express that through
 * [keyForResolvedPendingSet]; the only caller that passes a *new* key is `saveUser`, because a
 * local edit is the only thing that creates a mutation.
 *
 * @param pendingChangeKey must be non-null whenever [VersionedUser.locallyChanged] is
 *   non-empty. The type cannot say so, so `UserRepositoryImplIdempotencyKeyTest` does.
 */
fun VersionedUser.toEntity(pendingChangeKey: String?): UserEntity = UserEntity(
    id = user.id,
    displayName = user.displayName,
    email = user.email,
    avatarUrl = user.avatarUrl,
    version = version,
    locallyChanged = locallyChanged,
    pendingChangeKey = pendingChangeKey,
)

/**
 * The key a row should keep once a conflict resolution has decided what is still pending.
 *
 * A resolution can only ever shrink the pending set — a fetched row carries none of this
 * client's edits, so `MergeConflictResolver` intersects and `LastWriteWinsConflictResolver`
 * clears — so the key that named the mutation still names it, right up to the moment the set
 * empties and there is no mutation left to name.
 *
 * Minting a fresh key here instead would be the subtle way to break the whole mechanism: every
 * fetch that arrived while a push was in flight would rename the mutation mid-send, and the
 * retry after a lost response would introduce itself to the server as a new change.
 *
 * @receiver the row as the resolver decided it should be.
 * @param previousKey the key the stored row was holding, or `null` if it held none.
 */
fun VersionedUser.keyForResolvedPendingSet(previousKey: String?): String? =
    previousKey.takeIf { hasLocalEdits }
