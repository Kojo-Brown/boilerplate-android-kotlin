package com.kojo.boilerplate.core.network.model

import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The body of a user update: which fields this client changed, and what it changed them to.
 *
 * ## Why [changedFields] exists rather than the absence of a key meaning "unchanged"
 *
 * Because `avatarUrl` is nullable, and "the user cleared their avatar" and "this update does
 * not concern the avatar" are different instructions that both serialise to `null`. The
 * canonical fix is JSON Merge Patch (RFC 7396), where presence is the signal — but `provideJson`
 * sets `encodeDefaults = true` for the whole app, so kotlinx writes every property of every DTO
 * whether or not it differs from its default, and presence carries no information here. Turning
 * that off globally to make one request expressive would silently change the wire format of
 * `LoginRequest` and `RefreshTokenRequest` too.
 *
 * Naming the changed fields explicitly is the small, local answer: it survives
 * `encodeDefaults`, it says what it means, and it makes the body a faithful copy of
 * `UserEntity.locallyChanged` — which is the property the idempotency key depends on. See
 * "the payload is a function of the key" in `docs/idempotency.md`.
 *
 * ## What is not in it
 *
 * The version. A base version would let the server reject an update written against a row it
 * has since changed, which is worth having and is not this item: the client's copy of the
 * version is an ordering aid for local conflict resolution (`docs/conflict-resolution.md`), and
 * turning it into a wire-level precondition is an API contract — a header, a `412`, and a
 * defined recovery — rather than a field. `docs/idempotency.md` carries it as the known gap it
 * is. Nothing here loses an edit without it: a rejected-in-hindsight update is reconciled by
 * the next fetch, through the resolver that already handles exactly that.
 *
 * @property changedFields the wire names of the fields being changed, in [UserField]
 *   declaration order. Never empty — a push with nothing pending is not sent at all.
 * @property displayName the new value when `display_name` is in [changedFields], `null`
 *   otherwise. A reader must consult [changedFields] first; this being `null` on its own means
 *   nothing.
 * @property email as [displayName], for `email`.
 * @property avatarUrl as [displayName], for `avatar_url` — except that `null` here is a value
 *   in its own right when the field is named, and is what clears an avatar.
 */
@Serializable
data class UpdateUserRequest(
    @SerialName("changed_fields") val changedFields: List<String>,
    @SerialName("display_name") val displayName: String?,
    @SerialName("email") val email: String?,
    @SerialName("avatar_url") val avatarUrl: String?,
)

/**
 * The update that sends exactly [changed] and nothing else.
 *
 * A field outside [changed] is sent as `null` and excluded from
 * [UpdateUserRequest.changedFields], so a value this client happens to be holding for a field
 * it did not edit — which may be the server's own, or may be stale — cannot travel back and
 * overwrite anything.
 *
 * @param user the row as stored locally, whose [changed] fields carry the local values.
 * @param changed the pending field set. Must not be empty; the caller has already established
 *   that there is something to send, and an empty update is a request that asks the server to
 *   do nothing while still consuming an idempotency key.
 */
fun updateUserRequest(user: User, changed: Set<UserField>): UpdateUserRequest {
    require(changed.isNotEmpty()) { "an update with no changed fields must not be sent" }

    return UpdateUserRequest(
        changedFields = UserField.entries.filter { it in changed }.map { it.wireName },
        displayName = user.displayName.takeIf { UserField.DISPLAY_NAME in changed },
        email = user.email.takeIf { UserField.EMAIL in changed },
        avatarUrl = user.avatarUrl.takeIf { UserField.AVATAR_URL in changed },
    )
}

/**
 * The name this field goes by on the wire, which is the `@SerialName` [UserDto] reads it under.
 *
 * An exhaustive `when` and not a property on [UserField]: the enum lives in `:core:domain`,
 * which is deliberately free of anything to do with transport, and a field's JSON name is a
 * fact about this API rather than about the field. Exhaustive so that adding a fourth
 * [UserField] fails this compile instead of shipping an update the server silently ignores.
 */
private val UserField.wireName: String
    get() = when (this) {
        UserField.DISPLAY_NAME -> "display_name"
        UserField.EMAIL -> "email"
        UserField.AVATAR_URL -> "avatar_url"
    }
