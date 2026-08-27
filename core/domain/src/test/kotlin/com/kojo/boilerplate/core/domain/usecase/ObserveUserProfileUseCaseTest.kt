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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

    // What the store holds

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

    /**
     * A store read that failed every attempt. This is the only failure that still travels as a
     * throw — a failed *refresh* arrives as a value — and it is what the trailing `catch` is
     * for.
     */
    @Test
    fun `a failure that survives the retries is Unavailable, carrying the cause`() = runTest {
        val boom = IOException("connection reset")
        val useCase = ObserveUserProfileUseCase(repositoryEmitting(flow { throw boom }))

        val emissions = useCase("user-1").toList()

        assertEquals(listOf(UserProfile.Unavailable(boom)), emissions)
    }

    // The refresh, which is what this use case gained when it became a network-bound resource

    /**
     * The regression test for the whole item. Before this use case was built on
     * `networkBoundResource` it observed Room and nothing else, so a profile opened from a
     * deep link rendered whatever some other screen's sync happened to have left in the
     * database — and if nothing had, it rendered [UserProfile.Missing] forever. Subscribing is
     * now what asks.
     */
    @Test
    fun `subscribing refreshes the user from the network`() = runTest {
        val refreshed = mutableListOf<String>()
        val useCase = ObserveUserProfileUseCase(
            repositoryEmitting(flowOf(alice), sync = { id ->
                refreshed += id
                Result.success(alice)
            }),
        )

        useCase("user-1").toList()

        assertEquals(listOf("user-1"), refreshed)
    }

    /**
     * Once per subscription, not once per row. The store re-emits on every write to its table
     * — Room invalidates per table — and a refresh hung off each emission would answer its own
     * write with another request.
     */
    @Test
    fun `the refresh runs once per subscription, however many rows arrive`() = runTest {
        val refreshes = AtomicInteger(0)
        val source = flowOf(alice, alice.copy(displayName = "Alice J"), alice)
        val useCase = ObserveUserProfileUseCase(
            repositoryEmitting(source, sync = {
                refreshes.incrementAndGet()
                Result.success(alice)
            }),
        )

        useCase("user-1").toList()

        assertEquals(1, refreshes.get())
    }

    /**
     * The single-source-of-truth rule, stated as an assertion: what reaches the screen is what
     * the *store* holds after the refresh, never the value the refresh returned. The fake here
     * writes one user into the store and returns a different one, and the different one is not
     * supposed to be reachable from the collector at all.
     */
    @Test
    fun `a row that only the refresh produces comes from the store, not from its return value`() =
        runTest {
            val rows = MutableStateFlow<User?>(null)
            val useCase = ObserveUserProfileUseCase(
                repositoryEmitting(rows, sync = {
                    rows.value = alice
                    Result.success(alice.copy(displayName = "never rendered"))
                }),
            )

            assertEquals(UserProfile.Loaded(alice), useCase("user-1").first())
        }

    /**
     * The offline-first decision. A refresh that fails over a row the app already has must
     * leave that row on screen: blanking a profile because the network went away is the
     * failure mode the pattern exists to remove.
     */
    @Test
    fun `a refresh that fails leaves the cached row on screen`() = runTest {
        val useCase = ObserveUserProfileUseCase(
            repositoryEmitting(flowOf(alice), sync = { Result.failure(IOException("offline")) }),
        )

        assertEquals(listOf(UserProfile.Loaded(alice)), useCase("user-1").toList())
    }

    /**
     * The other half of it: with nothing cached there is nothing to protect, so the refresh
     * failure is the only thing worth reporting and it keeps its cause.
     */
    @Test
    fun `a refresh that fails with nothing cached is Unavailable, carrying the cause`() = runTest {
        val boom = IOException("offline")
        val useCase = ObserveUserProfileUseCase(
            repositoryEmitting(flowOf(null), sync = { Result.failure(boom) }),
        )

        assertEquals(listOf(UserProfile.Unavailable(boom)), useCase("user-1").toList())
    }

    // Retry, dedupe and cancellation

    /**
     * Retry sits inside the resource's `query`, so a blip reading the store never reaches the
     * screen. Four subscriptions, not the three the retry policy allows: `networkBoundResource`
     * collects `query` twice — once for the value that accompanies the loading state, once for
     * the stream that outlives the refresh — and the counter here is shared across both. Three
     * of them are the retry (two failures plus the attempt that succeeded); the fourth is the
     * second collection, which succeeds first time.
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
        assertEquals(4, subscriptions.get())
    }

    /**
     * A 404 is not worth asking again — `isTransientFailure` says so — and the point of
     * checking here is that the use case did not quietly wrap the source in an unconditional
     * retry while extracting it. One subscription and no second collection, because the first
     * one threw before the resource had a value to emit.
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
     * byte-identical user. The dedupe is downstream of the mapping now, which is also what
     * absorbs the duplicate this pattern introduces: the same row arrives once as
     * [com.kojo.boilerplate.core.coroutines.Resource.Loading] and again as
     * [com.kojo.boilerplate.core.coroutines.Resource.Success], and only the profile they map to
     * is the same.
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
     * A refresh cancelled with its collector is not a refresh that failed. `safeCall` inside
     * `networkBoundResource` rethrows the [kotlinx.coroutines.CancellationException] rather
     * than turning it into a [com.kojo.boilerplate.core.coroutines.Resource.Failure], so
     * nothing is emitted on the way out — not even the cached row the failure path would have
     * kept showing.
     */
    @Test
    fun `cancelling during the refresh does not emit a failure`() = runTest {
        val emissions = mutableListOf<UserProfile>()
        val useCase = ObserveUserProfileUseCase(
            repositoryEmitting(flowOf(null), sync = { awaitCancellation() }),
        )

        val job = launch { useCase("user-1").collect { emissions += it } }
        testScheduler.advanceUntilIdle()
        job.cancel()
        job.join()

        assertEquals(emptyList<UserProfile>(), emissions)
        assertTrue(job.isCancelled, "the collecting job should have been cancelled")
    }

    /**
     * The use case needs `(String) -> Flow<User?>` and `(String) -> Result<User>`, and depends
     * on a six-method interface to get them — `docs/solid.md` finding 6, visible from here as
     * the four methods this double has to declare and none of these tests want. Hand-written
     * rather than `FakeUserRepository` because these cases need the source flow itself, not a
     * list of rows behind one.
     *
     * [sync] defaults to succeeding rather than to `error(...)`, because every one of these
     * subscriptions now refreshes: a default that threw would make the refresh the subject of
     * every test rather than only of the ones that ask about it.
     */
    private fun repositoryEmitting(
        source: Flow<User?>,
        sync: suspend (String) -> Result<User> = { Result.success(alice) },
    ): UserRepository = object : UserRepository {
        override fun getUser(id: String): Flow<User?> = source

        override suspend fun syncUser(id: String): Result<User> = sync(id)

        override fun getUsers(): Flow<List<User>> = error("not used by ObserveUserProfileUseCase")

        override suspend fun saveUser(user: User) = error("not used by ObserveUserProfileUseCase")

        override suspend fun syncCurrentUser(): Result<User> =
            error("not used by ObserveUserProfileUseCase")

        override suspend fun syncUsers(ids: List<String>): FanOutResult<String, User> =
            error("not used by ObserveUserProfileUseCase")
    }
}
