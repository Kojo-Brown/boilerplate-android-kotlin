package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.database.dao.FakeUserDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.domain.model.PushOutcome
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.LastWriteWinsConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.MergeConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.UpdateUserRequest
import com.kojo.boilerplate.core.network.model.UserDto
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The push half of the sync, and above all the property the whole item exists for: **a retry
 * carries the key the first attempt did.**
 *
 * That one is worth being explicit about, because it is the assertion that is easy to write and
 * not mean. Asserting that *a* key travelled passes against a repository that mints one per
 * attempt — which is the bug — so every test here that cares about the key names it, and the
 * generator is `SequentialIdempotencyKeyGenerator` for exactly that reason.
 *
 * The DAO is `FakeUserDao`, which inherits `upsertResolving` from the real `UserDao`, so the
 * read-modify-write these tests exercise is the code Room runs. What it cannot reproduce is the
 * transaction; concurrent pushes of one row serialising is Room's property and belongs in
 * `androidTest`, alongside the rest of that gap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingUserChangeRepositoryImplTest {

    private val testDispatcher = StandardTestDispatcher()

    private fun row(
        id: String = "1",
        displayName: String = "Ada",
        email: String = "ada@example.com",
        avatarUrl: String? = null,
        version: Long = 3L,
        locallyChanged: Set<UserField> = setOf(UserField.DISPLAY_NAME),
        pendingChangeKey: String? = "key-1",
    ) = UserEntity(
        id = id,
        displayName = displayName,
        email = email,
        avatarUrl = avatarUrl,
        version = version,
        locallyChanged = locallyChanged,
        pendingChangeKey = pendingChangeKey,
    )

    private fun dto(
        id: String = "1",
        displayName: String = "Ada",
        email: String = "ada@example.com",
        avatarUrl: String? = null,
        version: Long = 4L,
    ) = UserDto(
        id = id,
        displayName = displayName,
        email = email,
        avatarUrl = avatarUrl,
        version = version,
    )

    private fun repository(
        dao: FakeUserDao,
        api: UserApi,
        resolver: ConflictResolver = MergeConflictResolver(),
    ) = PendingUserChangeRepositoryImpl(
        userDao = dao,
        userApi = api,
        writer = ResolvingUserWriter(dao, resolver),
        ioDispatcher = testDispatcher,
    )

    // --- what gets sent ------------------------------------------------------------------

    @Test
    fun `it sends the key the row is holding`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(row(pendingChangeKey = "key-7")))
        val api = RecordingUserApi(dto())

        repository(dao, api).pushPendingChanges()

        assertEquals(listOf("key-7"), api.updates.map { it.idempotencyKey })
    }

    /**
     * The assertion the item is named after. Two runs over a row that stayed pending because
     * the first attempt failed — a request lost on the way out, or a response lost on the way
     * back, which the client cannot tell apart — and the server sees one mutation under one
     * name rather than two changes.
     *
     * A repository that generated a key at send time passes every other test in this file and
     * fails this one.
     */
    @Test
    fun `a retry sends the same key as the attempt that failed`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(row(pendingChangeKey = "key-7")))
        val api = RecordingUserApi(dto(), failuresFirst = 1)
        val repository = repository(dao, api)

        val first = repository.pushPendingChanges()
        val second = repository.pushPendingChanges()

        assertEquals(PushOutcome(pushed = 0, failed = 1), first)
        assertEquals(PushOutcome(pushed = 1, failed = 0), second)
        assertEquals(listOf("key-7", "key-7"), api.updates.map { it.idempotencyKey })
    }

    /**
     * Only the pending fields travel. A value this client holds for a field it did not edit may
     * be the server's own and may be months old, and sending it back is how a background push
     * silently reverts somebody else's change to the same row.
     */
    @Test
    fun `it sends only the fields that were edited`() = runTest(testDispatcher) {
        val dao = FakeUserDao(
            listOf(
                row(
                    displayName = "Ada Renamed",
                    email = "stale@example.com",
                    locallyChanged = setOf(UserField.DISPLAY_NAME),
                ),
            ),
        )
        val api = RecordingUserApi(dto())

        repository(dao, api).pushPendingChanges()

        assertEquals(
            UpdateUserRequest(
                changedFields = listOf("display_name"),
                displayName = "Ada Renamed",
                email = null,
                avatarUrl = null,
            ),
            api.updates.single().update,
        )
    }

    /**
     * A cleared avatar is a change to `null`, and it has to be distinguishable from a field
     * this update does not concern — which is the entire reason `changed_fields` exists rather
     * than the request relying on a key being absent. Both fields serialise identically; only
     * the list separates them.
     */
    @Test
    fun `a cleared avatar is named even though its value is null`() = runTest(testDispatcher) {
        val dao = FakeUserDao(
            listOf(row(avatarUrl = null, locallyChanged = setOf(UserField.AVATAR_URL))),
        )
        val api = RecordingUserApi(dto())

        repository(dao, api).pushPendingChanges()

        assertEquals(listOf("avatar_url"), api.updates.single().update.changedFields)
        assertNull(api.updates.single().update.avatarUrl)
    }

    // --- what gets pushed, and what does not ---------------------------------------------

    @Test
    fun `a clean row is not sent`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(row(locallyChanged = emptySet(), pendingChangeKey = null)))
        val api = RecordingUserApi(dto())

        val outcome = repository(dao, api).pushPendingChanges()

        assertEquals(PushOutcome.NOTHING_TO_PUSH, outcome)
        assertTrue(api.updates.isEmpty())
    }

    @Test
    fun `every pending row is sent`() = runTest(testDispatcher) {
        val dao = FakeUserDao(
            listOf(
                row(id = "1", pendingChangeKey = "key-1"),
                row(id = "2", locallyChanged = emptySet(), pendingChangeKey = null),
                row(id = "3", pendingChangeKey = "key-3"),
            ),
        )
        val api = RecordingUserApi(dto())

        val outcome = repository(dao, api).pushPendingChanges()

        assertEquals(PushOutcome(pushed = 2, failed = 0), outcome)
        assertEquals(setOf("1", "3"), api.updates.map { it.id }.toSet())
    }

    /**
     * The invariant that cannot be reached through the app — `toEntity` writes the key and the
     * pending set together, and `MIGRATION_3_4` backfills the rows that predate the column — is
     * still worth pinning, because the response to breaking it must be to *skip*, not to invent
     * a key. An unnamed change is a change the server reapplies on every retry.
     */
    @Test
    fun `a pending row with no key is counted as failed rather than sent unnamed`() =
        runTest(testDispatcher) {
            val dao = FakeUserDao(listOf(row(pendingChangeKey = null)))
            val api = RecordingUserApi(dto())

            val outcome = repository(dao, api).pushPendingChanges()

            assertEquals(PushOutcome(pushed = 0, failed = 1), outcome)
            assertTrue(api.updates.isEmpty())
        }

    // --- what happens when it lands ------------------------------------------------------

    @Test
    fun `an acknowledged push clears the pending set and its key`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(row(displayName = "Ada Renamed")))
        val api = RecordingUserApi(dto(displayName = "Ada Renamed", version = 4L))

        repository(dao, api).pushPendingChanges()

        val stored = dao.entities.single()
        assertEquals(emptySet<UserField>(), stored.locallyChanged)
        assertNull(stored.pendingChangeKey)
        assertEquals(4L, stored.version)
    }

    /**
     * The mid-flight edit, and the reason the acknowledgement compares keys at all.
     *
     * The row is holding `key-9` by the time the response to `key-7` is committed, which is what
     * a `saveUser` during the request looks like from here. Clearing on the strength of "the
     * push succeeded" would discard an edit the user made seconds earlier, with nothing
     * reporting an error and nothing left to notice afterwards.
     */
    @Test
    fun `an edit made during the request survives the acknowledgement`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(row(displayName = "First edit", pendingChangeKey = "key-7")))
        val api = RecordingUserApi(dto(displayName = "First edit", version = 4L)) {
            // Runs while the request is "in flight": the row moves on to a newer mutation,
            // exactly as saveUser would leave it.
            dao.upsert(row(displayName = "Second edit", pendingChangeKey = "key-9"))
        }

        repository(dao, api).pushPendingChanges()

        val stored = dao.entities.single()
        assertEquals("Second edit", stored.displayName)
        assertEquals(setOf(UserField.DISPLAY_NAME), stored.locallyChanged)
        assertEquals("key-9", stored.pendingChangeKey)
    }

    /**
     * The same case under the other policy, because it is the policy that decides what happens
     * to the newer edit and not this class. `LAST_WRITE_WINS` takes the server's row wholesale,
     * so the second edit is discarded — which is what that policy means — and the key goes with
     * it rather than being left behind naming a mutation that no longer exists.
     */
    @Test
    fun `last-write-wins drops the newer edit and its key with it`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(row(displayName = "First edit", pendingChangeKey = "key-7")))
        val api = RecordingUserApi(dto(displayName = "Server copy", version = 9L)) {
            dao.upsert(row(displayName = "Second edit", pendingChangeKey = "key-9"))
        }

        repository(dao, api, LastWriteWinsConflictResolver()).pushPendingChanges()

        val stored = dao.entities.single()
        assertEquals("Server copy", stored.displayName)
        assertEquals(emptySet<UserField>(), stored.locallyChanged)
        assertNull(stored.pendingChangeKey)
    }

    // --- failure ---------------------------------------------------------------------------

    /**
     * A failure is a count, not a throw: nine rows that landed are not worth discarding because
     * a tenth did not. The row that failed keeps everything it needs to be sent again.
     */
    @Test
    fun `a failed row keeps its pending set and its key`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(row(pendingChangeKey = "key-7")))
        val api = RecordingUserApi(dto(), failuresFirst = 1)

        val outcome = repository(dao, api).pushPendingChanges()

        assertEquals(PushOutcome(pushed = 0, failed = 1), outcome)
        val stored = dao.entities.single()
        assertEquals(setOf(UserField.DISPLAY_NAME), stored.locallyChanged)
        assertEquals("key-7", stored.pendingChangeKey)
    }

    @Test
    fun `one row failing does not stop the others`() = runTest(testDispatcher) {
        val dao = FakeUserDao(
            listOf(row(id = "1", pendingChangeKey = "key-1"), row(id = "2", pendingChangeKey = "key-2")),
        )
        val api = RecordingUserApi(dto(), failuresFirst = 1)

        val outcome = repository(dao, api).pushPendingChanges()

        assertEquals(PushOutcome(pushed = 1, failed = 1), outcome)
    }
}

