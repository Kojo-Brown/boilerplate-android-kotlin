package com.kojo.boilerplate.core.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator.InitializeAction
import androidx.paging.RemoteMediator.MediatorResult
import com.kojo.boilerplate.core.database.dao.FakeUserPagingDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.database.entity.UserPageKeyEntity
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.MergeConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.UpdateUserRequest
import com.kojo.boilerplate.core.network.model.UserDto
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The mediator's whole contract: which page it asks for, what it stores, and what it reports
 * back to Paging.
 *
 * Nothing here goes near a `PagingSource` or a `Pager`. A `RemoteMediator` is the half of
 * Paging that never returns data — it writes to Room and answers "was that the last page?" —
 * so the interesting behaviour is entirely observable from a fake DAO and a fake API, and a
 * test that stood up a real `Pager` would be asserting about Paging's presenter rather than
 * about this class.
 *
 * The one property it cannot see is atomicity: `FakeUserPagingDao` has no transaction. That is
 * Room's to provide and is asserted against a real database under `androidTest`.
 */
@OptIn(ExperimentalPagingApi::class)
class UsersRemoteMediatorTest {

    private val pageSize = 3

    // --- fixtures --------------------------------------------------------------------------

    private fun dto(id: String, version: Long = 1L) = UserDto(
        id = id,
        displayName = "User $id",
        email = "$id@example.com",
        avatarUrl = null,
        version = version,
    )

    private fun entity(
        id: String,
        displayName: String = "User $id",
        email: String = "$id@example.com",
        version: Long = 1L,
        locallyChanged: Set<UserField> = emptySet(),
    ) = UserEntity(
        id = id,
        displayName = displayName,
        email = email,
        avatarUrl = null,
        version = version,
        locallyChanged = locallyChanged,
    )

    private fun state() = PagingState<Int, UserEntity>(
        pages = emptyList(),
        anchorPosition = null,
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        leadingPlaceholderCount = 0,
    )

    private fun mediator(
        api: UserApi,
        dao: FakeUserPagingDao,
        resolver: ConflictResolver = MergeConflictResolver(),
    ) = UsersRemoteMediator(api, dao, resolver)

    // --- what it asks for --------------------------------------------------------------------

    @Test
    fun `refresh asks for page one at the config's page size`() = runTest {
        val api = RecordingUserApi(listOf(dto("a"), dto("b"), dto("c")))
        val dao = FakeUserPagingDao()

        mediator(api, dao).load(LoadType.REFRESH, state())

        assertEquals(listOf(1 to pageSize), api.requests)
    }

    @Test
    fun `append asks for the page the stored cursor points at`() = runTest {
        val api = RecordingUserApi(listOf(dto("d"), dto("e"), dto("f")))
        val dao = FakeUserPagingDao(initialPageKey = UserPageKeyEntity(id = 0, nextPage = 4))

        mediator(api, dao).load(LoadType.APPEND, state())

        assertEquals(listOf(4 to pageSize), api.requests)
    }

    /**
     * An append with no cursor row means no page has ever been stored — an initial refresh that
     * failed is the usual way — so page one is the recovery rather than the end of the list.
     * The alternative reading, "nothing left to fetch", would leave the list permanently empty
     * after one failed refresh.
     */
    @Test
    fun `append with no cursor at all restarts at page one`() = runTest {
        val api = RecordingUserApi(listOf(dto("a")))
        val dao = FakeUserPagingDao()

        mediator(api, dao).load(LoadType.APPEND, state())

        assertEquals(listOf(1 to pageSize), api.requests)
    }

    @Test
    fun `append past the end fetches nothing and reports end of pagination`() = runTest {
        val api = RecordingUserApi(emptyList())
        val dao = FakeUserPagingDao(initialPageKey = UserPageKeyEntity(id = 0, nextPage = null))

        val result = mediator(api, dao).load(LoadType.APPEND, state())

        assertEquals(emptyList<Pair<Int, Int>>(), api.requests)
        assertTrue((result as MediatorResult.Success).endOfPaginationReached)
    }

    /**
     * Page one is the beginning, so there is never anything before what is loaded. Answering
     * without a request is the point — a prepend that went to the network would fetch page one
     * again on every scroll back to the top.
     */
    @Test
    fun `prepend reports end of pagination without a request`() = runTest {
        val api = RecordingUserApi(listOf(dto("a")))
        val dao = FakeUserPagingDao(initialPageKey = UserPageKeyEntity(id = 0, nextPage = 2))

        val result = mediator(api, dao).load(LoadType.PREPEND, state())

        assertEquals(emptyList<Pair<Int, Int>>(), api.requests)
        assertTrue((result as MediatorResult.Success).endOfPaginationReached)
    }

