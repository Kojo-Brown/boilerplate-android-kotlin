package com.kojo.boilerplate.feature.home

import com.kojo.boilerplate.core.coroutines.FanOutFailure
import com.kojo.boilerplate.core.coroutines.FanOutResult
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.repository.UserRepository
import com.kojo.boilerplate.core.domain.usecase.RefreshVisibleUsersUseCase
import com.kojo.boilerplate.core.testing.FakeNetworkMonitor
import com.kojo.boilerplate.core.testing.MainDispatcherExtension
import com.kojo.boilerplate.core.testing.syncStrategyFactoryOver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * The network half of the home screen — `HomeUiEvent.RefreshClicked` and what it reports —
 * separate from [HomeViewModelTest], which covers the paged list and the search.
 *
 * Every dispatcher here shares one scheduler, including `Dispatchers.Main`, so that the
 * coroutine the refresh launches into `viewModelScope` is on the same clock the test
 * advances. With `Main` on a scheduler of its own, `advanceUntilIdle()` would return with
 * the refresh still pending and the assertions would race it. That covers the effect
 * channel as well: `emitEffect` sends from `viewModelScope`.
 *
 * ### The ids are given rather than read
 *
 * They used to come out of `state.value.content`, and half of this file was about keeping a
 * collector alive so that there was a content to read. Under paging the rows on screen are a
 * fact about the `LazyListState`, so they arrive on the event — see `HomeUiEvent.RefreshClicked`
 * — and the tests that used to establish a filtered list before refreshing now just pass the
 * ids. What the view model still owns, and what is asserted here, is the in-flight lock and the
 * decision to report only a shortfall.
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

    /** Everything the view model decided should happen once, in order. */
    private val effects = mutableListOf<HomeUiEffect>()

    private val testUsers = listOf(
        User(id = "1", displayName = "Alice Johnson", email = "alice@example.com"),
        User(id = "2", displayName = "Bob Smith", email = "bob@example.com"),
        User(id = "3", displayName = "Carol White", email = "carol@example.com"),
    )

    private val visibleIds = testUsers.map { it.id }

    /**
     * `state` is `WhileSubscribed`, so `isRefreshing` read with nothing collecting is the
     * initial value however long the view model has been alive. Every assertion about the flag
     * needs a subscribed view model for the same reason the screen is one.
     *
     * The effect collector is part of the same setup rather than opt-in per test: a `Channel`
     * buffers what nobody has taken yet, so a test that asserts "no effect was emitted"
     * without collecting would pass whether or not one was sent.
     *
     * The pages are deliberately *not* collected. Nothing in a refresh touches them — which is
     * itself the point of the redesign, and would have been impossible to say before.
     */
    private fun TestScope.buildSubscribedViewModel(): HomeViewModel {
        val viewModel = HomeViewModel(
            pagedUsers = FakePagedUserRepository(testUsers),
            refreshVisibleUsers = RefreshVisibleUsersUseCase(syncStrategyFactoryOver(userRepository)),
            networkMonitor = networkMonitor,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { }
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
    fun `refresh fans out over the ids the screen supplied`() = runTest(mainDispatcher) {
        val requested = slot<List<String>>()
        coEvery { userRepository.syncUsers(capture(requested)) } returns succeedWith(testUsers)
        val viewModel = buildSubscribedViewModel()

        viewModel.onEvent(HomeUiEvent.RefreshClicked(visibleIds))
        advanceUntilIdle()

        assertEquals(visibleIds, requested.captured)
    }

    @Test
    fun `refresh covers the viewport and not every page loaded`() = runTest(mainDispatcher) {
        val requested = slot<List<String>>()
        coEvery { userRepository.syncUsers(capture(requested)) } returns
            succeedWith(listOf(testUsers[0]))
        val viewModel = buildSubscribedViewModel()

        // What `LazyListState.layoutInfo.visibleItemsInfo` yields: the rows laid out, not the
        // pages behind them. The fan-out makes one request per id, so a refresh defined over
        // everything loaded would grow with the scroll — hundreds of requests to update the
        // dozen rows in front of the reader.
        viewModel.onEvent(HomeUiEvent.RefreshClicked(listOf("1")))
        advanceUntilIdle()

        assertEquals(listOf("1"), requested.captured)
    }

    @Test
    fun `an empty viewport refreshes nothing`() = runTest(mainDispatcher) {
        val viewModel = buildSubscribedViewModel()

        // A list still loading, or one a search emptied. The use case skips the fan-out
        // entirely rather than opening a scope to discover there is nothing in it.
        viewModel.onEvent(HomeUiEvent.RefreshClicked(emptyList()))
        advanceUntilIdle()

        coVerify(exactly = 0) { userRepository.syncUsers(any()) }
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun `a clean refresh reports nothing`() = runTest(mainDispatcher) {
        coEvery { userRepository.syncUsers(any()) } returns succeedWith(testUsers)
        val viewModel = buildSubscribedViewModel()

        viewModel.onEvent(HomeUiEvent.RefreshClicked(visibleIds))
        advanceUntilIdle()

        // The refreshed rows are already on screen — Room invalidates the `PagingSource` the
        // repository wrote them through, so Paging re-presents them with nothing subscribing
        // the list to anything. "Everything worked" would be a message the user has to dismiss
        // to get their screen back.
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

            viewModel.onEvent(HomeUiEvent.RefreshClicked(visibleIds))
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

        viewModel.onEvent(HomeUiEvent.RefreshClicked(listOf("1")))
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

        viewModel.onEvent(HomeUiEvent.RefreshClicked(visibleIds))
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

        viewModel.onEvent(HomeUiEvent.RefreshClicked(visibleIds))
        runCurrent()
        viewModel.onEvent(HomeUiEvent.RefreshClicked(visibleIds))
        viewModel.onEvent(HomeUiEvent.RefreshClicked(visibleIds))
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

        viewModel.onEvent(HomeUiEvent.RefreshClicked(visibleIds))
        advanceUntilIdle()
        viewModel.onEvent(HomeUiEvent.RefreshClicked(visibleIds))
        advanceUntilIdle()

        // The in-flight guard must not latch.
        coVerify(exactly = 2) { userRepository.syncUsers(any()) }
    }
}
