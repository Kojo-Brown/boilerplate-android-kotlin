package com.kojo.boilerplate.core.security.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the build writes into `BuildConfig`, read back.
 *
 * The case that matters is the third one. A value that does not parse must not become
 * [IntegrityConfiguration.DISABLED]: that is attestation quietly switching itself off on a build
 * that asked for it, and nothing downstream can tell the difference — a build with attestation
 * off behaves exactly like a build whose every device happens to be unable to attest. So the
 * malformed cases throw, at injection, where the message can name the properties file.
 */
class IntegrityConfigurationTest {

    @Test
    fun `an empty value is attestation off`() {
        assertEquals(IntegrityConfiguration.DISABLED, IntegrityConfiguration.parse(""))
        assertNull(IntegrityConfiguration.parse("").cloudProjectNumber)
    }

    @Test
    fun `whitespace around the value is the build's, not the reader's`() {
        // `buildConfigField` writes whatever the properties file held; trimming here rather
        // than relying on the build having done it keeps the two ends independently correct.
        assertEquals(
            IntegrityConfiguration(PROJECT_NUMBER),
            IntegrityConfiguration.parse("  $PROJECT_NUMBER  "),
        )
        assertEquals(IntegrityConfiguration.DISABLED, IntegrityConfiguration.parse("   "))
    }

    @Test
    fun `a number is the linked cloud project`() {
        assertEquals(
            IntegrityConfiguration(PROJECT_NUMBER),
            IntegrityConfiguration.parse(PROJECT_NUMBER.toString()),
        )
    }

    @Test
    fun `anything that is not a positive number is rejected rather than ignored`() {
        // The project ID rather than the project number is the mistake this is shaped around:
        // Play Console shows both, they sit beside each other, and one of them is not digits.
        listOf("my-project-id", "0", "-1", "12.5", "1_2", "\"$PROJECT_NUMBER\"", "99999999999999999999")
            .forEach { malformed ->
                val thrown = assertThrows(IllegalArgumentException::class.java) {
                    IntegrityConfiguration.parse(malformed)
                }
                assertTrue(
                    "the message for `$malformed` should name the file to fix: ${thrown.message}",
                    thrown.message.orEmpty().contains("play-integrity.properties"),
                )
            }
    }

    @Test
    fun `a non-positive project number cannot be constructed directly either`() {
        // `parse` is not the only door: `IntegrityConfiguration(0)` is one autocomplete away,
        // and a zero reaches Play as CLOUD_PROJECT_NUMBER_IS_INVALID on every device.
        assertThrows(IllegalArgumentException::class.java) { IntegrityConfiguration(0) }
        assertThrows(IllegalArgumentException::class.java) { IntegrityConfiguration(-1) }
    }

    private companion object {
        private const val PROJECT_NUMBER = 123456789012L
    }
}
