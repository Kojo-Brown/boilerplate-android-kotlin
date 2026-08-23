package com.kojo.boilerplate.core.data.repository.decorator

import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.repository.UserRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How long a fetched user is treated as current. */
private val DEFAULT_FRESHNESS = 30.seconds

/**
 * Users held in memory before the least recently used one is dropped. A `User` is four short
 * strings, so this is kilobytes; the bound is here because an unbounded map keyed by user id
 * grows for the life of the process, not because the entries are large.
 */
private const val DEFAULT_MAX_ENTRIES = 64

private const val INITIAL_CAPACITY = 16
private const val LOAD_FACTOR = 0.75f

/** The key a fetch of the signed-in user is filed under. */
private const val CURRENT_USER_KEY = "currentUser"

/**
 * Suppresses network work the app does not need: repeats of a fetch that just happened, and
 * duplicates of one already in flight.
 *
 * ## What it does not cache
 *
 * Users. Room already does that, `getUsers`/`getUser` already observe it, and a second copy in
 * a map here would be a second source of truth that nothing invalidates and no `Flow` notifies.
 * Those two methods pass straight through.
 *
 * What is cached is the *decision to make a request*. `syncUser("7")` twice within
 * [freshness] costs one round trip; a screen and its detail pane refreshing the same user at
 * the same moment costs one round trip; a list refresh asks only for the ids that are not
 * already current. In each case the network is what is saved, and Room keeps its job.
 *
 * ## Two mechanisms, and why both are needed
 *
 * **Freshness** answers "was this fetched recently enough?" — a request that has already
 * finished. **In-flight coalescing** answers "is this already being fetched?" — a request that
 * has not. Freshness alone lets two simultaneous callers both miss and both fetch, which is the
 * common case on a screen that starts two loads at once; coalescing alone re-fetches
 * everything the moment the previous request completes.
 *
 * ## The decision that makes coalescing safe
 *
 * The shared request runs in [scope] — the process-lifetime `@ApplicationScope` — and not in
 * the caller's coroutine. Starting it with the first caller's `async` is the obvious
 * implementation and is wrong in a way that only shows up under navigation: the second caller
 * awaits a `Deferred` owned by the first, so when the first screen is closed mid-flight its
 * cancellation propagates into the shared request, and a caller that is still on screen and
 * still waiting gets a `CancellationException` from a coroutine it does not own and never
 * cancelled. Hosting the request somewhere neither caller owns is what makes joining it safe;
 * a caller that goes away just stops awaiting. This is what `@ApplicationScope` was declared
 * for — bounded work that must outlive the screen that asked for it.
 *
 * ## Failures are not cached
 *
 * Only a success sets the freshness mark. A failed sync leaves no entry, so the next caller
 * tries again immediately rather than being told for the next [freshness] that the network is
 * broken. Caching failures is how a 30-second cache turns one bad response into a 30-second
 * outage.
 *
 * ## Where it sits
 *
 * Above the retry decorator and below telemetry. A cache hit should cost neither a request nor
 * a retry schedule, so caching goes above retry; telemetry stays outside so what it measures is
 * what the caller waited for. See `docs/decorator.md`.
 */
