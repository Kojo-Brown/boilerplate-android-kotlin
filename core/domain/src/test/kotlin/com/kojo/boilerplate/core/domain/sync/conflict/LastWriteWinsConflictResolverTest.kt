package com.kojo.boilerplate.core.domain.sync.conflict

import com.kojo.boilerplate.core.domain.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What [ConflictPolicy.LAST_WRITE_WINS] does with a genuine conflict, which is to lose the
 * local edit.
 *
 * These assertions read as bugs and are the specification: the policy is documented as
 * discarding an unpushed change, and a suite that only asserted the comfortable half of that
 * would not be describing it. The comparison with `MergeConflictResolverTest` over the same
 * fixture is the argument for `ConflictPolicy.DEFAULT` being [ConflictPolicy.MERGE].
 */
class LastWriteWinsConflictResolverTest {

    private val resolver = LastWriteWinsConflictResolver()

    private val ada = User(
        id = "1",
        displayName = "Ada",
        email = "ada@example.com",
        avatarUrl = null,
    )

    /**
     * The same fixture as `MergeConflictResolverTest`'s headline case, where the local rename
     * survives. Here a server change to an unrelated field is enough to take it away.
     */
    @Test
    fun `a newer server row discards a local edit to an unrelated field`() {
        val local = VersionedUser(
            user = ada.copy(displayName = "Ada Renamed"),
            version = 3,
            locallyChanged = setOf(UserField.DISPLAY_NAME),
        )
        val remote = VersionedUser(
            user = ada.copy(avatarUrl = "https://cdn.example.com/fake-avatar.png"),
            version = 4,
        )

        assertEquals(ConflictResolution.Write(remote), resolver.resolve(local, remote))
    }

    /**
     * The row that results carries nothing pending. That is not incidental: the edit is gone,
     * so a row still claiming to hold one would keep winning conflicts on behalf of a value
     * no longer stored anywhere.
     */
    @Test
    fun `the row written after a discard has nothing pending`() {
        val local = VersionedUser(
            user = ada.copy(displayName = "Ada Renamed", email = "ada@local.example.com"),
            version = 3,
            locallyChanged = setOf(UserField.DISPLAY_NAME, UserField.EMAIL),
        )
        val remote = VersionedUser(ada, version = 4)

        val written = (resolver.resolve(local, remote) as ConflictResolution.Write).record

        assertEquals(emptySet<UserField>(), written.locallyChanged)
        assertEquals(ada, written.user)
        assertEquals(4L, written.version)
    }

    /**
     * "Last write" is the highest version, not the latest arrival — the distinction the name
     * obscures. A response that arrives now carrying an older version still loses, which is
     * what makes this policy safe to run over a concurrent fan-out at all.
     */
    @Test
    fun `an older version loses however recently it arrived`() {
        val local = VersionedUser(ada.copy(displayName = "Newer"), version = 8)
        val remote = VersionedUser(ada.copy(displayName = "Older"), version = 2)

        assertEquals(ConflictResolution.KeepLocal, resolver.resolve(local, remote))
    }
}
