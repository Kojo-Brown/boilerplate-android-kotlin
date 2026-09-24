package com.kojo.boilerplate.core.security.integrity

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.kojo.boilerplate.core.coroutines.IoDispatcher
import com.kojo.boilerplate.core.coroutines.rethrowIfCancellation
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * [IntegrityAttestation] over the standard Play Integrity API.
 *
 * The one file in this repository that names a `com.google.android.play` type, which is what
 * lets everything above it be tested on a plain JVM. There is no local test mode for this API —
 * a token request needs a Play Store, a signed-in account and a package name Play recognises —
 * so this class is exercised on a device, while the decision it takes — what a Play error code
 * means for the caller — lives in [PlayIntegrityErrorCode], which is ordinary Kotlin over an
 * `Int` and is unit tested as such.
 *
 * ### Standard requests, not classic
 *
 * The classic API (`requestIntegrityToken`) takes a caller-supplied nonce, costs a round trip of
 * several hundred milliseconds to a second, and is rate limited hard enough that Google's own
 * guidance is a handful per day. The standard API splits that in two: `prepareIntegrityToken`
 * warms up a token provider once — the expensive half, and the half that can be done long
 * before anything is needed — and each `request` afterwards is served from that warm state in
 * tens of milliseconds. That is what makes attesting on the request path defensible at all.
 *
 * The provider is cached for the life of the process and guarded by a [Mutex], so that two
 * concurrent requests warm up once rather than twice. It has a finite life and Play will say so
 * — `INTEGRITY_TOKEN_PROVIDER_INVALID`, which also arrives when the user clears Play Store data
 * — and [attest] answers that by discarding the cached provider and trying exactly once more.
 * Once, not in a loop: a second failure of the same kind is a device that is not going to serve
 * a token, and retrying on the request path is latency the user pays for nothing.
 *
 * ### Nothing here decides anything about the device
 *
 * There is no verdict in this file, because there is no verdict in the client. The token is
 * opaque, it goes to the backend, and the backend decodes it. See [IntegrityAttestation] for why
 * that is the point rather than a limitation.
 */
@Singleton
class PlayIntegrityAttestation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configuration: IntegrityConfiguration,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : IntegrityAttestation {

    /**
     * Created lazily rather than in the constructor, so that a build with no Cloud project never
     * touches the Play Integrity entry point at all — on a device without Play services,
     * constructing it is the call that fails, and it would fail while Hilt was building the
     * singleton graph.
     */
    private val integrityManager by lazy { IntegrityManagerFactory.createStandard(context) }

    private val providerLock = Mutex()

    private var preparedProvider: StandardIntegrityTokenProvider? = null

    override suspend fun attest(requestHash: String): AttestationResult {
        require(requestHash.isNotBlank()) {
            "an integrity token must be bound to a request; see IntegrityRequestHash"
        }
        val cloudProjectNumber = configuration.cloudProjectNumber
            ?: return unavailable(AttestationFailure.NOT_CONFIGURED)
        if (requestHash.toByteArray(Charsets.UTF_8).size > IntegrityRequestHash.MAX_LENGTH) {
            // Caught here rather than left to Play, because Play answers it with
            // REQUEST_HASH_TOO_LONG after a round trip and this is arithmetic.
            return unavailable(AttestationFailure.MISCONFIGURED)
        }
        return withContext(ioDispatcher) {
            attempt(cloudProjectNumber, requestHash, mayDiscardProvider = true)
        }
    }

    private suspend fun attempt(
        cloudProjectNumber: Long,
        requestHash: String,
        mayDiscardProvider: Boolean,
    ): AttestationResult {
        val provider = try {
            warmedProvider(cloudProjectNumber)
        } catch (expected: Exception) {
            return unavailable(failureOf(expected), expected)
        }

        return try {
            val token = provider.request(
                StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build(),
            ).await().token()
            AttestationResult.Issued(token)
        } catch (expected: Exception) {
            val failure = failureOf(expected)
            if (failure == AttestationFailure.PROVIDER_EXPIRED && mayDiscardProvider) {
                discard(provider)
                attempt(cloudProjectNumber, requestHash, mayDiscardProvider = false)
            } else {
                unavailable(failure, expected)
            }
        }
    }

    /** The warmed-up provider, warming it up on first use. */
    private suspend fun warmedProvider(
        cloudProjectNumber: Long,
    ): StandardIntegrityTokenProvider = providerLock.withLock {
        preparedProvider ?: integrityManager.prepareIntegrityToken(
            PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .build(),
        ).await().also { preparedProvider = it }
    }

    /**
     * Forgets [stale] so the next call warms up again — unless another call already replaced it,
     * which is why this compares identity rather than clearing unconditionally.
     */
    private suspend fun discard(stale: StandardIntegrityTokenProvider) = providerLock.withLock {
        if (preparedProvider === stale) preparedProvider = null
    }

    /**
     * What [failure] means for the caller, with one line in the log.
     *
     * Logged at warn and never at error, and without [cause]'s stack trace unless it is one of
     * the misconfigurations: on a device that simply has no Play Store, every attested request
     * produces one of these, and a stack trace per request would be noise that buries the case
     * worth seeing. The token itself is never logged, here or anywhere.
     */
    private fun unavailable(
        failure: AttestationFailure,
        cause: Throwable? = null,
    ): AttestationResult {
        if (failure == AttestationFailure.MISCONFIGURED) {
            Log.w(TAG, failure.summary, cause)
        } else {
            Log.w(TAG, failure.summary)
        }
        return AttestationResult.Unavailable(failure)
    }

    /**
     * [thrown] reduced to one of the outcomes the app behaves differently about.
     *
     * Anything that is not a [StandardIntegrityException] is [AttestationFailure.TRANSIENT]: the
     * Play library can also surface a dead binder or a Play services connection failure as its
     * own exception types, and none of those is a statement about the device. Cancellation is
     * put back on its way first — a coroutine that was cancelled must complete as cancelled
     * rather than as a device that failed to attest.
     *
     * The mapping itself is in [PlayIntegrityErrorCode], which is ordinary Kotlin over an `Int`
     * and is unit tested as such; this method is the two lines that cannot be.
     */
    private fun failureOf(thrown: Throwable): AttestationFailure {
        thrown.rethrowIfCancellation()
        val exception = thrown as? StandardIntegrityException ?: return AttestationFailure.TRANSIENT
        return PlayIntegrityErrorCode.failureFor(exception.errorCode)
    }

    private companion object {
        private const val TAG = "PlayIntegrity"
    }
}
