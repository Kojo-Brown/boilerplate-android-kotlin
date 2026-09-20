package com.kojo.boilerplate.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three ways encrypted token storage stops protecting anything while every other gate stays
 * green.
 *
 * Each of them compiles, passes lint, passes every unit test, and produces an app that signs in
 * and out correctly on the device it was built on. That is what makes them worth pinning here
 * rather than reviewing for.
 *
 * ### A store that is backed up
 *
 * `android:allowBackup="true"` copies the app's files off the device. The AES key behind the
 * tokens is in the Android keystore and does not go with them — by design; that is most of what
 * a keystore is for. So a restore lands well-formed ciphertext on a device that can never read
 * it, and the reader is silently signed out on a new handset with nothing in the log. Worse in
 * the other direction: a *plaintext* store under `allowBackup` is a credential leaving the
 * device entirely, which is the original bug this item exists to close, and it would come back
 * the moment someone stored something next to the tokens without thinking about backup.
 *
 * Two files say this, not one, and that is the part a review misses. The platform reads
 * `android:dataExtractionRules` from API 31 and `android:fullBackupContent` below it — never
 * both — so a rule added to one and not the other silently covers part of the install base.
 * This app's `minSdk` is 26.
 *
 * ### A path that drifted
 *
 * The exclusions are file paths in XML naming a store whose name is a Kotlin constant. Nothing
 * connects the two: renaming the constant moves the file and leaves the exclusion pointing at
 * a path that no longer exists, which is not an error in any tool — an exclusion that matches
 * nothing simply excludes nothing.
 *
 * ### A plaintext key written again
 *
 * The two preference keys an older version of this app wrote its tokens into are still in the
 * source, because an installation carrying them has to be migrated before it can be cleaned.
 * They are read-then-delete only. A future edit that assigns to one — a revert, a merge, a
 * copy-paste from the migration path — puts plaintext tokens back on disk beside the encrypted
 * ones, and every test in the suite still passes, because the app reads the encrypted pair.
 *
 * ### Why this reads source and XML
 *
 * For the reason [SharedElementContractTest] does: a manifest attribute and an `<exclude>` path
 * are not Kotlin, and "this constant is never assigned to" is not a fact a class file records.
 * Comments and string literals are blanked before the Kotlin half is matched ([KotlinSource]),
 * which this file needs as much as its neighbours do — its own prose names the keys it bans.
 */
class TokenStorageContractTest {

    @Test
    fun `the auth token store is excluded from cloud backup on both platform versions`() {
        val expected = "datastore/$storeName.preferences_pb"

        assertTrue(excludedPaths(FULL_BACKUP_RULES).contains(expected)) {
            "${FULL_BACKUP_RULES} does not exclude `$expected`, so on API 30 and below Auto " +
                "Backup copies the auth-token store off the device. The ciphertext restores " +
                "fine and the keystore key does not follow it, so the reader is signed out on " +
                "the new device with nothing logged anywhere. Found: " +
                excludedPaths(FULL_BACKUP_RULES).joinToString(", ").ifEmpty { "no exclusions" }
        }

        val extraction = excludedPaths(DATA_EXTRACTION_RULES)
        assertTrue(extraction.contains(expected)) {
            "${DATA_EXTRACTION_RULES} does not exclude `$expected`, so on API 31 and above the " +
                "auth-token store is backed up or transferred to the new device without the " +
                "keystore key that makes it readable. Found: " +
                extraction.joinToString(", ").ifEmpty { "no exclusions" }
        }
    }

    @Test
    fun `the API 31 rules exclude the store from device transfer as well as cloud backup`() {
        val expected = "datastore/$storeName.preferences_pb"
        val sections = sectionsExcluding(DATA_EXTRACTION_RULES, expected)

        assertEquals(setOf("cloud-backup", "device-transfer"), sections) {
            "A direct device-to-device transfer copies the app's files with no cloud in the " +
                "path, which makes it read as the safer channel — but the keystore key does " +
                "not travel with it either, so restored ciphertext is exactly as unreadable " +
                "there. `$expected` must be excluded from both sections of " +
                "$DATA_EXTRACTION_RULES; it is excluded from: " +
                sections.sorted().joinToString(", ").ifEmpty { "neither" }
        }
    }

    @Test
    fun `the manifest names both rules files`() {
        val manifest = file(MANIFEST).readText()

        REQUIRED_MANIFEST_ATTRIBUTES.forEach { attribute ->
            assertTrue(manifest.contains(attribute)) {
                "$MANIFEST is missing `$attribute`. Without it the rules file it names is " +
                    "never read, every exclusion in it is inert, and the auth-token store goes " +
                    "back into Auto Backup on the platform versions that attribute covers."
            }
        }
    }

