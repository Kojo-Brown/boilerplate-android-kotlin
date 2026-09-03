package com.kojo.boilerplate.core.testing

import com.kojo.boilerplate.core.domain.model.PushOutcome
import com.kojo.boilerplate.core.domain.repository.PendingUserChangeRepository

/**
 * A [PendingUserChangeRepository] that returns what a test tells it to, and counts how often it
 * was asked.
 *
 * It has no queue and stores nothing. Whether a push actually sends the right key, and what
 * happens to the row when it lands, is `PendingUserChangeRepositoryImplTest`'s subject and
 * needs a real `UserDao` behind it; the callers that inject this interface — today
 * `PerformBackgroundSyncUseCase` — only ever ask whether the push ran and what it reported.
 * Modelling the queue here as well would be a second implementation of the behaviour under
 * test, which is how a fake starts passing tests the real class fails.
 *
 * It lives in `:core:testing` rather than in a `src/test` source set because a test source set
 * is invisible from any other module, and the use case that needs this is in `:core:domain`.
 *
 * @param outcome what [pushPendingChanges] reports. Defaults to a clean run over nothing, which
 *   is what a device whose user is not editing anything does.
 */
class FakePendingUserChangeRepository(
    var outcome: PushOutcome = PushOutcome.NOTHING_TO_PUSH,
) : PendingUserChangeRepository {

    /**
     * How many times [pushPendingChanges] has been called.
     *
     * The assertion that matters for a background sync is *that it pushed at all* — the run
     * used to be a pull and nothing else, and a regression that dropped the push would leave
     * every other assertion in `PerformBackgroundSyncUseCaseTest` passing.
     */
    var pushCount: Int = 0
        private set

    /** Set to throw instead of returning, for the paths that only a throw can reach. */
    var failure: (() -> Throwable)? = null

    override suspend fun pushPendingChanges(): PushOutcome {
        pushCount++
        failure?.let { throw it() }
        return outcome
    }
}
