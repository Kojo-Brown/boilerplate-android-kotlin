package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.database.dao.FakeUserDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.LastWriteWinsConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.MergeConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.UpdateUserRequest
import com.kojo.boilerplate.core.network.model.UserDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * When a row gets an idempotency key, when it keeps the one it has, and when it loses it.
 *
 * These are all `UserRepositoryImpl`'s decisions rather than the push path's, and they are here
 * rather than in `UserRepositoryImplConflictTest` because they answer a different question about
 * the same writes: that suite asks what the *fields* end up as, this one asks what the row is
 * called while it waits to be sent.
 *
 * The invariant every test here is a case of: **a row has a key exactly while it has pending
 * fields.** Both halves of it can break silently — a key left on a clean row makes a later push
 * send a change nobody made, and a pending row with no key is an edit that can never be safely
 * retried.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryImplIdempotencyKeyTest {

    private val testDispatcher = StandardTestDispatcher()
    private val keys = SequentialIdempotencyKeyGenerator()

    private val ada = User(id = "1", displayName = "Ada", email = "ada@example.com", avatarUrl = null)

    private fun entity(
        user: User = ada,
        version: Long = 0L,
        locallyChanged: Set<UserField> = emptySet(),
        pendingChangeKey: String? = null,
    ) = UserEntity(
        id = user.id,
        displayName = user.displayName,
        email = user.email,
        avatarUrl = user.avatarUrl,
        version = version,
        locallyChanged = locallyChanged,
        pendingChangeKey = pendingChangeKey,
    )

    private fun dto(user: User = ada, version: Long) = UserDto(
        id = user.id,
        displayName = user.displayName,
        email = user.email,
        avatarUrl = user.avatarUrl,
        version = version,
    )

    private fun repository(
        dao: FakeUserDao,
        response: UserDto = dto(version = 0L),
        resolver: ConflictResolver = MergeConflictResolver(),
    ) = userRepositoryOver(dao, SingleResponseUserApi(response), testDispatcher, resolver, keys)

    private val FakeUserDao.stored: UserEntity get() = entities.single()

    // --- a local edit names a mutation ---------------------------------------------------

    @Test
    fun `an edit to a stored row names the mutation`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))

        repository(dao).saveUser(ada.copy(displayName = "Ada Renamed"))

        assertEquals("key-1", dao.stored.pendingChangeKey)
    }

    /**
     * A row that did not exist is created with every field pending — it has never been synced,
     * so none of its values came from the server — and that is a mutation like any other.
     */
    @Test
    fun `a row saved for the first time is named too`() = runTest(testDispatcher) {
        val dao = FakeUserDao()

        repository(dao).saveUser(ada)

        assertEquals(UserField.entries.toSet(), dao.stored.locallyChanged)
        assertEquals("key-1", dao.stored.pendingChangeKey)
    }

    /** Nothing pending, nothing to name. A key here would be a push waiting to happen. */
    @Test
    fun `a save that changes nothing leaves the row unnamed`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))

        repository(dao).saveUser(ada)

        assertEquals(emptySet<UserField>(), dao.stored.locallyChanged)
        assertNull(dao.stored.pendingChangeKey)
        assertEquals(0, keys.minted)
    }

    // --- the same mutation keeps its name ------------------------------------------------

    /**
     * The *only when* half of the rule. A re-submitted form, or a retry of `saveUser` itself,
     * asks for the change that is already pending — so it is the same mutation and keeps the
     * same name. Renaming it would leave a push already in flight unable to match its own
     * acknowledgement, costing a second round trip to send what the server already has.
     */
    @Test
    fun `saving the same edit twice keeps one key`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))
        val repository = repository(dao)

        repository.saveUser(ada.copy(displayName = "Ada Renamed"))
        repository.saveUser(ada.copy(displayName = "Ada Renamed"))

        assertEquals("key-1", dao.stored.pendingChangeKey)
        assertEquals(1, keys.minted)
    }

    /**
     * The *when* half. A second, different edit is a different request, and reusing the name
     * would let a server that had already seen the first one recognise this as a duplicate and
     * drop it — the app would have shown the user a change it then silently abandoned.
     */
    @Test
    fun `a further edit to the same field is a new mutation`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))
        val repository = repository(dao)

        repository.saveUser(ada.copy(displayName = "Ada Renamed"))
        repository.saveUser(ada.copy(displayName = "Ada Renamed Again"))

        assertEquals("key-2", dao.stored.pendingChangeKey)
    }

    /** A second field joining the pending set changes the payload, so it changes the name. */
    @Test
    fun `an edit to a second field is a new mutation`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))
        val repository = repository(dao)

        repository.saveUser(ada.copy(displayName = "Ada Renamed"))
        repository.saveUser(ada.copy(displayName = "Ada Renamed", email = "ada@new.example.com"))

        assertEquals(
            setOf(UserField.DISPLAY_NAME, UserField.EMAIL),
            dao.stored.locallyChanged,
        )
        assertNotEquals("key-1", dao.stored.pendingChangeKey)
    }

    // --- a fetch never renames a mutation ------------------------------------------------

    /**
     * The subtle one, and the reason `keyForResolvedPendingSet` exists at all. A fetch arriving
     * while a push is in flight must not rename the row: the retry after a lost response would
     * introduce itself to the server as a change it had never seen.
     */
    @Test
    fun `a merge that leaves the edit pending keeps its key`() = runTest(testDispatcher) {
        val dao = FakeUserDao(
            listOf(
                entity(
                    user = ada.copy(displayName = "Ada Renamed"),
                    version = 4,
                    locallyChanged = setOf(UserField.DISPLAY_NAME),
                    pendingChangeKey = "key-7",
                ),
            ),
        )

        repository(dao, dto(version = 9L)).syncUser("1")

        assertEquals(setOf(UserField.DISPLAY_NAME), dao.stored.locallyChanged)
        assertEquals("key-7", dao.stored.pendingChangeKey)
        assertEquals(0, keys.minted)
    }

    /**
     * The other side of the invariant. When the server's row carries the edited value, the
     * field has converged and stops being pending — and with the last pending field gone there
     * is no mutation left for the key to name.
     */
    @Test
    fun `a merge that converges the last field clears the key`() = runTest(testDispatcher) {
        val renamed = ada.copy(displayName = "Ada Renamed")
        val dao = FakeUserDao(
            listOf(
                entity(
                    user = renamed,
                    version = 4,
                    locallyChanged = setOf(UserField.DISPLAY_NAME),
                    pendingChangeKey = "key-7",
                ),
            ),
        )

        repository(dao, dto(user = renamed, version = 9L)).syncUser("1")

        assertEquals(emptySet<UserField>(), dao.stored.locallyChanged)
        assertNull(dao.stored.pendingChangeKey)
    }

    /**
     * `LAST_WRITE_WINS` discards the local edit wholesale, which is what that policy means. The
     * key has to go with it: a name left behind on a clean row would make the next push send a
     * change that no longer exists.
     */
    @Test
    fun `last-write-wins clears the key with the edit`() = runTest(testDispatcher) {
        val dao = FakeUserDao(
            listOf(
                entity(
                    user = ada.copy(displayName = "Ada Renamed"),
                    version = 4,
                    locallyChanged = setOf(UserField.DISPLAY_NAME),
                    pendingChangeKey = "key-7",
                ),
            ),
        )

        repository(dao, dto(version = 9L), LastWriteWinsConflictResolver()).syncUser("1")

        assertEquals(emptySet<UserField>(), dao.stored.locallyChanged)
        assertNull(dao.stored.pendingChangeKey)
    }

    /** A first fetch of a row nobody has edited stores no key. */
    @Test
    fun `a fetched row is stored unnamed`() = runTest(testDispatcher) {
        val dao = FakeUserDao()

        repository(dao, dto(version = 9L)).syncUser("1")

        assertNull(dao.stored.pendingChangeKey)
        assertEquals(0, keys.minted)
    }
}

/**
 * Answers every read with one response; the push path is not what this suite exercises.
 *
 * Top-level rather than nested, and named for what it does rather than `StubUserApi`, because
 * `UserRepositoryImplConflictTest` already has a nested class by that name in this package and
 * two top-level ones would collide on the JVM.
 */
private class SingleResponseUserApi(private val response: UserDto) : UserApi {

    override suspend fun getCurrentUser(): UserDto = response

    override suspend fun getUser(id: String): UserDto = response

    override suspend fun getUsers(page: Int, perPage: Int): List<UserDto> =
        error("getUsers is not used by this test")

    override suspend fun updateUser(
        id: String,
        idempotencyKey: String,
        update: UpdateUserRequest,
    ): UserDto = error("updateUser is not used by this test")
}
