package com.kojo.boilerplate.feature.home

import com.kojo.boilerplate.core.coroutines.FanOutFailure
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.domain.sync.syncStrategyFactoryOver
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * The network half of the home screen — `HomeUiEvent.RefreshClicked` and what it reports —
 * separate from [HomeViewModelTest], which covers the database-backed list and the search.
 *
 * Every dispatcher here shares one scheduler, including `Dispatchers.Main`, so that the
 * coroutine the refresh launches into `viewModelScope` is on the same clock the test
 * advances. With `Main` on a scheduler of its own, `advanceUntilIdle()` would return with
 * the refresh still pending and the assertions would race it. That now covers the effect
 * channel as well: `emitEffect` sends from `viewModelScope`.
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

    /** Keeps `state` hot; the states themselves are [HomeViewModelTest]'s subject, not this one's. */
    private val rendered = mutableListOf<HomeUiState>()

    /** Everything the view model decided should happen once, in order. */
    private val effects = mutableListOf<HomeUiEffect>()

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
     * `state` is `WhileSubscribed`, so a refresh reads `HomeContent.Loading` and refreshes
     * nothing unless something is collecting. Every test here needs a subscribed view model
     * for the same reason the screen does.
     *
     * The effect collector is part of the same setup rather than opt-in per test: a `Channel`
     * buffers what nobody has taken yet, so a test that asserts "no effect was emitted"
     * without collecting would pass whether or not one was sent.
     */
    private fun TestScope.buildSubscribedViewModel(): HomeViewModel {
        val viewModel = HomeViewModel(
            userRepository = userRepository,
            refreshVisibleUsers = RefreshVisibleUsersUseCase(syncStrategyFactoryOver(userRepository)),
            defaultDispatcher = UnconfinedTestDispatcher(testScheduler),
            networkMonitor = networkMonitor,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { rendered += it }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { effects += it }
        }
        runCurrent()
        return viewModel
    }

    private fun succeedWith(users: List<User>) = FanOutResult<String, User>(
        successes = users,
        failures = emptyList(),
    )

    @Test
    fun `nothing is refreshing to begin with`() = runTest(mainDispatcher) {
        assertFalse(buildSubscribedViewModel().state.value.isRefreshing)
    }

    @Test
    fun `refresh fans out over the users currently on screen`() = runTest(mainDispatcher) {
        val requested = slot<List<String>>()
        coEvery { userRepository.syncUsers(capture(requested)) } returns succeedWith(testUsers)
        val viewModel = buildSubscribedViewModel()

        viewModel.onEvent(HomeUiEvent.RefreshClicked)
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

            viewModel.onEvent(HomeUiEvent.SearchQueryChanged("alice"))
            advanceTimeBy(SEARCH_SETTLE)
            runCurrent()
            viewModel.onEvent(HomeUiEvent.RefreshClicked)
            advanceUntilIdle()

            // Refreshing the whole table would be work the user did not ask for, on rows
            // they cannot see.
            assertEquals(listOf("1"), requested.captured)
        }

    @Test
    fun `a clean refresh reports nothing`() = runTest(mainDispatcher) {
        coEvery { userRepository.syncUsers(any()) } returns succeedWith(testUsers)
        val viewModel = buildSubscribedViewModel()

        viewModel.onEvent(HomeUiEvent.RefreshClicked)
        advanceUntilIdle()

        // The refreshed rows are already on screen — the list observes the database the
        // repository wrote them to. "Everything worked" would be a message the user has to
        // dismiss to get their screen back.
        assertEquals(emptyList<HomeUiEffect>(), effects)
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

            viewModel.onEvent(HomeUiEvent.RefreshClicked)
            advanceUntilIdle()

            assertEquals(
                listOf(HomeUiEffect.RefreshIncomplete(refreshed = 1, failed = 2)),
                effects,
            )
        }

    /**
     * The shortfall is an effect and not a field on the state, so it is delivered once. Held
     * as state it had to be cleared by hand — and a rotation with the banner still up left it
     * in place for the next composition to show again. Rule 2 of `docs/state-and-events.md`.
     */
    @Test
    fun `the shortfall is not left behind in the state`() = runTest(mainDispatcher) {
        coEvery { userRepository.syncUsers(any()) } returns FanOutResult(
            successes = emptyList(),
            failures = listOf(FanOutFailure("1", IllegalStateException("boom"))),
        )
        val viewModel = buildSubscribedViewModel()

        viewModel.onEvent(HomeUiEvent.RefreshClicked)
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun `isRefreshing stays true until the fan-out completes`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { userRepository.syncUsers(any()) } coAnswers {
            gate.await()
            succeedWith(testUsers)
        }
        val viewModel = buildSubscribedViewModel()

        viewModel.onEvent(HomeUiEvent.RefreshClicked)
        runCurrent()
        assertTrue(viewModel.state.value.isRefreshing)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun `a second refresh while one is in flight is dropped`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { userRepository.syncUsers(any()) } coAnswers {
            gate.await()
            succeedWith(testUsers)
        }
        val viewModel = buildSubscribedViewModel()

        viewModel.onEvent(HomeUiEvent.RefreshClicked)
        runCurrent()
        viewModel.onEvent(HomeUiEvent.RefreshClicked)
        viewModel.onEvent(HomeUiEvent.RefreshClicked)
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

        viewModel.onEvent(HomeUiEvent.RefreshClicked)
        advanceUntilIdle()
        viewModel.onEvent(HomeUiEvent.RefreshClicked)
        advanceUntilIdle()

        // The in-flight guard must not latch.
        coVerify(exactly = 2) { userRepository.syncUsers(any()) }
    }

    private companion object {
        /** Comfortably past the 300ms search debounce, so the filtered list has settled. */
        val SEARCH_SETTLE: Duration = 400.milliseconds
    }
}
