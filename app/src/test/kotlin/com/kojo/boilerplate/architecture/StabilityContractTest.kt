package com.kojo.boilerplate.architecture

import androidx.lifecycle.ViewModel
import com.kojo.boilerplate.core.ui.adaptive.AdaptiveNavItem
import com.kojo.boilerplate.feature.home.HomeItem
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Holds every type Compose reads to the stability contract `CLAUDE.md` asks for:
 * `@Immutable`/`@Stable` on the declaration, `val` properties only, and no property typed as
 * a `kotlin.collections` interface.
 *
 * ### Why this exists rather than trusting the compiler
 *
 * The Compose compiler infers stability and reports it in a metrics file nobody reads. When
 * an inference flips from stable to unstable — someone adds a `List` field to a state class —
 * nothing fails. The screen just stops skipping, silently, and the cost surfaces later as
 * jank that has to be traced back to a one-line change made months earlier.
 *
 * `@Immutable` is worse than silent: it is an *unchecked promise*. The compiler takes the
 * annotation at its word and skips recomposition on the strength of it, so a `var` or a
 * mutable collection behind one produces a screen showing stale data with no diagnostic
 * anywhere. That is the failure this class is really here to prevent.
 *
 * ### How the roots are found
 *
 * Not from a hand-written list — a list omits the state class added next month, which is
 * exactly the case worth catching. Every `ViewModel` in the app is read out of the compiled
 * output of every module via [CompiledApp], its `StateFlow` properties are collected, and
 * their type arguments become the roots. From each root the check walks everything reachable: sealed subclasses, then
 * property types, then their type arguments.
 *
 * Only `StateFlow` is walked. A `Flow` of one-shot events is consumed by `ObserveAsEvents`
 * and never held across a recomposition, so stability says nothing about it.
 */
class StabilityContractTest {

