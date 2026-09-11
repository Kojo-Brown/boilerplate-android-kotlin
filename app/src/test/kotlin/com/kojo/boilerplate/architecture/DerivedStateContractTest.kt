package com.kojo.boilerplate.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Holds the two memoisation calls to the rules that decide whether they cut work or add it.
 *
 * ### What this is really checking
 *
 * `derivedStateOf` and a keyed `remember` are the only two tools in Compose whose *correct* and
 * *pointless* forms compile identically, read identically in review, and behave identically —
 * until they do not.
 *
 * - **`derivedStateOf` outside a `remember`** builds a fresh derived state on every recomposition.
 *   A derived state earns its keep by holding on to the dependencies it read last time and
 *   comparing the new result against the old one; a new instance has neither, so it recomputes
 *   every pass and invalidates every reader anyway. It is strictly the plain read plus an
 *   allocation and a dependency record. Nothing fails, nothing is reported, and the line that was
 *   added to cut recompositions is now the only thing adding them.
 * - **An unkeyed `remember` over a parameter** freezes that parameter at its first value. The
 *   screen then renders the argument it was given the first time it composed, for as long as it
 *   stays composed — which in a `NavHost` entry is until the entry leaves the back stack. This
 *   one is not silent in production, but it is invisible in review and it survives every test
 *   that renders a screen once.
 *
 * ### What it deliberately does not check
 *
 * Whether a derivation actually *narrows* — reads a value that changes more often than the
 * result it produces. That is the whole question of whether `derivedStateOf` belongs at a call
 * site, and it cannot be read off the source: it is a claim about how often the source state
 * changes at runtime. So the sites are pinned instead ([EXPECTED_DERIVATIONS]), which makes
 * adding one a deliberate act with a reviewer attached rather than a habit.
 * `docs/derived-state.md` has the analysis behind the one that is there, and the three that look
 * like it and are not.
 *
 * ### Why this reads source rather than compiled output
 *
 * For the same reason as [RecompositionContractTest], and see [SourceTree]: neither "was this
 * remembered" nor "what did this lambda capture" is a fact the class file records. The costs are
 * the ones a text check always has — it cannot see through an alias, and it matches text, so
 * comments and literals are blanked first ([KotlinSource]) or the KDoc above would be the first
 * thing to fail it.
 */
class DerivedStateContractTest {

