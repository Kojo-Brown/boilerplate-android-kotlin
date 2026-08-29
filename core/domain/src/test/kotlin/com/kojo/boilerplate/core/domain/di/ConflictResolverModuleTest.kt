package com.kojo.boilerplate.core.domain.di

import com.kojo.boilerplate.core.domain.sync.conflict.ConflictPolicy
import com.kojo.boilerplate.core.domain.sync.conflict.LastWriteWinsConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.MergeConflictResolver
import javax.inject.Provider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * That the module hands back the resolver [ConflictPolicy.DEFAULT] names.
 *
 * The `when` inside it is exhaustive, so the compiler already refuses a policy with no branch.
 * What the compiler cannot see is a branch pointing at the wrong resolver —
 * `LAST_WRITE_WINS -> merge.get()` type-checks perfectly — and that is what these cases are
 * for. It is the same class of mistake `SyncStrategyFactory` guards against at runtime, caught
 * here instead because the choice is made once rather than per call.
 */
class ConflictResolverModuleTest {

    private var lastWriteWinsConstructions = 0
    private var mergeConstructions = 0

    private val lastWriteWins = Provider {
        lastWriteWinsConstructions++
        LastWriteWinsConflictResolver()
    }

    private val merge = Provider {
        mergeConstructions++
        MergeConflictResolver()
    }

    private fun provide() = ConflictResolverModule.provideConflictResolver(lastWriteWins, merge)

    @Test
    fun `the resolver provided is the one the default policy names`() {
        assertEquals(ConflictPolicy.DEFAULT, provide().policy)
    }

    /**
     * The branch not taken is never constructed. Both resolvers are free to build today, so
     * this asserts the shape rather than a saving: a resolver that grows a dependency must not
     * have it built by an app configured to use the other policy.
     */
    @Test
    fun `only the selected resolver is constructed`() {
        provide()

        assertEquals(1, lastWriteWinsConstructions + mergeConstructions)
        assertFalse(lastWriteWinsConstructions > 0 && mergeConstructions > 0)
    }

    /**
     * The app ships with merging on. Stated here rather than only in a KDoc because it is a
     * product decision with a visible consequence — under the other policy the six-hourly
     * background sync would silently undo a profile edit — and a change to it should have to
     * be made twice, deliberately.
     */
    @Test
    fun `the app is wired to merge`() {
        assertEquals(ConflictPolicy.MERGE, ConflictPolicy.DEFAULT)
        assertEquals(ConflictPolicy.MERGE, provide().policy)
    }
}
