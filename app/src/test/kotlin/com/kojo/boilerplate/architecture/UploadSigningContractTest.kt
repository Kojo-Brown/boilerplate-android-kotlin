package com.kojo.boilerplate.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ways the release signing key stops coming only from the secret store, while every other gate
 * stays green.
 *
 * SPEC.md Phase 11 item 5. What the item asks for is not "the release build is signed" — that is
 * one line of Gradle — but *where the key comes from*, and nothing about a key's provenance shows
 * up in a build, a test run or an artifact. A release signed with a keystore committed next to the
 * source and a release signed from a secret store produce byte-identical APKs.
 *
 * `scripts/verify-upload-signing.sh` covers the behaviour: it generates a throwaway key, drives the
 * real configuration with it, and checks that the gate is wired, that the right variant gets the
 * right key and that no password reaches the log. What it cannot see is the shape of the thing it
 * is driving — a second, quieter path to the same signing config would pass every check in it. That
 * is this file's half.
 *
 * ### A keystore in the repository
 *
 * The failure with the longest tail. A committed keystore is in every clone, every fork and every
 * copy of the history forever after, and the remedy is not deleting the file: it is generating a
 * new key, and for an app already published, asking Google to reset the upload key. `.gitignore`
 * covers the four extensions, which stops the accident and not the decision.
 *
 * ### A second way to reach the key
 *
 * `storePassword = project.property("…")`, a `signingConfigs` block reading `local.properties`, a
 * `keystore.properties` beside the build file: each is a line that works, that a guide will
 * recommend, and that moves the key from the secret store to the developer's disk — from where it
 * reaches a commit, a backup or a support ticket. The environment is the one channel, and it is the
 * one channel because there is nothing else to fall back to.
 *
 * ### A secret interpolated into a shell script
 *
 * `run: echo ${{ secrets.UPLOAD_KEYSTORE_PASSWORD }} | …` is the single most common way a CI secret
 * ends up readable. GitHub substitutes the value into the script *before* the shell sees it, so a
 * password with a quote or a backtick in it becomes syntax — command substitution running as the
 * job — and `set -x`, a failing pipeline or a stack trace prints the whole line. Passed as `env:`,
 * the value is handed to the process rather than to the script's text, and the masking GitHub
 * applies to it survives.
 *
 * ### A variable renamed on one side
 *
 * Six files name these three variables. Rename the secret in the workflow and the build reads
 * nothing: `checkUploadSigning` fails with "unset", which is at least loud. Rename it in the build
 * and the *workflow* still passes the old one — the failure is identical and the file to fix is the
 * other one. Holding them equal is cheaper than reading either message.
 *
 * ### Why this reads source
 *
 * Because every fact here is about a file rather than about a class: a `.gitignore` pattern, a
 * workflow step, an environment variable name. None of it survives into compiled output, and two of
 * the five are about files that are not code at all.
 */
class UploadSigningContractTest {

