package com.kojo.boilerplate.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The five ways a shared-element transition or a predictive back gesture stops happening while
 * every screen still renders correctly.
 *
 * That is what makes this worth a test file rather than a review note. Every failure below
 * produces an app that builds, passes every other gate, and looks right in a screenshot — the
 * animation simply does not play, which is indistinguishable from the app as it was before anyone
 * tried to animate anything. There is no exception, no log at the default level, and no assertion
 * anywhere else in this repository that would notice.
 *
 * ### A key with only one side
 *
 * Shared content is matched by `equals` on its key, and the two halves of every match here are
 * written in two modules that cannot see each other — the row in `:feature:home`, the profile in
 * `:feature:profile`. `SharedElementKey` makes the spelling the compiler's problem, which is most
 * of the risk; what it cannot make the compiler's problem is a key one side forgot to declare at
 * all. A `SharedElementKey.UserAvatar` used in exactly one place is a shared element with nothing
 * to be shared with, and the runtime's response to that is to draw it normally.
 *
 * ### The raw API used directly
 *
 * `rememberSharedContentState` takes `Any`. A feature reaching past `SharedElementKey` to hand it a
 * string gets back exactly the drift the sealed type exists to remove, and gets it in a form that
 * reads as ordinary Compose code. So the three raw names are confined to the one file whose job is
 * to wrap them.
 *
 * ### A screen that takes a transition and drops it
 *
 * The mirror of `CustomLayoutContractTest`'s rule about a layout swallowing its `modifier`, and
 * silent in the same way: the screen renders, the transition is simply never joined. It is a live
 * risk here rather than a hypothetical, because the transition is threaded through four nested
 * composables on the home side and three on the profile side, and forwarding it to one child and
 * not another compiles.
 *
 * ### The manifest opt-in
 *
 * `android:enableOnBackInvokedCallback` is one attribute on one node, and without it the back
 * gesture never reports progress: `NavHost` cannot seek its pop transition, so a shared element
 * has no seek to follow, and `PredictiveBackHandler` receives a completed gesture instead of a
 * stream. Every back still navigates. This is the single highest-leverage line in the change and
 * the easiest one to lose to an unrelated manifest edit.
 *
 * ### Why this reads source
 *
 * For the reason [CustomLayoutContractTest] and [LazyListContractTest] do, and see [SourceTree]:
 * a key is an argument value, a forwarded parameter is a parameter forwarded into a call, and a
 * manifest attribute is not Kotlin at all. None of the three survives into a class file as the
 * fact asserted here. It costs what a text check always costs — an alias defeats it — and
 * comments and string literals are blanked first ([KotlinSource]), which this file needs more than
 * its neighbours do: its own prose names every identifier it bans.
 */
class SharedElementContractTest {

    @Test
    fun `every shared-element key is used on more than one side`() {
        val oneSided = keyUsage().filterValues { it.size < 2 }

        assertTrue(oneSided.isEmpty()) {
            "A shared element is matched to its counterpart by `equals` on the key, so a key " +
                "declared in one place has nothing to be shared with — and the runtime's answer " +
                "to that is to draw the element normally rather than to complain. Either give " +
                "the key its other side or delete it:\n" +
                oneSided.entries.joinToString("\n") { (key, sites) ->
                    "  - SharedElementKey.$key is used at ${sites.size} site(s): " +
                        sites.joinToString(", ") { "${it.first}:${it.second}" }
                }
        }
    }

