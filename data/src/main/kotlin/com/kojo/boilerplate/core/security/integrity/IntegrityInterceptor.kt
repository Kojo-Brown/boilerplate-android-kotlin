package com.kojo.boilerplate.core.security.integrity

import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches a Play Integrity token to the requests that ask for one.
 *
 * ### Opt-in, per endpoint
 *
 * Attesting every request would be wrong twice over. Play rate limits token requests per app and
 * per device — the standard API's own guidance is one per meaningful user action, not one per
 * HTTP call — and a token costs a round trip to Play on the request path, which is not something
 * to spend on a list refresh. So an endpoint asks, by carrying [REQUIRE_ATTESTATION] as a
 * Retrofit `@Headers` line, and this interceptor swaps that marker for the real token.
 *
 * A marker header rather than a Retrofit annotation and an `Invocation` tag, because the marker
 * survives things the annotation does not: a request built by hand, a retry reconstructed from a
 * `Request.Builder`, a call issued by something that is not Retrofit at all. The tag approach
 * reads the `Invocation` OkHttp attaches, which exists only on the original Retrofit-issued
 * request.
 *
 * The marker never reaches the network — it is removed whether or not a token was obtained,
 * because a header naming this app's security posture is free reconnaissance and tells the
 * server nothing it cannot see from the endpoint that was called.
 *
 * ### Why the client fails open
 *
 * When no token is available the request goes out without one, and the server decides. Failing
 * closed here — refusing to send — is a client-side security decision, which is the category of
 * decision Play Integrity exists precisely because the client cannot be trusted to make: an
 * attacker running a patched build simply removes the refusal. What it *would* reliably do is
 * lock out the honest population it cannot help — a device with Play services disabled by an
 * MDM profile, a user who force stopped the Play Store, anyone briefly offline — and it would
 * do it in the one place with no recovery path, since the request that would fetch the fix is
 * the request being refused.
 *
 * The server sees the absence and weighs it: it knows whether this account has ever presented a
 * valid token, what the endpoint is worth, and what a plausible rate of unattested traffic looks
 * like. None of that is knowable here.
 *
 * ### Why [runBlocking]
 *
 * An OkHttp [Interceptor] is a blocking interface: it hands back a [Response] and there is no
 * suspending equivalent. The thread this blocks is OkHttp's own dispatcher thread, which is
 * already committed to this one call and would otherwise be blocked in `chain.proceed` — this
 * moves where it waits rather than adding a wait. The alternative, a token fetched eagerly on
 * some other schedule and cached, trades that for a token bound to a request that had not been
 * built yet, which is the binding in [IntegrityRequestHash] given up.
 */
class IntegrityInterceptor @Inject constructor(
    private val attestation: IntegrityAttestation,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(REQUIRE_ATTESTATION) == null) return chain.proceed(request)

        val stripped = request.newBuilder().removeHeader(REQUIRE_ATTESTATION)
        val requestHash = IntegrityRequestHash.of(request)
        val result = requestHash?.let { hash -> runBlocking { attestation.attest(hash) } }

        if (result is AttestationResult.Issued) {
            stripped.header(INTEGRITY_TOKEN, result.token)
        }
        return chain.proceed(stripped.build())
    }

    companion object {

        /**
         * The marker header's name. Read here, never sent: [intercept] removes it whether or
         * not a token replaced it.
         */
        const val REQUIRE_ATTESTATION = "X-Require-Integrity"

        /**
         * The marker an endpoint carries to ask for attestation, as a whole Retrofit
         * `@Headers` line.
         *
         * One constant rather than a name and a value, because `@Headers` takes the whole line
         * as a compile-time constant annotation argument — a call site composing the two would
         * be doing string arithmetic inside an annotation.
         *
         * The value is a version rather than `true`, because what an endpoint opts into is the
         * request-hash contract in [IntegrityRequestHash]. Changing that contract means a
         * backend running the old one must reject rather than silently mis-verify, and a second
         * value here is how that is staged.
         */
        const val REQUIRE_ATTESTATION_HEADER = "$REQUIRE_ATTESTATION: v1"

        /** The header the token is sent in, when there is one. */
        const val INTEGRITY_TOKEN = "X-Integrity-Token"
    }
}
