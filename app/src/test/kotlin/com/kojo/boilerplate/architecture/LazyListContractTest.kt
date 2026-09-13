package com.kojo.boilerplate.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two things a lazy list gets wrong by omission, and the one it can only be pinned on.
 *
 * ### What a missing `contentType` costs
 *
 * A lazy layout does not keep a composition per row; it keeps a small pool of scrolled-off
 * subcompositions and offers one to each incoming item. The offer is only made between items of
 * the *same* content type, because a reused subcomposition is a slot table already shaped like the
 * tree that wrote it — hand a `Button`'s table to a bordered card and there is nothing in it to
 * reuse, so the runtime discards it and composes from scratch.
 *
 * The default content type is `null`, which is one type shared by every slot in the list. A list
 * of one shape is therefore correct by default, and that is most lists. A list that mixes shapes —
 * a heading, a panel, a run of cards, a pair of buttons — is the case where the default is wrong,
 * and it is wrong *silently*: every item still renders, in the right order, at the right size. The
 * only symptom is that the reuse pool never hits, which looks like nothing until a list is long
 * enough to scroll on a slow device.
 *
 * So the rule below asks for a content type only where it can pay: a list that emits more than one
 * kind of slot. Requiring one everywhere would put a name on the single class a homogeneous list
 * already is, and a rule with a pointless fix is a rule people learn to satisfy rather than read.
 *
 * ### What a missing `key` costs
 *
 * A slot with no key is identified by its index in the item provider. That is correct exactly
 * while indices are stable, and the two things that move them are a reorder of the items and a
 * *conditional* slot appearing or disappearing above them — at which point the runtime believes
 * the items below were replaced by different ones, drops the state they remembered, and re-anchors
 * a scroll that was pinned to one of them.
 *
 * The fixed slots of a mixed list are the ones this is always fixable for: each is a distinct,
 * nameable piece of content, so it can carry a name. `items`/`itemsIndexed` over a model is the
 * case where it may not be — see the pin below.
 *
 * ### What it deliberately does not check
 *
 * Whether the identity an `items` call uses is the *right* one. A key has to be unique and stable
 * across the emissions the list will actually see, and both halves are claims about the model
 * rather than about the source: a key that repeats is an exception thrown by the lazy layout, and
 * a key that moves is the bug keys exist to prevent. Where the model has an id the answer is
 * obvious and where it has none the index is usually right, so [EXPECTED_INDEX_KEYED] pins the
 * calls that take the index and `docs/lazy-lists.md` carries the argument for each.
 *
 * ### Why this reads source rather than compiled output
 *
 * For the same reason as [DerivedStateContractTest] and [RecompositionContractTest], and see
 * [SourceTree]: `key` and `contentType` are arguments to an inline builder call, so what reaches
 * the class file is the lambda the builder was given, not the fact that a named argument was
 * supplied at all. It costs what a text check always costs — it cannot see through an alias — and
 * comments and string literals are blanked first ([KotlinSource]) or this KDoc would be the first
 * thing to fail it.
 */
class LazyListContractTest {

