package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.database.dao.FakeUserDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.network.api.UserApi
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryImplTest {

    private lateinit var userDao: FakeUserDao
    private lateinit var repository: UserRepositoryImpl

    /**
     * The repository's I/O dispatcher for every case below, and the clock `runTest` runs on:
     * passing it to `runTest` makes the test scheduler the one this dispatcher queues to, so
     * the repository's `withContext` and `flowOn` hops are work the test drives rather than
     * work it races. `StandardTestDispatcher` and not `UnconfinedTestDispatcher` on purpose —
     * unconfined runs each hop eagerly on the calling thread, which would let a missing
     * `flowOn` pass just as happily as a present one and defeat the point of injecting it.
     */
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        userDao = FakeUserDao(
            initialEntities = listOf(
                UserEntity(id = "1", displayName = "Alice Johnson", email = "alice@example.com", avatarUrl = null),
                UserEntity(id = "2", displayName = "Bob Smith", email = "bob@example.com", avatarUrl = null),
                UserEntity(id = "3", displayName = "Carol White", email = "carol@example.com", avatarUrl = null),
            ),
        )
        // Deliberately an unstubbed strict mock. Every case in this class exercises
        // the DAO-backed reads and writes, none of which touch the network, so any
        // call that reaches UserApi here means the test drifted onto the sync path
        // and should fail loudly rather than quietly succeed against a fake.
        // The network path has its own coverage in UserRepositoryImplSyncTest,
        // which drives a real Retrofit client against MockWebServer.
        repository = UserRepositoryImpl(userDao, mockk<UserApi>(), testDispatcher)
    }

    @Test
    fun `getUsers returns mapped domain users`() = runTest(testDispatcher) {
        val users = repository.getUsers().first()
        assertEquals(3, users.size)
    }

    @Test
    fun `getUser returns correct user by id`() = runTest(testDispatcher) {
        val user = repository.getUser("2").first()
        assertEquals("Bob Smith", user?.displayName)
        assertEquals("bob@example.com", user?.email)
    }

    @Test
    fun `getUser returns null for unknown id`() = runTest(testDispatcher) {
        val user = repository.getUser("999").first()
        assertNull(user)
    }

    @Test
    fun `saveUser adds new user when id not present`() = runTest(testDispatcher) {
        val newUser = User(id = "4", displayName = "Dave Brown", email = "dave@example.com")
        repository.saveUser(newUser)
        val users = repository.getUsers().first()
        assertEquals(4, users.size)
    }

    @Test
    fun `saveUser updates existing user when id matches`() = runTest(testDispatcher) {
        val updated = User(id = "1", displayName = "Alice Updated", email = "alice-new@example.com")
        repository.saveUser(updated)
        val user = repository.getUser("1").first()
        assertEquals("Alice Updated", user?.displayName)
        assertEquals("alice-new@example.com", user?.email)
        assertEquals(3, repository.getUsers().first().size)
    }
}