    @Test
    fun `every derivedStateOf is remembered`() {
        val violations = scans().flatMap { it.unrememberedDerivations() }

        assertTrue(violations.isEmpty()) {
            "`derivedStateOf` must be the whole body of a `remember { }`. Unremembered, it is " +
                "rebuilt every recomposition, which throws away both the cached result and the " +
                "recorded dependencies that are the only reasons to use it:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Pins where derived state is used, because whether a derivation narrows is a claim about
     * runtime frequencies that no source check can settle. A new entry here is the moment to make
     * that claim out loud.
     */
    @Test
    fun `derived state stays where its narrowing was argued for`() {
        assertEquals(
            EXPECTED_DERIVATIONS,
            scans().associate { it.path to it.derivations.size }.filterValues { it > 0 },
            "The set of `derivedStateOf` call sites changed. A derivation pays only when its " +
                "source changes more often than its result — say which state that is, and where " +
                "it is read, in docs/derived-state.md before adding it here.",
        )
    }

    @Test
    fun `an unkeyed remember never captures a parameter of its composable`() {
        val violations = scans().flatMap { it.unkeyedCaptures() }

        assertTrue(violations.isEmpty()) {
            "An unkeyed `remember { }` computes once and keeps that value for the life of the " +
                "composition, so a parameter read inside it is frozen at the argument the first " +
                "composition supplied. Key the remember on it:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Pins the population the two rules above are applied to. Both are `flatMap`s over whatever
     * the walk found, so a walk that finds nothing passes having audited nothing — the one way
     * this class can fail useless.
     */
    @Test
    fun `discovery finds every memoised call in the app`() {
        assertEquals(
            EXPECTED_MEMOISATIONS,
            scans().associate { it.path to it.memoisations.size }.filterValues { it > 0 },
            "The set of `remember` / `rememberSaveable` call sites changed. Adding one is the " +
                "moment to decide what it is keyed on.",
        )
    }

    /**
     * Guards the parser rather than the code. Attribution to an enclosing composable is what the
     * unkeyed-capture rule compares parameters against, so a declaration pattern the parser stops
     * recognising would not fail that rule — it would quietly shrink what it audits. Every
     * `remember` is inside a composable by definition, so anything unattributed is this file
     * being wrong about Kotlin, and it is reported as that rather than as a finding about the app.
     */
    @Test
    fun `every memoised call is attributed to an enclosing composable`() {
        val orphans = scans().flatMap { it.unattributedMemoisations() }

        assertTrue(orphans.isEmpty()) {
            "`remember` can only be called from a composable, so an unattributed call means the " +
                "declaration parser in this test missed one:\n" +
                orphans.joinToString("\n") { "  - $it" }
        }
    }

    private fun scans(): List<MemoScan> =
        SourceTree.mainSources().map { MemoScan(it.repositoryPath(), KotlinSource(it.readText())) }

    /** One `@Composable` declaration: where it starts, and the parameters in scope inside it. */
    private class ComposableFunction(
        val name: String,
        val declaredAt: Int,
        val parameters: List<String>,
    )

    /**
     * One `remember` or `rememberSaveable` call. [block] is the text of the calculation lambda and
     * is only read for unkeyed calls — a keyed one has had its inputs thought about, and which of
     * them matter is not something this can second-guess.
     */
    private class Memoisation(
        val name: String,
        val at: Int,
        val keyed: Boolean,
        val block: String,
    )

    /** Everything one source file has to say about memoisation. */
    private class MemoScan(val path: String, private val source: KotlinSource) {

        private val composables: List<ComposableFunction> = findComposables()

        val memoisations: List<Memoisation> = findMemoisations()

        val derivations: List<Int> =
            DERIVED_CALL.findAll(source.code).map { it.range.first }.toList()

        fun unrememberedDerivations(): List<String> =
            derivations.filterNot { directlyInsideRemember(source.code, it) }
                .map { "$path:${source.lineOf(it)} calls `derivedStateOf` outside a `remember`" }

        fun unkeyedCaptures(): List<String> =
            memoisations.filter { !it.keyed && !it.block.isStateFactory() }
                .flatMap { call -> captures(call) }

        fun unattributedMemoisations(): List<String> =
            memoisations.filter { enclosing(it) == null }
                .map { "$path:${source.lineOf(it.at)} (`${it.name}`)" }

        private fun captures(call: Memoisation): List<String> {
            val enclosing = enclosing(call) ?: return emptyList()
            return enclosing.parameters.filter { Regex("\\b$it\\b").containsMatchIn(call.block) }
                .map {
                    "$path:${source.lineOf(call.at)} — `${call.name} { … }` reads `$it`, a " +
                        "parameter of ${enclosing.name}(), and is keyed on nothing"
                }
        }

        /**
         * The composable a call sits in, taken as the last one declared before it. Cheaper and
         * more robust than matching the function's braces: an expression-bodied composable —
         * `rememberEventSink` is one — has no body braces to match, and `remember` cannot appear
         * anywhere but inside a composable, so "the most recent declaration" is the answer.
         */
        private fun enclosing(call: Memoisation): ComposableFunction? =
            composables.lastOrNull { it.declaredAt < call.at }

        private fun findComposables(): List<ComposableFunction> =
            FUN_DECLARATION.findAll(source.code).mapNotNull { match ->
                val open = match.range.last
                val close = closingBracket(source.code, open) ?: return@mapNotNull null
                if (!annotatedComposable(match.range.first)) return@mapNotNull null
                ComposableFunction(
                    name = match.groupValues[1],
                    declaredAt = match.range.first,
                    parameters = parameterNames(source.code.substring(open + 1, close)),
                )
            }.toList()

        /**
         * Whether the declaration at [start] carries `@Composable` — the last one before it, with
         * nothing but annotations and modifiers in between. That last part is what keeps a
         * `content: @Composable () -> Unit` *parameter* from annotating the next function down:
         * the text between it and that `fun` contains brackets, which a header cannot.
         */
        private fun annotatedComposable(start: Int): Boolean {
            val window = source.code.substring(maxOf(0, start - HEADER_WINDOW), start)
            val annotation = window.lastIndexOf(COMPOSABLE_ANNOTATION)
            if (annotation < 0) return false
            return HEADER_ONLY.matches(window.substring(annotation + COMPOSABLE_ANNOTATION.length))
        }

        private fun findMemoisations(): List<Memoisation> =
            MEMO_CALL.findAll(source.code).mapNotNull { match ->
                val bracket = match.range.last
                val close = closingBracket(source.code, bracket) ?: return@mapNotNull null
                val keyed = source.code[bracket] == '('
                Memoisation(
                    name = match.groupValues[1],
                    at = match.range.first,
                    keyed = keyed,
                    block = if (keyed) "" else source.code.substring(bracket + 1, close),
                )
            }.toList()
    }

    private companion object {

        /**
         * A function declaration, with the offset of its opening parenthesis as the match's last
         * character. Receivers and type parameters are stepped over so that the captured name is
         * the function's own.
         */
        val FUN_DECLARATION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")

        val MEMO_CALL = Regex("""\b(remember|rememberSaveable)\s*[({]""")

        val DERIVED_CALL = Regex("""\bderivedStateOf\s*\{""")

        const val COMPOSABLE_ANNOTATION = "@Composable"

        /**
         * Everything allowed to stand between an annotation and the `fun` it applies to:
         * whitespace, further annotations, and declaration modifiers. Deliberately without
         * brackets of any kind.
         */
        val HEADER_ONLY = Regex(
            """(?:\s|@[\w.]+(?:\([^()]*\))?|private|internal|public|protected|inline|noinline|""" +
                """crossinline|suspend|operator|infix|expect|actual|override|open|final|tailrec)*""",
        )

        /** Far more than any declaration header in this repository, and bounded so it is cheap. */
        const val HEADER_WINDOW = 400

        /**
         * The calls for which an unkeyed `remember` is the point rather than a mistake: a state
         * holder takes an *initial* value by definition, and keying it on that value would reset
         * the state every time the argument changed, discarding whatever the user had done to it.
         */
        val STATE_FACTORY = Regex(
            """^\s*mutable(?:State(?:List|Map)?|(?:Int|Long|Float|Double)State)Of\s*[(<]""",
        )

        private fun String.isStateFactory(): Boolean = STATE_FACTORY.containsMatchIn(this)

        /** The index of the bracket closing the one at [open], or null if it is unbalanced. */
        private fun closingBracket(code: String, open: Int): Int? {
            val close = if (code[open] == '(') ')' else '}'
            var depth = 0
            for (index in open until code.length) {
                when (code[index]) {
                    code[open] -> depth++
                    close -> if (--depth == 0) return index
                }
            }
            return null
        }

        /**
         * Whether the call at [start] is the whole body of a `remember { }`, read by walking
         * backwards past the brace and any key list. `rememberSaveable` is not accepted: a
         * derived state is a window onto other state rather than a value of its own, and there is
         * nothing about it to restore.
         */
        private fun directlyInsideRemember(code: String, start: Int): Boolean {
            var index = code.skipSpaceBefore(start)
            if (index < 0 || code[index] != '{') return false
            index = code.skipSpaceBefore(index)
            if (index >= 0 && code[index] == ')') {
                index = code.skipSpaceBefore(openingParenBefore(code, index))
            }
            if (index < 0) return false
            val end = index + 1
            while (index >= 0 && (code[index].isLetterOrDigit() || code[index] == '_')) index--
            return code.substring(index + 1, end) == "remember"
        }

        /** The index of the parenthesis opening the one at [close]. */
        private fun openingParenBefore(code: String, close: Int): Int {
            var depth = 0
            for (index in close downTo 0) {
                when (code[index]) {
                    ')' -> depth++
                    '(' -> if (--depth == 0) return index
                }
            }
            return -1
        }

        /** The index of the last non-whitespace character before [index], or -1 if there is none. */
        private fun String.skipSpaceBefore(index: Int): Int {
            var cursor = index - 1
            while (cursor >= 0 && this[cursor].isWhitespace()) cursor--
            return cursor
        }

        /**
         * The names declared in a parameter list. Split at bracket depth zero, which leaves a
         * generic argument list carrying a comma — `Map<String, Int>` — as two fragments; the
         * second matches nothing and is dropped, so the cost of not tracking `<` is a parameter
         * this does not know about rather than a name it invents.
         */
        private fun parameterNames(parameters: String): List<String> {
            val names = mutableListOf<String>()
            var depth = 0
            var start = 0
            parameters.forEachIndexed { index, character ->
                when (character) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ',' -> if (depth == 0) {
                        names.addParameterIn(parameters.substring(start, index))
                        start = index + 1
                    }
                }
            }
            names.addParameterIn(parameters.substring(start))
            return names
        }

        val PARAMETER_NAME =
            Regex("""^\s*(?:@[\w.]+(?:\([^()]*\))?\s*)*(?:vararg\s+)?(\w+)\s*:""")

        private fun MutableList<String>.addParameterIn(fragment: String) {
            PARAMETER_NAME.find(fragment)?.let { add(it.groupValues[1]) }
        }

        val EXPECTED_DERIVATIONS = mapOf(
            "feature/home/src/main/kotlin/com/kojo/boilerplate/feature/home/HomeScreen.kt" to 1,
        )

        val EXPECTED_MEMOISATIONS = mapOf(
            "app/src/main/kotlin/com/kojo/boilerplate/navigation/AppNavHost.kt" to 2,
            "core/ui/src/main/kotlin/com/kojo/boilerplate/core/ui/udf/EventSink.kt" to 1,
            "feature/home/src/main/kotlin/com/kojo/boilerplate/feature/home/HomeScreen.kt" to 2,
            "feature/scanner/src/main/kotlin/com/kojo/boilerplate/feature/scanner/" +
                "BarcodeScannerScreen.kt" to 1,
            "feature/signin/src/main/kotlin/com/kojo/boilerplate/feature/signin/" +
                "GoogleSignInScreen.kt" to 1,
            "feature/textrecognition/src/main/kotlin/com/kojo/boilerplate/feature/" +
                "textrecognition/TextRecognitionScreen.kt" to 1,
        )
    }
}