    @Test
    fun `no signing key material is committed`() {
        val committed = SourceTree.root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" }
            .filter { it.isFile && it.extension.lowercase() in KEYSTORE_EXTENSIONS }
            .map { it.repositoryPath() }
            .toList()

        assertTrue(committed.isEmpty()) {
            "These are keystores, and a keystore in a repository is in every clone and every fork " +
                "of it from now on. Deleting the file does not undo it: the key has to be " +
                "regenerated, and for a published app the upload key has to be reset by Google. " +
                "The upload key reaches this build only as UPLOAD_KEYSTORE_BASE64:\n" +
                committed.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `gitignore covers every extension a keystore arrives under`() {
        val patterns = File(SourceTree.root, ".gitignore").readLines()
            .map { it.substringBefore('#').trim() }
            .toSet()

        KEYSTORE_EXTENSIONS.forEach { extension ->
            assertTrue("*.$extension" in patterns) {
                "`.gitignore` does not cover `*.$extension`. The test above catches a keystore " +
                    "that is already committed; this is what stops one being staged in the first " +
                    "place, which is the only point at which it is still cheap."
            }
        }
    }

    @Test
    fun `the build reads the signing secrets from the environment and from nowhere else`() {
        val script = File(SourceTree.root, APP_BUILD_SCRIPT).readText()
        val code = KotlinSource(script).code

        assertTrue(ENVIRONMENT_READ.containsMatchIn(code)) {
            "$APP_BUILD_SCRIPT does not read anything through `providers.environmentVariable`. " +
                "That is the only channel the key travels on, and it is the only one because " +
                "there is nothing to fall back to. Which names travel on it is the next test; " +
                "this is the mechanism being there at all."
        }

        val fallbacks = OTHER_SOURCES_OF_A_SECRET.findAll(code)
            .map { it.value.trim() }
            .toList()
        assertTrue(fallbacks.isEmpty()) {
            "$APP_BUILD_SCRIPT reaches for a signing secret somewhere other than the environment: " +
                fallbacks.joinToString(", ") + ". Each of these puts the upload key on a disk " +
                "somebody works on, which is where a commit, a backup and a support ticket can " +
                "reach it. A Gradle property is worse again: it is visible in the process list of " +
                "every user on the machine for as long as the build runs."
        }
    }

    @Test
    fun `the rule that finds another source of a secret can still find one`() {
        // Both directions, because a regex over source can go quiet either way — by matching
        // nothing after a rename, or by matching so much that it gets loosened until it matches
        // nothing. Every positive here is a line a Play signing guide actually recommends.
        fun finds(line: String) = OTHER_SOURCES_OF_A_SECRET.containsMatchIn(line)

        assertTrue(finds("""storePassword = keystoreProperties["storePassword"]"""))
        assertTrue(finds("""keyPassword = project.property("KEY_PASSWORD") as String"""))
        assertTrue(finds("""storePassword = providers.gradleProperty("storePass").get()"""))
        assertTrue(finds("""load(File(rootDir, "local.properties").inputStream())"""))
        assertTrue(finds("""storeFile = file("upload-keystore.jks")"""))
        assertTrue(!finds("""storePassword = key.secrets.storePassword"""))
        assertTrue(!finds("""providers.gradleProperty("uploadSigningIdentity")"""))
    }

    @Test
    fun `no workflow interpolates a secret into a shell script`() {
        val offenders = workflows().flatMap { workflow ->
            val lines = workflow.readLines()
            lines.withIndex()
                .filter { (_, line) -> SECRET_REFERENCE.containsMatchIn(line) }
                .filterNot { (index, _) -> lines.enclosingKeyOf(index) == "env:" }
                .map { (index, line) -> "${workflow.repositoryPath()}:${index + 1} — ${line.trim()}" }
        }

        assertTrue(offenders.isEmpty()) {
            "A secret referenced anywhere but under an `env:` mapping is substituted into the " +
                "text of the step before the shell reads it. A value containing a quote or a " +
                "backtick then becomes shell syntax running as the job, and `set -x`, a failing " +
                "pipeline or a stack trace prints the line it is in. Hand it over as `env:` and " +
                "read it as an environment variable from the script:\n" +
                offenders.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `the signing secrets are named identically everywhere they are named`() {
        // The build is the definition and everything else has to agree with it, so the build is
        // read first and the rest are checked against what it says rather than against a list here
        // — a list is what stops covering the variable somebody adds next.
        //
        // Read as string literals rather than as arguments to a particular call, because the name
        // reaches `providers.environmentVariable` through a local helper that also collects the
        // ones that are unset, and a rule shaped around that helper's name would be a rule about
        // how the function is factored. `UPLOAD_` is the prefix every one of these carries; a
        // secret added without it is a secret this test does not see, which is the one blind spot
        // here and the reason the prefix is a convention rather than a coincidence.
        val script = File(SourceTree.root, APP_BUILD_SCRIPT).readText()
        val declared = UPLOAD_SECRET_LITERAL.findAll(script)
            .map { it.groupValues[1] }
            .toSortedSet()

        assertEquals(SIGNING_SECRETS.toSortedSet(), declared) {
            "$APP_BUILD_SCRIPT reads a different set of environment variables than this test knows " +
                "about. If a secret was added or renamed, the workflows, the rehearsal script, " +
                "gradle/upload-signing.properties and docs/release-signing.md all name it too."
        }

        NAME_THE_SECRETS.forEach { path ->
            val text = File(SourceTree.root, path).readText()
            declared.forEach { variable ->
                assertTrue(text.contains(variable)) {
                    "$path does not mention `$variable`. Every one of these files names the set of " +
                        "secrets, and a rename on one side of it is silent: the build reads " +
                        "nothing, the workflow passes something nothing reads, and both report " +
                        "the same 'unset'."
                }
            }
        }
    }

    @Test
    fun `CI runs the rehearsal and the rehearsal's own tests`() {
        val ci = File(SourceTree.root, CI_WORKFLOW).readText()

        assertTrue(ci.contains(REHEARSAL_SCRIPT)) {
            "$CI_WORKFLOW does not run $REHEARSAL_SCRIPT. It is the only thing that ever exercises " +
                "the signing configuration: `release` cannot be built here, so without it the " +
                "whole block in $APP_BUILD_SCRIPT is code no build runs."
        }
        assertTrue(ci.contains(REHEARSAL_SELF_TEST)) {
            "$CI_WORKFLOW does not run $REHEARSAL_SELF_TEST. The rehearsal's happy path runs on " +
                "every build and its failure paths run nowhere else, so a rehearsal that stopped " +
                "checking would report green about exactly that."
        }
        assertTrue(File(SourceTree.root, IDENTITY_FILE).isFile) {
            "$IDENTITY_FILE is gone. It is where the upload key's identity is declared; without it " +
                "the build has nothing to compare a keystore against and no release can be signed."
        }
    }

    private companion object {
        const val APP_BUILD_SCRIPT = "app/build.gradle.kts"
        const val CI_WORKFLOW = ".github/workflows/ci.yml"
        const val IDENTITY_FILE = "gradle/upload-signing.properties"
        const val REHEARSAL_SCRIPT = "scripts/verify-upload-signing.sh"
        const val REHEARSAL_SELF_TEST = "scripts/verify-upload-signing.test.sh"

        /** Every extension a signing keystore is written under, PKCS #12 and JKS alike. */
        val KEYSTORE_EXTENSIONS = setOf("jks", "keystore", "p12", "pfx")

        /** The secrets, as the build names them. Asserted against the build itself, not trusted. */
        val SIGNING_SECRETS = setOf(
            "UPLOAD_KEYSTORE_BASE64",
            "UPLOAD_KEYSTORE_PASSWORD",
            "UPLOAD_KEY_PASSWORD",
        )

        /** Every file that names the set of secrets, and so every file a rename has to reach. */
        val NAME_THE_SECRETS = listOf(
            IDENTITY_FILE,
            REHEARSAL_SCRIPT,
            "docs/release-signing.md",
            ".github/workflows/release.yml",
        )

        /**
         * A signing secret taken from anywhere but the environment.
         *
         * Four families, each a line that appears in a published guide to Play signing: a
         * properties file loaded and indexed, a Gradle property, `local.properties`, and a
         * `storeFile` pointed at a path inside the checkout. `storeFile` is deliberately included —
         * a keystore the build can open by relative path is a keystore in the repository, which is
         * the first test above arriving from the other direction.
         */
        val OTHER_SOURCES_OF_A_SECRET = Regex(
            """(store|key)Password\s*=\s*(?!.*\.secrets\.)[^\n]*""" +
                """(Properties|property|gradleProperty|getProperty|System\.getenv)[^\n]*""" +
                """|local\.properties""" +
                """|storeFile\s*=\s*(file|File)\s*\(""",
        )

        /** The one mechanism the build is allowed to read a secret through. */
        val ENVIRONMENT_READ = Regex("""providers\.environmentVariable\s*\(""")

        /**
         * A string literal that is exactly the name of one of these secrets.
         *
         * Anchored on both quotes so that the same name inside a longer message — every failure
         * message in the signing block names the variable it is about — is not a second finding.
         */
        val UPLOAD_SECRET_LITERAL = Regex(""""(UPLOAD_[A-Z0-9_]+)"""")

        /** A `${'$'}{{ secrets.NAME }}` reference, wherever it appears in a workflow. */
        val SECRET_REFERENCE = Regex("""\$\{\{\s*secrets\.[A-Z0-9_]+\s*\}\}""")

        /** Every workflow in the repository. */
        fun workflows(): List<File> =
            File(SourceTree.root, ".github/workflows").listFiles()
                ?.filter { it.extension == "yml" || it.extension == "yaml" }
                ?.sortedBy { it.name }
                .orEmpty()

        /**
         * The nearest key above [index] that this line is nested under, trimmed of its value.
         *
         * Enough for the one question asked of it — "is this line inside an `env:` mapping" — and
         * deliberately not a YAML parser: a mapping key at a smaller indentation is what a nesting
         * level is in YAML's block style, which is the only style these workflows use. A line
         * inside a block scalar would be misread, and no `run:` script here contains a `key:` at a
         * smaller indentation than its own step's.
         */
        fun List<String>.enclosingKeyOf(index: Int): String? {
            val indent = this[index].indentation()
            return (index - 1 downTo 0)
                .asSequence()
                .map { this[it] }
                .filter { it.isNotBlank() }
                .firstOrNull { it.indentation() < indent }
                ?.trim()
        }

        fun String.indentation(): Int = takeWhile { it == ' ' }.length
    }
}