    @Test
    fun `every view model exposes only stable state`() {
        val violations = walkStateGraph().violations().distinct()

        assertTrue(violations.isEmpty()) {
            "Compose state types must satisfy the stability contract:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Pins the state properties typed as a cold [Flow], because whether one is stable is a claim
     * about the view model that produces it rather than about the type.
     *
     * `Flow` is an interface, so the compiler can infer nothing and treats anything holding one
     * as unstable. The annotation can still be *truthful*, and for `HomeUiState.users` it is: the
     * view model builds exactly one `cachedIn` stream and copies the same reference into every
     * state it emits, so the property never changes and reference equality — which is the only
     * equality a `Flow` has — is stable across emissions. That is precisely what `@Immutable`
     * asserts and what the compiler cannot see.
     *
     * It is also a promise that is easy to break by accident, in a way nothing else here would
     * catch: a view model that built its flow *inside* the state transform would hand the screen
     * a new instance on every emission, and a `collectAsLazyPagingItems()` over it would rebuild
     * its presenter and restart paging from page one on every keystroke. The screen would still
     * render. So the exception is a pin rather than an allowance — the next `Flow` property has
     * to arrive through this list, which is where the argument for it gets made.
     */
    @Test
    fun `flow-typed state stays where its identity was argued for`() {
        assertEquals(
            EXPECTED_FLOW_PROPERTIES,
            walkStateGraph().flowProperties().sorted(),
            "The set of Flow-typed state properties changed. A `Flow` is stable only while the " +
                "same instance is handed to every state a view model emits — say why that holds " +
                "for this one, and add it here.",
        )
    }

    /**
     * Guards the discovery itself. If the walk silently found nothing — a feature module `:app`
     * stopped depending on, a classloader that no longer exposes a file path — the test above
     * would pass having checked zero types, which is the one way an audit like this fails
     * useless.
     */
    @Test
    fun `discovery finds every view model in the app`() {
        val found = viewModels()
        assertEquals(
            EXPECTED_VIEW_MODELS,
            found.map { it.simpleName }.sorted(),
            "The set of ViewModels changed. Update EXPECTED_VIEW_MODELS once the new one's " +
                "state has been confirmed to satisfy the contract above.",
        )
    }

    // Walking the graph

    /**
     * Everything reachable from the state roots, collected once.
     *
     * The walk is shared rather than run per rule because the two tests above ask different
     * questions of the same graph — "does anything violate the contract" and "which properties
     * are the pinned exception" — and a second traversal is a second definition of what the
     * graph is.
     */
    private class StateGraph {

        private val seen = mutableSetOf<KClass<*>>()

        val declarations = mutableListOf<KClass<*>>()

        val properties = mutableListOf<StateProperty>()

        fun visit(type: KClass<*>) {
            if (!seen.add(type) || !type.isOwnedByThisApp()) return
            declarations += type
            type.sealedSubclasses.forEach { visit(it) }
            type.memberProperties.forEach { property ->
                properties += StateProperty(
                    owner = type,
                    name = property.name,
                    isMutable = property is KMutableProperty<*>,
                    type = property.returnType,
                )
                property.returnType.appTypesWithin().forEach { visit(it) }
            }
        }

        private fun KClass<*>.isOwnedByThisApp(): Boolean =
            qualifiedName?.startsWith(APP_PACKAGE) == true

        /** The type itself plus every type argument, restricted to types this app declares. */
        private fun KType.appTypesWithin(): List<KClass<*>> =
            (listOf(jvmErasure) + arguments.mapNotNull { it.type?.jvmErasure })
                .filter { it.isOwnedByThisApp() }
    }

    /** One property reachable from a state root. */
    private class StateProperty(
        val owner: KClass<*>,
        val name: String,
        val isMutable: Boolean,
        val type: KType,
    ) {
        val qualifiedName: String get() = "${owner.qualifiedName}.$name"
    }

    private fun walkStateGraph(): StateGraph = StateGraph().apply {
        (discoverStateRoots() + ADDITIONAL_ROOTS).forEach { visit(it) }
    }

    private fun StateGraph.violations(): List<String> =
        declarations.flatMap { checkDeclaration(it) } + properties.flatMap { checkProperty(it) }

    private fun StateGraph.flowProperties(): List<String> = properties
        .filter { it.type.jvmErasure == Flow::class }
        .map { it.qualifiedName }

    private fun checkDeclaration(type: KClass<*>): List<String> = when {
        // Every enum is stable to the Compose compiler, so an annotation would add nothing.
        type.java.isEnum -> emptyList()
        type.hasStabilityAnnotation() -> emptyList()
        // An abstract type's stability cannot be inferred from its own body, so Compose
        // treats it as unstable however its subclasses are written. `isSealed` is tested
        // separately because Kotlin reports a sealed class's modality as SEALED, not
        // ABSTRACT, so `isAbstract` is false for exactly the state hierarchies this
        // codebase is built out of.
        type.isAbstract || type.isSealed || type.java.isInterface ->
            listOf("${type.qualifiedName} is abstract and carries no @Immutable/@Stable")
        // A `data object` has no state that can go stale; annotating each one is noise.
        type.memberProperties.isEmpty() -> emptyList()
        else -> listOf("${type.qualifiedName} has properties but carries no @Immutable/@Stable")
    }

    private fun checkProperty(property: StateProperty): List<String> {
        val where = property.qualifiedName
        val type = property.type
        val erasure = type.jvmErasure.qualifiedName
        val mutability = if (property.isMutable) {
            listOf("$where is a var; Compose state must be val")
        } else {
            emptyList()
        }
        // A collection gets the message that names its fix and not the generic one as well;
        // one property should not produce two lines saying the same thing.
        val instability = when {
            erasure in UNSTABLE_COLLECTIONS ->
                listOf("$where is a $erasure; use kotlinx.collections.immutable.Immutable{List,Set,Map}")
            // A cold Flow held by identity — allowed only where it has been argued for, and
            // reported against the pin rather than against the type. See the pinning test above.
            type.jvmErasure == Flow::class ->
                if (where in EXPECTED_FLOW_PROPERTIES) {
                    emptyList()
                } else {
                    listOf(
                        "$where is a Flow, which is stable only while one instance is handed to " +
                            "every state emitted; pin it in EXPECTED_FLOW_PROPERTIES with the " +
                            "reason that holds",
                    )
                }
            type.jvmErasure.isStableToCompose() -> emptyList()
            else -> listOf("$where is typed $erasure, which Compose cannot treat as stable")
        }
        return mutability + instability
    }

    private fun KClass<*>.isStableToCompose(): Boolean {
        val name = qualifiedName.orEmpty()
        return when {
            name in KNOWN_STABLE -> true
            name in STABLE_IMMUTABLE_COLLECTIONS -> true
            java.isEnum -> true
            // Compose treats function types as stable and memoizes the lambda at the call
            // site. Kotlin reflection reports these as `kotlin.FunctionN`; the JVM interface
            // they compile to is checked too, since which one surfaces is an implementation
            // detail of the reflection layer.
            name.startsWith("kotlin.Function") -> true
            name.startsWith("kotlin.jvm.functions.Function") -> true
            else -> hasStabilityAnnotation()
        }
    }

    // Reading @Immutable / @Stable

    /**
     * Both annotations are declared `@Retention(AnnotationRetention.BINARY)`, so they are
     * written into the class file and then dropped by the JVM — `KClass.annotations` and
     * `Class.isAnnotationPresent` come back empty for them. The class file is therefore the
     * only place left to look, and the annotation's type descriptor lands in the constant
     * pool whenever it is applied. Searching for that descriptor cannot miss an application;
     * it could in principle match a class that names the annotation type for some other
     * reason, which nothing here does.
     */
    private fun KClass<*>.hasStabilityAnnotation(): Boolean {
        val resource = java.name.replace('.', '/') + ".class"
        val loader = java.classLoader ?: ClassLoader.getSystemClassLoader()
        val bytes = loader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("No class file on the classpath for $qualifiedName")
        return STABILITY_DESCRIPTORS.any { bytes.containsBytes(it.toByteArray(Charsets.UTF_8)) }
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start ->
            needle.indices.all { this[start + it] == needle[it] }
        }

    // Discovery

    private fun discoverStateRoots(): List<KClass<*>> = viewModels()
        .flatMap { viewModel ->
            viewModel.kotlin.memberProperties
                .filter { it.returnType.jvmErasure == StateFlow::class }
                .mapNotNull { it.returnType.arguments.firstOrNull()?.type?.jvmErasure }
        }
        .distinct()

    /**
     * The screens' view models: every `ViewModel` this app compiles, minus the abstract ones.
     *
     * `UdfViewModel` is the reason for the second half. It is a `ViewModel`, so it satisfies
     * the assignability test, and it has a `state: StateFlow<S>` — but `S` is a type parameter,
     * so there is no state type behind it to walk, and counting it here would mean listing a
     * base class among the screens it is a base class *for*. An abstract view model has no
     * state of its own by construction; its subclasses are where the roots are, and every one
     * of them is in this list.
     */
    private fun viewModels(): List<Class<*>> = CompiledApp.classes().filter {
        ViewModel::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers)
    }

    private companion object {
        const val APP_PACKAGE = CompiledApp.PACKAGE

        /**
         * Types no `StateFlow` reaches, so discovery cannot find them. They are read during
         * composition all the same and the contract is the same.
         *
         * [AdaptiveNavItem] is a composable's parameter and was never on a state class at all.
         * [HomeItem] is the other shape this happens in, and the more instructive one: it *is*
         * on `HomeUiState`, as the argument of a `Flow<PagingData<HomeItem>>`. The walk follows
         * a type's arguments only while the types themselves belong to this app, and `PagingData`
         * does not — so wrapping a model in a framework generic silently takes it out of scope.
         * Every row of the list is that model, so it is put back by hand.
         */
        val ADDITIONAL_ROOTS = listOf(AdaptiveNavItem::class, HomeItem::class)

        /**
         * The state properties typed as a cold [Flow], each of which is a promise this file
         * cannot check: that one instance is handed to every state the view model emits. The
         * pinning test is where the argument for each belongs.
         */
        val EXPECTED_FLOW_PROPERTIES = listOf(
            "com.kojo.boilerplate.feature.home.HomeUiState.users",
        )

        val STABILITY_DESCRIPTORS = listOf(
            "Landroidx/compose/runtime/Immutable;",
            "Landroidx/compose/runtime/Stable;",
        )

        /**
         * Alphabetical, and asserted whole rather than as a subset: a ViewModel that
         * disappears changes this contract's coverage as much as one that appears.
         */
        val EXPECTED_VIEW_MODELS = listOf(
            "BarcodeScannerViewModel",
            "GoogleSignInViewModel",
            "HomeViewModel",
            "ProfileDetailPaneViewModel",
            "ProfileViewModel",
            "TextRecognitionViewModel",
        )

        /**
         * Read-only *interfaces* over collections that may well be an `ArrayList` underneath.
         * Listed separately from the general stability check so the failure names the fix.
         */
        val UNSTABLE_COLLECTIONS = setOf(
            "kotlin.collections.Collection",
            "kotlin.collections.Iterable",
            "kotlin.collections.List",
            "kotlin.collections.Map",
            "kotlin.collections.MutableList",
            "kotlin.collections.MutableMap",
            "kotlin.collections.MutableSet",
            "kotlin.collections.Set",
        )

        /** Mirrors the Compose compiler's `KnownStableConstructs` entries for this artifact. */
        val STABLE_IMMUTABLE_COLLECTIONS = setOf(
            "kotlinx.collections.immutable.ImmutableCollection",
            "kotlinx.collections.immutable.ImmutableList",
            "kotlinx.collections.immutable.ImmutableMap",
            "kotlinx.collections.immutable.ImmutableSet",
            "kotlinx.collections.immutable.PersistentCollection",
            "kotlinx.collections.immutable.PersistentList",
            "kotlinx.collections.immutable.PersistentMap",
            "kotlinx.collections.immutable.PersistentSet",
        )

        /** Primitives and the stdlib types the Compose compiler hard-codes as stable. */
        val KNOWN_STABLE = setOf(
            "kotlin.Boolean",
            "kotlin.Byte",
            "kotlin.Char",
            "kotlin.Double",
            "kotlin.Float",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Nothing",
            "kotlin.Short",
            "kotlin.String",
            "kotlin.Unit",
        )
    }
}
