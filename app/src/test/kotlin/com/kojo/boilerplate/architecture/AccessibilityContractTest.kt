package com.kojo.boilerplate.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two things an edge-to-edge app gets wrong silently, and neither of them is a type error.
 *
 * ### A `Scaffold` whose padding is dropped
 *
 * `Scaffold` does not apply its own content insets. It computes them — the app bars it drew, plus
 * the window insets `ScaffoldDefaults.contentWindowInsets` asks for — and hands them to the
 * content lambda as a `PaddingValues` for the caller to apply. Ignore the parameter and the code
 * still compiles, still renders, and still looks correct on the emulator the developer happens to
 * have open: the failure is content drawn *underneath* the status bar, the navigation bar or a
 * display cutout, and which of those it is depends on the device. Kotlin does not warn about an
 * unused lambda parameter, so nothing else in the toolchain says a word.
 *
 * Once the window is edge-to-edge this stops being a cosmetic bug for a subset of devices and
 * becomes the default outcome on all of them, which is why the rule arrived with
 * `MainActivity`'s system bar work rather than before it.
 *
 * ### `enableEdgeToEdge()` deciding light or dark for itself
 *
 * The no-argument overload styles both system bars with a `detectDarkMode` that reads
 * `Configuration.UI_MODE_NIGHT_MASK` — the *device's* night mode. This app also has a
 * [com.kojo.boilerplate.core.common.ThemeMode] preference, so the two disagree whenever a reader
 * has chosen Light on a device in dark mode, or Dark on one in light mode. What that produces is
 * white status-bar icons on a white app bar: the clock, the battery and the signal strength gone,
 * on the first screen such a reader ever sees. It is invisible to every gate — it compiles, it
 * lints, and a screenshot taken in the matching configuration looks right.
 *
 * So the rule is not "never call the no-argument overload": `MainActivity` calls it once, before
 * `setContent`, so that the window is laid out edge-to-edge from the first frame rather than from
 * the first composition. It is that a file which sets the window up this way must also, somewhere,
 * name both bar styles — which is the only way the app's own answer reaches them.
 *
 * ### Why this reads source rather than compiled output
 *
 * For the reason [LazyListContractTest] does, and see [SourceTree]. Both properties here are about
 * an *argument* — a lambda parameter that is never read, a named argument that is never supplied —
 * and neither survives into a class file in a form that can be asked about. It costs what a text
 * check always costs: an alias for `Scaffold`, or a wrapper around `enableEdgeToEdge`, would not
 * be seen. The discovery pins below are what turn that from a silent loss of coverage into a
 * failure.
 */
class AccessibilityContractTest {

