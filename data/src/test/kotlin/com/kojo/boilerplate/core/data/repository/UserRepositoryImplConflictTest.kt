package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.database.dao.FakeUserDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.sync.conflict.ConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.LastWriteWinsConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.MergeConflictResolver
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.UserDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Conflict resolution as the repository actually runs it, end to end: a response arrives, the
 * stored row is read, the policy decides, and something — or nothing — is written.
 *
 * The resolver suites in `:core:domain` already pin the decisions. What only shows up here is
 * the wiring around them: that `saveUser` records *which* fields it changed, that the version
 * is carried rather than invented, that a declined write leaves the row untouched, and that
 * `syncUser` returns what the store holds rather than what the server said. Every one of those
 * is a place where a correct policy could still be plumbed in wrongly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryImplConflictTest {

    private val testDispatcher = StandardTestDispatcher()

    private val ada = User(
        id = "1",
        displayName = "Ada",
        email = "ada@example.com",
        avatarUrl = null,
    )

    private fun entity(
        user: User = ada,
        version: Long = 0L,
        locallyChanged: Set<UserField> = emptySet(),
    ) = UserEntity(
        id = user.id,
        displayName = user.displayName,
        email = user.email,
        avatarUrl = user.avatarUrl,
        version = version,
        locallyChanged = locallyChanged,
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
        response: UserDto,
        resolver: ConflictResolver = MergeConflictResolver(),
    ) = UserRepositoryImpl(dao, StubUserApi(response), resolver, testDispatcher)

    // --- saveUser records the edit -------------------------------------------------------

    @Test
    fun `saveUser marks only the fields it changed`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))
        val repository = repository(dao, dto(version = 4))

        repository.saveUser(ada.copy(displayName = "Ada Renamed"))

        assertEquals(setOf(UserField.DISPLAY_NAME), dao.entities.single().locallyChanged)
    }

    /**
     * Versions are the server's to assign. A local edit that bumped one would make the row
     * claim to have seen a server version that does not exist, and the fetch eventually
     * carrying the real one would be discarded as stale.
     */
    @Test
    fun `saveUser leaves the version alone`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))

        repository(dao, dto(version = 4)).saveUser(ada.copy(displayName = "Ada Renamed"))

        assertEquals(4L, dao.entities.single().version)
    }

    /**
     * A row created locally has never been synced, so none of its values came from the server
     * and the first response to arrive is a genuine conflict with all of them rather than a
     * fast-forward over them.
     */
    @Test
    fun `saveUser on a row that does not exist marks every field`() = runTest(testDispatcher) {
        val dao = FakeUserDao()

        repository(dao, dto(version = 1)).saveUser(ada)

        val stored = dao.entities.single()
        assertEquals(UserField.entries.toSet(), stored.locallyChanged)
        assertEquals(0L, stored.version)
    }

    /**
     * The dirty set unions rather than replaces. Once a field is pending this row no longer
     * holds the server's value for it, so a later edit cannot tell whether it has been changed
     * back — and keeping it marked is the reading that cannot lose a change.
     */
    @Test
    fun `a second edit keeps the first field marked`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))
        val repository = repository(dao, dto(version = 4))

        repository.saveUser(ada.copy(displayName = "Ada Renamed"))
        repository.saveUser(ada.copy(displayName = "Ada Renamed", email = "ada@local.example.com"))

        assertEquals(
            setOf(UserField.DISPLAY_NAME, UserField.EMAIL),
            dao.entities.single().locallyChanged,
        )
    }

    // --- sync writes through the resolver ------------------------------------------------

    @Test
    fun `a stale response does not overwrite a newer row`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(ada.copy(displayName = "Newer"), version = 9)))

        val result = repository(dao, dto(ada.copy(displayName = "Older"), version = 2)).syncUser("1")

        assertEquals("Newer", dao.entities.single().displayName)
        // The result is what the store holds, not what the server said — a caller reads it as
        // "the user, now", and handing back a value Room will never emit would make the two
        // answers disagree.
        assertEquals("Newer", result.getOrNull()?.displayName)
    }

    @Test
    fun `a newer response over a clean row lands`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 1)))

        val result = repository(dao, dto(ada.copy(displayName = "Ada L"), version = 2)).syncUser("1")

        val stored = dao.entities.single()
        assertEquals("Ada L", stored.displayName)
        assertEquals(2L, stored.version)
        assertEquals("Ada L", result.getOrNull()?.displayName)
    }

    /**
     * The end-to-end version of the case the whole item exists for: a user renames themselves,
     * a background sync brings down a server change to a different field, and the rename is
     * still there afterwards.
     */
    @Test
    fun `a local rename survives a server change to another field`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))
        val avatar = "https://cdn.example.com/fake-avatar.png"
        val repository = repository(dao, dto(ada.copy(avatarUrl = avatar), version = 5))

        repository.saveUser(ada.copy(displayName = "Ada Renamed"))
        repository.syncUser("1")

        val stored = dao.entities.single()
        assertEquals("Ada Renamed", stored.displayName)
        assertEquals(avatar, stored.avatarUrl)
        assertEquals(5L, stored.version)
        assertEquals(setOf(UserField.DISPLAY_NAME), stored.locallyChanged)
    }

    /** The same run under the other policy, where the rename is discarded. */
    @Test
    fun `under last-write-wins the same rename is discarded`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))
        val avatar = "https://cdn.example.com/fake-avatar.png"
        val repository = repository(
            dao,
            dto(ada.copy(avatarUrl = avatar), version = 5),
            LastWriteWinsConflictResolver(),
        )

        repository.saveUser(ada.copy(displayName = "Ada Renamed"))
        repository.syncUser("1")

        val stored = dao.entities.single()
        assertEquals("Ada", stored.displayName)
        assertEquals(emptySet<UserField>(), stored.locallyChanged)
    }

    /**
     * Once the server's row carries the edit, the field stops being pending — which is what
     * makes the dirty set drain rather than grow for the life of the row.
     */
    @Test
    fun `a pending field clears once the server agrees`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 4)))
        val renamed = ada.copy(displayName = "Ada Renamed")
        val repository = repository(dao, dto(renamed, version = 5))

        repository.saveUser(renamed)
        repository.syncUser("1")

        val stored = dao.entities.single()
        assertEquals(emptySet<UserField>(), stored.locallyChanged)
        assertEquals(5L, stored.version)
    }

    @Test
    fun `a response identical to the stored row writes nothing`() = runTest(testDispatcher) {
        val dao = FakeUserDao(listOf(entity(version = 3)))
        val before = dao.entities.single()

        val result = repository(dao, dto(version = 3)).syncUser("1")

        assertEquals(before, dao.entities.single())
        assertEquals(ada, result.getOrNull())
    }

    private class StubUserApi(private val response: UserDto) : UserApi {
        override suspend fun getCurrentUser(): UserDto = response
        override suspend fun getUser(id: String): UserDto = response
    }
}
