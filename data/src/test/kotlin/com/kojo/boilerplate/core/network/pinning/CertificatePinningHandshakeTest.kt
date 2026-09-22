package com.kojo.boilerplate.core.network.pinning

import java.net.Proxy
import java.time.Instant
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The pinner against a real TLS handshake.
 *
 * [PinningPolicyTest] covers the policy as a table of values, which is where the decisions live.
 * What it cannot establish is that any of it reaches a socket, and that is not a pedantic gap:
 * the failure this whole item exists to close is pinning configuration that is present,
 * well-formed, covered by tests and enforcing nothing. A pinner built from a pin set that
 * matches no host, installed on a client, produces exactly the same green suite as one that
 * works.
 *
 * So this stands up a CA, signs two server certificates under two different keys, puts
 * MockWebServer behind TLS and drives real requests through a client configured the way
 * `NetworkModule` configures its two. Every certificate here is generated in-process, lives for
 * the length of one test and is signed by a root this file invented; none of it is or resembles
 * a credential.
 *
 * It is a plain JVM test — `okhttp-tls` is ordinary `java.security` — so it runs in CI and in the
 * offline harness alike, which is what makes this checkable at all from an environment with no
 * Android SDK.
 */
class CertificatePinningHandshakeTest {

    private lateinit var root: HeldCertificate

    /** The key the server in [setUp] serves. */
    private lateinit var servingCertificate: HeldCertificate

    /** A second key under the same CA: the one a rotation moves to. */
    private lateinit var rotationCertificate: HeldCertificate

    private lateinit var clientCertificates: HandshakeCertificates
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        root = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("boilerplate-test-root")
            .build()
        servingCertificate = leafSignedByRoot()
        rotationCertificate = leafSignedByRoot()

        // The client trusts the test CA, which is deliberately the *easy* case: ordinary system
        // trust is satisfied, so anything rejected below is rejected by the pins alone. Were the
        // CA untrusted too, a pin failure and a chain failure would be indistinguishable and
        // this file would pass without measuring anything.
        clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(root.certificate)
            .build()

        server = serverServing(servingCertificate)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a matching pin lets the request through`() {
        assertEquals(HTTP_OK, call(policy(pin(servingCertificate), pin(rotationCertificate))))
    }

    @Test
    fun `a pin set that does not name the serving key refuses the connection`() {
        // Every other condition is satisfied: the pins are well-formed, the host is covered, the
        // certificate is valid, in date and issued by a CA the client trusts. The chain is
        // rejected anyway. That is the property this item is for.
        val failure = assertThrows(SSLPeerUnverifiedException::class.java) {
            call(policy(pin(rotationCertificate), OTHER_PIN))
        }

        // OkHttp's message lists the peer's pins beside the configured ones, which is the thing
        // that makes a real mismatch diagnosable rather than a mystery. Asserted so a later
        // change that wraps or replaces the exception has to keep it.
        val message = failure.message.orEmpty()
        assertTrue(message, message.contains("Certificate pinning failure"))
        assertTrue(message, message.contains(pin(servingCertificate)))
    }

    @Test
    fun `a backup pin is what makes a key rotation possible without an app release`() {
        // The rotation plan, executed. The app pins two keys and the server has moved to the
        // second. Nothing about the installed app changed and the connection succeeds, so the
        // switch is a server deployment rather than a release plus however long it takes every
        // user to install it. Remove the backup pin and this is the test that goes red.
        val rotated = serverServing(rotationCertificate)

        try {
            val response = call(
                policy = policy(pin(servingCertificate), pin(rotationCertificate)),
                url = urlOf(rotated),
            )
            assertEquals(HTTP_OK, response)
        } finally {
            rotated.shutdown()
        }
    }

    @Test
    fun `an expired pin set connects rather than bricking the installation`() {
        // Fail-open on expiry, end to end: the pins name neither serving key, the policy is past
        // its date, and the request succeeds on system trust. See `PinningPolicy` for why this
        // is the one pinning failure that is not a refusal to connect.
        val expired = PinningPolicy(
            hosts = listOf(HostPins(HOST, listOf(pin(rotationCertificate), OTHER_PIN))),
            expiresAt = NOW,
        )

        assertEquals(HTTP_OK, call(expired, now = NOW.plusSeconds(1)))
    }

    @Test
    fun `an unconfigured pin set connects`() {
        assertEquals(HTTP_OK, call(PinningPolicy.UNPINNED))
    }

    /** One request to [url] through a client carrying [policy]'s decision as of [now]. */
    private fun call(
        policy: PinningPolicy,
        now: Instant = NOW,
        url: HttpUrl = urlOf(server),
    ): Int {
        val client = OkHttpClient.Builder()
            // `Proxy.NO_PROXY` rather than the default selector, which reads the JVM's proxy
            // system properties. Whether the machine running this has any is not something this
            // file should have an opinion about.
            .proxy(Proxy.NO_PROXY)
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager,
            )
            .certificatePinner(policy.decideFor(url.host, now).certificatePinner)
            .build()

        return client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
    }

    private fun policy(vararg pins: String) =
        PinningPolicy(listOf(HostPins(HOST, pins.toList())), NOW.plusSeconds(1))

    private fun serverServing(certificate: HeldCertificate): MockWebServer =
        MockWebServer().apply {
            useHttps(
                HandshakeCertificates.Builder()
                    .heldCertificate(certificate, root.certificate)
                    .build()
                    .sslSocketFactory(),
                false,
            )
            enqueue(MockResponse().setBody("{}"))
            start()
        }

    private fun leafSignedByRoot(): HeldCertificate = HeldCertificate.Builder()
        .commonName(HOST)
        .addSubjectAlternativeName(HOST)
        .addSubjectAlternativeName(LOOPBACK)
        .signedBy(root)
        .build()

    /**
     * [server]'s URL with its host forced to [HOST].
     *
     * MockWebServer names itself by reverse-resolving the loopback address, which answers
     * differently on different machines — and the name has to be one the certificate's subject
     * alternative names cover, or hostname verification fails and this file reports a pinning
     * result it never measured.
     */
    private fun urlOf(server: MockWebServer): HttpUrl =
        server.url("/").newBuilder().host(HOST).build()

    private companion object {
        const val HOST = "localhost"
        const val LOOPBACK = "127.0.0.1"
        const val HTTP_OK = 200

        /** A pin of the right shape and of nobody's key. */
        const val OTHER_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")

        fun pin(certificate: HeldCertificate): String =
            CertificatePinner.pin(certificate.certificate)
    }
}
