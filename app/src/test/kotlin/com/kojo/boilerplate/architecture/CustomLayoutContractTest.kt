package com.kojo.boilerplate.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two things a hand-written `Layout` or `SubcomposeLayout` gets wrong in a way that compiles,
 * renders, and looks right on the machine that wrote it.
 *
 * ### A slot id that is not a stable value
 *
 * `SubcomposeLayout` keeps a subcomposition per slot id and reuses it across measure passes, and
 * "the same slot" means `equals`. Two things break that and neither is an error:
 *
 * - **An id that is a fresh object each pass** — a lambda, an anonymous object, a `Pair` built
 *   inline. Nothing ever matches the previous pass's id, so every measure discards the slot's
 *   composition and builds a new one. In a layout that measures on scroll that is a subtree
 *   recomposed per frame, visible only as the screen being slower than it should be.
 * - **Two `subcompose` calls sharing one id in a single pass** — the second composes *over* the
 *   first, so the first's measurables are stale and the node it measured is gone. The usual way in
 *   is a copy-pasted call whose id was not changed with the rest of it.
 *
 * An enum entry is immune to both by construction, which is why the rule below asks for one and
 * rejects a string or a number rather than merely asking for distinctness: a `"header"` typed twice
 * is exactly the collision, and the compiler has nothing to say about it.
 *
 * ### A layout that swallows its modifier
 *
 * A custom layout is the one composable where dropping the `modifier` parameter is *invisible* to
 * its author: the children still draw, at the sizes the measure policy chose, and what is lost is
 * whatever the caller asked for around them — padding, a size, a click target, a test tag, a
 * semantics property. The call site reads exactly as if it worked. So every composable calling
 * `Layout` or `SubcomposeLayout` must both take a `modifier` and hand it to the call.
 *
 * ### What it deliberately does not check
 *
 * Whether `SubcomposeLayout` was the right tool. The rule that matters — subcompose only when a
 * measurement decides *what to compose*, rather than where it goes — is a claim about the design
 * and not about the text, and `docs/custom-layout.md` is where the argument for each one lives.
 * [EXPECTED_CUSTOM_LAYOUTS] is the pin that puts a reviewer in front of it: a new custom layout
 * cannot arrive without an entry here, and adding the entry is the moment to say which kind it is.
 *
 * It also cannot follow an id through a helper. `ExpandableText` subcomposes from a local function
 * taking the slot as a parameter, so what this file sees at that call is the parameter's name; the
 * entries reaching it are at the helper's call sites. That hole is precisely what the enum closes —
 * the type has four entries and nothing else can be passed — which is the other reason the rule
 * asks for the type rather than for distinct spellings.
 *
 * ### Why this reads source rather than compiled output
 *
 * For the reason [LazyListContractTest] does, and see [SourceTree]: a slot id is an argument value
 * and a modifier is a parameter forwarded into a call, and neither survives into the class file as
 * the fact this asserts. It costs what a text check always costs — an alias defeats it — and
 * comments and string literals are blanked first ([KotlinSource]) or this KDoc would fail the
 * first rule by talking about `"header"`.
 */
class CustomLayoutContractTest {

