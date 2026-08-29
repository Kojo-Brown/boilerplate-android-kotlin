package com.kojo.boilerplate.core.network.model

import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.sync.conflict.VersionedUser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @property version the server's version of this row, incremented by the server on every
 *   change it makes. Conflict resolution is ordered by it — see `ConflictResolver`.
 *
 *   Defaulted, because a server that does not send the field is the case this client has to
 *   keep working against. At `0` every stored row stays at `0` too, so no fetch is ever
 *   strictly newer than what is stored and conflict resolution degrades to "the latest
 *   response wins" — which is what this app did before the column existed, so nothing
 *   regresses. What is still gained without server support is protection of unpushed local
 *   edits, which does not depend on the version having moved. The alternative, a version this
 *   client invents, would be worse than none: it would make responses look ordered when they
 *   are not, and the wrong one would win silently.
 */
@Serializable
data class UserDto(
    @SerialName("id") val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("email") val email: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("version") val version: Long = 0L,
)

fun UserDto.toDomain(): User = User(
    id = id,
    displayName = displayName,
    email = email,
    avatarUrl = avatarUrl,
)

/**
 * The response as a record the conflict resolvers can order.
 *
 * `locallyChanged` is empty and is not a parameter: a row that just came off the network
 * carries no edit of this client's by definition, and the resolvers rely on that — see
 * `ConflictResolver.resolve`.
 */
fun UserDto.toVersioned(): VersionedUser = VersionedUser(
    user = toDomain(),
    version = version,
)
