package com.kojo.boilerplate.core.domain.sync.conflict

import com.kojo.boilerplate.core.domain.model.User

/**
 * A [User] together with everything needed to decide whether it should overwrite another copy
 * of itself.
 *
 * ## Why this is not on [User]
 *
 * [User] is what a screen renders. A version counter and a set of pending edits are neither
 * rendered nor meaningful to a screen, and putting them there would put them in every
 * `UiState`, every preview fixture and every equality check that asks "did what the user sees
 * change?" — where a version bump carrying identical fields would read as a change. Keeping
 * the two apart is also what lets `getUser`/`getUsers` keep returning `User`: the sync
 * bookkeeping exists between the network and the database and stops there.
 *
 * @property user the fields themselves.
 * @property version the server's version of this row — the value the server had when [user]'s
 *   non-[locallyChanged] fields were fetched. Monotonic per row and assigned by the server,
 *   never by this client: a client that invents versions is a client that can talk another
 *   one out of a change it should have taken. A row that has never been synced is version `0`,
 *   which is below every version a server can assign, so the first fetch always wins over it.
 * @property locallyChanged the fields carrying an edit this client made and the server has not
 *   acknowledged. Empty for anything that came off the network, and empty for a row that is a
 *   faithful copy of what the server last said.
 */
data class VersionedUser(
    val user: User,
    val version: Long,
    val locallyChanged: Set<UserField> = emptySet(),
) {

    /** Whether this row carries an edit the server has not seen. */
    val hasLocalEdits: Boolean get() = locallyChanged.isNotEmpty()
}
