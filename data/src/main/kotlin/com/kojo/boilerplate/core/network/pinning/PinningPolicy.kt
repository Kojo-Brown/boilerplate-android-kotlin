package com.kojo.boilerplate.core.network.pinning

import java.time.Instant
import java.time.format.DateTimeParseException
import okhttp3.CertificatePinner

/**
 * One host pattern and every SPKI pin accepted for it.
 *
 * [hostPattern] is OkHttp's, not a regex: a bare name matches exactly, `*.example.com` matches
 * one label below `example.com` and **not** `example.com` itself, and `**.example.com` matches
 * any depth. Which of the three was meant is the kind of thing that reads as obviously right and
 * pins nothing — see [PinningPolicy.decideFor].
 *
 * [pins] are `sha256/` + Base64 of the SHA-256 of the certificate's *subject public key info*,
 * not of the certificate. That distinction is the whole rotation story: a renewal that reuses the
 * key keeps the pin valid, so the ordinary yearly certificate replacement is not an app release.
 * `docs/certificate-pinning.md` has the procedure.
 */
data class HostPins(
    val hostPattern: String,
    val pins: List<String>,
) {
    init {
        require(hostPattern.isNotBlank()) { "a pinned host pattern cannot be blank" }
        require(pins.size >= PinningPolicy.MINIMUM_PINS_PER_HOST) {
            "`$hostPattern` has ${pins.size} pin(s) and needs at least " +
                "${PinningPolicy.MINIMUM_PINS_PER_HOST}. A host pinned to one key is pinned to " +
                "one key's lifetime: the day it has to be replaced — expiry, a leak, a CA " +
                "withdrawing support for an algorithm — every installed copy of this app stops " +
                "reaching the server, and no server-side change can fix it because the app is " +
                "what holds the pin. The second pin is a key that is generated, kept offline " +
                "and not yet serving traffic, so that the switch is a server deployment rather " +
                "than an app release plus the weeks it takes everyone to install it."
        }
        require(pins.distinct().size == pins.size) {
            "`$hostPattern` lists the same pin twice. Two copies of one key are one pin's worth " +
                "of protection wearing a second pin's clothing, and the duplicate is usually a " +
                "backup pin that was pasted rather than generated."
        }
    }
}

/**
 * Which hosts this build pins, to what, and until when.
 *
 * ### Why a policy object rather than a `CertificatePinner`
 *
 * [CertificatePinner] answers exactly one question — does this chain match? — and every way
 * pinning goes wrong in practice is a question it does not answer:
 *
 * * **A host with no pins is not an error.** `CertificatePinner.check` looks the hostname up,
 *   finds nothing, and returns. So a typo in a hostname, a `*.` where `**.` was meant, or a base
 *   URL that moved to a new subdomain turns pinning off in full while the client still carries a
 *   pinner, the build still passes and every request still succeeds. [decideFor] is where that
 *   is caught, because it is the one place that knows both the pin set and the host this app
 *   actually talks to.
 * * **A pin set is a time bomb with no timer.** Pins are held by the *app*, so a pin set that
 *   outlives the keys it names bricks every installation that has not updated. [expiresAt] is
 *   the timer, and [decideAt] is what reads it.
 *
 * ### Fail closed on misconfiguration, fail open on expiry
 *
 * Those are different failures and they get opposite treatment, deliberately.
 *
 * A pin set that does not cover the host this build talks to is a mistake in the build, and the
 * person who can fix it is the one running it — so [decideFor] throws, on the first injection of
 * an `OkHttpClient`, which is app start. A shipped build cannot reach that state without having
 * failed on every developer machine and in CI first.
 *
 * An *expired* pin set is the opposite: the build was correct when it shipped and the world moved
 * on underneath it. The only devices that reach the expiry are ones running a version old enough
 * that its pins may name keys nobody holds any more, and refusing to connect there is a brick
 * with no recovery path — not even an in-app update prompt, which would itself need the network.
 * So pinning stops being enforced and the connection falls back to ordinary system trust, which
 * is what every app without pinning has. This is what Android's own `<pin-set expiration="…">`
 * does, for the same reason.
 *
 * The decision is taken once, when the singleton `OkHttpClient` is built, so a process that
 * outlives the expiry keeps enforcing until it is restarted. That is intentional: a process lives
 * hours and the expiry is months out, and re-deciding per request would mean a policy that
 * changes under a connection pool.
 */
