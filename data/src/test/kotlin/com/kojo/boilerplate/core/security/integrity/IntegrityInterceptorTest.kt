package com.kojo.boilerplate.core.security.integrity

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The interceptor against a real client and a real socket.
 *
 * Every assertion here is about a *recorded* request rather than about a builder, because the
 * failures worth catching are all "what actually went out": a marker header that reached the
 * server, a token attached to a hash computed from a different request, an unavailable
 * attestation that turned into no request at all.
 */
class IntegrityInterceptorTest {

    private val server = MockWebServer()
    private val attestation = RecordingAttestation()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().addInterceptor(IntegrityInterceptor(attestation)).build()
    }

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a marked request carries a token and not the marker`() {
        attestation.result = AttestationResult.Issued(TOKEN)
        val recorded = send(marked = true)

        assertEquals(TOKEN, recorded.getHeader(IntegrityInterceptor.INTEGRITY_TOKEN))
        assertNull(recorded.getHeader(IntegrityInterceptor.REQUIRE_ATTESTATION))
    }

    @Test
    fun `an unmarked request is not attested at all`() {
        // Attestation costs a round trip to Play and comes out of a per-device budget, so the
        // default has to be "no", not "yes unless something says otherwise".
        val recorded = send(marked = false)

        assertEquals(0, attestation.calls.size)
        assertNull(recorded.getHeader(IntegrityInterceptor.INTEGRITY_TOKEN))
        assertNull(recorded.getHeader(IntegrityInterceptor.REQUIRE_ATTESTATION))
    }

    @Test
    fun `a device that cannot attest still sends the request`() {
        // The client fails open and the server decides. Failing closed here would lock out the
        // honest population — a device with Play services disabled, anyone briefly offline —
        // while an attacker running a patched build simply removes the refusal.
        attestation.result = AttestationResult.Unavailable(AttestationFailure.PLAY_UNAVAILABLE)
        val recorded = send(marked = true)

        assertEquals("/v1/auth/login", recorded.path)
        assertNull(recorded.getHeader(IntegrityInterceptor.INTEGRITY_TOKEN))
    }

    @Test
    fun `the marker never reaches the server, attested or not`() {
        // A header naming this app's security posture is free reconnaissance, and it says
        // nothing the endpoint being called does not already say.
        attestation.result = AttestationResult.Unavailable(AttestationFailure.NOT_CONFIGURED)

        assertNull(send(marked = true).getHeader(IntegrityInterceptor.REQUIRE_ATTESTATION))
    }

    @Test
    fun `the token is bound to the request that was actually sent`() {
        // The whole point of the request hash. If these two disagree the backend rejects every
        // attested request, and — worse — a hash computed over something other than what was
        // sent would let a token be reused for a different call.
        attestation.result = AttestationResult.Issued(TOKEN)
        val recorded = send(marked = true)

        assertEquals(
            IntegrityRequestHash.over("POST", "/v1/auth/login", recorded.body.readByteArray()),
            attestation.calls.single(),
        )
    }

    @Test
    fun `two requests to the same endpoint are attested separately`() {
        // A token is bound to a request, not to an endpoint. Two sign-in attempts with
        // different credentials must not be able to share one.
        attestation.result = AttestationResult.Issued(TOKEN)
        send(marked = true)
        server.enqueue(MockResponse().setResponseCode(HTTP_OK))
        client.newCall(
            markedRequest().newBuilder().post(OTHER_BODY.toRequestBody(JSON)).build(),
        ).execute().close()
        server.takeRequest()

        assertEquals(2, attestation.calls.size)
        assertTrue(
            "each request hashes to its own value: ${attestation.calls}",
            attestation.calls[0] != attestation.calls[1],
        )
    }

    @Test
    fun `a body that cannot be read leaves the request unattested rather than empty`() {
        // Reading a one-shot body to hash it would leave nothing for the connection to send.
        // This repository has no streaming uploads; the branch exists so that adding one
        // degrades to an unattested request rather than to an empty POST.
        attestation.result = AttestationResult.Issued(TOKEN)
        server.enqueue(MockResponse().setResponseCode(HTTP_OK))
        val streaming = markedRequest().newBuilder()
            .post(object : RequestBody() {
                override fun contentType() = JSON
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8(BODY)
                }
            })
            .build()

        client.newCall(streaming).execute().close()
        val recorded = server.takeRequest()

        assertEquals(0, attestation.calls.size)
        assertNull(recorded.getHeader(IntegrityInterceptor.INTEGRITY_TOKEN))
        assertNull(recorded.getHeader(IntegrityInterceptor.REQUIRE_ATTESTATION))
        assertEquals(BODY, recorded.body.readUtf8())
    }

    private fun send(marked: Boolean): RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(HTTP_OK))
        val request = if (marked) markedRequest() else unmarkedRequest()
        client.newCall(request).execute().close()
        return server.takeRequest()
    }

    private fun markedRequest(): Request = unmarkedRequest().newBuilder()
        .header(IntegrityInterceptor.REQUIRE_ATTESTATION, MARKER_VALUE)
        .build()

    private fun unmarkedRequest(): Request = Request.Builder()
        .url(server.url("/v1/auth/login"))
        .post(BODY.toRequestBody(JSON))
        .build()

    private class RecordingAttestation : IntegrityAttestation {

        var result: AttestationResult =
            AttestationResult.Unavailable(AttestationFailure.NOT_CONFIGURED)

        val calls = mutableListOf<String>()

        override suspend fun attest(requestHash: String): AttestationResult {
            calls += requestHash
            return result
        }
    }

    private companion object {
        private const val TOKEN = "mock-integrity-token"
        private const val BODY = """{"email":"user@example.com","password":"not-a-real-password"}"""
        private const val OTHER_BODY = """{"email":"other@example.com","password":"also-not-real"}"""
        private const val HTTP_OK = 200
        private val JSON = "application/json".toMediaType()

        /** The value half of `IntegrityInterceptor.REQUIRE_ATTESTATION_HEADER`. */
        private val MARKER_VALUE =
            IntegrityInterceptor.REQUIRE_ATTESTATION_HEADER.substringAfter(": ")
    }
}
