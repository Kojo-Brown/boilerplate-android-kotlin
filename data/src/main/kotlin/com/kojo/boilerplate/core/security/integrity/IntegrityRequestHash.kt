package com.kojo.boilerplate.core.security.integrity

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import okhttp3.Request
import okio.Buffer

/**
 * What binds an integrity token to the request it was minted for.
 *
 * ### The attack this exists to close
 *
 * Without it, a token is a bearer statement that *some* request from this app on this device was
 * attested. An attacker who obtains one — from their own rooted device running an instrumented
 * copy of the app, or by replaying one the app already sent — can attach it to any other
 * request. The backend decodes it, sees `MEETS_DEVICE_INTEGRITY`, and has learnt nothing about
 * the request in front of it.
 *
 * Play copies [MAX_LENGTH] bytes of caller-supplied text verbatim into the verdict. Put a digest
 * of the request in it, and the backend can recompute that digest from the request it actually
 * received and compare. A token now attests one request rather than one device-moment, and a
 * replay attaches a hash that does not match what arrived.
 *
 * It is not a replay *nonce*: two identical requests hash identically, so the backend still has
 * to bound how long it will accept a given token — by the `timestampMillis` in the verdict, and
 * by remembering the tokens it has already spent. `docs/root-detection.md` says so on the server
 * side, where that state lives.
 *
 * ### The canonical form is a wire contract
 *
 * The backend recomputes this from the HTTP request it received, so the two sides must agree
 * byte for byte. That makes the shape below a contract rather than an implementation detail, and
 * the reason it is written out in full here and in `docs/root-detection.md`:
 *
 * ```
 * SHA-256( "<METHOD>\n<encoded path>[?<encoded query>]\n<lowercase hex SHA-256 of the body>" )
 * ```
 *
 * base64url-encoded without padding — 43 characters, comfortably inside the limit however long
 * the request was.
 *
 * Three decisions in that line are worth their reasoning:
 *
 * * **The body is hashed separately and included as hex**, rather than concatenated raw. A
 *   server can then digest the body as it streams it in, without buffering a large upload to
 *   build one string, and the separator can never be confused with body content.
 * * **The scheme, host and port are left out.** They are not in dispute — the request reached
 *   this server — and including them means a hash that changes when a deployment moves behind a
 *   different hostname while the request itself is identical.
 * * **The path and query are taken already-encoded**, exactly as they go out on the wire, so
 *   that neither side has to agree on a normalisation of percent-escapes.
 *
 * Nothing about the request's *contents* reaches Google: the verdict carries the digest, not the
 * path or the body.
 */
internal object IntegrityRequestHash {

    /**
     * Play's limit on the request hash, in bytes. Exceeding it fails the token request with
     * `REQUEST_HASH_TOO_LONG` rather than truncating, which is why this is a digest rather than
     * the request text.
     */
    const val MAX_LENGTH = 500

    /**
     * The hash for [request], or `null` when its body cannot be read without destroying it.
     *
     * A one-shot or duplex body can be written exactly once, to the connection — reading it here
     * would leave nothing to send. Those bodies are produced by streaming uploads, which this
     * repository has none of; the branch exists so that adding one degrades to an unattested
     * request rather than to an empty PUT. The same goes for an [IOException] out of [writeTo]:
     * whatever went wrong, it is not worth turning into a failed request here when the call has
     * not been attempted yet.
     */
    fun of(request: Request): String? {
        val body = request.body
        val bodyBytes = when {
            body == null -> EMPTY_BODY
            body.isOneShot() || body.isDuplex() -> return null
            else -> try {
                Buffer().also { body.writeTo(it) }.readByteArray()
            } catch (expected: IOException) {
                return null
            }
        }
        return over(
            method = request.method,
            target = request.url.encodedPath + request.url.encodedQuery.orEmpty().prefixedQuery(),
            body = bodyBytes,
        )
    }

    /**
     * The hash over an already-decomposed request. Public to the module so that a test can pin
     * the canonical form without constructing an [Request], and so that a server-side
     * implementation in another language has one unambiguous thing to match.
     */
    fun over(method: String, target: String, body: ByteArray): String {
        val canonical = "$method\n$target\n${sha256(body).toHex()}"
        return BASE64.encodeToString(sha256(canonical.toByteArray(Charsets.UTF_8)))
    }

    private fun String.prefixedQuery(): String = if (isEmpty()) "" else "?$this"

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val EMPTY_BODY = ByteArray(0)

    private val BASE64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
}