/**
 * Records every update it is asked to make, and can be told to fail the first [failuresFirst]
 * of them.
 *
 * @param onUpdate runs after the call is recorded and before the response is produced, so a
 *   test can make the world change "while the request is in flight" — which is the only way to
 *   reach the mid-flight-edit branch without a real dispatcher and a real race.
 */
private class RecordingUserApi(
    private val response: UserDto,
    private val failuresFirst: Int = 0,
    private val onUpdate: suspend () -> Unit = {},
) : UserApi {

    data class Update(val id: String, val idempotencyKey: String, val update: UpdateUserRequest)

    val updates = mutableListOf<Update>()

    override suspend fun updateUser(
        id: String,
        idempotencyKey: String,
        update: UpdateUserRequest,
    ): UserDto {
        updates += Update(id, idempotencyKey, update)
        onUpdate()
        if (updates.size <= failuresFirst) throw IOException("the response never arrived")
        return response.copy(id = id)
    }

    // The read half is not what this suite is about. `error` rather than a fabricated user, so
    // a test that drifts onto a fetch fails loudly instead of quietly asserting about one.
    override suspend fun getCurrentUser(): UserDto = error("getCurrentUser is not used here")

    override suspend fun getUser(id: String): UserDto = error("getUser is not used here")

    override suspend fun getUsers(page: Int, perPage: Int): List<UserDto> =
        error("getUsers is not used here")
}
