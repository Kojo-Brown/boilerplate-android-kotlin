package com.kojo.boilerplate.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Holds every screen to the one rule that decides whether Compose can skip it: a composable
 * names its view model to read `state`, to read `effects`, and to hand it to
 * `rememberEventSink`. Nowhere else.
 *
 * ### What this is really checking
 *
 * `onClick = { viewModel.onEvent(Clicked) }` captures the view model, and a `ViewModel` is
 * unstable to the Compose compiler. Whether that costs anything is decided by strong skipping:
 * with it off the lambda is not memoised at all, so it is a new instance on every recomposition
 * and nothing below that screen can skip; with it on — the default this project compiles with —
 * the lambda is memoised against the view model's identity and the children skip after all.
 *
 * This test exists because that is a property of the build rather than of the code. A screen
 * that captures its view model is one `composeCompiler { }` line away from recomposing its whole
 * subtree on every keystroke, and nothing would report it. Capturing the sink instead is the
 * same behaviour under either mode. `docs/recomposition.md` has the pass this came out of, and
 * `rememberEventSink` the reasoning.
 *
 * ### Why this reads source rather than compiled output
 *
 * The rest of the architecture suite reads compiled output, which is stronger, and it cannot
 * work here. Whether a lambda was memoised is not recorded in the class file as a fact about
 * the lambda: Kotlin 2.x compiles lambdas through `invokedynamic`, so what remains is a
 * synthetic static method whose parameters happen to be the captures. A check over that shape
 * would be a check on a calling convention, and it would go quiet — passing, auditing nothing —
 * the first time the convention changed. The capture is written down plainly in the source, so
 * the source is what this reads.
 *
 * It costs the two things a text check always costs, and both are bounded here. It cannot see
 * through an alias, so `val vm = viewModel` defeats it. And it matches text, so comments and
 * string literals are blanked out first ([KotlinSource]) — otherwise the KDoc explaining the
 * rule would be the first thing to break it.
 */
class RecompositionContractTest {

    @Test
    fun `no composable captures its view model in a callback`() {
        val violations = screensWithViewModels().flatMap { screen -> screen.violations() }

        assertTrue(violations.isEmpty()) {
            "A composable may name its view model only as `state`, `effects`, or the argument " +
                "to `rememberEventSink`. Anything else is captured by a lambda that then " +
                "cannot be memoised, and every child it reaches stops skipping:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Guards the discovery itself. The check above is a `flatMap` over whatever the walk found,
     * so a walk that finds nothing — a repository root located from an unexpected working
     * directory, a screen that stopped matching the declaration pattern — passes having audited
     * zero files. That is the one way this class fails useless, so the set is pinned.
     */
    @Test
    fun `discovery finds every composable that takes a view model`() {
        assertEquals(
            EXPECTED_SCREENS,
            screensWithViewModels().map { it.path }.sorted(),
            "The set of composables taking a view model changed. Add the new screen here once " +
                "it satisfies the rule above.",
        )
    }

    private fun screensWithViewModels(): List<Screen> =
        SourceTree.mainSources().mapNotNull { file ->
            val source = KotlinSource(file.readText())
            val parameter = VIEW_MODEL_PARAMETER.find(source.code)?.groupValues?.get(1)
            parameter?.let { Screen(file.repositoryPath(), source, it) }
        }

    /** A composable that takes a view model, and the name it gave that parameter. */
    private class Screen(
        val path: String,
        private val source: KotlinSource,
        private val parameter: String,
    ) {

        fun violations(): List<String> =
            Regex("\\b$parameter\\b").findAll(source.code)
                .filterNot { permits(it) }
                .map { "$path:${source.lineOf(it.range.first)} names `$parameter` for something " +
                    "other than `state`, `effects` or `rememberEventSink`" }
                .toList()

        private fun permits(match: MatchResult): Boolean {
            val code = source.code
            val declaresOrReads = PERMITTED_SUFFIXES.any { code.startsWith(it, match.range.last + 1) }
            val handedToTheSink = code.take(match.range.first).trimEnd().endsWith(SINK_CALL)
            return declaresOrReads || handedToTheSink
        }
    }

    private companion object {

        /**
         * The screen's view model, found by its default rather than by its type: `hiltViewModel`
         * is what makes a parameter *the* view model a composable owns, and it reads the same
         * whether the call is the bare one or the assisted-injection form with type arguments.
         */
        val VIEW_MODEL_PARAMETER = Regex("""(\w+)\s*:\s*[\w.]+\s*=\s*hiltViewModel""")

        const val SINK_CALL = "rememberEventSink("

        val PERMITTED_SUFFIXES = listOf(":", ".state", ".effects")

        val EXPECTED_SCREENS = listOf(
            "feature/home/src/main/kotlin/com/kojo/boilerplate/feature/home/HomeScreen.kt",
            "feature/profile/src/main/kotlin/com/kojo/boilerplate/feature/profile/" +
                "ProfileDetailPane.kt",
            "feature/profile/src/main/kotlin/com/kojo/boilerplate/feature/profile/" +
                "ProfileScreen.kt",
            "feature/scanner/src/main/kotlin/com/kojo/boilerplate/feature/scanner/" +
                "BarcodeScannerScreen.kt",
            "feature/signin/src/main/kotlin/com/kojo/boilerplate/feature/signin/" +
                "GoogleSignInScreen.kt",
            "feature/textrecognition/src/main/kotlin/com/kojo/boilerplate/feature/" +
                "textrecognition/TextRecognitionScreen.kt",
        )
    }
}
