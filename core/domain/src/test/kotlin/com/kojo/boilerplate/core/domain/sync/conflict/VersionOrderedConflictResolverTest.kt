package com.kojo.boilerplate.core.domain.sync.conflict

import com.kojo.boilerplate.core.domain.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The five cases in [VersionOrderedConflictResolver], asserted against *both* policies.
 *
 * Four of the five are shared, and the point of running them twice is that they stay shared: a
 * policy that overrode one of them would be answering a question policies are not supposed to
 * answer, and the identical expectations below are what would catch it. The fifth case — the
 * conflict proper — is deliberately absent here and lives in each policy's own suite.
 */
class VersionOrderedConflictResolverTest {

    private val resolvers = listOf(LastWriteWinsConflictResolver(), MergeConflictResolver())

    private val ada = User(id = "1", displayName = "Ada", email = "ada@example.com")

    private fun stored(user: User = ada, version: Long, changed: Set<UserField> = emptySet()) =
        VersionedUser(user = user, version = version, locallyChanged = changed)

    private fun fetched(user: User = ada, version: Long) =
        VersionedUser(user = user, version = version)

    private fun eachResolver(assert: (ConflictResolver) -> Unit) = resolvers.forEach(assert)

    @Test
    fun `a row that is not stored yet is taken`() {
        val remote = fetched(version = 7)

        eachResolver { resolver ->
            assertEquals(
                ConflictResolution.Write(remote),
                resolver.resolve(local = null, remote = remote),
                resolver.policy.name,
            )
        }
    }

    /**
     * The out-of-order case, and the one an unconditional upsert got wrong. A fan-out issues
     * its requests concurrently and nothing makes the slower response the older one.
     */
    @Test
    fun `a response older than the stored row is discarded`() {
        val local = stored(version = 9)
        val remote = fetched(ada.copy(displayName = "Stale"), version = 4)

        eachResolver { resolver ->
            assertEquals(
                ConflictResolution.KeepLocal,
                resolver.resolve(local, remote),
                resolver.policy.name,
            )
        }
    }

    /**
     * A refresh that changed nothing writes nothing. Room invalidates the queries observing a
     * table on any write, identical row or not, so a list refresh over twenty unchanged rows
     * would otherwise re-emit to every screen showing them.
     */
    @Test
    fun `a response identical to the stored row writes nothing`() {
        val local = stored(version = 3)

        eachResolver { resolver ->
            assertEquals(
                ConflictResolution.KeepLocal,
                resolver.resolve(local, fetched(version = 3)),
                resolver.policy.name,
            )
        }
    }

    /**
     * Same fields, higher version. The write is not redundant even though nothing visible
     * changes: the version is what the *next* response is ordered against, and a row that
     * failed to record it would treat a later, genuinely newer response as a conflict.
     */
    @Test
    fun `a newer version carrying identical fields is still written`() {
        val local = stored(version = 3)
        val remote = fetched(version = 4)

        eachResolver { resolver ->
            assertEquals(
                ConflictResolution.Write(remote),
                resolver.resolve(local, remote),
                resolver.policy.name,
            )
        }
    }

    @Test
    fun `a newer version over a clean row is taken whole`() {
        val local = stored(version = 3)
        val remote = fetched(ada.copy(displayName = "Ada L", email = "ada.l@example.com"), version = 4)

        eachResolver { resolver ->
            assertEquals(
                ConflictResolution.Write(remote),
                resolver.resolve(local, remote),
                resolver.policy.name,
            )
        }
    }

    /**
     * Both policies protect an edit the server has not had a chance to see. A server still on
     * the version this row was fetched at has said nothing new, so the difference between the
     * two rows is this client's own unpushed change — and taking the server's copy of it is
     * how a refresh undoes the edit it was meant to confirm.
     */
    @Test
    fun `a local edit survives a response at the version it was fetched at`() {
        val local = stored(ada.copy(displayName = "Ada Renamed"), version = 3, changed = setOf(UserField.DISPLAY_NAME))

        eachResolver { resolver ->
            assertEquals(
                ConflictResolution.KeepLocal,
                resolver.resolve(local, fetched(version = 3)),
                resolver.policy.name,
            )
        }
    }

    /**
     * With no version support on the server every row sits at `0`, so only the clean-row
     * branch is reachable and the behaviour degrades to "the latest response wins" — which is
     * what this app did before the column existed. Asserted so that the degradation is a
     * property, not an accident: a change that made an unversioned response *lose* would stop
     * an unversioned backend from ever updating a row again.
     */
    @Test
    fun `without server versions a changed response still lands`() {
        val local = stored(version = 0)
        val remote = fetched(ada.copy(email = "ada@new.example.com"), version = 0)

        eachResolver { resolver ->
            assertEquals(
                ConflictResolution.Write(remote),
                resolver.resolve(local, remote),
                resolver.policy.name,
            )
        }
    }

    @Test
    fun `each resolver reports the policy it implements`() {
        assertEquals(ConflictPolicy.LAST_WRITE_WINS, LastWriteWinsConflictResolver().policy)
        assertEquals(ConflictPolicy.MERGE, MergeConflictResolver().policy)
    }

    /**
     * Every policy has an implementation. `ConflictResolverModule`'s `when` already fails to
     * compile if a policy has no branch, but a branch can be added pointing at a resolver that
     * reports something else, and this is what notices.
     */
    @Test
    fun `every policy is implemented exactly once`() {
        assertEquals(
            ConflictPolicy.entries.toSet(),
            resolvers.map { it.policy }.toSet(),
        )
        assertEquals(ConflictPolicy.entries.size, resolvers.size)
    }
}
