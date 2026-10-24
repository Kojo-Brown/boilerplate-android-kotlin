package com.kojo.boilerplate.core.ui.screenshot

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Holds the committed golden images to the tests that produce them.
 *
 * ### The half of the gate Paparazzi does not cover
 *
 * `verifyPaparazziDebug` walks the tests and asks each one for its image, so a snapshot with no
 * golden fails loudly — `File ... does not exist`, named after the test that wanted it. It never
 * walks the images, so nothing asks the opposite question. Rename a test and the old PNG stays in
 * the tree forever, verified by nothing and reviewed by nobody; delete a component and its images
 * outlive it. A stale golden is not harmless: it is the file a reader opens to see what a widget
 * looks like, and it is weight in every clone.
 *
 * This reads the directory instead, parses each file name back into the test it names — Paparazzi
 * writes `<package>_<class>_<method>.png` — and fails on anything that no longer resolves to a
 * JUnit 4 test method on a class that is still on the test classpath. It needs no renderer, so it
 * runs in the ordinary `testDebugUnitTest` gate as well as under the diff gate.
 *
 * The directory is taken from `paparazzi.snapshot.dir`, which the Gradle plugin sets on every test
 * task in this module. Reading it rather than walking up to the repository root keeps this test and
 * the plugin agreeing about one path instead of two.
 */
class GoldenImageInventoryTest {

    /**
     * An empty golden tree is a failure rather than an absence of work. Every screenshot test in
     * this package is expected to have a committed image; a tree with none of them means the
     * images were recorded in CI and never added to the commit, which would otherwise show up as
     * a wall of "does not exist" from the diff gate and nothing at all from this suite.
     */
    @Test
    fun `the golden images are committed`() {
        assertTrue(goldenImages().isNotEmpty()) {
            "No golden images under ${imagesDirectory()}. Record them in CI and commit them — " +
                "see docs/screenshot-testing.md."
        }
    }

    @Test
    fun `every golden image belongs to a test that still exists`() {
        val orphans = goldenImages().filterNot { it.namesALiveTest() }.map { it.name }.sorted()

        assertTrue(orphans.isEmpty()) {
            "These golden images name no test on the classpath. A renamed or deleted screenshot " +
                "test leaves its image behind, and nothing else in the build will ever look at " +
                "it again — delete them, or restore the test:\n" +
                orphans.joinToString("\n") { "  - $it" }
        }
    }

    private fun imagesDirectory(): File {
        val root = requireNotNull(System.getProperty(SNAPSHOT_DIR_PROPERTY)) {
            "$SNAPSHOT_DIR_PROPERTY is unset, so this test cannot find the golden images. It is " +
                "set by the Paparazzi Gradle plugin, which means this ran outside Gradle."
        }
        return File(root, "images")
    }

    private fun goldenImages(): List<File> =
        imagesDirectory().listFiles().orEmpty().filter { it.extension == "png" }.sortedBy { it.name }

    /**
     * Whether this file name resolves to a test method that exists.
     *
     * The split is on the first two delimiters only: the package carries dots rather than
     * underscores and a class name carries neither, so whatever follows the second one is the
     * method — plus a label, for a test that takes more than one snapshot and names them.
     */
    private fun File.namesALiveTest(): Boolean {
        val parts = nameWithoutExtension.split("_", limit = PARTS)
        if (parts.size < PARTS) return false
        val (packageName, className, methodAndLabel) = parts

        val testClass = runCatching { Class.forName("$packageName.$className") }.getOrNull()
            ?: return false

        return testClass.declaredMethods.any { method ->
            method.isAnnotationPresent(org.junit.Test::class.java) &&
                (methodAndLabel == method.name || methodAndLabel.startsWith("${method.name}_"))
        }
    }

    private companion object {
        /** Set by the Paparazzi Gradle plugin; the same directory `recordPaparazziDebug` writes. */
        private const val SNAPSHOT_DIR_PROPERTY = "paparazzi.snapshot.dir"

        /** Package, class, and then the method and any snapshot label it chose. */
        private const val PARTS = 3
    }
}
