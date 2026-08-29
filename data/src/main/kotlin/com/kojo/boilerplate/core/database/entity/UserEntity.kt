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

fun VersionedUser.toEntity(): UserEntity = UserEntity(
    id = user.id,
    displayName = user.displayName,
    email = user.email,
    avatarUrl = user.avatarUrl,
    version = version,
    locallyChanged = locallyChanged,
)
