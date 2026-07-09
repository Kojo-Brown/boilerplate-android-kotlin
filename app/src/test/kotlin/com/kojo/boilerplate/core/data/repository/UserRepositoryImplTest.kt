package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.database.dao.FakeUserDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class UserRepositoryImplTest {

    private lateinit var userDao: FakeUserDao
    private lateinit var repository: UserRepositoryImpl

    @Before
    fun setUp() {
        userDao = FakeUserDao(
            initialEntities = listOf(
                UserEntity(id = "1", displayName = "Alice Johnson", email = "alice@example.com", avatarUrl = null),
                UserEntity(id = "2", displayName = "Bob Smith", email = "bob@example.com", avatarUrl = null),
                UserEntity(id = "3", displayName = "Carol White", email = "carol@example.com", avatarUrl = null),
            ),
        )
        repository = UserRepositoryImpl(userDao)
    }

    @Test
    fun `getUsers returns mapped domain users`() = runTest {
        val users = repository.getUsers().first()
        assertEquals(3, users.size)
    }

    @Test
    fun `getUser returns correct user by id`() = runTest {
        val user = repository.getUser("2").first()
        assertEquals("Bob Smith", user?.displayName)
        assertEquals("bob@example.com", user?.email)
    }

    @Test
    fun `getUser returns null for unknown id`() = runTest {
        val user = repository.getUser("999").first()
        assertNull(user)
    }

    @Test
    fun `saveUser adds new user when id not present`() = runTest {
        val newUser = User(id = "4", displayName = "Dave Brown", email = "dave@example.com")
        repository.saveUser(newUser)
        val users = repository.getUsers().first()
        assertEquals(4, users.size)
    }

    @Test
    fun `saveUser updates existing user when id matches`() = runTest {
        val updated = User(id = "1", displayName = "Alice Updated", email = "alice-new@example.com")
        repository.saveUser(updated)
        val user = repository.getUser("1").first()
        assertEquals("Alice Updated", user?.displayName)
        assertEquals("alice-new@example.com", user?.email)
        assertEquals(3, repository.getUsers().first().size)
    }
}
