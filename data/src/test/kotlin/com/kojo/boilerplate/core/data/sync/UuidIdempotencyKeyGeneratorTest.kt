package com.kojo.boilerplate.core.data.sync

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two properties a key has to have, asserted rather than assumed.
 *
 * Neither is interesting on its own, and that is the point: the ways a key generator goes wrong
 * are boring. It returns the same string twice, or it returns something the server will not
 * accept in a header, and both produce a sync that appears to work while silently dropping
 * changes.
 */
class UuidIdempotencyKeyGeneratorTest {

    private val generator = UuidIdempotencyKeyGenerator()

    /**
     * Distinctness is the whole contract: two mutations sharing a name are one mutation as far
     * as a server that deduplicates is concerned, and the second is dropped without an error.
     *
     * Ten thousand is not a probabilistic test of a 122-bit space — a collision there would be
     * a miracle, not a bug. It is a test of the *implementation*: a generator that seeded once
     * and reused the value, or that cached a key per thread, fails on the second call, and this
     * catches it however the repetition is arranged.
     */
    @Test
    fun `every key is new`() {
        val keys = List(SAMPLE_SIZE) { generator.newKey() }

        assertEquals(SAMPLE_SIZE, keys.toSet().size)
    }

    /**
     * The key travels in an HTTP header, so it has to be something a header can carry —
     * printable ASCII with no whitespace, no colon and no newline. A UUID's canonical form is,
     * and this is what stops that being taken on trust if the implementation ever changes.
     */
    @Test
    fun `a key is a canonical UUID and safe to put in a header`() {
        val key = generator.newKey()

        assertEquals(key, UUID.fromString(key).toString())
        assertTrue(key.matches(HEADER_SAFE), "not usable as a header value: $key")
    }

    private companion object {
        const val SAMPLE_SIZE = 10_000

        /** RFC 9110's `token` characters, narrowed to what a UUID actually uses. */
        val HEADER_SAFE = Regex("[0-9a-f-]+")
    }
}
