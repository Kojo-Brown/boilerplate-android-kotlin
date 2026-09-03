package com.kojo.boilerplate.core.domain.repository

import com.kojo.boilerplate.core.domain.model.PushOutcome

/**
 * The write half of the sync: sends this device's unacknowledged edits to the server, once
 * each, however many times it has to ask.
 *
 * ## The half of offline-first that was missing
 *
 * `UserRepository.saveUser` records an edit locally and marks which fields it changed;
 * `ConflictResolver` then protects those fields from being overwritten by a fetch that does not
 * know about them. Both were built on the assumption that something would eventually push the
 * edit, and nothing did — so a marked field beat the server's value on this device forever and
 * never reached any other one. `docs/offline-first.md` carried that as "still an outbox, and
 * still open". This is it.
 *
 * ## Why it is not a seventh method on [UserRepository]
 *
 * Same reason `PagedUserRepository` is its own interface (`docs/solid.md`, finding 9): a caller
 * asks for one of these or the other, never for both, and merging them makes every implementer
 * of the read interface carry a write it has nothing to say about. Concretely, the three
 * decorators around `UserRepository` — cache, retry, telemetry — would each need an override
 * here, and all three would be wrong:
 *
 * - **Caching** answers "has this been fetched recently enough to skip?". A pending edit is
 *   never *not* worth sending, and a write suppressed by a freshness window is an edit lost.
 * - **Retry** is the interesting one, because a push does want retrying — but not there. The
 *   in-process backoff in `RetryingUserRepository` covers a few seconds; the failure a push has
 *   to survive is an offline device, an app that is killed, and a retry that arrives hours
 *   later in a different process. That is WorkManager's job, and it is what the idempotency key
 *   makes safe — see [pushPendingChanges].
 * - **Telemetry** would measure it happily, and is the one loss. It is recorded in
 *   `docs/idempotency.md` rather than paid for with a decorator stack around a one-method
 *   interface.
 *
 * ## What it deliberately is not
 *
 * A general outbox. There is one queue, it is the `users` table's own pending-field set, and it
 * holds at most one unsent mutation per row — a second edit to the same row supersedes the
 * first rather than queueing behind it, which is the right answer for "the user changed their
 * display name twice" and the wrong one for "the user made two payments". An entity whose
 * mutations must not collapse needs a real outbox table; the shape of the key and the
 * acknowledgement rule below are the parts that carry over unchanged.
 */
interface PendingUserChangeRepository {

    /**
     * Sends every row holding an unacknowledged edit, and reports how much of it landed.
     *
     * ## The contract this exists to keep
     *
     * **Calling this twice for one edit must change the server once.** That is not achieved by
     * being careful about when to call it — the caller is a background worker that can be
     * killed mid-request and re-run in a new process — but by every attempt carrying the same
     * client-generated key, which the row itself stores. A server that recognises the key
     * replays its first answer instead of applying the change again; a server that does not is
     * no worse off than it would have been without one.
     *
     * The key changes when, and only when, the edit does: a further local edit to a row makes a
     * new mutation, which needs a new name. See `docs/idempotency.md`.
     *
     * ## Failure is a count, not a throw
     *
     * A push over ten rows where one 503s has still sent nine, and reporting that as a thrown
     * failure would throw away the nine. Rows that did not land keep their pending fields and
     * their key, so the next call resends exactly those, under exactly the same names.
     * Cancellation is not a failure and is not counted: it propagates, as everywhere else in
     * this codebase.
     *
     * @return [PushOutcome.NOTHING_TO_PUSH] when no row had anything pending, which is the
     *   ordinary case on a device whose user is not editing anything.
     */
    suspend fun pushPendingChanges(): PushOutcome
}