    @Test
    fun `every Scaffold applies the content padding it is handed`() {
        val violations = scans().flatMap { it.scaffoldsDroppingPadding() }

        assertTrue(violations.isEmpty()) {
            "A `Scaffold` computes the insets its content must avoid — its own app bars plus the " +
                "system bars and any display cutout — and applies none of them. The content " +
                "lambda's `PaddingValues` is the only place that answer exists, so a lambda that " +
                "does not read it draws underneath the status bar, the navigation bar, or both:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `edge-to-edge setup names both system bar styles`() {
        val violations = scans().flatMap { it.edgeToEdgeWithoutStyles() }

        assertTrue(violations.isEmpty()) {
            "`enableEdgeToEdge()` with no arguments takes light-or-dark from the device's night " +
                "mode, which is not this app's answer: ThemeMode.Light on a device in dark mode " +
                "then puts white system bar icons over a light app bar. Pass `statusBarStyle` " +
                "and `navigationBarStyle` with a `detectDarkMode` built from the resolved theme:" +
                "\n" + violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Pins the population the padding rule is applied to. It is a `flatMap` over whatever the walk
     * found, so a walk that finds nothing passes having audited nothing — and the thing most
     * likely to empty it is a screen adopting a scaffold this file cannot spell, which is exactly
     * when the rule is needed.
     */
    @Test
    fun `discovery finds every Scaffold in the app`() {
        assertEquals(
            EXPECTED_SCAFFOLDS,
            scans().associate { it.path to it.scaffolds.size }.filterValues { it > 0 },
            "The set of `Scaffold` calls changed. A new one is the moment to decide what it does " +
                "with the insets it is handed; one that disappeared from this map without the " +
                "screen losing its scaffold means the parser has stopped recognising it.",
        )
    }

    /** The same guard for the other rule, and the reason it cannot pass by finding no calls. */
    @Test
    fun `discovery finds every edge-to-edge call in the app`() {
        assertEquals(
            EXPECTED_EDGE_TO_EDGE_CALLS,
            scans().associate { it.path to it.edgeToEdgeCalls.size }.filterValues { it > 0 },
            "The set of `enableEdgeToEdge` calls changed. Exactly one file should be setting the " +
                "window up, and it should be doing it twice: once before `setContent` so the " +
                "first frame is edge-to-edge, once per resolved theme so the bar icons follow it.",
        )
    }

    /**
     * Proves the padding rule can fail, over sources written to fail it.
     *
     * The three pins above only show that the scanners found something; none of them shows that a
     * violation would be *reported*. A rule whose detector silently matches nothing passes on
     * every repository in the world, and the discovery pins would still be green — they count
     * scaffolds, not verdicts. These four cases are the ones the parser has to tell apart: a named
     * parameter that is read, one that is not, the implicit `it`, and `_`.
     */
    @Test
    fun `the padding rule recognises the shapes it is written for`() {
        val scan = AccessibilityScan("Fixture.kt", KotlinSource(PADDING_FIXTURE))

        assertEquals(4, scan.scaffolds.size, "the fixture's four scaffolds were not all found")
        assertEquals(
            listOf(3, 4),
            scan.scaffoldsDroppingPadding().map { it.substringAfter(':').substringBefore(' ').toInt() },
            "Expected the unused named parameter and the `_` to be reported, and the two that " +
                "read their padding — one named, one implicit — to be left alone. Got:\n" +
                scan.scaffoldsDroppingPadding().joinToString("\n") { "  - $it" },
        )
    }

    private fun scans(): List<AccessibilityScan> =
        SourceTree.mainSources().map { AccessibilityScan(it.repositoryPath(), KotlinSource(it.readText())) }

    /**
     * One `Scaffold` call. [parameter] is the name its content lambda gives the `PaddingValues` —
     * `it` when the lambda declares none — and is `null` when the call has no trailing lambda at
     * all, which is a shape this file does not pretend to understand rather than one to report.
     */
    private class Scaffold(val at: Int, val parameter: String?, val body: String)

    /** Everything one source file has to say about the two rules. */
    private class AccessibilityScan(val path: String, private val source: KotlinSource) {

        val scaffolds: List<Scaffold> = findScaffolds()

        val edgeToEdgeCalls: List<String> = findEdgeToEdgeArguments()

        fun scaffoldsDroppingPadding(): List<String> = scaffolds.mapNotNull { scaffold ->
            val parameter = scaffold.parameter ?: return@mapNotNull null
            val fault = when {
                parameter == "_" ->
                    "names the content padding away with `_`"
                !IDENTIFIER.matches(parameter) ->
                    "declares a content lambda parameter this test cannot read (`$parameter`)"
                !Regex("""\b${Regex.escape(parameter)}\b""").containsMatchIn(scaffold.body) ->
                    "never reads `$parameter`"
                else -> return@mapNotNull null
            }
            "$path:${source.lineOf(scaffold.at)} — `Scaffold` $fault"
        }

        /**
         * Reported per *file* rather than per call, because the no-argument overload is legitimate
         * as the pre-composition bootstrap and only becomes a fault when it is the whole story.
         */
        fun edgeToEdgeWithoutStyles(): List<String> {
            if (edgeToEdgeCalls.isEmpty()) return emptyList()
            val missing = BAR_STYLES.filterNot { style ->
                edgeToEdgeCalls.any { Regex("""\b$style\s*=""").containsMatchIn(it) }
            }
            if (missing.isEmpty()) return emptyList()
            return listOf("$path — no `enableEdgeToEdge` call supplies ${missing.joinToString(" or ")}")
        }

        private fun findScaffolds(): List<Scaffold> =
            SCAFFOLD_CALL.findAll(source.code).map { match ->
                val at = match.range.first
                val lambda = trailingLambdaAt(source.code, match.range.last)
                    ?: return@map Scaffold(at = at, parameter = null, body = "")
                val inside = source.code.substring(lambda.first + 1, lambda.last)
                // The parameter list is whatever precedes the first `->` on the lambda's opening
                // line. Narrowed to that one line on purpose: `->` is also `when`'s arrow and a
                // function type's, and both are common inside a scaffold's content. Every lambda
                // in this repository declares its parameter beside the brace, which is also what
                // every formatter produces.
                val firstLine = inside.substringBefore('\n')
                val declaresParameter = ARROW in firstLine
                Scaffold(
                    at = at,
                    parameter = if (declaresParameter) firstLine.substringBefore(ARROW).trim() else IMPLICIT,
                    body = if (declaresParameter) inside.substringAfter(ARROW) else inside,
                )
            }.toList()

        private fun findEdgeToEdgeArguments(): List<String> =
            EDGE_TO_EDGE_CALL.findAll(source.code).mapNotNull { match ->
                val open = match.range.last
                val close = source.code.closingBracketAt(open) ?: return@mapNotNull null
                source.code.substring(open + 1, close)
            }.toList()
    }

    private companion object {

        /**
         * The Material 3 scaffold, and only it. The word boundary is what keeps
         * `NavigationSuiteScaffold` and this app's own `MainNavScaffold` out: neither hands its
         * content a `PaddingValues`, so neither has the parameter this rule is about.
         */
        val SCAFFOLD_CALL = Regex("""\bScaffold\s*\(""")

        val EDGE_TO_EDGE_CALL = Regex("""\benableEdgeToEdge\s*\(""")

        val BAR_STYLES = listOf("statusBarStyle", "navigationBarStyle")

        val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        const val ARROW = "->"

        const val IMPLICIT = "it"

        /**
         * The span of the trailing lambda after the argument list opening at [bracket], or `null`
         * when there is none. The same walk [LazyListContractTest] does for a lazy container's
         * builder, over the same bracket matcher.
         */
        private fun trailingLambdaAt(code: String, bracket: Int): IntRange? {
            val arguments = code.closingBracketAt(bracket) ?: return null
            val brace = code.nextNonSpaceFrom(arguments + 1)?.takeIf { code[it] == '{' } ?: return null
            return brace..(code.closingBracketAt(brace) ?: return null)
        }

        val EXPECTED_SCAFFOLDS = mapOf(
            "feature/home/src/main/kotlin/com/kojo/boilerplate/feature/home/HomeScreen.kt" to 1,
            "feature/profile/src/main/kotlin/com/kojo/boilerplate/feature/profile/ProfileScreen.kt" to 1,
            "feature/scanner/src/main/kotlin/com/kojo/boilerplate/feature/scanner/" +
                "BarcodeScannerScreen.kt" to 1,
            "feature/signin/src/main/kotlin/com/kojo/boilerplate/feature/signin/" +
                "GoogleSignInScreen.kt" to 1,
            "feature/textrecognition/src/main/kotlin/com/kojo/boilerplate/feature/" +
                "textrecognition/TextRecognitionScreen.kt" to 1,
        )

        /**
         * One file, two calls: the bootstrap before `setContent` and the styled one inside the
         * `DisposableEffect` that re-runs per resolved theme. Anywhere else setting the window up
         * is a second answer to a question with one.
         */
        val EXPECTED_EDGE_TO_EDGE_CALLS = mapOf(
            "app/src/main/kotlin/com/kojo/boilerplate/MainActivity.kt" to 2,
        )

        /**
         * Lines 1, 2, 3 and 4 hold the four shapes, one per line, so that a reported line number
         * is the case it came from.
         *
         * The parameter is called `innerPadding` here for the same reason every scaffold in the
         * app calls it that, and it is worth stating: a parameter named `padding` would be
         * matched by the `Modifier.padding(` in its own body, so the rule would report nothing
         * whatever the lambda did with it. That is the one name this check cannot see through.
         */
        val PADDING_FIXTURE = """
            Scaffold(topBar = {}) { innerPadding -> Body(Modifier.padding(innerPadding)) }
            Scaffold(topBar = {}) { Body(Modifier.padding(it)) }
            Scaffold(topBar = {}) { innerPadding -> Body() }
            Scaffold(topBar = {}) { _ -> Body() }
        """.trimIndent()
    }
}