class PinningPolicy(
    val hosts: List<HostPins>,
    /**
     * When enforcement stops. `null` only when [hosts] is empty — an empty pin set has nothing to
     * expire, and giving it a date would invite reading "not expired" as "pinned".
     */
    val expiresAt: Instant?,
) {

    init {
        require(hosts.isEmpty() == (expiresAt == null)) {
            if (hosts.isEmpty()) {
                "an empty pin set was given an expiry of $expiresAt; there is nothing to expire"
            } else {
                "a pin set covering ${hosts.size} host(s) has no expiry. See " +
                    "`docs/certificate-pinning.md` for why an unbounded pin set is a brick " +
                    "waiting for a key rotation."
            }
        }
        require(hosts.distinctBy { it.hostPattern }.size == hosts.size) {
            "the same host pattern is pinned twice: " +
                hosts.groupingBy { it.hostPattern }.eachCount()
                    .filterValues { it > 1 }.keys.sorted().joinToString(", ") +
                ". OkHttp unions the pin sets of every entry matching a host, so the second " +
                "entry silently widens the first rather than replacing it."
        }
    }

    /**
     * Every pin in the set, as one [CertificatePinner].
     *
     * Built eagerly, so that a malformed pin — one without the `sha256/` prefix, or with a Base64
     * body that is not a hash of the right length — is rejected by OkHttp here, at construction,
     * rather than on the first TLS handshake. `add` is called once per pin rather than once per
     * host with a spread: it appends, and the vararg overload would otherwise mean copying an
     * array to satisfy a signature.
     */
    private val certificatePinner: CertificatePinner =
        CertificatePinner.Builder()
            .apply { hosts.forEach { host -> host.pins.forEach { add(host.hostPattern, it) } } }
            .build()

    /**
     * Whether [host] — a hostname, not a pattern — is matched by any entry in the set.
     *
     * Answered by OkHttp rather than by a rule written here. The wildcard semantics are OkHttp's
     * and a second implementation of them would be a second set of bugs, agreeing with the real
     * one in every test anybody thought to write and disagreeing in production.
     */
    fun covers(host: String): Boolean = certificatePinner.findMatchingPins(host).isNotEmpty()

    /**
     * The policy for a client that talks to [host], as of [now].
     *
     * @throws IllegalStateException when this build pins *something* but not [host]. See the
     *   class documentation: an uncovered host is pinning that silently protects nothing, and it
     *   is a fault in the build rather than in the device.
     */
    fun decideFor(host: String, now: Instant): PinningDecision {
        check(hosts.isEmpty() || covers(host)) {
            "this build pins ${hosts.joinToString(", ") { it.hostPattern }} and talks to " +
                "`$host`, which none of those patterns match — so every request to it is " +
                "unpinned while the client carries a pinner and every test stays green. Note " +
                "that `*.` matches exactly one label and does not match the bare domain; `**.` " +
                "is the one that matches any depth."
        }
        return decideAt(now)
    }

    /** The policy as of [now], without reference to any particular host. */
    fun decideAt(now: Instant): PinningDecision = when {
        hosts.isEmpty() -> PinningDecision.NotConfigured
        expiresAt != null && !now.isBefore(expiresAt) -> PinningDecision.Expired(expiresAt)
        else -> PinningDecision.Enforced(certificatePinner, hosts.map { it.hostPattern })
    }

    companion object {

        /**
         * A pinned host needs a current key and a backup. See [HostPins] for what the second one
         * buys and `docs/certificate-pinning.md` for where it is kept.
         */
        const val MINIMUM_PINS_PER_HOST = 2

        /** What this build ships when no pin set was configured for it. */
        val UNPINNED = PinningPolicy(hosts = emptyList(), expiresAt = null)

        private const val HOST_SEPARATOR = ';'
        private const val PIN_SEPARATOR = '|'
        private const val HOST_PINS_SEPARATOR = '='

        /**
         * Reads the two `BuildConfig` fields `data/build.gradle.kts` writes from
         * `gradle/certificate-pins.properties`.
         *
         * [serialisedHosts] is `host=pin|pin;host=pin|pin` and [serialisedExpiry] is an ISO-8601
         * instant; both are empty when the properties file declares no pins, which is what an
         * unmodified checkout of this boilerplate does.
         *
         * Strict on purpose. Everything here is generated by the build from a file a human
         * edits, so every malformed case is a typo somebody is about to ship, and the only worse
         * outcome than failing loudly is [UNPINNED] reached by way of a `split` that quietly
         * produced nothing.
         */
        fun parse(serialisedHosts: String, serialisedExpiry: String): PinningPolicy {
            if (serialisedHosts.isEmpty() && serialisedExpiry.isEmpty()) return UNPINNED
            require(serialisedHosts.isNotEmpty()) {
                "an expiry of `$serialisedExpiry` was configured with no pins to expire"
            }
            require(serialisedExpiry.isNotEmpty()) {
                "`$serialisedHosts` was configured with no expiry"
            }

            val hosts = serialisedHosts.split(HOST_SEPARATOR).map { entry ->
                val separator = entry.indexOf(HOST_PINS_SEPARATOR)
                require(separator > 0) {
                    "`$entry` is not `host${HOST_PINS_SEPARATOR}pin${PIN_SEPARATOR}pin`"
                }
                HostPins(
                    hostPattern = entry.substring(0, separator),
                    pins = entry.substring(separator + 1).split(PIN_SEPARATOR),
                )
            }

            val expiresAt = try {
                Instant.parse(serialisedExpiry)
            } catch (expected: DateTimeParseException) {
                throw IllegalArgumentException(
                    "`$serialisedExpiry` is not an ISO-8601 instant such as " +
                        "`2027-03-01T00:00:00Z`",
                    expected,
                )
            }
            return PinningPolicy(hosts = hosts, expiresAt = expiresAt)
        }
    }
}

