package com.kojo.boilerplate.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a token pair cannot be printed.
 *
 * Small, and it covers a leak that no other gate in this repository would see: the generated
 * `toString` of a `data class` prints its properties, so one string interpolation puts a
 * credential into a log, a crash report or a pasted test failure. Encrypting the store does
 * nothing about that.
 */
class AuthTokensTest {

    private val tokens = AuthTokens("mock-access-token", "mock-refresh-token")

    @Test
    fun `toString does not contain either token`() {
        assertFalse(tokens.toString().contains("mock-access-token"))
        assertFalse(tokens.toString().contains("mock-refresh-token"))
    }

    @Test
    fun `toString still says which fields are set and how long they are`() {
        assertTrue(tokens.toString().contains("accessToken"))
        assertTrue(tokens.toString().contains("refreshToken"))
        assertTrue(tokens.toString().contains("${"mock-access-token".length} chars"))
    }

    @Test
    fun `equality is unaffected`() {
        // The redaction is on toString alone. Every test that compares a pair — and the flow
        // that emits one — relies on the generated equals.
        assertEquals(AuthTokens("mock-access-token", "mock-refresh-token"), tokens)
        assertFalse(tokens == AuthTokens("mock-access-token", "other-refresh-token"))
    }
}
