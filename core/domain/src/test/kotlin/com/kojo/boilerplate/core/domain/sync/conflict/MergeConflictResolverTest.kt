package com.kojo.boilerplate.core.domain.sync.conflict

import com.kojo.boilerplate.core.domain.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one case a policy exists to answer: the server has moved on, and this client is holding
 * an edit the server has not seen.
 *
 * Every case here reaches [MergeConflictResolver.reconcile], which means every `local` carries
 * a pending field and a version strictly below its `remote`. The shared cases that stop short
 * of that are in `VersionOrderedConflictResolverTest`.
 */
class MergeConflictResolverTest {

    private val resolver = MergeConflictResolver()

    private val ada = User(
        id = "1",
        displayName = "Ada",
        email = "ada@example.com",
        avatarUrl = null,
    )

    private fun resolve(local: VersionedUser, remote: VersionedUser): VersionedUser {
        val resolution = resolver.resolve(local, remote)
        assertTrue(resolution is ConflictResolution.Write, "expected a write, got $resolution")
        return (resolution as ConflictResolution.Write).record
    }

    /**
     * The headline case, and the one [ConflictPolicy.LAST_WRITE_WINS] gets wrong: two fields
     * changed on two sides, and both changes survive.
     */
    @Test
    fun `a local edit and a server edit to different fields both survive`() {
        val local = VersionedUser(
            user = ada.copy(displayName = "Ada Renamed"),
            version = 3,
            locallyChanged = setOf(UserField.DISPLAY_NAME),
        )
        val remote = VersionedUser(
            user = ada.copy(avatarUrl = "https://cdn.example.com/fake-avatar.png"),
            version = 4,
        )

        val merged = resolve(local, remote)

        assertEquals("Ada Renamed", merged.user.displayName)
        assertEquals("https://cdn.example.com/fake-avatar.png", merged.user.avatarUrl)
    }

    /** A field nobody touched locally takes the server's value even when it changed. */
    @Test
    fun `a field with no local edit takes the server value`() {
        val local = VersionedUser(
            user = ada.copy(displayName = "Ada Renamed"),
            version = 3,
            locallyChanged = setOf(UserField.DISPLAY_NAME),
        )
        val remote = VersionedUser(ada.copy(email = "ada@new.example.com"), version = 4)

        assertEquals("ada@new.example.com", resolve(local, remote).user.email)
    }

    /**
     * The merged row takes the server's version, and that is what makes merging terminate.
     * Keeping the local version would leave the row permanently behind, so every later fetch
     * would arrive as a fresh conflict and re-run this same merge forever.
     */
    @Test
    fun `the merged row records the server version`() {
        val local = VersionedUser(
            user = ada.copy(displayName = "Ada Renamed"),
            version = 3,
            locallyChanged = setOf(UserField.DISPLAY_NAME),
        )

        assertEquals(9L, resolve(local, VersionedUser(ada, version = 9)).version)
    }

    /** An edit that has not reached the server yet is still pending after the merge. */
    @Test
    fun `an unpushed field stays pending`() {
        val local = VersionedUser(
            user = ada.copy(displayName = "Ada Renamed"),
            version = 3,
            locallyChanged = setOf(UserField.DISPLAY_NAME),
        )

        assertEquals(
            setOf(UserField.DISPLAY_NAME),
            resolve(local, VersionedUser(ada, version = 4)).locallyChanged,
        )
    }

    /**
     * Convergence. Once the server's row carries the same value, the field is no longer
     * divergent — whether the edit was pushed or someone else made the same change, which
     * this client cannot tell apart and does not need to. Leaving it marked would mean
     * re-applying a value identical to the server's on every sync, forever.
     */
    @Test
    fun `a field the server has caught up with stops being pending`() {
        val renamed = ada.copy(displayName = "Ada Renamed")
        val local = VersionedUser(renamed, version = 3, locallyChanged = setOf(UserField.DISPLAY_NAME))

        val merged = resolve(local, VersionedUser(renamed, version = 4))

        assertEquals(emptySet<UserField>(), merged.locallyChanged)
        assertEquals("Ada Renamed", merged.user.displayName)
        assertEquals(4L, merged.version)
    }

    /**
     * The mixed case: one pending field has converged and one has not. Only the one still
     * diverging is re-applied and only it stays marked — which is the difference between a
     * dirty set that drains and one that never does.
     */
    @Test
    fun `convergence is per field`() {
        val local = VersionedUser(
            user = ada.copy(displayName = "Ada Renamed", email = "ada@local.example.com"),
            version = 3,
            locallyChanged = setOf(UserField.DISPLAY_NAME, UserField.EMAIL),
        )
        val remote = VersionedUser(ada.copy(displayName = "Ada Renamed"), version = 4)

        val merged = resolve(local, remote)

        assertEquals(setOf(UserField.EMAIL), merged.locallyChanged)
        assertEquals("ada@local.example.com", merged.user.email)
        assertEquals("Ada Renamed", merged.user.displayName)
    }

    /**
     * A null is a value, not an absence. Clearing an avatar locally is an edit that has to
     * beat a server row still carrying one, and treating `null` as "nothing to apply" is the
     * ordinary way a merge loses a deletion.
     */
    @Test
    fun `clearing a field locally beats a server value`() {
        val local = VersionedUser(
            user = ada.copy(avatarUrl = null),
            version = 3,
            locallyChanged = setOf(UserField.AVATAR_URL),
        )
        val remote = VersionedUser(
            user = ada.copy(avatarUrl = "https://cdn.example.com/fake-avatar.png"),
            version = 4,
        )

        assertEquals(null, resolve(local, remote).user.avatarUrl)
    }

    /**
     * Merging is idempotent once it has run: the merged row is at the server's version, so
     * re-delivering the same response is no longer a conflict and is declined outright.
     */
    @Test
    fun `re-delivering the same response after a merge writes nothing`() {
        val local = VersionedUser(
            user = ada.copy(displayName = "Ada Renamed"),
            version = 3,
            locallyChanged = setOf(UserField.DISPLAY_NAME),
        )
        val remote = VersionedUser(ada.copy(email = "ada@new.example.com"), version = 4)

        val merged = resolve(local, remote)

        assertEquals(ConflictResolution.KeepLocal, resolver.resolve(merged, remote))
    }
}
