package com.kojo.boilerplate.core.security.integrity

import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of [PlayIntegrityErrorCode] that needs the real library.
 *
 * That enum writes Play's error codes out as numbers so the mapping can be compiled and tested
 * without the Android toolchain. The cost of that is two copies of a list, and this is where the
 * copies are held together: it reflects over the real `StandardIntegrityErrorCode` and fails if
 * any number disagrees, if the library declares a code the enum has never heard of, or if the
 * enum names one the library has dropped.
 *
 * The third case is the one that pays for this test twice. A Play Integrity upgrade that adds a
 * code otherwise lands in [PlayIntegrityErrorCode.failureFor]'s `else` branch — treated as
 * transient, retried, and never noticed, however much it was worth branching on. Here it is a
 * red test that names the constant.
 *
 * Reflection rather than a `when`, because a `when` over the library's constants would only
 * prove that the constants this file already knows about exist. What is wanted is the
 * *difference* between the two sets, which nothing but enumeration can produce.
 */
class PlayIntegrityErrorCodeContractTest {

    @Test
    fun `every code this app maps has the value the library gives it`() {
        val declared = declaredErrorCodes()

        PlayIntegrityErrorCode.entries.forEach { entry ->
            val libraryValue = declared[entry.name]
            assertTrue(
                "StandardIntegrityErrorCode has no constant named ${entry.name}. Either Play " +
                    "dropped it — in which case remove the enum entry — or this test is " +
                    "reading the wrong class. Found: ${declared.keys.sorted()}",
                libraryValue != null,
            )
            assertEquals(
                "StandardIntegrityErrorCode.${entry.name} is $libraryValue and " +
                    "PlayIntegrityErrorCode.${entry.name} says ${entry.code}. The enum's " +
                    "numbers are what the app maps on, so this one is currently mapped to " +
                    "nothing and falls through to the transient default.",
                libraryValue,
                entry.code,
            )
        }
    }

    @Test
    fun `the library declares no code this app has never heard of`() {
        val unmapped = declaredErrorCodes().keys - PlayIntegrityErrorCode.entries.map { it.name }.toSet()

        assertTrue(
            "StandardIntegrityErrorCode declares ${unmapped.sorted()}, which " +
                "PlayIntegrityErrorCode does not list — so they reach failureFor's else branch " +
                "and are retried as transient whatever they actually mean. Add an entry for " +
                "each with the AttestationFailure it deserves.",
            unmapped.isEmpty(),
        )
    }

    /**
     * The library's constants, by name.
     *
     * Asserted non-empty rather than returned quietly. `StandardIntegrityErrorCode` is a
     * constants holder today; if a release turns it into something whose values are not public
     * static `int` fields, both tests above would pass by comparing against nothing, which is
     * the one way this file can be worse than not existing.
     */
    private fun declaredErrorCodes(): Map<String, Int> {
        val constants = StandardIntegrityErrorCode::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }

        assertTrue(
            "No public static int constants on StandardIntegrityErrorCode. This test reads the " +
                "library by reflection and has just read nothing, so it is asserting nothing — " +
                "find out what shape the codes take now and rewrite it rather than deleting it.",
            constants.isNotEmpty(),
        )
        return constants
    }
}