    @Test
    fun `it always refreshes on first collection`() = runTest {
        val mediator = mediator(RecordingUserApi(emptyList()), FakeUserPagingDao())

        assertEquals(InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
    }

    // --- what it stores ----------------------------------------------------------------------

    @Test
    fun `a full page stores its rows and advances the cursor`() = runTest {
        val api = RecordingUserApi(listOf(dto("a"), dto("b"), dto("c")))
        val dao = FakeUserPagingDao()

        val result = mediator(api, dao).load(LoadType.REFRESH, state())

        assertEquals(listOf("a", "b", "c"), dao.storedUsers.map { it.id })
        assertEquals(2, dao.storedPageKey?.nextPage)
        assertFalse((result as MediatorResult.Success).endOfPaginationReached)
    }

    /**
     * A page shorter than the request is how this API says "that was the last one" — see
     * `UserApi.getUsers`. The cursor is cleared rather than advanced, which is what makes the
     * next append answer without a round trip.
     */
    @Test
    fun `a short page clears the cursor and reports end of pagination`() = runTest {
        val api = RecordingUserApi(listOf(dto("a"), dto("b")))
        val dao = FakeUserPagingDao()

        val result = mediator(api, dao).load(LoadType.REFRESH, state())

        assertNull(dao.storedPageKey?.nextPage)
        assertTrue((result as MediatorResult.Success).endOfPaginationReached)
    }

    /** A page repeating a user is one row, not two, and keeps the order the server sent. */
    @Test
    fun `a page that repeats a user stores it once`() = runTest {
        val api = RecordingUserApi(listOf(dto("a"), dto("b"), dto("a")))
        val dao = FakeUserPagingDao()

        mediator(api, dao).load(LoadType.REFRESH, state())

        assertEquals(listOf("a", "b"), dao.storedUsers.map { it.id })
    }

    /**
     * A refresh refills the list; it does not empty it first. The rows `syncUser` and
     * `syncCurrentUser` cached are in this same table, and so is every unpushed local edit —
     * the codelab's `deleteAll()` on refresh would take both.
     */
    @Test
    fun `refresh leaves rows the server did not return alone`() = runTest {
        val api = RecordingUserApi(listOf(dto("a")))
        val dao = FakeUserPagingDao(initialUsers = listOf(entity("z")))

        mediator(api, dao).load(LoadType.REFRESH, state())

        assertEquals(listOf("z", "a"), dao.storedUsers.map { it.id })
    }

    // --- the conflict policy still applies -----------------------------------------------------

    /**
     * Scrolling must not be a way to overwrite an edit the server has not seen. This is the
     * fifth conflict case — a strictly newer server row against a pending local change — and
     * the merge policy keeps the changed field while taking everything else.
     */
    @Test
    fun `a page does not overwrite an unpushed local edit`() = runTest {
        val stored = entity(
            id = "a",
            displayName = "Local Ada",
            version = 1L,
            locallyChanged = setOf(UserField.DISPLAY_NAME),
        )
        val api = RecordingUserApi(
            listOf(dto("a", version = 2L).copy(displayName = "Server Ada", email = "new@example.com")),
        )
        val dao = FakeUserPagingDao(initialUsers = listOf(stored))

        mediator(api, dao).load(LoadType.REFRESH, state())

        val row = dao.storedUsers.single()
        assertEquals("Local Ada", row.displayName)
        assertEquals("new@example.com", row.email)
        assertEquals(2L, row.version)
        assertEquals(setOf(UserField.DISPLAY_NAME), row.locallyChanged)
    }

    /** A response older than what is stored moves nothing — the same rule every fetch follows. */
    @Test
    fun `a stale page is not written`() = runTest {
        val stored = entity(id = "a", displayName = "Newer", version = 5L)
        val api = RecordingUserApi(listOf(dto("a", version = 2L)))
        val dao = FakeUserPagingDao(initialUsers = listOf(stored))

        mediator(api, dao).load(LoadType.REFRESH, state())

        assertEquals("Newer", dao.storedUsers.single().displayName)
        assertEquals(5L, dao.storedUsers.single().version)
    }

    // --- failure ------------------------------------------------------------------------------

    /**
     * A failed load is an error Paging surfaces as `LoadState.Error` next to the cached pages,
     * not an exception the collector has to survive. The cursor must not move: the page was
     * never stored, so the next attempt has to ask for it again.
     */
    @Test
    fun `a failing request becomes an error and leaves the cursor alone`() = runTest {
        val dao = FakeUserPagingDao(initialPageKey = UserPageKeyEntity(id = 0, nextPage = 4))
        val failure = IOException("offline")

        val result = mediator(FailingUserApi(failure), dao).load(LoadType.APPEND, state())

        assertEquals(failure, (result as MediatorResult.Error).throwable)
        assertEquals(4, dao.storedPageKey?.nextPage)
    }

    /**
     * Not only `IOException`. A malformed body is a failed load like any other, and narrowing
     * the catch to the network's own exception types would let it escape into the collector.
     */
    @Test
    fun `a non-network failure is an error too`() = runTest {
        val failure = IllegalStateException("malformed body")

        val result = mediator(FailingUserApi(failure), FakeUserPagingDao())
            .load(LoadType.REFRESH, state())

        assertEquals(failure, (result as MediatorResult.Error).throwable)
    }

    // --- doubles --------------------------------------------------------------------------------

    /** Records `(page, perPage)` per call so a test can assert what was asked for, not only what came back. */
    private class RecordingUserApi(private val response: List<UserDto>) : UserApi {
        val requests = mutableListOf<Pair<Int, Int>>()

        override suspend fun getUsers(page: Int, perPage: Int): List<UserDto> {
            requests += page to perPage
            return response
        }

        override suspend fun getCurrentUser(): UserDto = error("not used by this test")
        override suspend fun getUser(id: String): UserDto = error("not used by this test")

        // Not part of what this suite exercises. `error` rather than a fabricated response so
        // a test that drifts onto the push path fails loudly instead of quietly succeeding.
        override suspend fun updateUser(
            id: String,
            idempotencyKey: String,
            update: UpdateUserRequest,
        ): UserDto = error("updateUser is not used by this test")
    }

    private class FailingUserApi(private val failure: Throwable) : UserApi {
        override suspend fun getUsers(page: Int, perPage: Int): List<UserDto> = throw failure
        override suspend fun getCurrentUser(): UserDto = error("not used by this test")
        override suspend fun getUser(id: String): UserDto = error("not used by this test")

        // Not part of what this suite exercises. `error` rather than a fabricated response so
        // a test that drifts onto the push path fails loudly instead of quietly succeeding.
        override suspend fun updateUser(
            id: String,
            idempotencyKey: String,
            update: UpdateUserRequest,
        ): UserDto = error("updateUser is not used by this test")
    }
}
