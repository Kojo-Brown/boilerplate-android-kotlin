package com.kojo.boilerplate.testing

import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.UpdateUserRequest
import com.kojo.boilerplate.core.network.model.UserDto
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [UserApi] the test graph binds in place of the Retrofit one, and the whole of the network
 * in an instrumented test.
 *
 * ## Why it is a real implementation and not a mock
 *
 * The callers are `UserRepositoryImpl`, `PendingUserChangeRepositoryImpl` and
 * `UsersRemoteMediator`, and all three are read-modify-write against the database: a fetch is
 * committed through conflict resolution, a push is acknowledged against the key the row holds.
 * A mock returning a canned value per method cannot express that the server's copy *changed
 * because of the request* — so an acknowledgement would carry the version the fixture was
 * written with and the resolver would be exercised against a value that could not occur.
 * [updateUser] applies the patch and bumps [UserDto.version], which is the smallest amount of
 * server this repository's own code reasons about.
 *
 * ## Thread safety is not optional here
 *
 * `syncUsers` and `pushPendingChanges` both go through `mapConcurrentlyCatching`, so several
 * coroutines call into this object at once on `Dispatchers.IO`. Hence [ConcurrentHashMap] and
 * [CopyOnWriteArrayList] rather than the plain collections a JVM fake would use: with a
 * `mutableListOf` the recorded calls are a race, and a lost record reads as "the request never
 * reached the network", which is the one conclusion this class exists to support.
 */
@Singleton
class TestUserApi @Inject constructor() : UserApi {

    private val stored = ConcurrentHashMap<String, UserDto>()

    private val recorded = CopyOnWriteArrayList<Call>()

    /**
     * Thrown by every endpoint while it is set, *after* the call has been recorded.
     *
     * That order is deliberate: a test asserting that a failure was not retried is asserting on
     * the number of requests that reached this object, and a failure that is not recorded cannot
     * be counted. Which exception it is matters — `RetryingUserRepository` retries a transient
     * one, and `isTransientFailure` reads `IOException` and a retryable `HttpException` as
     * transient and everything else as terminal.
     */
    @Volatile
    var failure: Throwable? = null

    /** Which user [getCurrentUser] answers with. */
    @Volatile
    var currentUserId: String = DEFAULT_CURRENT_USER_ID

    /** Every call that reached this object, in the order the records were appended. */
    val calls: List<Call> get() = recorded.toList()

    /** One request. A `data` shape so that a failed assertion prints what was actually sent. */
    sealed interface Call {

        data object CurrentUser : Call

        data class SingleUser(val id: String) : Call

        data class Page(val page: Int, val perPage: Int) : Call

        data class Update(
            val id: String,
            val idempotencyKey: String,
            val body: UpdateUserRequest,
        ) : Call
    }

    /** Puts [users] on the server, replacing any row with the same id. */
    fun seed(vararg users: UserDto) {
        users.forEach { stored[it.id] = it }
    }

    /** The server's current copy of [id], which [updateUser] may have moved on. */
    fun serverCopyOf(id: String): UserDto? = stored[id]

    override suspend fun getCurrentUser(): UserDto {
        record(Call.CurrentUser)
        return stored[currentUserId] ?: throw notFound(currentUserId)
    }

    override suspend fun getUser(id: String): UserDto {
        record(Call.SingleUser(id))
        return stored[id] ?: throw notFound(id)
    }

    /**
     * One page, ordered by id so that paging over this object is repeatable, and short of
     * [perPage] at the end — which is how `UsersRemoteMediator` recognises the last page, since
     * [UserApi.getUsers] deliberately returns a bare list rather than an envelope.
     */
    override suspend fun getUsers(page: Int, perPage: Int): List<UserDto> {
        record(Call.Page(page = page, perPage = perPage))
        return stored.values
            .sortedBy { it.id }
            .drop((page - FIRST_PAGE) * perPage)
            .take(perPage)
    }

    /**
     * Applies the patch and returns the row as it now is, version included.
     *
     * The fields are read from [UpdateUserRequest.changedFields] rather than from whichever of
     * the three values happens to be non-null, because `avatarUrl` is nullable on the wire: a
     * cleared avatar and an unsent avatar are both `null`, and only the field list tells them
     * apart. The wire names are spelled here because `updateUserRequest`'s mapping of
     * `UserField` to them is private to `:data` — if one of them changes, this fake stops
     * applying that field and the push test fails on the value it reads back, which is the
     * failure a test double should produce rather than a silent pass.
     *
     * Nothing here dedupes on [idempotencyKey]. A server that honours the header would, and
     * `docs/idempotency.md` says what that buys; what the client is responsible for is sending
     * the *same* key for the same edit, and the key is recorded above so a test can assert it.
     */
    override suspend fun updateUser(
        id: String,
        idempotencyKey: String,
        update: UpdateUserRequest,
    ): UserDto {
        record(Call.Update(id = id, idempotencyKey = idempotencyKey, body = update))
        val existing = stored[id] ?: throw notFound(id)
        val changed = update.changedFields.toSet()
        val applied = existing.copy(
            displayName = if (DISPLAY_NAME_FIELD in changed) {
                requireNotNull(update.displayName) { "$DISPLAY_NAME_FIELD changed but not sent" }
            } else {
                existing.displayName
            },
            email = if (EMAIL_FIELD in changed) {
                requireNotNull(update.email) { "$EMAIL_FIELD changed but not sent" }
            } else {
                existing.email
            },
            avatarUrl = if (AVATAR_URL_FIELD in changed) update.avatarUrl else existing.avatarUrl,
            version = existing.version + 1,
        )
        stored[id] = applied
        return applied
    }

    private fun record(call: Call) {
        recorded += call
        failure?.let { throw it }
    }

    private fun notFound(id: String): Throwable =
        NoSuchElementException("no user $id has been seeded on TestUserApi")

    companion object {

        /** Obviously fake, and the id [seed] is normally called with in these suites. */
        const val DEFAULT_CURRENT_USER_ID: String = "test-user-1"

        /** [UserApi.getUsers] is 1-indexed; see its KDoc. */
        private const val FIRST_PAGE = 1

        private const val DISPLAY_NAME_FIELD = "display_name"

        private const val EMAIL_FIELD = "email"

        private const val AVATAR_URL_FIELD = "avatar_url"
    }
}
