package com.kojo.boilerplate.architecture

import java.io.File

/**
 * The repository's own Kotlin sources, and the one way the contract tests are allowed to read
 * them.
 *
 * Most of this suite reads *compiled* output, which is stronger, and two of its members cannot:
 * [RecompositionContractTest] and [DerivedStateContractTest] both check properties that the class
 * file does not record — whether a lambda captured a view model, and whether a value was
 * remembered — because Kotlin 2.x compiles lambdas through `invokedynamic` and what survives is a
 * synthetic method whose parameters happen to be the captures. Those properties are written down
 * plainly in the source, so the source is what they read.
 *
 * Extracted here when the second of the two arrived. Both need the same three things — find the
 * repository root, walk `src/main`, and look at code rather than at prose — and a second copy of
 * [KotlinSource] would be a second copy of its bugs.
 */
internal object SourceTree {

    /**
     * Found by walking up rather than taken as a constant, because the working directory differs
     * between the two things that run these tests: Gradle starts a test task in the module
     * directory, the offline harness starts it at the repository root.
     */
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("no settings.gradle.kts at or above ${File("").absolutePath}")

    /** Every `src/main` Kotlin file in the repository, build output and tooling excluded. */
    fun mainSources(): List<File> =
        root.walkTopDown()
            .onEnter { it.name !in SKIPPED_DIRECTORIES }
            .filter { it.isFile && it.extension == "kt" }
            .filter { MAIN_SOURCE_SET in it.invariantPath() }
            .toList()

    private val SKIPPED_DIRECTORIES = setOf("build", "build-logic", ".git", ".gradle", "scripts")

    private const val MAIN_SOURCE_SET = "/src/main/"
}

internal fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

/** This file's path relative to the repository root, in the form the pinned sets are written in. */
internal fun File.repositoryPath(): String = relativeTo(SourceTree.root).invariantPath()

/**
 * A Kotlin file with its comments, string literals and character literals blanked out, so that a
 * match over [code] is a match on a declaration rather than on prose.
 *
 * Blanked rather than deleted: every offset stays where it was, so [lineOf] can still name the
 * line a match is on, and an offset taken from one pass is still valid in another.
 *
 * Character literals are blanked for the same reason as the other two and one more. `'{'` and
 * `'}'` are single braces as far as anything counting depth is concerned, and
 * [DerivedStateContractTest] counts depth to find where a `remember` block ends — so a file
 * containing a brace character literal would silently shift every subsequent block boundary.
 */
internal class KotlinSource(text: String) {

    val code: String = blankNonCode(text)

    fun lineOf(offset: Int): Int = code.take(offset).count { it == '\n' } + 1

    private fun blankNonCode(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val end = endOfNonCodeSpanAt(text, index)
            if (end < 0) {
                out.append(text[index])
                index++
            } else {
                // Newlines are kept so that line numbers survive; everything else becomes a
                // space, which cannot be part of an identifier and so cannot create a match.
                text.substring(index, end).forEach { out.append(if (it == '\n') '\n' else ' ') }
                index = end
            }
        }
        return out.toString()
    }

    /** The offset just past the comment or literal starting at [index], or -1 if none does. */
    private fun endOfNonCodeSpanAt(text: String, index: Int): Int = when {
        text.startsWith("//", index) -> text.indexOf('\n', index).orEndOf(text)
        text.startsWith("/*", index) -> text.indexOf("*/", index + 2).past(2, text)
        text.startsWith("\"\"\"", index) -> text.indexOf("\"\"\"", index + 3).past(3, text)
        text.startsWith("\"", index) -> endOfQuotedLiteral(text, index, '"')
        text.startsWith("'", index) -> endOfQuotedLiteral(text, index, '\'')
        else -> -1
    }

    /** The offset past the closing [quote], honouring a backslash escape. Interpolation is not read. */
    private fun endOfQuotedLiteral(text: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index++
                quote -> return index + 1
                '\n' -> return index
            }
            index++
        }
        return text.length
    }

    /** An unterminated comment or literal runs to the end of the file rather than failing. */
    private fun Int.orEndOf(text: String): Int = if (this < 0) text.length else this

    private fun Int.past(delimiter: Int, text: String): Int =
        if (this < 0) text.length else this + delimiter
}
