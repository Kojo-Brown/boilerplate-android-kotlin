package com.kojo.boilerplate.core.network.pinning

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy, as a value.
 *
 * Every question worth asking about a pin set is a question about a table — is this host
 * covered, is this date past the expiry, does this many pins count — and every one of them is
 * answered here rather than against a socket. [CertificatePinningHandshakeTest] is the other
 * half: it proves that a pinner built from one of these actually rejects a chain, which is the
 * part no table can establish.
 *
 * The pins are `sha256/` plus 43 Base64 characters of nothing in particular. They are the right
 * *shape* — OkHttp rejects a pin that is not, and that rejection is asserted below — and they
 * are obviously not the hash of anybody's key.
 */
class PinningPolicyTest {

    @Test
    fun `an empty pin set is not configured`() {
        assertEquals(PinningDecision.NotConfigured, PinningPolicy.UNPINNED.decideAt(NOW))
    }

    @Test
    fun `a configured pin set is enforced before its expiry`() {
        val decision = policy(expiresAt = NOW.plusSeconds(1)).decideAt(NOW)

        assertTrue(decision is PinningDecision.Enforced)
        assertEquals(listOf("api.example.com"), (decision as PinningDecision.Enforced).hostPatterns)
        assertEquals(2, decision.certificatePinner.pins.size)
    }

    @Test
    fun `a pin set is not enforced at the instant it expires`() {
        // The boundary is `now >= expiresAt`, and the direction matters in only one way: an
        // off-by-one that enforced *past* the expiry is a brick on the devices the expiry
        // exists for, while one that stops a second early is a second of system trust.
        val expiresAt = NOW.plusSeconds(1)

        assertTrue(policy(expiresAt = expiresAt).decideAt(expiresAt) is PinningDecision.Expired)
        assertTrue(
            policy(expiresAt = expiresAt).decideAt(expiresAt.minusMillis(1))
                is PinningDecision.Enforced,
        )
    }

    @Test
    fun `an expired pin set pins nothing rather than refusing to connect`() {
        val decision = policy(expiresAt = NOW).decideAt(NOW.plusSeconds(1))

        // The failure this asserts against is the tempting one: treating an expired pin set as a
        // reason to refuse the connection. That bricks exactly the installations too old to have
        // a current pin set, with no recovery path — an in-app update prompt needs the network
        // too. See `PinningPolicy` for the whole argument.
        assertTrue(decision is PinningDecision.Expired)
        assertTrue(decision.certificatePinner.pins.isEmpty())
    }

