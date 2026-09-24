package com.kojo.boilerplate.core.security.integrity

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request hash as a wire contract.
 *
 * A backend recomputes this from the HTTP request it received and compares it with the one Play
 * copied into the verdict. That makes every property below something two independent
 * implementations have to agree on, and the two golden vectors the thing a server-side
 * implementation can be checked against without running this app.
 *
 * The negative half matters as much as the positive one. A hash that did not change when the
 * body changed would be a token bound to an endpoint rather than to a request, and every test
 * here would still pass while the replay this whole mechanism exists to stop went through.
 */
class IntegrityRequestHashTest {

    @Test
    fun `golden vector for a request with a body`() {
        // SHA-256 of "POST\n/v1/auth/login\n<hex SHA-256 of the body>", base64url, unpadded.
        // Computed independently of this implementation; a server-side implementation that
        // reproduces these two lines reproduces the contract.
        assertEquals(
            "ZUN6uG-inW47VFUwY_RA3mRQgo2eL1VrLiLTjFGqq88",
            IntegrityRequestHash.over(
                method = "POST",
                target = "/v1/auth/login",
                body = """{"email":"user@example.com"}""".toByteArray(),
            ),
        )
    }

    @Test
    fun `golden vector for a request with a query and no body`() {
        assertEquals(
            "qiINyI642U6Mj1LIFPGqinUzyId9Ti6OMcTYw7EgZz4",
            IntegrityRequestHash.over(
                method = "GET",
                target = "/v1/users?page=2&per_page=20",
                body = ByteArray(0),
            ),
        )
    }

    @Test
    fun `is far shorter than the limit Play imposes`() {
        val hash = IntegrityRequestHash.over("POST", "/v1/auth/login", ByteArray(LARGE_BODY))

        // 43 characters whatever went in, which is the point of hashing rather than sending the
        // request text: REQUEST_HASH_TOO_LONG cannot be reached by a large upload.
        assertEquals(HASH_LENGTH, hash.length)
        assertTrue(hash.length < IntegrityRequestHash.MAX_LENGTH)
    }

    @Test
    fun `changes when the body changes`() {
        assertNotEquals(
            IntegrityRequestHash.over("POST", "/v1/users/7", """{"name":"Ada"}""".toByteArray()),
            IntegrityRequestHash.over("POST", "/v1/users/7", """{"name":"Eve"}""".toByteArray()),
        )
    }

    @Test
    fun `changes when the method changes`() {
        assertNotEquals(
            IntegrityRequestHash.over("POST", "/v1/users/7", ByteArray(0)),
            IntegrityRequestHash.over("DELETE", "/v1/users/7", ByteArray(0)),
        )
    }

    @Test
    fun `changes when the path or the query changes`() {
        val path = IntegrityRequestHash.over("GET", "/v1/users/7", ByteArray(0))
        assertNotEquals(path, IntegrityRequestHash.over("GET", "/v1/users/8", ByteArray(0)))
        assertNotEquals(path, IntegrityRequestHash.over("GET", "/v1/users/7?full=1", ByteArray(0)))
    }

    @Test
    fun `an empty body and an empty-string body are the same request`() {
        // Not a quirk worth preserving so much as one worth pinning: OkHttp gives a POST with no
        // content an empty body rather than a null one, and a server that reads "no body" and a
        // server that reads "zero bytes" must not disagree about the hash.
        assertEquals(
            IntegrityRequestHash.over("POST", "/v1/ping", ByteArray(0)),
            IntegrityRequestHash.over("POST", "/v1/ping", "".toByteArray()),
        )
    }

    @Test
    fun `reads an OkHttp request into the same canonical form`() {
        val body = """{"email":"user@example.com"}"""
        val request = Request.Builder()
            .url("https://api.example.com/v1/auth/login")
            .post(body.toRequestBody(JSON))
            .build()

        assertEquals(
            IntegrityRequestHash.over("POST", "/v1/auth/login", body.toByteArray()),
            IntegrityRequestHash.of(request),
        )
    }

    @Test
    fun `carries the query but not the scheme, host or port`() {
        // The host is not in dispute — the request reached this server — and leaving it out is
        // what keeps the hash stable when a deployment moves behind a different hostname while
        // the request itself is identical.
        val direct = Request.Builder()
            .url("https://api.example.com/v1/users?page=2&per_page=20")
            .build()
        val elsewhere = Request.Builder()
            .url("http://staging.example.com:8080/v1/users?page=2&per_page=20")
            .build()

        assertEquals(IntegrityRequestHash.of(direct), IntegrityRequestHash.of(elsewhere))
        assertEquals("qiINyI642U6Mj1LIFPGqinUzyId9Ti6OMcTYw7EgZz4", IntegrityRequestHash.of(direct))
    }

    @Test
    fun `a body that can only be written once is not read`() {
        // Reading it here would leave nothing for the connection to send. This repository has no
        // streaming uploads; the branch exists so that adding one degrades to an unattested
        // request rather than to an empty PUT.
        val request = Request.Builder()
            .url("https://api.example.com/v1/upload")
            .post(OneShotBody)
            .build()

        assertNull(IntegrityRequestHash.of(request))
    }

    private object OneShotBody : RequestBody() {
        override fun contentType() = JSON
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8("streamed")
        }
    }

    private companion object {
        private val JSON = "application/json".toMediaType()
        private const val HASH_LENGTH = 43
        private const val LARGE_BODY = 1_000_000
    }
}
