package com.kojo.boilerplate.core.domain.di

import com.kojo.boilerplate.core.domain.sync.CurrentUserSyncStrategy
import com.kojo.boilerplate.core.domain.sync.SyncMode
import com.kojo.boilerplate.core.domain.sync.SyncStrategy
import com.kojo.boilerplate.core.domain.sync.VisibleUsersSyncStrategy
import com.kojo.boilerplate.core.testing.FakeUserRepository
import com.kojo.boilerplate.core.testing.syncStrategyFactoryOver
import dagger.Binds
import dagger.multibindings.IntoMap
import java.lang.reflect.Method
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Holds `SyncStrategyModule` to the two things Dagger will not check for it.
 *
 * Dagger rejects a duplicate map key and a bound type that is not a [SyncStrategy], so those
 * need no test. What compiles perfectly and is still wrong is a [SyncMode] with **no**
 * binding — `SyncStrategyFactory` then throws the first time anything asks for that mode,
 * which for a mode only a background worker uses could be long after release — and a binding
 * whose key names a different mode than the strategy itself does.
 *
 * There is no Hilt runtime in this source set (no `hilt-android-testing`, and these run on a
 * plain JVM), so the component cannot be built here and asked for its map. The module's own
 * annotations are the next best source and are read directly: `@Binds` methods carry
 * `RUNTIME` retention, so the key, the bound implementation and the bound-as type are all
 * legible through reflection without a graph.
 *
 * It is also what keeps `TestSyncStrategies.syncStrategyFactoryOver` — the hand-written stand-in
 * for the real map — from drifting away from the module it stands in for.
 */
class SyncStrategyModuleContractTest {

    private val repository = FakeUserRepository()

    /**
     * Every strategy this module is expected to bind, instantiated. Written out rather than
     * reflected into existence: constructing them by reflection would bake in the assumption
     * that a strategy takes exactly one `UserRepository`, and the first one that needs a
     * second dependency would fail here for a reason that has nothing to do with the contract.
     * A new strategy fails the roster assertion below instead, which says what to do about it.
     */
    private val documentedStrategies: List<SyncStrategy> = listOf(
        VisibleUsersSyncStrategy(repository),
        CurrentUserSyncStrategy(repository),
    )

    private val bindings: List<Method> = SyncStrategyModule::class.java.declaredMethods
        .filter { it.isAnnotationPresent(Binds::class.java) }

    @Test
    fun `every binding is a map contribution of a SyncStrategy keyed by a SyncMode`() {
        val malformed = bindings.filterNot { binding ->
            binding.isAnnotationPresent(IntoMap::class.java) &&
                binding.isAnnotationPresent(SyncModeKey::class.java) &&
                binding.returnType == SyncStrategy::class.java
        }

        assertEquals(
            emptyList<Method>(),
            malformed,
            "Every @Binds method in SyncStrategyModule must be @IntoMap, carry a " +
                "@SyncModeKey, and be bound as SyncStrategy — otherwise it does not reach " +
                "the map SyncStrategyFactory injects.",
        )
    }

    /**
     * The assertion this file exists for. A mode added to the enum and forgotten in the module
     * is a `create()` that throws, and nothing before this notices.
     */
    @Test
    fun `every sync mode is bound exactly once`() {
        // Null-safe rather than `!!`: a @Binds method with no key is already reported by the
        // test above, and dropping it here lets this one fail on the mode that is missing
        // instead of on a NullPointerException.
        val boundModes = bindings.mapNotNull { it.getAnnotation(SyncModeKey::class.java)?.value }

        assertEquals(
            SyncMode.entries.toList().sorted(),
            boundModes.sorted(),
            "The bound modes are not exactly the SyncMode constants. A mode with no binding " +
                "fails at the first SyncStrategyFactory.create for it; a mode bound twice is " +
                "already a Dagger error. Add or remove a @Binds @IntoMap method in " +
                "SyncStrategyModule.",
        )
    }

    /**
     * The roster, asserted whole, so a strategy cannot be added to the graph without the two
     * places that stand in for the graph in tests being updated with it.
     */
    @Test
    fun `the bound strategies are the ones this test knows about`() {
        val boundImplementations = bindings.map { it.parameterTypes.single().name }.sorted()

        assertEquals(
            documentedStrategies.map { it.javaClass.name }.sorted(),
            boundImplementations,
            "SyncStrategyModule binds a strategy this test has never constructed. Add it to " +
                "documentedStrategies here and to TestSyncStrategies.syncStrategyFactoryOver, " +
                "which is the map the ViewModel and use-case tests run against.",
        )
    }

    /**
     * Key against implementation. `@SyncModeKey(VISIBLE_USERS)` over a method binding
     * `CurrentUserSyncStrategy` is a well-typed lie that no compiler objects to;
     * `SyncStrategyFactory` catches it at runtime and this catches it in CI.
     */
    @Test
    fun `each strategy is bound under the mode it reports`() {
        val modesByImplementation = documentedStrategies.associate { it.javaClass.name to it.mode }

        val mismatches = bindings.mapNotNull { binding ->
            val implementation = binding.parameterTypes.single().name
            val key = binding.getAnnotation(SyncModeKey::class.java)?.value
            val declared = modesByImplementation[implementation]
            "$implementation is bound under $key but reports $declared".takeIf { declared != key }
        }

        assertEquals(emptyList<String>(), mismatches)
    }

    /**
     * The stand-in map answers for every mode the real one does. Without this, a strategy
     * added to the module could be missing from `syncStrategyFactoryOver` and every test that
     * uses it would keep passing until something asked for that mode.
     */
    @Test
    fun `the test factory resolves every bound mode`() {
        val factory = syncStrategyFactoryOver(repository)

        val resolvedModes = SyncMode.entries.map { factory.create(it).mode }

        assertTrue(
            resolvedModes.containsAll(SyncMode.entries.toList()),
            "TestSyncStrategies.syncStrategyFactoryOver resolved $resolvedModes, not every " +
                "SyncMode.",
        )
    }
}