    @Test
    fun `every slot of a mixed lazy list declares a content type`() {
        val violations = scans().flatMap { it.slotsMissingContentType() }

        assertTrue(violations.isEmpty()) {
            "A lazy layout reuses a scrolled-off item's composition only for an item declaring " +
                "the same `contentType`, and the default is one type shared by the whole list. " +
                "In a list of mixed shapes that means every reuse is a miss, silently. Name the " +
                "shape each slot produces:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `every fixed slot of a mixed lazy list declares a key`() {
        val violations = scans().flatMap { it.fixedSlotsMissingKey() }

        assertTrue(violations.isEmpty()) {
            "An unkeyed slot is identified by its index in the item provider, so a sibling slot " +
                "emitted conditionally shifts the identity of everything after it — dropping " +
                "remembered state and moving an anchored scroll. A fixed slot is one nameable " +
                "piece of content; give it a name:\n" +
                violations.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Pins the `items`/`itemsIndexed` calls that fall back to index identity, because whether that
     * is right is a property of the model rather than of the call. A new entry here is the moment
     * to say why the model has no id worth keying on.
     */
    @Test
    fun `index identity stays where it was argued for`() {
        assertEquals(
            EXPECTED_INDEX_KEYED,
            scans().associate { it.path to it.indexKeyedCollections.size }.filterValues { it > 0 },
            "The set of unkeyed `items` / `itemsIndexed` calls changed. Index identity is right " +
                "only for a list that is replaced whole and never reordered — say which model " +
                "that is, and why it carries no id, in docs/lazy-lists.md before adding it here.",
        )
    }

    /**
     * Pins the population the two rules above are applied to. Both are `flatMap`s over whatever the
     * walk found, so a walk that finds nothing passes having audited nothing — the one way this
     * class can fail useless.
     */
    @Test
    fun `discovery finds every lazy list in the app`() {
        assertEquals(
            EXPECTED_LAZY_LISTS,
            scans().associate { it.path to it.lazyLists.size }.filterValues { it > 0 },
            "The set of lazy containers changed. Adding one is the moment to decide what " +
                "identifies its items and how many shapes it emits.",
        )
    }

    /**
     * Guards the parser rather than the code, and covers the hole in [LAZY_BUILDERS] at the same
     * time. Everything the two rules above assert is scoped to a slot's enclosing lazy container,
     * so a container spelling this file does not know — a new `Lazy…` type, or an alias — would not
     * fail them; it would quietly stop auditing the slots inside it.
     *
     * `item` is not exclusively a lazy-list word, so the answer is a pin rather than an emptiness
     * assertion: Material 3's `NavigationSuiteScope` has one too. The known non-lazy call sites are
     * listed, and anything else arriving here is either a lazy container this file cannot see or a
     * declaration shape its parser has stopped recognising.
     */
    @Test
    fun `every slot call is either inside a lazy container or a known non-lazy builder`() {
        assertEquals(
            EXPECTED_NON_LAZY_SLOTS,
            scans().associate { it.path to it.slotsOutsideLazyLists.size }.filterValues { it > 0 },
            "An `item` / `items` call was found outside every lazy container this test knows " +
                "about. Either it belongs to another DSL — record it here — or LAZY_BUILDERS is " +
                "missing a container type and the rules above have stopped auditing its slots.",
        )
    }

    private fun scans(): List<LazyScan> =
        SourceTree.mainSources().map { LazyScan(it.repositoryPath(), KotlinSource(it.readText())) }

    /**
     * One lazy container call, with the span of the trailing lambda that builds its items. A
     * container whose builder lambda cannot be found is not recorded at all — see [LazyScan].
     */
    private class LazyContainer(val name: String, val at: Int, val body: IntRange)

    /**
     * One slot-producing call. [arguments] is the text between its parentheses and is empty for the
     * brace form (`item { … }`), which is exactly the case where no named argument was supplied.
     */
    private class Slot(val name: String, val at: Int, val arguments: String) {

        val fixed: Boolean get() = name in FIXED_SLOTS

        fun declares(argument: String): Boolean =
            Regex("""\b$argument\s*=""").containsMatchIn(arguments)
    }

    /** Everything one source file has to say about its lazy lists. */
    private class LazyScan(val path: String, private val source: KotlinSource) {

        val lazyLists: List<LazyContainer> = findContainers()

        private val slots: List<Slot> = findSlots()

        /** Containers emitting more than one slot — the only ones a content type can pay in. */
        private val mixed: List<LazyContainer> =
            lazyLists.filter { container -> slotsIn(container).size > 1 }

        val indexKeyedCollections: List<Slot> =
            slots.filter { !it.fixed && enclosing(it) != null && !it.declares(KEY) }

        val slotsOutsideLazyLists: List<Slot> = slots.filter { enclosing(it) == null }

        fun slotsMissingContentType(): List<String> =
            mixed.flatMap { slotsIn(it) }.filterNot { it.declares(CONTENT_TYPE) }
                .map { describe(it, "declares no `contentType`") }

        fun fixedSlotsMissingKey(): List<String> =
            mixed.flatMap { slotsIn(it) }.filter { it.fixed && !it.declares(KEY) }
                .map { describe(it, "declares no `key`") }

        private fun describe(slot: Slot, fault: String): String =
            "$path:${source.lineOf(slot.at)} — `${slot.name}` in " +
                "${enclosing(slot)?.name ?: "?"} $fault"

        private fun slotsIn(container: LazyContainer): List<Slot> =
            slots.filter { enclosing(it) === container }

        /**
         * The container a slot belongs to, taken as the innermost whose builder lambda spans it. A
         * lazy list nested inside another's item is the case that makes "innermost" rather than
         * "any" the answer.
         */
        private fun enclosing(slot: Slot): LazyContainer? =
            lazyLists.filter { slot.at in it.body }.minByOrNull { it.body.last - it.body.first }

        private fun findContainers(): List<LazyContainer> =
            LAZY_CALL.findAll(source.code).mapNotNull { match ->
                val body = builderBodyAt(source.code, match.range.last) ?: return@mapNotNull null
                LazyContainer(name = match.groupValues[1], at = match.range.first, body = body)
            }.toList()

        private fun findSlots(): List<Slot> =
            SLOT_CALL.findAll(source.code).mapNotNull { match ->
                val bracket = match.range.last
                val arguments = if (source.code[bracket] == '(') {
                    val close = source.code.closingBracketAt(bracket) ?: return@mapNotNull null
                    source.code.substring(bracket + 1, close)
                } else {
                    ""
                }
                Slot(name = match.groupValues[1], at = match.range.first, arguments = arguments)
            }.toList()
    }

    private companion object {

        /**
         * Every lazy container in the foundation library. The list is a vocabulary rather than a
         * guess: a container missing from it is caught by the orphan pin, because its slots would
         * then belong to nothing.
         */
        val LAZY_BUILDERS = listOf(
            "LazyColumn",
            "LazyRow",
            "LazyVerticalGrid",
            "LazyHorizontalGrid",
            "LazyVerticalStaggeredGrid",
            "LazyHorizontalStaggeredGrid",
        )

        /**
         * The call, with the offset of the bracket opening either its argument list or its builder
         * lambda as the match's last character.
         */
        val LAZY_CALL = Regex("""\b(${LAZY_BUILDERS.joinToString("|")})\s*[({]""")

        /**
         * The slot-producing calls of `LazyListScope` and its grid equivalents, longest name first
         * so that `itemsIndexed` is never read as `items`. Matching requires the bracket, which is
         * what keeps `itemContent`, `items.forEach` and `item = item` out.
         */
        val SLOT_CALL = Regex("""\b(itemsIndexed|items|stickyHeader|item)\s*[({]""")

        /** Slots producing exactly one piece of content, and so always nameable. */
        val FIXED_SLOTS = setOf("item", "stickyHeader")

        const val KEY = "key"

        const val CONTENT_TYPE = "contentType"

        /**
         * The span of the builder lambda for a container whose call starts a bracket at [bracket] —
         * the brace form directly, and the parenthesised form by stepping over the arguments to the
         * trailing lambda after them. `null` when there is no trailing lambda, which is a call this
         * file should not pretend to understand rather than one to report.
         */
        private fun builderBodyAt(code: String, bracket: Int): IntRange? {
            val brace = if (code[bracket] == '{') {
                bracket
            } else {
                val arguments = code.closingBracketAt(bracket) ?: return null
                code.nextNonSpaceFrom(arguments + 1)?.takeIf { code[it] == '{' } ?: return null
            }
            return brace..(code.closingBracketAt(brace) ?: return null)
        }

        val EXPECTED_LAZY_LISTS = mapOf(
            "feature/home/src/main/kotlin/com/kojo/boilerplate/feature/home/HomeScreen.kt" to 1,
            "feature/textrecognition/src/main/kotlin/com/kojo/boilerplate/feature/" +
                "textrecognition/TextRecognitionScreen.kt" to 1,
        )

        /**
         * The one list in the app whose model carries no identity: an ML Kit text block is a string
         * and a confidence, two of which can be identical in one frame — so a key derived from its
         * content would be a duplicate, and a duplicate key is an exception rather than a
         * mis-render. The list is replaced whole on the next scan and never reordered, which is
         * precisely the case index identity is correct for.
         */
        val EXPECTED_INDEX_KEYED = mapOf(
            "feature/textrecognition/src/main/kotlin/com/kojo/boilerplate/feature/" +
                "textrecognition/TextRecognitionScreen.kt" to 1,
        )

        /**
         * `NavigationSuiteScope.item` — Material 3's navigation-suite builder, which shares the
         * name and none of the behaviour: it declares a destination, there is no recycling and
         * nothing to key.
         */
        val EXPECTED_NON_LAZY_SLOTS = mapOf(
            "core/ui/src/main/kotlin/com/kojo/boilerplate/core/ui/adaptive/" +
                "AdaptiveNavigationScaffold.kt" to 1,
        )
    }
}
