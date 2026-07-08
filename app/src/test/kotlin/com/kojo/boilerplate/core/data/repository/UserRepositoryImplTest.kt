package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.data.model.User
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class UserRepositoryImplTest {

    private lateinit var repository: UserRepositoryImpl

    @Before
    fun setUp() {
        repository = UserRepositoryImpl()
    }

    @Test
    fun `getUsers returns initial seed list`() = runTest {
        val users = repository.getUsers().first()
        assertEquals(3, users.size)
        assertEquals("1", users[0].id)
        assertEquals("Alice Johnson", users[0].displayName)
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
        assertEquals("Dave Brown", users.last().displayName)
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