    @Test
    fun `the plaintext token keys are only ever read and removed, never written`() {
        val writes = mutableListOf<String>()
        var removals = 0
        var declarations = 0

        SourceTree.mainSources().forEach { file ->
            val source = KotlinSource(file.readText())
            LEGACY_KEY_REFERENCE.findAll(source.code).forEach { match ->
                val before = source.code.take(match.range.first).trimEnd()
                val after = source.code.substring(match.range.last + 1)
                when {
                    // The key being declared: `internal val KEY_LEGACY_… = stringPreferencesKey(…)`.
                    DECLARATION.containsMatchIn(lineAt(source.code, match.range.first)) ->
                        declarations++

                    before.endsWith("remove(") -> removals++

                    // `prefs[KEY] = …`, which is the thing being banned. It has to be told
                    // apart from `prefs[KEY]` on its own, because the migration *reads* both
                    // keys — a rule that banned every mention would ban the code that cleans
                    // them up.
                    ASSIGNMENT.containsMatchIn(after) ->
                        writes += "${file.repositoryPath()}:" +
                            "${source.lineOf(match.range.first)} — ${match.value}"

                    // A read: `prefs[KEY]`. That is the migration, and it is the point.
                    else -> Unit
                }
            }
        }

        // Discovery pins. Without them this test passes by finding nothing the day the keys are
        // renamed, which is precisely the day it should be asserting hardest.
        assertTrue(declarations == LEGACY_KEYS.size) {
            "Expected ${LEGACY_KEYS.size} declarations of the legacy plaintext preference keys " +
                "(${LEGACY_KEYS.joinToString(", ")}) and found $declarations. If they were " +
                "renamed, rename them here; if the migration was deleted, delete this test with " +
                "it — but not before every installation carrying a plaintext store is gone."
        }
        assertTrue(removals > 0) {
            "The legacy plaintext preference keys are declared and never removed, so an " +
                "installation carrying them keeps its plaintext tokens on disk forever."
        }

        assertTrue(writes.isEmpty()) {
            "The legacy preference keys hold *plaintext* tokens and exist only to be read once " +
                "and deleted. Writing to one puts a readable credential back on disk beside the " +
                "encrypted pair, and nothing else in this repository would notice — the app " +
                "reads the encrypted pair:\n" + writes.joinToString("\n") { "  - $it" }
        }
    }

    @Test
    fun `the rule that tells a write from a read can still tell them apart`() {
        // The discriminator above decides whether this test asserts anything at all, and it can
        // go quiet in both directions: a pattern that matched nothing would report every write
        // as a read, and one that matched too much would report the migration's reads as writes.
        // Both are silent, so both are pinned here against the four shapes that occur.
        assertTrue(ASSIGNMENT.containsMatchIn("] = encrypted")) { "an assignment is a write" }
        assertTrue(ASSIGNMENT.containsMatchIn("]  =  encrypted")) { "spacing is not meaningful" }
        assertTrue(!ASSIGNMENT.containsMatchIn("],")) { "a read in an argument list is not" }
        assertTrue(!ASSIGNMENT.containsMatchIn("] == other")) { "a comparison is not a write" }
    }

    /** The `name` given to the auth-token `DataStore`, read from the constant that declares it. */
    private val storeName: String by lazy {
        val declaration = file(AUTH_TOKEN_STORE_FILE).readText()
        STORE_NAME.find(declaration)?.groupValues?.get(1)
            ?: error(
                "no `const val AUTH_TOKEN_STORE = \"…\"` in $AUTH_TOKEN_STORE_FILE. That " +
                    "constant is what ties the backup exclusions to the file they exclude.",
            )
    }

    /** Every `path` named by an `<exclude>` in [relativePath]. */
    private fun excludedPaths(relativePath: String): List<String> =
        EXCLUDE_PATH.findAll(file(relativePath).readText()).map { it.groupValues[1] }.toList()

    /** The rule sections of [relativePath] that exclude [path]. */
    private fun sectionsExcluding(relativePath: String, path: String): Set<String> {
        val text = file(relativePath).readText()
        return SECTION.findAll(text)
            .filter { EXCLUDE_PATH.findAll(it.groupValues[2]).any { m -> m.groupValues[1] == path } }
            .map { it.groupValues[1] }
            .toSet()
    }

    /** The whole source line [offset] falls on. */
    private fun lineAt(code: String, offset: Int): String {
        val start = code.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
        val end = code.indexOf('\n', offset).let { if (it < 0) code.length else it }
        return code.substring(start, end)
    }

    private fun file(relativePath: String): File {
        val resolved = File(SourceTree.root, relativePath)
        check(resolved.isFile) { "$relativePath does not exist" }
        return resolved
    }

    private companion object {
        const val MANIFEST = "app/src/main/AndroidManifest.xml"
        const val FULL_BACKUP_RULES = "app/src/main/res/xml/backup_rules.xml"
        const val DATA_EXTRACTION_RULES = "app/src/main/res/xml/data_extraction_rules.xml"
        const val AUTH_TOKEN_STORE_FILE =
            "data/src/main/kotlin/com/kojo/boilerplate/core/datastore/AuthTokenDataStore.kt"

        val REQUIRED_MANIFEST_ATTRIBUTES = listOf(
            "android:fullBackupContent=\"@xml/backup_rules\"",
            "android:dataExtractionRules=\"@xml/data_extraction_rules\"",
        )

        val LEGACY_KEYS = listOf("KEY_LEGACY_ACCESS_TOKEN", "KEY_LEGACY_REFRESH_TOKEN")

        val LEGACY_KEY_REFERENCE = Regex("\\b(?:${LEGACY_KEYS.joinToString("|")})\\b")

        val DECLARATION = Regex("\\bval\\s+KEY_LEGACY_\\w+\\s*=")

        /**
         * A subscript assignment starting where a key reference ended: `]` then `=`, and not
         * `==`. Anchored at the start of what follows the match so it cannot reach past the
         * expression the key sits in.
         */
        val ASSIGNMENT = Regex("\\A\\s*]\\s*=(?!=)")

        val STORE_NAME = Regex("""const\s+val\s+AUTH_TOKEN_STORE\s*=\s*"([^"]+)"""")

        val EXCLUDE_PATH = Regex("""<exclude\b[^>]*\bpath="([^"]+)"""")

        /** A `<cloud-backup>` or `<device-transfer>` element and everything inside it. */
        val SECTION = Regex(
            """<(cloud-backup|device-transfer)\b[^>]*>(.*?)</\1>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