/**
 * What a client should do about pinning, and why — the "why" being the half that has to reach a
 * log, because all three of these produce a client that works.
 */
sealed interface PinningDecision {

    /** The pinner to install on an `OkHttpClient.Builder`. Empty for everything but [Enforced]. */
    val certificatePinner: CertificatePinner

    /** One line, for the single log statement that records which of these a build got. */
    val summary: String

    /** No pin set was configured for this build. */
    data object NotConfigured : PinningDecision {
        override val certificatePinner: CertificatePinner = CertificatePinner.DEFAULT
        override val summary: String =
            "certificate pinning is not configured for this build; see " +
                "gradle/certificate-pins.properties"
    }

    /**
     * The pin set is past its expiry and is no longer enforced. The connection falls back to
     * system trust. See [PinningPolicy] for why this is not a refusal to connect.
     */
    data class Expired(val expiresAt: Instant) : PinningDecision {
        override val certificatePinner: CertificatePinner = CertificatePinner.DEFAULT
        override val summary: String =
            "certificate pinning expired at $expiresAt and is no longer enforced; this build " +
                "needs a new pin set"
    }

    /** Pinning is on, for [hostPatterns]. */
    data class Enforced(
        override val certificatePinner: CertificatePinner,
        val hostPatterns: List<String>,
    ) : PinningDecision {
        override val summary: String =
            "certificate pinning enforced for ${hostPatterns.joinToString(", ")}"
    }
}
