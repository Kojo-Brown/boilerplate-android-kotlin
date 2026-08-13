package com.kojo.boilerplate.feature.home

import com.kojo.boilerplate.core.coroutines.FanOutFailure
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.domain.usecase.RefreshVisibleUsersUseCase
import com.kojo.boilerplate.core.network.connectivity.FakeNetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * `HomeViewModel.refresh()` — the network half of the screen, separate from
 * [HomeViewModelTest] which covers the database-backed list and the search.
 *
 * Every dispatcher here shares one scheduler, including `Dispatchers.Main`, so that the
 * coroutine `refresh()` launches into `viewModelScope` is on the same clock the test
 * advances. With `Main` on a scheduler of its own, `advanceUntilIdle()` would return with
 * the refresh still pending and the assertions would race it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class HomeViewModelRefreshTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension(mainDispatcher)

    @MockK
    lateinit var userRepository: UserRepository

    private val networkMonitor = FakeNetworkMonitor()

    /** Keeps `uiState` hot; the states themselves are [HomeViewModelTest]'s subject, not this one's. */
    private val rendered = mutableListOf<HomeUiState>()

    private val testUsers = listOf(
        User(id = "1", displayName = "Alice Johnson", email = "alice@example.com"),
        User(id = "2", displayName = "Bob Smith", email = "bob@example.com"),
        User(id = "3", displayName = "Carol White", email = "carol@example.com"),
    )

    @BeforeEach
    fun setUp() {
        every { userRepository.getUsers() } returns flowOf(testUsers)
    }

    /**
     * uiState is `WhileSubscribed`, so `refresh()` reads `HomeUiState.Loading` and refreshes
     * nothing unless something is collecting. Every test here needs a subscribed view model
     * for the same reason the screen does.
     */
    private fun TestScope.buildSubscribedViewModel(): HomeViewModel {
        val viewModel = HomeViewModel(
            userRepository = userRepository,
            refreshVisibleUsers = RefreshVisibleUsersUseCase(userRepository),
            defaultDispatcher = UnconfinedTestDispatcher(testScheduler),
            networkMonitor = networkMonitor,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { rendered += it }
        }
        runCurrent()
        return viewModel
    }

    private fun succeedWith(users: List<User>) = FanOutResult<String, User>(
        successes = users,
        failures = emptyList(),
    )

    @Test
    fun `refresh state starts Idle`() = runTest(mainDispatcher) {
        assertEquals(RefreshState.Idle, buildSubscribedViewModel().refreshState.value)
    }

    @Test
    fun `refresh fans out over the users currently on screen`() = runTest(mainDispatcher) {
        val requested = slot<List<String>>()
        coEvery { userRepository.syncUsers(capture(requested)) } returns succeedWith(testUsers)
        val viewModel = buildSubscribedViewModel()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf("1", "2", "3"), requested.captured)
    }

    @Test
    fun `refresh covers only the filtered users when a search is active`() =
        runTest(mainDispatcher) {
            val requested = slot<List<String>>()
            coEvery { userRepository.syncUsers(capture(requested)) } returns
                succeedWith(listOf(testUsers[0]))
            val viewModel = buildSubscribedViewModel()

            viewModel.updateSearchQuery("alice")
            advanceTimeBy(SEARCH_SETTLE)
            runCurrent()
            viewModel.refresh()
            advanceUntilIdle()

            // Refreshing the whole table would be work the user did not ask for, on rows
            // they cannot see.
            assertEquals(listOf("1"), requested.captured)
        }

    @Test
    fun `refresh reports every user when the fan-out is clean`() = runTest(mainDispatcher) {
        coEvery { userRepository.syncUsers(any()) } returns succeedWith(testUsers)
        val viewModel = buildSubscribedViewModel()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(RefreshState.Finished(refreshed = 3, failed = 0), viewModel.refreshState.value)
    }

    @Test
    fun `refresh reports the shortfall when part of the fan-out fails`() =
        runTest(mainDispatcher) {
            coEvery { userRepository.syncUsers(any()) } returns FanOutResult(
                successes = listOf(testUsers[0]),
                failures = listOf(
                    FanOutFailure("2", IllegalStateException("boom")),
                    FanOutFailure("3", IllegalStateException("boom")),
                ),
            )
            val viewModel = buildSubscribedViewModel()

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                RefreshState.Finished(refreshed = 1, failed = 2),
                viewModel.refreshState.value,
            )
        }

    @Test
    fun `refresh is InProgress until the fan-out completes`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { userRepository.syncUsers(any()) } coAnswers {
            gate.await()
            succeedWith(testUsers)
        }
        val viewModel = buildSubscribedViewModel()

        viewModel.refresh()
        runCurrent()
        assertEquals(RefreshState.InProgress, viewModel.refreshState.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.refreshState.value is RefreshState.Finished)
    }

    @Test
    fun `a second refresh while one is in flight is dropped`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { userRepository.syncUsers(any()) } coAnswers {
            gate.await()
            succeedWith(testUsers)
        }
        val viewModel = buildSubscribedViewModel()

        viewModel.refresh()
        runCurrent()
        viewModel.refresh()
        viewModel.refresh()
        gate.complete(Unit)
        advanceUntilIdle()

        // Without the claim in refresh() this is three fan-outs over the same ids, racing
        // each other to write the result.
        coVerify(exactly = 1) { userRepository.syncUsers(any()) }
    }

    @Test
    fun `a refresh after the previous one finished runs again`() = runTest(mainDispatcher) {
        coEvery { userRepository.syncUsers(any()) } returns succeedWith(testUsers)
        val viewModel = buildSubscribedViewModel()

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        // The in-flight guard must not latch: Finished is a claimable state.
        coVerify(exactly = 2) { userRepository.syncUsers(any()) }
    }

    @Test
    fun `dismissRefreshResult returns the screen to Idle`() = runTest(mainDispatcher) {
        coEvery { userRepository.syncUsers(any()) } returns succeedWith(testUsers)
        val viewModel = buildSubscribedViewModel()

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.dismissRefreshResult()

        assertEquals(RefreshState.Idle, viewModel.refreshState.value)
    }

    private companion object {
        /** Comfortably past the 300ms search debounce, so the filtered list has settled. */
        val SEARCH_SETTLE: Duration = 400.milliseconds
    }
}
