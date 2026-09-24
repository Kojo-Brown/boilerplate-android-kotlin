package com.kojo.boilerplate.core.security.integrity

/**
 * A Play Integrity token for one outgoing request, or the reason this device could not produce
 * one.
 *
 * ### This interface deliberately cannot answer "is the device rooted?"
 *
 * That is the whole design, so it is worth stating before the method signature rather than after
 * it. A Play Integrity token is *opaque to the app*: it is encrypted to a key only Google and
 * this app's Play Console project hold, and the verdict inside it — whether the device passes
 * `MEETS_DEVICE_INTEGRITY`, whether the binary is the one Play distributed, whether the install
 * is licensed — is readable only by the backend, which decrypts it through Google's
 * `decodeIntegrityToken` endpoint.
 *
 * That is not an inconvenience of the API. It is the reason the API is worth using at all. Any
 * check the *app* performs, and any decision the app takes on the result of one, runs on the
 * device the attacker owns: an `isRooted()` that reads `/system/bin/su` is an `if` an attacker
 * patches out, and a `su` binary that answers honestly is one they rename. Root detection that
 * an app can perform is root detection an app can be made not to perform. What an attacker
 * cannot patch is a verdict signed on Google's servers about a device they do not control, sent
 * to a backend they do not control, and acted on there.
 *
 * So the app's entire job is the plumbing: obtain a token, bind it to the request it accompanies,
 * attach it, and let the server decide. `docs/root-detection.md` has the threat model and the
 * server-side half.
 *
 * ### Why this is a port rather than a Play class
 *
 * [PlayIntegrityAttestation] is the only file in this repository that names a
 * `com.google.android.play` type. Everything above it — the interceptor, its tests, the Hilt
 * graph — sees this interface, which is ordinary Kotlin and so can be faked in a unit test on a
 * plain JVM. Play Integrity has no local test mode: `prepareIntegrityToken` requires a Play
 * Store, a signed-in account, and an app whose package name Play recognises, none of which a
 * test JVM or a CI runner has.
 */
interface IntegrityAttestation {

    /**
     * An integrity token bound to [requestHash], or why there is not one.
     *
     * @param requestHash what this token is being minted *for* — see [IntegrityRequestHash].
     *   Play copies it verbatim into the verdict, which is what lets the backend check that the
     *   token it was handed belongs to the request it arrived with rather than to some earlier
     *   one. Never blank: a token bound to nothing is a token that can be replayed against
     *   anything.
     */
    suspend fun attest(requestHash: String): AttestationResult
}

/** The outcome of one [IntegrityAttestation.attest] call. */
sealed interface AttestationResult {

    /**
     * A token, to be forwarded to the backend and decoded there.
     *
     * [token] is a JWE — encrypted, not merely signed — and the app holds none of the keys. It
     * is short-lived and single-use in practice, and it is not a credential in the sense the
     * access token is: possession of it grants nothing, because the only party that can read it
     * is the backend it is being sent to. It is still kept out of logs, because the request it
     * is bound to is not, and a token plus its request is a replayable pair for as long as the
     * token is fresh.
     */
    data class Issued(val token: String) : AttestationResult

    /**
     * No token, for [reason]. Every caller in this repository treats this as "send the request
     * without one" — see [IntegrityInterceptor] for why the client is the wrong place to fail
     * closed.
     */
    data class Unavailable(val reason: AttestationFailure) : AttestationResult
}

/**
 * Why a device produced no token, reduced to the cases that lead to different behaviour.
 *
 * Play reports around seventeen error codes and the distinctions between most of them do not
 * reach the app: `PLAY_STORE_NOT_FOUND` and `PLAY_SERVICES_VERSION_OUTDATED` are both "this
 * device will not be producing tokens today", and an app that branched on the difference would
 * be writing two copies of the same `return`. [PlayIntegrityErrorCode] is where the full set is
 * mapped onto this one, and it is the only place the codes are named.
 *
 * @property transient whether the same call, made again later, could succeed. This is what
 *   separates "retry with backoff" from "stop asking": retrying a [PLAY_UNAVAILABLE] on a device
 *   with no Play Store is a request that will fail identically every time, for the life of the
 *   install.
 */
enum class AttestationFailure(val transient: Boolean, val summary: String) {

    /**
     * This build carries no cloud project number, so attestation is off. The default state of an
     * unmodified checkout of this boilerplate — see `gradle/play-integrity.properties`.
     */
    NOT_CONFIGURED(
        transient = false,
        summary = "no cloud project number is configured for this build, so no integrity token " +
            "is requested; see gradle/play-integrity.properties",
    ),

    /**
     * Play cannot serve this device or this install: no Play Store, no Play services, a version
     * of either too old to carry the API, or an install Play does not recognise as this app.
     *
     * Not transient, and worth dwelling on, because it is the verdict-shaped one. A sideloaded
     * build, a de-Googled ROM and an emulator without Play services all land here — which
     * overlaps heavily with the population the item is about. It is still not evidence: a
     * corporate device with Play services disabled lands here too, and so does anyone who force
     * stopped the Play Store. The app therefore does not act on it; the *server* sees a request
     * with no token, which is the same thing said in the one place that can weigh it.
     */
    PLAY_UNAVAILABLE(
        transient = false,
        summary = "Play integrity is not available on this device or for this install",
    ),

    /**
     * A network failure, a Google backend that was unavailable, or a transient device-side
     * error. Retrying the same call later can succeed.
     */
    TRANSIENT(
        transient = true,
        summary = "a transient error prevented an integrity token being issued",
    ),

    /**
     * This app asked for too many tokens too quickly. Transient, but the retry has to be slower
     * rather than sooner — see `docs/root-detection.md` on which requests are worth attesting.
     */
    RATE_LIMITED(
        transient = true,
        summary = "integrity token requests from this app are being rate limited",
    ),

    /**
     * The warmed-up token provider went stale — it expired, or the user cleared Play Store data.
     * Transient because the recovery is to warm up again, which
     * [PlayIntegrityAttestation.attest] does once before giving up.
     */
    PROVIDER_EXPIRED(
        transient = true,
        summary = "the prepared integrity token provider is no longer valid and was discarded",
    ),

    /**
     * The request Play was given is wrong in a way no device can recover from: a cloud project
     * number that is not this app's, or a request hash over the 500-byte limit. A mistake in
     * this repository rather than a condition of the device, and it fails identically on every
     * install — which is why it is called out separately from [PLAY_UNAVAILABLE] despite leading
     * to the same immediate behaviour.
     */
    MISCONFIGURED(
        transient = false,
        summary = "this build's integrity request is malformed and will fail on every device",
    ),
}
