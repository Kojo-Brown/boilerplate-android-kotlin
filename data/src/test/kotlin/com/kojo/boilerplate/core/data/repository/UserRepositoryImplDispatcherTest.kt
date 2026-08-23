package com.kojo.boilerplate.core.data.repository

import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.UserDto
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * That [UserRepositoryImpl] confines its work to the dispatcher it was given, rather than
 * running it on whatever called in.
 *
 * This is the test the injection is *for*. The other suites in this package would pass
 * whether or not the `flowOn` and `withContext` hops were there — Room and Retrofit dispatch
 * their own suspending work, so a repository with no threading contract at all still returns
 * the right answers, just on the caller's thread. Nothing about the *results* distinguishes
 * the two, which is why the contract went unstated for as long as it did. What distinguishes
 * them is which dispatcher the code between the library calls runs on, and a repository that
 * takes its dispatcher as a constructor parameter is one whose answer to that can be read
 * off directly.
 *
 * The mechanism is [ContinuationInterceptor]: a `CoroutineDispatcher` *is* the interceptor in
 * a coroutine's context, so a fake that records `currentCoroutineContext()[Key]` at the
 * moment it is called reports exactly which dispatcher its caller had installed. The two
 * dispatchers here share one [TestCoroutineScheduler], so there is a single virtual clock and
 * the only thing that differs between them is identity — which is the whole assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryImplDispatcherTest {

    private val scheduler = TestCoroutineScheduler()

    /** Stands in for `@IoDispatcher`; what the repository is expected to confine work to. */
    private val ioDispatcher = StandardTestDispatcher(scheduler, name = "repository-io")

    /**
     * Stands in for the caller — a `viewModelScope` collecting on the main thread. Distinct
     * from [ioDispatcher] so that "the repository hopped" and "the repository did nothing"
     * cannot produce the same observation.
     */
    private val callerDispatcher = StandardTestDispatcher(scheduler, name = "caller")

    private val entity = UserEntity(
        id = "1",
        displayName = "Ada Lovelace",
        email = "ada@example.com",
        avatarUrl = null,
    )

    private val user = User(
        id = "1",
        displayName = "Ada Lovelace",
        email = "ada@example.com",
        avatarUrl = null,
    )

    private val dto = UserDto(
        id = "1",
        displayName = "Ada Lovelace",
        email = "ada@example.com",
        avatarUrl = null,
    )

    private val dao = RecordingUserDao(entity)

    private fun repository(api: UserApi = RespondingUserApi(dto)) =
        UserRepositoryImpl(dao, api, ioDispatcher)

    @Test
    fun `getUsers runs its query and row mapping on the injected dispatcher`() =
        runTest(callerDispatcher) {
            repository().getUsers().first()

            // The DAO flow sits upstream of the repository's flowOn, and so does the
            // entity-to-domain mapping between them, so this covers both.
            assertSame(ioDispatcher, dao.observeAllContext)
        }

    @Test
    fun `getUser runs on the injected dispatcher`() = runTest(callerDispatcher) {
        repository().getUser("1").first()

        assertSame(ioDispatcher, dao.observeByIdContext)
    }

    @Test
    fun `saveUser runs its write on the injected dispatcher`() = runTest(callerDispatcher) {
        repository().saveUser(user)

        // The write landed as well as landing in the right place: `withContext` on a
        // StandardTestDispatcher only runs when the scheduler reaches it, so an unawaited
        // hop would leave this list empty rather than merely mis-dispatched.
        assertEquals(listOf(entity), dao.written)
        assertSame(ioDispatcher, dao.upsertContext)
    }

    /**
     * The [kotlinx.coroutines.NonCancellable] wrapper around the cache write replaces the
     * job and nothing else, so the write inherits the dispatcher already installed. Asserted
     * rather than assumed, because reading `withContext(NonCancellable)` as a full context
     * switch is the ordinary way to misread it — and if it were one, the write would land
     * back on the caller's thread and no other test here would notice.
     */
    @Test
    fun `syncUser writes through NonCancellable on the injected dispatcher`() =
        runTest(callerDispatcher) {
            val result = repository().syncUser("1")

            assertEquals(user, result.getOrNull())
            assertSame(ioDispatcher, dao.upsertContext)
        }

    @Test
    fun `syncCurrentUser runs on the injected dispatcher`() = runTest(callerDispatcher) {
        val result = repository().syncCurrentUser()

        assertEquals(user, result.getOrNull())
        assertSame(ioDispatcher, dao.upsertContext)
    }

    /**
     * The control. Every assertion above is only worth anything if the caller was somewhere
     * else to begin with; without this, a bug that made `callerDispatcher` and [ioDispatcher]
     * the same object would turn the whole class green.
     */
    @Test
    fun `the caller is not already on the io dispatcher`() = runTest(callerDispatcher) {
        val callerContext = currentCoroutineContext()[ContinuationInterceptor]

        assertSame(callerDispatcher, callerContext)
        assertNotSame(ioDispatcher, callerContext)
    }

    /**
     * Records the [ContinuationInterceptor] in force each time it is called. The reads are
     * `flow { }` builders rather than stored flows so that the recording happens at
     * collection time, which is the moment `flowOn` governs.
     */
    private class RecordingUserDao(private val stored: UserEntity) : UserDao {

        val written = mutableListOf<UserEntity>()

        var observeAllContext: ContinuationInterceptor? = null
            private set
        var observeByIdContext: ContinuationInterceptor? = null
            private set
        var upsertContext: ContinuationInterceptor? = null
            private set

        override fun observeAll(): Flow<List<UserEntity>> = flow {
            observeAllContext = currentCoroutineContext()[ContinuationInterceptor]
            emit(listOf(stored))
        }

        override fun observeById(id: String): Flow<UserEntity?> = flow {
            observeByIdContext = currentCoroutineContext()[ContinuationInterceptor]
            emit(stored.takeIf { it.id == id })
        }

        override suspend fun upsert(entity: UserEntity) {
            upsertContext = currentCoroutineContext()[ContinuationInterceptor]
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
}
