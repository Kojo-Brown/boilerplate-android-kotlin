package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.model.UserProfile
import com.kojo.boilerplate.core.domain.repository.UserRepository
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * These run on a plain JVM with no Robolectric, no `Dispatchers.Main` substitute and no
 * `ViewModel`, which is the practical argument for the layer existing at all — the same
 * policy was previously reachable only through two `ViewModel`s that each needed all three.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveUserProfileUseCaseTest {

    private val alice = User(
        id = "user-1",
        displayName = "Alice Johnson",
        email = "alice@example.com",
        avatarUrl = null,
    )

    @Test
    fun `a row that exists is Loaded`() = runTest {
        val useCase = ObserveUserProfileUseCase(repositoryEmitting(flowOf(alice)))

        assertEquals(listOf(UserProfile.Loaded(alice)), useCase("user-1").toList())
    }

    /**
     * The finding-1 decision itself: a query that succeeds and finds nothing is a distinct
     * outcome, not an empty success. Both profile screens render it as an error, and this is
     * the single place that now says so.
     */
    @Test
    fun `a row that does not exist is Missing, carrying the id that was asked for`() = runTest {
        val useCase = ObserveUserProfileUseCase(repositoryEmitting(flowOf(null)))

        assertEquals(listOf(UserProfile.Missing("ghost-1")), useCase("ghost-1").toList())
    }

    @Test
    fun `a failure that survives the retries is Unavailable, carrying the cause`() = runTest {
        val boom = IOException("connection reset")
        val useCase = ObserveUserProfileUseCase(repositoryEmitting(flow { throw boom }))

        val emissions = useCase("user-1").toList()

        assertEquals(listOf(UserProfile.Unavailable(boom)), emissions)
    }

    /**
     * Retry sits inside the use case, so a blip never reaches the screen. Four subscriptions
     * is `retryWithBackoff`'s default of three retries plus the original attempt, and the
     * `delay` between them is virtual time here rather than three real seconds.
     */
    @Test
    fun `a transient failure is retried and never reaches the caller`() = runTest {
        val subscriptions = AtomicInteger(0)
        val source = flow {
            if (subscriptions.incrementAndGet() < 3) throw IOException("blip")
            emit(alice)
        }

        val emissions = ObserveUserProfileUseCase(repositoryEmitting(source))("user-1").toList()

        assertEquals(listOf(UserProfile.Loaded(alice)), emissions)
        assertEquals(3, subscriptions.get())
    }

    /**
     * A 404 is not worth asking again — `isTransientFailure` says so — and the point of
     * checking here is that the use case did not quietly wrap the source in an unconditional
     * retry while extracting it.
     */
    @Test
    fun `a non-transient failure is not retried`() = runTest {
        val subscriptions = AtomicInteger(0)
        val source = flow<User?> {
            subscriptions.incrementAndGet()
            error("malformed row")
        }

        val emissions = ObserveUserProfileUseCase(repositoryEmitting(source))("user-1").toList()

        assertEquals(1, subscriptions.get())
        assertInstanceOf(UserProfile.Unavailable::class.java, emissions.single())
    }

    /**
     * Room invalidates per table rather than per row, so an unrelated write re-delivers a
     * byte-identical user. `distinctUntilChanged` sits upstream of the mapping so the
     * duplicate is dropped before a [UserProfile] is allocated for it.
     */
    @Test
    fun `an unchanged row re-emitted upstream is not re-emitted downstream`() = runTest {
        val source = flowOf(alice, alice, alice.copy(displayName = "Alice J"), alice)

        val emissions = ObserveUserProfileUseCase(repositoryEmitting(source))("user-1").toList()

        assertEquals(
            listOf(
                UserProfile.Loaded(alice),
                UserProfile.Loaded(alice.copy(displayName = "Alice J")),
                UserProfile.Loaded(alice),
            ),
            emissions,
        )
    }

    /**
     * Cancelling the collector must stay cancellation. Reporting it as [UserProfile.Unavailable]
     * would hand a screen the user has already left a tidy error to render, and the parent job
     * would never see the cancellation it is waiting for.
     */
    @Test
    fun `cancelling the collector does not emit Unavailable`() = runTest {
        val emissions = mutableListOf<UserProfile>()
        val neverCompletes = flow<User?> {
            emit(alice)
            awaitCancellation()
        }

        val job = launch {
            ObserveUserProfileUseCase(repositoryEmitting(neverCompletes))("user-1")
                .collect { emissions += it }
        }
        testScheduler.advanceUntilIdle()
        job.cancel()
        job.join()

        assertEquals(listOf(UserProfile.Loaded(alice)), emissions)
        assertTrue(job.isCancelled, "the collecting job should have been cancelled")
    }

    /**
     * The use case needs `(String) -> Flow<User?>` and depends on a six-method interface to
     * get it — `docs/solid.md` finding 6, visible from here as the five methods this double
     * has to declare and none of these tests want. Hand-written rather than `FakeUserRepository`
     * because these cases need the source flow itself, not a list of rows behind one.
     */
    private fun repositoryEmitting(source: Flow<User?>): UserRepository = object : UserRepository {
        override fun getUser(id: String): Flow<User?> = source

        override fun getUsers(): Flow<List<User>> = error("not used by ObserveUserProfileUseCase")

        override suspend fun saveUser(user: User) = error("not used by ObserveUserProfileUseCase")

        override suspend fun syncCurrentUser(): Result<User> =
            error("not used by ObserveUserProfileUseCase")

        override suspend fun syncUser(id: String): Result<User> =
            error("not used by ObserveUserProfileUseCase")

        override suspend fun syncUsers(ids: List<String>): FanOutResult<String, User> =
            error("not used by ObserveUserProfileUseCase")
    }
}
