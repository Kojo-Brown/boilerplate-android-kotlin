package com.kojo.boilerplate.feature.home

import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.domain.usecase.RefreshVisibleUsersUseCase
import com.kojo.boilerplate.core.network.connectivity.FakeNetworkMonitor
import com.kojo.boilerplate.core.network.connectivity.NetworkStatus
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class HomeViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension()

    @MockK
    lateinit var userRepository: UserRepository

    private val testUsers = listOf(
        User(id = "1", displayName = "Alice Johnson", email = "alice@example.com"),
        User(id = "2", displayName = "Bob Smith", email = "bob@example.com"),
        User(id = "3", displayName = "Carol White", email = "carol@example.com"),
    )

    @BeforeEach
    fun setUp() {
        every { userRepository.getUsers() } returns flowOf(testUsers)
    }

    private val networkMonitor = FakeNetworkMonitor()

    private fun buildViewModel() = HomeViewModel(
        userRepository = userRepository,
        refreshVisibleUsers = RefreshVisibleUsersUseCase(userRepository),
        defaultDispatcher = UnconfinedTestDispatcher(),
        networkMonitor = networkMonitor,
    )

    /**
     * uiState is built with stateIn(..., SharingStarted.WhileSubscribed), so its upstream
     * does not run until something collects it. Reading .value with no subscriber returns
     * the initial Loading value forever — which is why every assertion below used to fail
     * with ClassCastException or "expected Success". Collecting on backgroundScope keeps
     * the state hot for the test and runTest tears it down automatically.
     *
     * Both dispatchers are pinned to the test's own scheduler so there is a single clock —
     * the search debounce and the retry backoff are both `delay()` upstream of the view
     * model's own `flowOn`, so they only stay on virtual time while that holds.
     *
     * [states] records every state the UI would render, which is what the debounce assertions
     * are about: `uiState` is a StateFlow and conflates equal consecutive values, so the
     * intermediate states have to be counted as they go past rather than reconstructed after.
     */
    private fun TestScope.buildSubscribedViewModel(
        states: MutableList<HomeUiState> = mutableListOf(),
    ): HomeViewModel {
        val viewModel = HomeViewModel(
            userRepository = userRepository,
            refreshVisibleUsers = RefreshVisibleUsersUseCase(userRepository),
            defaultDispatcher = UnconfinedTestDispatcher(testScheduler),
            networkMonitor = networkMonitor,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { states += it }
        }
        runCurrent()
        return viewModel
    }

    /** Types [query] and lets the search debounce elapse. */
    private fun TestScope.enterQuery(viewModel: HomeViewModel, query: String) {
        viewModel.updateSearchQuery(query)
        advanceTimeBy(SETTLE)
        runCurrent()
    }

    private fun HomeViewModel.successItemCount(): Int =
        (uiState.value as HomeUiState.Success).items.size

    @Test
    fun `uiState initial value is Loading`() {
        assertEquals(HomeUiState.Loading, buildViewModel().uiState.value)
    }

    @Test
    fun `uiState emits Success with all users when search query is empty`() = runTest {
        val viewModel = buildSubscribedViewModel()
        val state = viewModel.uiState.value

        assertTrue(state is HomeUiState.Success)
        val success = state as HomeUiState.Success
        assertEquals(3, success.items.size)
        assertEquals("Alice Johnson", success.items[0].title)
        assertEquals("alice@example.com", success.items[0].description)
    }

    @Test
    fun `updateSearchQuery filters users by display name`() = runTest {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "alice")

        val success = viewModel.uiState.value as HomeUiState.Success
        assertEquals(1, success.items.size)
        assertEquals("Alice Johnson", success.items[0].title)
    }

    @Test
    fun `updateSearchQuery filters users by email`() = runTest {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "bob@")

        val success = viewModel.uiState.value as HomeUiState.Success
        assertEquals(1, success.items.size)
        assertEquals("Bob Smith", success.items[0].title)
    }

    @Test
    fun `updateSearchQuery is case insensitive`() = runTest {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "CAROL")

        val success = viewModel.uiState.value as HomeUiState.Success
        assertEquals(1, success.items.size)
        assertEquals("Carol White", success.items[0].title)
    }

    @Test
    fun `updateSearchQuery returns empty list when no match`() = runTest {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "xyz-no-match")

        assertEquals(0, viewModel.successItemCount())
    }

    @Test
    fun `clearing search query restores full list`() = runTest {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "alice")
        enterQuery(viewModel, "")

        assertEquals(3, viewModel.successItemCount())
    }

    @Test
    fun `searchQuery exposes every keystroke without waiting for the debounce`() = runTest {
        val viewModel = buildSubscribedViewModel()

        viewModel.updateSearchQuery("ali")

        // The text field is bound to this. Debouncing it would make typing feel broken.
        assertEquals("ali", viewModel.searchQuery.value)
    }

    @Test
    fun `keystrokes typed within the debounce window produce a single filtered state`() = runTest {
        val states = mutableListOf<HomeUiState>()
        val viewModel = buildSubscribedViewModel(states)

        listOf("a", "al", "ali", "alic", "alice").forEach { keystroke ->
            viewModel.updateSearchQuery(keystroke)
            advanceTimeBy(TYPING_GAP)
        }
        advanceTimeBy(SETTLE)
        runCurrent()

        // Without the debounce every prefix would filter the list and render: "a" alone
        // matches Alice and Carol, so the intermediate states are visibly different.
        assertEquals(
            listOf(3, 1),
            states.filterIsInstance<HomeUiState.Success>().map { it.items.size },
        )
    }

    @Test
    fun `clearing the query is not held back by the debounce`() = runTest {
        val viewModel = buildSubscribedViewModel()
        enterQuery(viewModel, "alice")
        val clearedAt = currentTime

        viewModel.updateSearchQuery("")
        runCurrent()

        assertEquals(3, viewModel.successItemCount())
        assertEquals(clearedAt, currentTime)
    }

    @Test
    fun `uiState reflects repository updates reactively`() = runTest {
        val usersFlow = MutableStateFlow(testUsers)
        every { userRepository.getUsers() } returns usersFlow
        val viewModel = buildSubscribedViewModel()

        assertTrue(viewModel.uiState.value is HomeUiState.Success)

        val newUser = User(id = "4", displayName = "Dave Brown", email = "dave@example.com")
        usersFlow.value = testUsers + newUser

        assertEquals(4, viewModel.successItemCount())
    }

    @Test
    fun `a transient repository failure recovers without the user tapping retry`() = runTest {
        var subscriptions = 0
        every { userRepository.getUsers() } returns flow {
            subscriptions++
            if (subscriptions == 1) throw IOException("connection reset")
            emit(testUsers)
        }

        val viewModel = buildSubscribedViewModel()

        // Still Loading rather than Error: the backoff has not elapsed, so the failure has
        // not been shown to anyone yet.
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)

        advanceUntilIdle()

        assertEquals(3, viewModel.successItemCount())
        assertEquals(2, subscriptions)
    }

    @Test
    fun `uiState emits Error only once the retries are exhausted`() = runTest {
        var subscriptions = 0
        every { userRepository.getUsers() } returns flow {
            subscriptions++
            throw IOException("network error")
        }

        val viewModel = buildSubscribedViewModel()
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Error)
        assertEquals("network error", (state as HomeUiState.Error).message)
        assertEquals(4, subscriptions) // the first attempt plus three retries
    }

    @Test
    fun `a failure that another attempt cannot fix is surfaced immediately`() = runTest {
        var subscriptions = 0
        every { userRepository.getUsers() } returns flow {
            subscriptions++
            error("unparseable row")
        }

        val viewModel = buildSubscribedViewModel()

        assertTrue(viewModel.uiState.value is HomeUiState.Error)
        assertEquals(1, subscriptions)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `retry triggers new collection after error`() = runTest {
        every { userRepository.getUsers() } returns flow { throw IOException("transient error") }
        val viewModel = buildSubscribedViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is HomeUiState.Error)

        every { userRepository.getUsers() } returns flowOf(testUsers)
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(3, viewModel.successItemCount())
    }

    @Test
    fun `retry cancels the collection it replaces`() = runTest {
        val firstCollection = MutableStateFlow(testUsers)
        every { userRepository.getUsers() } returns firstCollection
        val viewModel = buildSubscribedViewModel()
        assertEquals(3, viewModel.successItemCount())

        val secondCollection = MutableStateFlow(emptyList<User>())
        every { userRepository.getUsers() } returns secondCollection
        viewModel.retry()
        advanceUntilIdle()

        // flatMapLatest cancelled the first subscription, so the abandoned flow can no longer
        // write to uiState. Under flatMapConcat or merge this would race back to three items.
        firstCollection.value = testUsers.take(2)
        advanceUntilIdle()

        assertEquals(0, viewModel.successItemCount())
    }

    @Test
    fun `isOffline stays false with no subscriber rather than reporting a stale offline`() {
        // Nothing is collected, so the monitor is never subscribed and the platform callback
        // is never registered. The initial value is what a caller reading .value would see.
        assertEquals(false, buildViewModel().isOffline.value)
    }

    @Test
    fun `isOffline follows the monitor in both directions while subscribed`() = runTest {
        val viewModel = buildViewModel()
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.isOffline.collect { values += it }
        }
        runCurrent()

        networkMonitor.emit(NetworkStatus.Offline)
        runCurrent()
        networkMonitor.emit(FakeNetworkMonitor.ONLINE)
        runCurrent()

        // The first `false` is the initial value, which the monitor's own "online" agrees
        // with and StateFlow therefore conflates away rather than re-emitting.
        assertEquals(listOf(false, true, false), values)
    }

    @Test
    fun `a captive portal counts as online because a retry cannot fix it`() = runTest {
        val viewModel = buildViewModel()
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.isOffline.collect { values += it }
        }
        runCurrent()

        // Joined, routable, and every request will come back with a login page. That is not
        // the same failure as having no network, and the offline banner would be a lie.
        networkMonitor.emit(NetworkStatus.Online(isValidated = false, isMetered = false))
        runCurrent()

        assertEquals(listOf(false), values)
    }

    private companion object {
        /** Comfortably past the 300ms search debounce. */
        val SETTLE: Duration = 400.milliseconds

        /** A gap short enough that the next keystroke supersedes the previous one. */
        val TYPING_GAP: Duration = 50.milliseconds
    }
}
