package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.UserDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cancellation contract of [UserRepositoryImpl]: an in-flight request is abandoned when
 * the caller goes away, but a response that has already arrived is committed to the cache
 * rather than dropped half-way through the write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryImplCancellationTest {

    private val dto = UserDto(
        id = "1",
        displayName = "Ada Lovelace",
        email = "ada@example.com",
        avatarUrl = null,
    )

    private val entity = UserEntity(
        id = "1",
        displayName = "Ada Lovelace",
        email = "ada@example.com",
        avatarUrl = null,
    )

    /**
     * Unconfined, unlike the other repository suites, because these cases choreograph a
     * cancellation against a write that is already in flight. The gates below
     * ([CompletableDeferred] on both sides of `upsert`) are what order this test, and an
     * unconfined dispatcher keeps the repository's `withContext` hop from inserting a
     * scheduler round trip between completing a gate and observing its effect. Sharing the
     * test's scheduler still keeps `delay` on virtual time.
     */
    private fun TestScope.ioDispatcher() = UnconfinedTestDispatcher(testScheduler)

    @Test
    fun `syncUser completes the cache write after the caller is cancelled`() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val userDao = GatedUserDao(writeStarted, releaseWrite)
        val repository = UserRepositoryImpl(userDao, RespondingUserApi(dto), ioDispatcher())

        val job = launch { repository.syncUser("1") }
        writeStarted.await()
        job.cancel()
        // The write is suspended inside NonCancellable, so cancelling did not resume it
        // with a CancellationException the way it would in an ordinary coroutine.
        releaseWrite.complete(Unit)
        job.join()

        assertEquals(listOf(entity), userDao.written)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `syncCurrentUser completes the cache write after the caller is cancelled`() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val userDao = GatedUserDao(writeStarted, releaseWrite)
        val repository = UserRepositoryImpl(userDao, RespondingUserApi(dto), ioDispatcher())

        val job = launch { repository.syncCurrentUser() }
        writeStarted.await()
        job.cancel()
        releaseWrite.complete(Unit)
        job.join()

        assertEquals(listOf(entity), userDao.written)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `syncUser abandons a request that is still in flight when the caller is cancelled`() =
        runTest {
            val requestStarted = CompletableDeferred<Unit>()
            val userDao = GatedUserDao(CompletableDeferred(), CompletableDeferred(Unit))
            val repository = UserRepositoryImpl(userDao, HangingUserApi(requestStarted), ioDispatcher())

            val job = launch { repository.syncUser("1") }
            requestStarted.await()
            job.cancelAndJoin()

            // Nothing was fetched, so there is nothing to commit: cancellation still means
            // cancellation everywhere the response has not arrived yet.
            assertTrue(userDao.written.isEmpty())
            assertTrue(job.isCancelled)
        }

    @Test
    fun `syncUser caches the fetched user when it is not cancelled`() = runTest {
        val userDao = GatedUserDao(CompletableDeferred(), CompletableDeferred(Unit))
        val repository = UserRepositoryImpl(userDao, RespondingUserApi(dto), ioDispatcher())

        val result = repository.syncUser("1")

        assertTrue(result.isSuccess)
        assertEquals(entity, userDao.written.single())
    }

    /**
     * A [UserDao] whose write suspends until [releaseWrite] completes, so a test can
     * cancel the caller at the exact point where the response is in hand but the cache
     * write has not finished.
     */
    private class GatedUserDao(
        private val writeStarted: CompletableDeferred<Unit>,
        private val releaseWrite: CompletableDeferred<Unit>,
    ) : UserDao {

        val written = mutableListOf<UserEntity>()

        override fun observeAll(): Flow<List<UserEntity>> = flowOf(written.toList())

        override fun observeById(id: String): Flow<UserEntity?> =
            flowOf(written.firstOrNull { it.id == id })

        override suspend fun upsert(entity: UserEntity) {
            writeStarted.complete(Unit)
            releaseWrite.await()
            written += entity
        }

        override suspend fun delete(entity: UserEntity) {
            written.removeAll { it.id == entity.id }
        }
    }

    private class RespondingUserApi(private val dto: UserDto) : UserApi {
        override suspend fun getCurrentUser(): UserDto = dto
        override suspend fun getUser(id: String): UserDto = dto
    }

    private class HangingUserApi(
        private val requestStarted: CompletableDeferred<Unit>,
    ) : UserApi {
        override suspend fun getCurrentUser(): UserDto = hang()
        override suspend fun getUser(id: String): UserDto = hang()

        private suspend fun hang(): Nothing {
            requestStarted.complete(Unit)
            awaitCancellation()
        }
    }
}
