package com.kojo.boilerplate.architecture

import androidx.lifecycle.ViewModel
import com.kojo.boilerplate.core.ui.adaptive.AdaptiveNavItem
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure
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
        val roots = discoverStateRoots() + ADDITIONAL_ROOTS
        val violations = roots.flatMap { root -> violationsIn(root, mutableSetOf()) }.distinct()

        assertTrue(violations.isEmpty()) {
            "Compose state types must satisfy the stability contract:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
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

    private fun violationsIn(type: KClass<*>, seen: MutableSet<KClass<*>>): List<String> {
        if (!seen.add(type) || !type.isOwnedByThisApp()) return emptyList()
        return checkDeclaration(type) +
            type.sealedSubclasses.flatMap { violationsIn(it, seen) } +
            type.memberProperties.flatMap { property ->
                val isVar = property is KMutableProperty<*>
                checkProperty(type, property.name, isVar, property.returnType) +
                    property.returnType.appTypesWithin().flatMap { violationsIn(it, seen) }
            }
    }

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

    private fun checkProperty(
        owner: KClass<*>,
        name: String,
        isMutable: Boolean,
        type: KType,
    ): List<String> {
        val where = "${owner.qualifiedName}.$name"
        val erasure = type.jvmErasure.qualifiedName
        val mutability = if (isMutable) {
            listOf("$where is a var; Compose state must be val")
        } else {
            emptyList()
        }
        // A collection gets the message that names its fix and not the generic one as well;
        // one property should not produce two lines saying the same thing.
        val instability = when {
            erasure in UNSTABLE_COLLECTIONS ->
                listOf("$where is a $erasure; use kotlinx.collections.immutable.Immutable{List,Set,Map}")
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

    /** The type itself plus every type argument, restricted to types this app declares. */
    private fun KType.appTypesWithin(): List<KClass<*>> =
        (listOf(jvmErasure) + arguments.mapNotNull { it.type?.jvmErasure })
            .filter { it.isOwnedByThisApp() }

    private fun KClass<*>.isOwnedByThisApp(): Boolean =
        qualifiedName?.startsWith(APP_PACKAGE) == true

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
         * Composable parameter types that no `StateFlow` reaches, so discovery cannot find
         * them. They are inputs to a composable all the same and the contract is the same.
         */
        val ADDITIONAL_ROOTS = listOf(AdaptiveNavItem::class)

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
