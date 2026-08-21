package com.kojo.boilerplate.feature.home

import com.kojo.boilerplate.core.coroutines.MainDispatcherExtension
import com.kojo.boilerplate.core.data.model.User
import com.kojo.boilerplate.core.data.repository.UserRepository
import com.kojo.boilerplate.core.domain.sync.syncStrategyFactoryOver
import com.kojo.boilerplate.core.domain.usecase.RefreshVisibleUsersUseCase
import com.kojo.boilerplate.core.network.connectivity.FakeNetworkMonitor
import com.kojo.boilerplate.core.network.connectivity.NetworkStatus
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
        refreshVisibleUsers = RefreshVisibleUsersUseCase(syncStrategyFactoryOver(userRepository)),
        defaultDispatcher = UnconfinedTestDispatcher(),
        networkMonitor = networkMonitor,
    )

    /**
     * `state` is built with stateIn(..., SharingStarted.WhileSubscribed), so its upstream does
     * not run until something collects it. Reading .value with no subscriber returns the
     * initial value forever — which is why every assertion below needs a subscriber.
     * Collecting on backgroundScope keeps the state hot for the test and runTest tears it down
     * automatically.
     *
     * Both dispatchers are pinned to the test's own scheduler so there is a single clock —
     * the search debounce and the retry backoff are both `delay()` upstream of the view
     * model's own `flowOn`, so they only stay on virtual time while that holds.
     *
     * [contents] records each time the *list* changed, which is what the debounce assertion is
     * about. It is deliberately the content and not the whole state: a keystroke changes
     * `searchQuery` and therefore produces a new `HomeUiState` — that is the text field
     * updating, which must not be debounced — while leaving the list it was filtering
     * untouched. `distinctUntilChanged` on the content is exactly "the list changed".
     */
    private fun TestScope.buildSubscribedViewModel(
        contents: MutableList<HomeContent> = mutableListOf(),
    ): HomeViewModel {
        val viewModel = HomeViewModel(
            userRepository = userRepository,
            refreshVisibleUsers = RefreshVisibleUsersUseCase(syncStrategyFactoryOver(userRepository)),
            defaultDispatcher = UnconfinedTestDispatcher(testScheduler),
            networkMonitor = networkMonitor,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.map { it.content }.distinctUntilChanged().collect { contents += it }
        }
        runCurrent()
        return viewModel
    }

    /** Types [query] and lets the search debounce elapse. */
    private fun TestScope.enterQuery(viewModel: HomeViewModel, query: String) {
        viewModel.onEvent(HomeUiEvent.SearchQueryChanged(query))
        advanceTimeBy(SETTLE)
        runCurrent()
    }

    private fun HomeViewModel.users(): HomeContent.Users =
        state.value.content as HomeContent.Users

    private fun HomeViewModel.userCount(): Int = users().items.size

    @Test
    fun `the initial content is Loading`() {
        assertEquals(HomeContent.Loading, buildViewModel().state.value.content)
    }

    @Test
    fun `every user is listed when the search query is empty`() = runTest {
        val viewModel = buildSubscribedViewModel()
        val content = viewModel.state.value.content

        assertTrue(content is HomeContent.Users)
        val users = content as HomeContent.Users
        assertEquals(3, users.items.size)
        assertEquals("Alice Johnson", users.items[0].title)
        assertEquals("alice@example.com", users.items[0].description)
    }

    @Test
    fun `a search query filters users by display name`() = runTest {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "alice")

        assertEquals(1, viewModel.userCount())
        assertEquals("Alice Johnson", viewModel.users().items[0].title)
    }

    @Test
    fun `a search query filters users by email`() = runTest {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "bob@")

        assertEquals(1, viewModel.userCount())
        assertEquals("Bob Smith", viewModel.users().items[0].title)
    }

    @Test
    fun `a search query is case insensitive`() = runTest {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "CAROL")

        assertEquals(1, viewModel.userCount())
        assertEquals("Carol White", viewModel.users().items[0].title)
    }

    @Test
    fun `a search query with no match lists nothing`() = runTest {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "xyz-no-match")

        assertEquals(0, viewModel.userCount())
    }

    @Test
    fun `clearing the search query restores the full list`() = runTest {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "alice")
        enterQuery(viewModel, "")

        assertEquals(3, viewModel.userCount())
    }

    @Test
    fun `the search query in the state is every keystroke, without waiting for the debounce`() =
        runTest {
            val viewModel = buildSubscribedViewModel()

            viewModel.onEvent(HomeUiEvent.SearchQueryChanged("ali"))
            runCurrent()

            // The text field is bound to this. Debouncing it would make typing feel broken.
            assertEquals("ali", viewModel.state.value.searchQuery)
        }

    @Test
    fun `keystrokes typed within the debounce window produce a single filtered list`() = runTest {
        val contents = mutableListOf<HomeContent>()
        val viewModel = buildSubscribedViewModel(contents)

        listOf("a", "al", "ali", "alic", "alice").forEach { keystroke ->
            viewModel.onEvent(HomeUiEvent.SearchQueryChanged(keystroke))
            advanceTimeBy(TYPING_GAP)
        }
        advanceTimeBy(SETTLE)
        runCurrent()

        // Without the debounce every prefix would filter the list and render: "a" alone
        // matches Alice and Carol, so the intermediate lists are visibly different.
        assertEquals(
            listOf(3, 1),
            contents.filterIsInstance<HomeContent.Users>().map { it.items.size },
        )
    }

    @Test
    fun `clearing the query is not held back by the debounce`() = runTest {
        val viewModel = buildSubscribedViewModel()
        enterQuery(viewModel, "alice")
        val clearedAt = currentTime

        viewModel.onEvent(HomeUiEvent.SearchQueryChanged(""))
        runCurrent()

        assertEquals(3, viewModel.userCount())
        assertEquals(clearedAt, currentTime)
    }

    @Test
    fun `the list reflects repository updates reactively`() = runTest {
        val usersFlow = MutableStateFlow(testUsers)
        every { userRepository.getUsers() } returns usersFlow
        val viewModel = buildSubscribedViewModel()

        assertTrue(viewModel.state.value.content is HomeContent.Users)

        val newUser = User(id = "4", displayName = "Dave Brown", email = "dave@example.com")
        usersFlow.value = testUsers + newUser

        assertEquals(4, viewModel.userCount())
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
        assertEquals(HomeContent.Loading, viewModel.state.value.content)

        advanceUntilIdle()

        assertEquals(3, viewModel.userCount())
        assertEquals(2, subscriptions)
    }

    @Test
    fun `the error content appears only once the retries are exhausted`() = runTest {
        var subscriptions = 0
        every { userRepository.getUsers() } returns flow {
            subscriptions++
            throw IOException("network error")
        }

        val viewModel = buildSubscribedViewModel()
        assertEquals(HomeContent.Loading, viewModel.state.value.content)

        advanceUntilIdle()

        val content = viewModel.state.value.content
        assertTrue(content is HomeContent.Error)
        assertEquals("network error", (content as HomeContent.Error).message)
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

        assertTrue(viewModel.state.value.content is HomeContent.Error)
        assertEquals(1, subscriptions)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `retry triggers new collection after error`() = runTest {
        every { userRepository.getUsers() } returns flow { throw IOException("transient error") }
        val viewModel = buildSubscribedViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.content is HomeContent.Error)

        every { userRepository.getUsers() } returns flowOf(testUsers)
        viewModel.onEvent(HomeUiEvent.RetryClicked)
        advanceUntilIdle()

        assertEquals(3, viewModel.userCount())
    }

    @Test
    fun `retry cancels the collection it replaces`() = runTest {
        val firstCollection = MutableStateFlow(testUsers)
        every { userRepository.getUsers() } returns firstCollection
        val viewModel = buildSubscribedViewModel()
        assertEquals(3, viewModel.userCount())

        val secondCollection = MutableStateFlow(emptyList<User>())
        every { userRepository.getUsers() } returns secondCollection
        viewModel.onEvent(HomeUiEvent.RetryClicked)
        advanceUntilIdle()

        // flatMapLatest cancelled the first subscription, so the abandoned flow can no longer
        // write to the state. Under flatMapConcat or merge this would race back to three items.
        firstCollection.value = testUsers.take(2)
        advanceUntilIdle()

        assertEquals(0, viewModel.userCount())
    }

    @Test
    fun `isOffline stays false with no subscriber rather than reporting a stale offline`() {
        // Nothing is collected, so the monitor is never subscribed and the platform callback
        // is never registered. The initial value is what a caller reading .value would see.
        assertEquals(false, buildViewModel().state.value.isOffline)
    }

    @Test
    fun `isOffline follows the monitor in both directions while subscribed`() = runTest {
        val viewModel = buildViewModel()
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.map { it.isOffline }.distinctUntilChanged().collect { values += it }
        }
        runCurrent()

        networkMonitor.emit(NetworkStatus.Offline)
        runCurrent()
        networkMonitor.emit(FakeNetworkMonitor.ONLINE)
        runCurrent()

        // The leading `false` is the assumed-online value the state starts at, which the
        // monitor's own "online" agrees with — so nothing is emitted for it.
        assertEquals(listOf(false, true, false), values)
    }

    @Test
    fun `a captive portal counts as online because a retry cannot fix it`() = runTest {
        val viewModel = buildViewModel()
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.map { it.isOffline }.distinctUntilChanged().collect { values += it }
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