    @Test
    fun `a host the pin set does not cover is a fault in the build, not a connection`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            policy().decideFor(host = "api.other.example.com", now = NOW)
        }

        // The message has to name both sides. Everything about this failure looks like working
        // software — the build passed, the pinner exists, the requests succeed — so the only
        // thing that makes it diagnosable is being told which pattern was expected to match
        // which host.
        assertTrue(failure.message!!.contains("api.example.com"))
        assertTrue(failure.message!!.contains("api.other.example.com"))
    }

    @Test
    fun `an unpinned build talks to any host`() {
        // The empty pin set is what this boilerplate ships, so `decideFor` must not treat "no
        // pins cover this host" as a fault when there are no pins at all.
        assertEquals(
            PinningDecision.NotConfigured,
            PinningPolicy.UNPINNED.decideFor(host = "api.example.com", now = NOW),
        )
    }

    @Test
    fun `wildcard patterns mean what OkHttp says they mean`() {
        // Not a test of this class so much as a pin on the semantics it delegates: a single `*`
        // matches exactly one label and does NOT match the bare domain, which is the single most
        // common way a pin set ends up covering nothing it was meant to cover. Answered by
        // `CertificatePinner.findMatchingPins`, so this cannot drift from the library.
        val single = policy(hostPattern = "*.example.com")

        assertTrue(single.covers("api.example.com"))
        assertFalse(single.covers("example.com"))
        assertFalse(single.covers("eu.api.example.com"))

        val any = policy(hostPattern = "**.example.com")

        assertTrue(any.covers("api.example.com"))
        assertTrue(any.covers("eu.api.example.com"))
    }

    @Test
    fun `a host with one pin is rejected`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HostPins("api.example.com", listOf(PIN_A))
        }

        assertTrue(failure.message!!.contains("at least 2"))
    }

    @Test
    fun `a host pinned twice to the same key is rejected`() {
        // Two copies of one key read as a satisfied backup-pin rule and are not one. This is the
        // shape a pasted pin takes.
        assertThrows(IllegalArgumentException::class.java) {
            HostPins("api.example.com", listOf(PIN_A, PIN_A))
        }
    }

    @Test
    fun `the same host pattern cannot be pinned twice`() {
        // OkHttp unions the pin sets of every matching entry, so a second entry widens the first
        // rather than replacing it — which makes "I replaced the pins" and "I added to them"
        // indistinguishable at the call site.
        assertThrows(IllegalArgumentException::class.java) {
            PinningPolicy(
                hosts = listOf(
                    HostPins("api.example.com", listOf(PIN_A, PIN_B)),
                    HostPins("api.example.com", listOf(PIN_C, PIN_B)),
                ),
                expiresAt = NOW,
            )
        }
    }

    @Test
    fun `a pin set without an expiry is rejected, and an expiry without a pin set is too`() {
        assertThrows(IllegalArgumentException::class.java) {
            PinningPolicy(hosts = listOf(HostPins("api.example.com", PINS)), expiresAt = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PinningPolicy(hosts = emptyList(), expiresAt = NOW)
        }
    }

    @Test
    fun `a malformed pin is rejected at construction rather than at the first handshake`() {
        // OkHttp's own validation, pulled forward by building the pinner eagerly. Without that,
        // a pin missing its prefix is an exception on the first request of the session — in a
        // release build, on a device, inside whatever was calling the API.
        assertThrows(IllegalArgumentException::class.java) {
            PinningPolicy(
                hosts = listOf(HostPins("api.example.com", listOf(PIN_A, "not-a-pin"))),
                expiresAt = NOW,
            )
        }
    }

    @Test
    fun `an empty configuration parses to the unpinned policy`() {
        val parsed = PinningPolicy.parse(serialisedHosts = "", serialisedExpiry = "")

        assertTrue(parsed.hosts.isEmpty())
        assertNull(parsed.expiresAt)
    }

    @Test
    fun `a configuration parses back into the policy the build wrote`() {
        val parsed = PinningPolicy.parse(
            serialisedHosts = "api.example.com=$PIN_A|$PIN_B;**.other.example.com=$PIN_C|$PIN_A",
            serialisedExpiry = "2027-03-01T00:00:00Z",
        )

        assertEquals(
            listOf(
                HostPins("api.example.com", listOf(PIN_A, PIN_B)),
                HostPins("**.other.example.com", listOf(PIN_C, PIN_A)),
            ),
            parsed.hosts,
        )
        assertEquals(Instant.parse("2027-03-01T00:00:00Z"), parsed.expiresAt)
    }

    @Test
    fun `half a configuration is a failure rather than an unpinned build`() {
        // The outcome being guarded against is not an exception — it is `UNPINNED`, reached
        // silently, from a properties file that plainly declares pins.
        assertThrows(IllegalArgumentException::class.java) {
            PinningPolicy.parse(serialisedHosts = "api.example.com=$PIN_A|$PIN_B", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PinningPolicy.parse(serialisedHosts = "", serialisedExpiry = "2027-03-01T00:00:00Z")
        }
    }

    @Test
    fun `a malformed configuration names what it could not read`() {
        val noSeparator = assertThrows(IllegalArgumentException::class.java) {
            PinningPolicy.parse("api.example.com$PIN_A", "2027-03-01T00:00:00Z")
        }
        assertNotNull(noSeparator.message)

        val badExpiry = assertThrows(IllegalArgumentException::class.java) {
            PinningPolicy.parse("api.example.com=$PIN_A|$PIN_B", "March 2027")
        }
        assertTrue(badExpiry.message!!.contains("March 2027"))
    }

    private fun policy(
        hostPattern: String = "api.example.com",
        expiresAt: Instant = NOW.plusSeconds(1),
    ) = PinningPolicy(listOf(HostPins(hostPattern, PINS)), expiresAt)

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")

        const val PIN_A = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val PIN_B = "sha256/ERERERERERERERERERERERERERERERERERERERERERE="
        const val PIN_C = "sha256/IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI="

        val PINS = listOf(PIN_A, PIN_B)
    }
}
