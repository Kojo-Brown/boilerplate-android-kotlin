package com.kojo.boilerplate.architecture

import androidx.lifecycle.ViewModel
import com.kojo.boilerplate.core.ui.udf.UdfViewModel
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Holds every view model in the app to the contract in `docs/unidirectional-data-flow.md`:
 * one state out, one way in, one effect stream, and nothing else public.
 *
 * ### Why this is a test rather than a convention
 *
 * The convention is one line in `CLAUDE.md` and it had already drifted before anyone noticed.
 * `HomeViewModel` grew to four `StateFlow`s and four public methods, `BarcodeScannerViewModel`
 * to two flows and five methods, and each addition was locally reasonable — a second flow for
 * the flash, a fourth method for dismissing a banner. Nothing failed, because nothing was
 * checking. What it cost is in the doc: two flows that could disagree with each other, and a
 * screen that had to know which of eight members to call for what.
 *
 * The additions this catches are exactly the ones that look harmless. A `val isExpanded:
 * StateFlow<Boolean>` next to `state` is one line and one more thing a composable must
 * collect, and it is the first of the four that `HomeViewModel` ended up with.
 */
class UnidirectionalDataFlowContractTest {

    @Test
    fun `every view model is a UdfViewModel`() {
        val violations = viewModels().filter { it.udfTypeArguments() == null }

        assertTrue(violations.isEmpty()) {
            "These view models do not extend UdfViewModel directly:\n" +
                violations.joinToString("\n") { "  - ${it.name}" }
        }
    }

    /**
     * The one way in. A public method beside `onEvent` is a second entry point, and the
     * screens that use it stop being written against the contract — which is how `refresh()`,
     * `retry()`, `updateSearchQuery()` and `dismissRefreshResult()` accumulated on one class.
     */
    @Test
    fun `onEvent is the only public function`() {
        val violations = viewModels().mapNotNull { viewModel ->
            val extra = viewModel.kotlin.declaredMemberFunctions
                .filter { it.visibility == KVisibility.PUBLIC }
                .map { it.name }
                .filterNot { it == "onEvent" }
                .sorted()
            if (extra.isEmpty()) null else "${viewModel.simpleName}: ${extra.joinToString()}"
        }

        assertTrue(violations.isEmpty()) {
            "A view model may expose no public function but onEvent:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * The one way out. `state` and `effects` and nothing else — not a second `StateFlow`, and
     * not a plain public `val` either: a screen that reads a property directly is reading
     * something no recomposition is subscribed to.
     */
    @Test
    fun `state and effects are the only public properties`() {
        val violations = viewModels().mapNotNull { viewModel ->
            val extra = viewModel.kotlin.memberProperties
                .filter { it.visibility == KVisibility.PUBLIC }
                .map { it.name }
                .filterNot { it in CONTRACT_PROPERTIES }
                .sorted()
            if (extra.isEmpty()) null else "${viewModel.simpleName}: ${extra.joinToString()}"
        }

        assertTrue(violations.isEmpty()) {
            "A view model may expose no public property but state and effects:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * `state` is a [StateFlow] and `effects` is a bare [Flow], and the difference is the whole
     * point. A `StateFlow` always has a current value and replays it to whoever collects next
     * — which for effects means the composition that replaces this one after a rotation
     * navigating a second time.
     */
    @Test
    fun `state is a StateFlow and effects is not`() {
        viewModels().forEach { viewModel ->
            val properties = viewModel.kotlin.memberProperties.associateBy { it.name }
            val state = requireNotNull(properties["state"]) { "${viewModel.simpleName}.state" }
            val effects = requireNotNull(properties["effects"]) { "${viewModel.simpleName}.effects" }

            assertEquals(
                StateFlow::class,
                state.returnType.jvmErasure,
                "${viewModel.simpleName}.state must be a StateFlow",
            )
            assertEquals(
                Flow::class,
                effects.returnType.jvmErasure,
                "${viewModel.simpleName}.effects must be a plain Flow, never a StateFlow",
            )
        }
    }

    /**
     * Sealed, so that the `when` in `onEvent` is exhaustive and adding a member breaks the
     * build until it is handled. An open interface would let a screen invent an event the view
     * model has never heard of, and the compiler would have nothing to say about it.
     */
    @Test
    fun `every event type is sealed`() {
        val violations = viewModels().mapNotNull { viewModel ->
            val event = viewModel.udfTypeArguments()?.get(EVENT_ARGUMENT)?.classifier as? KClass<*>
            if (event != null && !event.isSealed) {
                "${viewModel.simpleName} -> ${event.simpleName}"
            } else {
                null
            }
        }

        assertTrue(violations.isEmpty()) {
            "UiEvent types must be sealed:\n" + violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Sealed for the same reason — or [Nothing], which is how a screen that decides no
     * one-shot of its own says so in a way the compiler enforces. `Nothing` has no instances,
     * so `emitEffect` cannot be called at all on one of those.
     */
    @Test
    fun `every effect type is sealed, or Nothing for a screen with no effects`() {
        val violations = viewModels().mapNotNull { viewModel ->
            val effect = viewModel.udfTypeArguments()?.get(EFFECT_ARGUMENT)?.classifier as? KClass<*>
            when {
                effect == null -> null
                effect == Nothing::class -> null
                effect.isSealed -> null
                else -> "${viewModel.simpleName} -> ${effect.simpleName}"
            }
        }

        assertTrue(violations.isEmpty()) {
            "UiEffect types must be sealed or Nothing:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Guards the discovery itself. If the walk silently found nothing — a renamed output
     * directory, a classloader that no longer exposes a file path — every test above would
     * pass having checked zero classes, which is the one way an audit like this fails useless.
     */
    @Test
    fun `discovery finds every view model in the app`() {
        assertEquals(
            EXPECTED_VIEW_MODELS,
            viewModels().map { it.simpleName }.sorted(),
            "The set of ViewModels changed. Add the new one here once it satisfies the " +
                "contract above.",
        )
    }

    /**
     * The screens' view models: every `ViewModel` this app compiles, minus the abstract ones.
     * [UdfViewModel] is itself a `ViewModel` and would otherwise be held to the contract it
     * defines — where `state` is typed on a type parameter and `onEvent` is abstract.
     */
    private fun viewModels(): List<Class<*>> = CompiledApp.classes().filter {
        ViewModel::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers)
    }

    /**
     * The `<S, E, F>` a view model bound, or null if it does not extend [UdfViewModel]
     * directly. Read from the declared supertype rather than from the properties, because that
     * is where the *contract* lives — a class could satisfy every test above by coincidence
     * while extending something else entirely.
     *
     * Kotlin reflection and not `java.lang.Class.getGenericSuperclass`: the JVM signature the
     * latter reads is erased for a supertype argument of `Nothing`, so the four screens with
     * no effects came back looking as though they had no type arguments at all. The Kotlin
     * metadata keeps them.
     */
    private fun Class<*>.udfTypeArguments(): List<KType?>? = kotlin.supertypes
        .firstOrNull { it.jvmErasure == UdfViewModel::class }
        ?.arguments
        ?.map { it.type }

    private companion object {
        val CONTRACT_PROPERTIES = setOf("state", "effects")

        const val EVENT_ARGUMENT = 1
        const val EFFECT_ARGUMENT = 2

        /**
         * Alphabetical, and asserted whole rather than as a subset: a view model that
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
    }
}
