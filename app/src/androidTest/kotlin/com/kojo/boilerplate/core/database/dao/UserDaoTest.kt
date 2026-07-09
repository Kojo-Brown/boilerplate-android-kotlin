package com.kojo.boilerplate.core.database.dao

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kojo.boilerplate.core.database.AppDatabase
import com.kojo.boilerplate.core.database.entity.UserEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class UserDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var userDao: UserDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        userDao = database.userDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertAndObserveAll() = runTest {
        val entity = UserEntity(id = "1", displayName = "Alice", email = "alice@example.com", avatarUrl = null)
        userDao.upsert(entity)
        val users = userDao.observeAll().first()
        assertEquals(1, users.size)
        assertEquals("Alice", users[0].displayName)
    }

    @Test
    fun observeByIdReturnsEntity() = runTest {
        val entity = UserEntity(id = "42", displayName = "Test User", email = "test@example.com", avatarUrl = null)
        userDao.upsert(entity)
        val result = userDao.observeById("42").first()
        assertEquals("Test User", result?.displayName)
    }

    @Test
    fun observeByIdReturnsNullForMissing() = runTest {
        val result = userDao.observeById("nonexistent").first()
        assertNull(result)
    }

    @Test
    fun upsertUpdatesExistingEntity() = runTest {
        val entity = UserEntity(id = "1", displayName = "Alice", email = "alice@example.com", avatarUrl = null)
        userDao.upsert(entity)
        val updated = entity.copy(displayName = "Alice Updated")
        userDao.upsert(updated)
        val users = userDao.observeAll().first()
        assertEquals(1, users.size)
        assertEquals("Alice Updated", users[0].displayName)
    }

    @Test
    fun deleteRemovesEntity() = runTest {
        val entity = UserEntity(id = "1", displayName = "Alice", email = "alice@example.com", avatarUrl = null)
        userDao.upsert(entity)
        userDao.delete(entity)
        val users = userDao.observeAll().first()
        assertEquals(0, users.size)
    }
}