    @Test
    fun `every subcompose slot id is an enum entry`() {
        val violations = scans().flatMap { it.slotIdsThatAreNotEnumEntries() }

        assertTrue(violations.isEmpty()) {
            "A slot id is how `SubcomposeLayout` finds a subcomposition to reuse, compared with " +
                "`equals`. A literal collides silently with the same literal elsewhere in the " +
                "layout, and a value built inline never equals the last pass's, so the slot is " +
                "rebuilt every measure. Name the slots in an enum:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `no two slots in one layout share an id`() {
        val violations = scans().flatMap { it.repeatedSlotIds() }

        assertTrue(violations.isEmpty()) {
            "Two `subcompose` calls in one measure pass sharing an id do not fail: the second " +
                "composes over the first, and the first's measurables are left describing a node " +
                "that is gone. Give each slot its own id:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `every custom layout takes a modifier and passes it on`() {
        val violations = scans().flatMap { it.layoutsNotForwardingAModifier() }

        assertTrue(violations.isEmpty()) {
            "A custom layout that drops its `modifier` still draws its children, so nothing looks " +
                "wrong — what is lost is the padding, size, test tag or semantics the caller " +
                "asked for. Declare `modifier: Modifier = Modifier` and pass it to the layout " +
                "call:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Pins the population the three rules above are applied to, and the design decision behind
     * each entry. Every rule is a `flatMap` over whatever the walk found, so a walk that finds
     * nothing passes having audited nothing — the one way this class can fail useless.
     */
    @Test
    fun `discovery finds every custom layout in the app`() {
        assertEquals(
            EXPECTED_CUSTOM_LAYOUTS,
            scans().flatMap { scan -> scan.layouts.map { scan.describe(it) } }.sorted(),
            "The set of hand-written layouts changed. A `Layout` places content a measurement " +
                "decides the position of; a `SubcomposeLayout` composes content a measurement " +
                "decides the existence of, and pays for the privilege. Say which this one is, and " +
                "why, in docs/custom-layout.md before adding it here.",
        )
    }

    private fun scans(): List<LayoutScan> =
        SourceTree.mainSources().map { LayoutScan(it.repositoryPath(), KotlinSource(it.readText())) }

    /** One `Layout` / `SubcomposeLayout` call, with the spans the rules need to read around it. */
    private class LayoutCall(
        val builder: String,
        val at: Int,
        val arguments: String,
        val body: IntRange?,
        val declaredIn: String,
        val declarationParameters: String,
    )

    /** One `subcompose` call, with the text of the id it was given. */
    private class SubcomposedSlot(val id: String, val at: Int)

    /** Everything one source file has to say about its custom layouts. */
    private class LayoutScan(val path: String, private val source: KotlinSource) {

        val layouts: List<LayoutCall> = findLayouts()

        fun describe(layout: LayoutCall): String =
            "$path: ${layout.builder} in ${layout.declaredIn}"

        fun slotIdsThatAreNotEnumEntries(): List<String> =
            layouts.flatMap { layout ->
                slotsIn(layout).filterNot { ENUM_ENTRY.matches(it.id) }
                    .map {
                        // A string id reaches here blank, because `KotlinSource` blanks literals
                        // before anything reads the file — so it is named rather than quoted.
                        val id = it.id.ifBlank { "a literal" }
                        "$path:${source.lineOf(it.at)} — slot id `$id` is not an enum entry"
                    }
            }

        fun repeatedSlotIds(): List<String> =
            layouts.flatMap { layout ->
                slotsIn(layout).groupBy { it.id }.filterValues { it.size > 1 }
                    .map { (id, slots) ->
                        "$path:${source.lineOf(slots.first().at)} — id `$id` is subcomposed " +
                            "${slots.size} times in ${layout.declaredIn}"
                    }
            }

        fun layoutsNotForwardingAModifier(): List<String> = layouts.mapNotNull { layout ->
            val fault = when {
                !MODIFIER_PARAMETER.containsMatchIn(layout.declarationParameters) ->
                    "${layout.declaredIn} declares no `modifier` parameter"
                !MODIFIER_ARGUMENT.containsMatchIn(layout.arguments) ->
                    "${layout.declaredIn} does not pass its `modifier` to ${layout.builder}"
                else -> null
            }
            fault?.let { "$path:${source.lineOf(layout.at)} — $it" }
        }

        /**
         * The slots of one layout: every `subcompose` call inside its trailing lambda. A layout
         * whose lambda could not be found contributes none, which the discovery pin is what
         * catches — a builder this parser stops understanding stops auditing rather than failing.
         */
        private fun slotsIn(layout: LayoutCall): List<SubcomposedSlot> {
            val body = layout.body ?: return emptyList()
            return SUBCOMPOSE_CALL.findAll(source.code).filter { it.range.first in body }
                .mapNotNull { match ->
                    val bracket = match.range.last
                    val close = source.code.closingBracketAt(bracket) ?: return@mapNotNull null
                    SubcomposedSlot(
                        id = source.code.substring(bracket + 1, close).substringBefore(',').trim(),
                        at = match.range.first,
                    )
                }.toList()
        }

        private fun findLayouts(): List<LayoutCall> =
            LAYOUT_CALL.findAll(source.code).mapNotNull { match ->
                val bracket = match.range.last
                val close = source.code.closingBracketAt(bracket) ?: return@mapNotNull null
                val declaration = declarationEnclosing(match.range.first)
                LayoutCall(
                    builder = match.groupValues[1],
                    at = match.range.first,
                    arguments = source.code.substring(bracket + 1, close),
                    body = trailingLambdaAfter(source.code, close),
                    declaredIn = declaration?.groupValues?.get(1) ?: "?",
                    declarationParameters = declaration?.let { parametersOf(it) } ?: "",
                )
            }.toList()

        /**
         * The function a call sits in, taken as the nearest declaration starting before it. Coarse
         * — it does not check that the call is inside that function's braces — and sound enough
         * for the one shape this rule is about, where the layout call *is* the composable's body.
         * A mis-association shows up in the discovery pin as a name nobody recognises.
         */
        private fun declarationEnclosing(at: Int): MatchResult? =
            FUNCTION.findAll(source.code).takeWhile { it.range.first < at }.lastOrNull()

        private fun parametersOf(declaration: MatchResult): String {
            val bracket = declaration.range.last
            val close = source.code.closingBracketAt(bracket) ?: return ""
            return source.code.substring(bracket + 1, close)
        }
    }

    private companion object {

        /**
         * The call, with the bracket opening its argument list as the match's last character.
         * `\b` is what keeps `SubcomposeLayout(` from also matching as a `Layout(`, and
         * `useListDetailLayout(` from matching at all: there is no word boundary in the middle of
         * an identifier.
         */
        val LAYOUT_CALL = Regex("""\b(SubcomposeLayout|Layout)\s*\(""")

        val SUBCOMPOSE_CALL = Regex("""\bsubcompose\s*\(""")

        /** `fun Name(`, with any type parameters stepped over and the bracket as the last char. */
        val FUNCTION = Regex("""\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        /**
         * `Type.Entry`, or a plain identifier — which is the slot held in a local or handed to a
         * helper as a parameter, and is trusted for the reason the class KDoc gives. What it
         * rejects is everything that is not a name: a number, a string (which arrives blank,
         * having been blanked with every other literal), and anything with a bracket in it, which
         * is every value constructed at the call.
         *
         * A bare `Unit` passes this one and is caught by the rule below instead: one `Unit` slot
         * is a layout with a single subcomposition and is fine, and a second is the collision.
         */
        val ENUM_ENTRY = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")

        val MODIFIER_PARAMETER = Regex("""\bmodifier\s*:\s*Modifier\b""")

        /**
         * `modifier` used as a *value* — so, not immediately followed by `=`.
         *
         * A bare `\bmodifier\b` was the first version and it passes `SubcomposeLayout(modifier =
         * Modifier)`, which is the mistake in its most plausible form: the parameter is named, the
         * call reads as if it forwards, and what it actually passes is a fresh empty `Modifier`
         * with the caller's padding and semantics dropped. The lookahead is what separates the
         * name of the argument from the thing being passed to it.
         */
        val MODIFIER_ARGUMENT = Regex("""\bmodifier\b(?!\s*=)""")

        /** The span of a trailing lambda following the argument list that closes at [close]. */
        fun trailingLambdaAfter(code: String, close: Int): IntRange? {
            val brace = code.nextNonSpaceFrom(close + 1)?.takeIf { code[it] == '{' } ?: return null
            return brace..(code.closingBracketAt(brace) ?: return null)
        }

        /**
         * Two, and the pair is the point. `LabelledValue` is a `Layout`: both of its children exist
         * under either outcome and the measurement only decides where they go, which is the case
         * that does **not** need subcomposition and the case people reach for it in anyway.
         * `ExpandableText` is the other one — whether a control exists at all depends on a text
         * layout, so what to compose is not known until something has been measured.
         */
        val EXPECTED_CUSTOM_LAYOUTS = listOf(
            "core/ui/src/main/kotlin/com/kojo/boilerplate/core/ui/layout/ExpandableText.kt: " +
                "SubcomposeLayout in ExpandableText",
            "core/ui/src/main/kotlin/com/kojo/boilerplate/core/ui/layout/LabelledValue.kt: " +
                "Layout in LabelledValue",
        )
    }
}