    @Test
    fun `only the transition wrapper names the shared-element API`() {
        val violations = SourceTree.mainSources()
            .filterNot { it.repositoryPath() == TRANSITION_WRAPPER }
            .flatMap { file ->
                val source = KotlinSource(file.readText())
                RAW_SHARED_ELEMENT_API.findAll(source.code).map { match ->
                    "${file.repositoryPath()}:${source.lineOf(match.range.first)} — " +
                        "`${match.value.trimEnd('(', ' ')}`"
                }.toList()
            }

        assertTrue(violations.isEmpty()) {
            "`rememberSharedContentState` takes `Any`, so calling it outside the wrapper is how a " +
                "raw string key gets in — and a string key spelled two ways in two modules is the " +
                "silent non-transition `SharedElementKey` exists to prevent. Add a key variant and " +
                "go through `sharedElementTransition` / `sharedBoundsTransition` in " +
                "$TRANSITION_WRAPPER:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `every composable taking a transition passes it on`() {
        val violations = SourceTree.mainSources().flatMap { file ->
            val source = KotlinSource(file.readText())
            declarationsTakingATransition(source).filterNot { (_, parameter, body) ->
                body.usesNameAsValue(parameter)
            }.map { (name, parameter, _) ->
                "${file.repositoryPath()} — $name declares `$parameter` and never uses it"
            }
        }

        assertTrue(violations.isEmpty()) {
            "A composable that takes a `SharedElementTransition` and drops it renders exactly as " +
                "it did before — the content is all there, at the right size, and simply does not " +
                "travel. Forward it to whichever child draws the shared content, or do not take " +
                "it:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `the manifest opts in to predictive back`() {
        val manifest = xmlWithoutComments(File(SourceTree.root, APP_MANIFEST).readText())

        assertTrue(PREDICTIVE_BACK_OPT_IN.containsMatchIn(manifest)) {
            "$APP_MANIFEST does not set `android:enableOnBackInvokedCallback=\"true\"` on its " +
                "`application` node. Without it the platform keeps the app on the legacy back " +
                "path, which reports no progress: `NavHost` cannot seek its pop transition, the " +
                "shared elements have no seek to follow, and the `PredictiveBackHandler` in " +
                "HomeTwoPaneScreen gets one completed event instead of a gesture. Every back " +
                "still works, one frame at a time, which is why nothing else here would notice."
        }
    }

    @Test
    fun `nothing intercepts back with a plain BackHandler`() {
        val violations = SourceTree.mainSources().flatMap { file ->
            val source = KotlinSource(file.readText())
            PLAIN_BACK_HANDLER.findAll(source.code).map { match ->
                "${file.repositoryPath()}:${source.lineOf(match.range.first)}"
            }.toList()
        }

        assertTrue(violations.isEmpty()) {
            "`BackHandler` consumes the back gesture without taking part in it: for as long as it " +
                "is enabled the system plays no predictive-back animation, so the user gets no " +
                "preview of where back leads and no way to abandon the gesture once begun. " +
                "`rememberPredictiveBackDismiss` is the same interception with the progress handed " +
                "back — use it, or leave back to `NavHost`:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Pins the keys and where each one is used, which is the claim no rule above can settle.
     *
     * "Used at two or more sites" is a property of the text. Whether those two sites are the two
     * *ends of a navigation* is a claim about the app's structure, and getting it wrong produces a
     * pair that satisfies every rule here and still never animates — two uses inside one screen,
     * say, or a source in `:feature:home` whose counterpart went to a pane that is drawn beside it
     * rather than after it. This is what puts a reviewer in front of that, and
     * `docs/shared-elements.md` is where the argument for each pair lives.
     *
     * It is also the discovery check the other five rules need: every one of them is a `flatMap`
     * over whatever the walk found, so a walk that finds nothing passes having audited nothing.
     */
    @Test
    fun `discovery finds every shared element in the app`() {
        assertEquals(
            EXPECTED_SHARED_ELEMENTS,
            // The files, not the lines: a pin carrying line numbers has to be re-pinned by
            // every edit above one of them, which is how a pin stops being read.
            keyUsage().mapValues { (_, sites) -> sites.map { it.first }.distinct().sorted() }
                .toSortedMap(),
            "The set of shared elements changed. `sharedElement` is for content that is the same " +
                "drawing on both sides — it animates a rectangle and cross-fades nothing, so two " +
                "copies of a widget are not enough; `sharedBounds` is for content that differs, " +
                "where the box travels and the contents cross-fade. Say which each one is, and " +
                "why, in docs/shared-elements.md before adding it here.",
        )
    }

    /** Every `SharedElementKey.Variant` reference in the app, by variant, as (path, line). */
    private fun keyUsage(): Map<String, List<Pair<String, Int>>> {
        val uses = SourceTree.mainSources().flatMap { file ->
            val source = KotlinSource(file.readText())
            val path = file.repositoryPath()
            KEY_REFERENCE.findAll(source.code).map { match ->
                match.groupValues[1] to (path to source.lineOf(match.range.first))
            }.toList()
        }
        // Declared-but-unused variants are in the map with no sites, so the one-sided rule
        // reports them rather than the discovery pin reporting a missing entry. A key nobody
        // uses is the same bug as a key only one side uses, reached from the other direction.
        val declared = declaredKeyVariants().associateWith { emptyList<Pair<String, Int>>() }
        return declared + uses.groupBy({ it.first }) { it.second }
    }

    private fun declaredKeyVariants(): List<String> {
        val declaration = File(SourceTree.root, KEY_DECLARATION)
        val source = KotlinSource(declaration.readText())
        return KEY_VARIANT_DECLARATION.findAll(source.code).map { it.groupValues[1] }.toList()
    }

    /**
     * Every declaration taking a [SharedElementTransition]-typed parameter, as (name, parameter
     * name, body).
     *
     * The "body" is the span from the end of the parameter list to the start of the next
     * declaration, rather than a brace block: two of these functions are expression-bodied
     * modifier extensions, and a rule that only understood block bodies would skip exactly the
     * two files closest to the API. Coarse in the same way [CustomLayoutContractTest]'s
     * enclosing-declaration walk is coarse, and sound for the same reason — a mis-association
     * shows up as a name nobody recognises.
     */
    private fun declarationsTakingATransition(
        source: KotlinSource,
    ): List<Triple<String, String, String>> {
        val declarations = FUNCTION.findAll(source.code).toList()
        return declarations.mapIndexedNotNull { index, declaration ->
            val bracket = declaration.range.last
            val close = source.code.closingBracketAt(bracket) ?: return@mapIndexedNotNull null
            val parameters = source.code.substring(bracket + 1, close)
            val parameter = TRANSITION_PARAMETER.find(parameters)?.groupValues?.get(1)
                ?: return@mapIndexedNotNull null
            val bodyEnd = declarations.getOrNull(index + 1)?.range?.first ?: source.code.length
            Triple(
                declaration.groupValues[1],
                parameter,
                source.code.substring(close + 1, maxOf(close + 1, bodyEnd)),
            )
        }
    }

    private companion object {

        const val TRANSITION_WRAPPER =
            "core/ui/src/main/kotlin/com/kojo/boilerplate/core/ui/transition/" +
                "SharedElementTransition.kt"

        const val KEY_DECLARATION =
            "core/ui/src/main/kotlin/com/kojo/boilerplate/core/ui/transition/SharedElementKey.kt"

        const val APP_MANIFEST = "app/src/main/AndroidManifest.xml"

        /** `SharedElementKey.Variant`, which is how every use of a key is spelled. */
        val KEY_REFERENCE = Regex("""\bSharedElementKey\.([A-Z][A-Za-z0-9_]*)""")

        /** A variant inside the sealed interface: `data class Name(`. */
        val KEY_VARIANT_DECLARATION = Regex("""\bdata\s+(?:class|object)\s+([A-Z][A-Za-z0-9_]*)""")

        /**
         * The three library names the wrapper exists to be the only caller of.
         *
         * `sharedElement` and `sharedBounds` are matched up to their opening bracket, which is
         * what keeps them from also matching `sharedElementTransition(` and
         * `sharedBoundsTransition(` — the wrappers that every feature is supposed to call. There
         * is no word boundary in the middle of an identifier, so the bracket is the whole of the
         * distinction.
         */
        val RAW_SHARED_ELEMENT_API =
            Regex("""\b(?:rememberSharedContentState|sharedElement|sharedBounds)\s*\(""")

        /**
         * `BackHandler` but not `PredictiveBackHandler`: the leading `\b` needs a non-word
         * character before the `B`, and `PredictiveBackHandler` has an `e` there.
         */
        val PLAIN_BACK_HANDLER = Regex("""\bBackHandler\b""")

        /** `fun Name(` or `fun Receiver.Name(`, with the bracket as the match's last character. */
        val FUNCTION = Regex("""\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_.]*)\s*\(""")

        val TRANSITION_PARAMETER =
            Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*:\s*SharedElementTransition\b""")

        // Written with escapes rather than as a raw string: the pattern ends in a quote, and a
        // raw string ending in a quote is a run of four the reader has to count.
        val PREDICTIVE_BACK_OPT_IN =
            Regex("android:enableOnBackInvokedCallback\\s*=\\s*\"true\"")

        /**
         * Two keys and the two files each is written in, and the pairs are the point.
         *
         * `UserAvatar` is one `UserMonogram` drawn at 40dp in the row and 80dp on the profile —
         * the same composable in `:core:ui`, which is what makes "the same drawing at two
         * magnifications" true by construction rather than by two copies staying in step, and
         * that is the precondition for `sharedElement`.
         *
         * `UserName` is the same string at two type scales, `titleMedium` in the row and
         * `headlineSmall` on the profile, so the two sides are *not* one drawing and it is a
         * `sharedBounds` element instead: the box travels, the contents cross-fade inside it.
         *
         * Neither appears in `ProfileDetailPane`, and that absence is load-bearing. The pane is
         * drawn beside the list it was selected from, so its half and the row's half would be
         * visible simultaneously inside one `SharedTransitionScope` — a duplicate key with no
         * defined winner, and nothing arriving or leaving to animate anyway. `ProfileIdentity` is
         * the one composable both draw, and the pane passes it `transition = null`.
         */
        val EXPECTED_SHARED_ELEMENTS = sortedMapOf(
            "UserAvatar" to listOf(
                "feature/home/src/main/kotlin/com/kojo/boilerplate/feature/home/HomeScreen.kt",
                "feature/profile/src/main/kotlin/com/kojo/boilerplate/feature/profile/" +
                    "ProfileIdentity.kt",
            ),
            "UserName" to listOf(
                "feature/home/src/main/kotlin/com/kojo/boilerplate/feature/home/HomeScreen.kt",
                "feature/profile/src/main/kotlin/com/kojo/boilerplate/feature/profile/" +
                    "ProfileIdentity.kt",
            ),
        )

        /** Strips `<!-- … -->` spans, so a match is on markup rather than on this file's prose. */
        fun xmlWithoutComments(text: String): String =
            text.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")

        /**
         * Whether [name] appears in this span as a *value*.
         *
         * Two positions are excluded and both are the name standing for itself rather than for
         * what it holds: followed by `:` it is a declaration, and followed by a single `=` it is
         * an argument label. The second is the one that matters — `Child(transition = null)` in a
         * function whose own parameter is called `transition` is precisely the bug this rule is
         * for, and a bare word match passes it. `==` is not excluded: a null check is a use.
         */
        fun String.usesNameAsValue(name: String): Boolean =
            Regex("""\b${Regex.escape(name)}\b""").findAll(this).any { match ->
                val after = nextNonSpaceFrom(match.range.last + 1) ?: return@any true
                when {
                    this[after] == ':' -> false
                    this[after] == '=' && getOrNull(after + 1) != '=' -> false
                    else -> true
                }
            }
    }
}