class CachingUserRepository(
    override val delegate: UserRepository,
    private val scope: CoroutineScope,
    private val freshness: Duration = DEFAULT_FRESHNESS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : UserRepositoryDecorator {

    /**
     * One `Mutex` over both maps rather than a concurrent map each.
     *
     * The invariant is a relationship *between* them — a key is in flight or it is fresh, and
     * the check-then-start that maintains it has to be atomic — so per-map atomicity would
     * still let two callers both miss and both start a request. It is held only across map
     * reads and writes; nothing suspends on the network while holding it.
     */
    private val mutex = Mutex()

    /**
     * Access-ordered so `get` counts as a use, bounded so it cannot grow without limit.
     *
     * Not thread-safe, and not required to be: every read and write of it happens under
     * [mutex]. That includes the reads — an access-ordered `LinkedHashMap` mutates its
     * ordering on `get`, so a "read-only" lookup off the lock would corrupt it.
     */
    private val fresh = object : LinkedHashMap<String, CachedUser>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedUser>): Boolean =
            size > maxEntries
    }

    private val inFlight = mutableMapOf<String, Deferred<Result<User>>>()

    private data class CachedUser(val user: User, val fetchedAt: TimeMark)

    override fun getUsers(): Flow<List<User>> = delegate.getUsers()

    override fun getUser(id: String): Flow<User?> = delegate.getUser(id)

    /**
     * Writes through and invalidates.
     *
     * The local row has just been changed, so the copy this class is holding is a description
     * of the server's state that the app has already diverged from. Keeping it would let a
     * `syncUser` moments later return the pre-edit user as though it were current — a cache
     * outliving a write is the classic way one appears to "lose" an edit.
     */
    override suspend fun saveUser(user: User) {
        delegate.saveUser(user)
        mutex.withLock { fresh.remove(userKey(user.id)) }
    }

    override suspend fun syncCurrentUser(): Result<User> =
        cachedSync(CURRENT_USER_KEY) { delegate.syncCurrentUser() }

    override suspend fun syncUser(id: String): Result<User> =
        cachedSync(userKey(id)) { delegate.syncUser(id) }

    /**
     * Asks the delegate only for the ids that are not already current, and reassembles the
     * result as though it had asked for all of them.
     *
     * The successes come back in request order, cache hits and fetched users interleaved, so a
     * caller cannot tell which was which — matching what `FanOutResult` documents and what the
     * uncached call would have returned.
     *
     * Known limit: this does not join the per-id in-flight requests. A `syncUsers` overlapping
     * a `syncUser` for the same id will make its own request for that id. Routing the fan-out
     * through the single-user path would fix it and would also bypass the delegate's own
     * `syncUsers` — its bounded concurrency, its dedupe, its one `withContext` for the whole
     * batch — which is a worse trade than the occasional duplicate request.
     */
    override suspend fun syncUsers(ids: List<String>): FanOutResult<String, User> {
        val requested = ids.distinct()
        val hits = mutex.withLock {
            requested.mapNotNull { id -> freshUser(userKey(id))?.let { id to it } }.toMap()
        }

        val stale = requested.filterNot { it in hits }
        if (stale.isEmpty()) {
            return FanOutResult(successes = requested.mapNotNull { hits[it] }, failures = emptyList())
        }

        val fetched = delegate.syncUsers(stale)
        val now = timeSource.markNow()
        mutex.withLock {
            fetched.successes.forEach { fresh[userKey(it.id)] = CachedUser(it, now) }
        }

        val fetchedById = fetched.successes.associateBy { it.id }
        return FanOutResult(
            successes = requested.mapNotNull { hits[it] ?: fetchedById[it] },
            failures = fetched.failures,
        )
    }

    /**
     * The whole cache in one function: serve if fresh, join if in flight, otherwise start the
     * one request everyone waiting on this key will share.
     *
     * The `also` that registers the [Deferred] runs while [mutex] is still held, and the
     * coroutine's own `finally` needs that same lock to deregister — so however fast the fetch
     * completes, it cannot remove an entry that has not been added yet.
     */
    private suspend fun cachedSync(key: String, fetch: suspend () -> Result<User>): Result<User> {
        val request = mutex.withLock {
            freshUser(key)?.let { return Result.success(it) }

            inFlight[key] ?: scope.async {
                try {
                    fetch().also { result ->
                        val now = timeSource.markNow()
                        mutex.withLock { result.onSuccess { fresh[key] = CachedUser(it, now) } }
                    }
                } finally {
                    // The entry has to go even when this request was cancelled, or every later
                    // caller would join a Deferred that will never produce a value.
                    // NonCancellable because withLock is a suspension, and a suspension in the
                    // finally block of a cancelled coroutine throws instead of running.
                    withContext(NonCancellable) { mutex.withLock { inFlight.remove(key) } }
                }
            }.also { inFlight[key] = it }
        }

        return request.await()
    }

    /**
     * The cached user for [key] if it is still current, dropping it if it is not.
     *
     * Expiry removes rather than merely reporting: an entry nobody will use again should not
     * occupy one of [maxEntries] slots and push out an entry someone might.
     *
     * Must be called under [mutex].
     */
    private fun freshUser(key: String): User? {
        val entry = fresh[key] ?: return null
        if (entry.fetchedAt.elapsedNow() >= freshness) {
            fresh.remove(key)
            return null
        }
        return entry.user
    }

    /**
     * Namespaces a user id so it cannot collide with [CURRENT_USER_KEY].
     *
     * The two are different operations against different endpoints — `users/me` and
     * `users/{id}` — so a fetch of one must not answer for the other even when they name the
     * same person. Every id key starts with the prefix and the current-user key does not, so
     * no id can produce it.
     */
    private fun userKey(id: String): String = "user:$id"
}
