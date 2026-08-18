package com.kojo.boilerplate.core.data.repository.decorator

import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.telemetry.RepositoryOperation
import com.kojo.boilerplate.core.telemetry.RepositoryOperationEvent
import com.kojo.boilerplate.core.telemetry.RepositoryOutcome
import com.kojo.boilerplate.core.telemetry.RepositoryTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlin.time.TimeSource

/**
 * Reports how long each network-backed operation took and how it ended.
 *
 * ## Where it sits, and what that decides
 *
 * Outermost. What it measures is therefore what the *caller* experienced: a duration that
 * includes every retry underneath it and the backoff between them, and near-zero for a call the
 * cache answered without a request. That is the number a screen's latency is made of, and the
 * one worth alerting on.
 *
 * The cost is that a single event cannot say whether a request was made. From out here a cache
 * hit is an unusually fast success, so hit rate is not derivable from this stream — and the fix
 * is not to give this class a way to ask, but to wrap twice: a second [TelemetryUserRepository]
 * *under* the cache would count attempts, and the difference between the two streams is the hit
 * rate. `docs/decorator.md` works that through.
 *
 * ## Cancellation is recorded, and recorded as itself
 *
 * A cancelled call is not a failed one. Reporting it as a failure is the standard way an error
 * rate becomes meaningless: every user who leaves a screen mid-refresh contributes an error, so
 * the metric ends up tracking how fast people navigate. It is still recorded, under
 * [RepositoryOutcome.Cancelled] — a rising cancellation rate says operations are taking longer
 * than users are willing to wait, which nothing else here would tell you — and the exception is
 * rethrown untouched so the coroutine that was cancelled still cancels.
 *
 * ## What is not measured
 *
 * `getUsers` and `getUser` return long-lived `Flow`s over a Room query. "How long did it take"
 * has no answer for a subscription that lives as long as the screen, and instrumenting
 * emissions would produce one event per row change — a metric of how busy the database is,
 * dressed up as repository latency. `saveUser` is a local upsert whose only failure mode is an
 * exception, which the coroutine failure path already reports. All three pass through.
 */
class TelemetryUserRepository(
    override val delegate: UserRepository,
    private val telemetry: RepositoryTelemetry,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : UserRepositoryDecorator {

    override fun getUsers(): Flow<List<User>> = delegate.getUsers()

    override fun getUser(id: String): Flow<User?> = delegate.getUser(id)

    override suspend fun saveUser(user: User) = delegate.saveUser(user)

    override suspend fun syncCurrentUser(): Result<User> =
        measured(RepositoryOperation.SYNC_CURRENT_USER, { it.toOutcome() }) {
            delegate.syncCurrentUser()
        }

    override suspend fun syncUser(id: String): Result<User> =
        measured(RepositoryOperation.SYNC_USER, { it.toOutcome() }) { delegate.syncUser(id) }

    override suspend fun syncUsers(ids: List<String>): FanOutResult<String, User> =
        measured(RepositoryOperation.SYNC_USERS, { it.toOutcome() }) { delegate.syncUsers(ids) }

    /**
     * Times [block], records exactly one event for it, and changes nothing about what it
     * returns or throws.
     *
     * @param outcomeOf reads the outcome out of a returned value. It is a parameter because the
     *   `sync*` methods report failure as a value in two different shapes — a [Result] and a
     *   [FanOutResult] — and neither can be recognised generically.
     */
    // The broad catch below is a measurement boundary, not error handling: every throwable is
    // recorded and then rethrown unchanged, so nothing is swallowed or reclassified.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> measured(
        operation: RepositoryOperation,
        outcomeOf: (T) -> RepositoryOutcome,
        block: suspend () -> T,
    ): T {
        val start = timeSource.markNow()

        val value = try {
            block()
        } catch (cancellation: CancellationException) {
            telemetry.record(RepositoryOperationEvent(operation, start.elapsedNow(), RepositoryOutcome.Cancelled))
            throw cancellation
        } catch (failure: Throwable) {
            // The sync methods return their failures rather than throwing, so nothing in this
            // app reaches here today. It is not dead code: this decorator wraps whatever it is
            // given, and a delegate that throws must still be measured rather than left
            // uncounted.
            telemetry.record(
                RepositoryOperationEvent(operation, start.elapsedNow(), RepositoryOutcome.Failed(failure)),
            )
            throw failure
        }

        telemetry.record(RepositoryOperationEvent(operation, start.elapsedNow(), outcomeOf(value)))
        return value
    }

    private fun Result<User>.toOutcome(): RepositoryOutcome = fold(
        onSuccess = { RepositoryOutcome.Succeeded },
        onFailure = { RepositoryOutcome.Failed(it) },
    )

    /**
     * A fan-out with no failures succeeded; one with some is [RepositoryOutcome.PartiallyFailed]
     * even when every child failed, because the counts are the interesting part and
     * `succeeded = 0` already says it was total.
     */
    private fun FanOutResult<String, User>.toOutcome(): RepositoryOutcome =
        if (failures.isEmpty()) {
            RepositoryOutcome.Succeeded
        } else {
            RepositoryOutcome.PartiallyFailed(succeeded = successes.size, failed = failures.size)
        }
}
