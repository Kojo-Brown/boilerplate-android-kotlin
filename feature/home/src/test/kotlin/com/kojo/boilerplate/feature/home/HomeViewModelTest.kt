package com.kojo.boilerplate.feature.home

import com.kojo.boilerplate.core.common.network.NetworkStatus
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.usecase.RefreshVisibleUsersUseCase
import com.kojo.boilerplate.core.testing.FakeNetworkMonitor
import com.kojo.boilerplate.core.testing.FakeUserRepository
import com.kojo.boilerplate.core.testing.MainDispatcherExtension
import com.kojo.boilerplate.core.testing.syncStrategyFactoryOver
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * The read half of the home screen: the search, and the connectivity flag beside it.
 * [HomeViewModelRefreshTest] covers the network fan-out.
 *
 * ### What moved out of this class when the list became paged
 *
 * Most of it. The assertions used to be over `HomeContent.Users.items` — a list the view model
 * built by filtering everything Room held — and there is no such list any more: the rows are
 * decided by a `LIKE` in `UserPagingDao` and delivered as pages. So the questions this file can
 * still answer are the ones about *what the view model does with a keystroke*, and the fake
 * records exactly that. Which rows a query returns is now a database test, and the ones that
 * matter need a real SQLite — see the `androidTest` note in `docs/paging.md`.
 *
 * `Loading` and `Error` went with them. They are `LazyPagingItems.loadState` now, which lives in
 * the composition; the retry tests that drove `HomeUiEvent.RetryClicked` went with the event.
 *
 * ### One scheduler
 *
 * Every dispatcher here shares one, `Dispatchers.Main` included, which is load-bearing and was
 * not before. The search debounce used to sit upstream of the view model's own
 * `flowOn(defaultDispatcher)`, so pinning that dispatcher was enough to put the `delay` on the
 * test's clock. There is no `flowOn` any more: the debounce runs wherever the paged stream is
 * collected, and `cachedIn(viewModelScope)` collects it on `Main`. With `Main` on a scheduler of
 * its own, `advanceTimeBy` would advance a clock the debounce is not waiting on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @JvmField
    @RegisterExtension
    val mainDispatcherExtension = MainDispatcherExtension(mainDispatcher)

    private val testUsers = listOf(
        User(id = "1", displayName = "Alice Johnson", email = "alice@example.com"),
        User(id = "2", displayName = "Bob Smith", email = "bob@example.com"),
        User(id = "3", displayName = "Carol White", email = "carol@example.com"),
    )

    private val pagedUsers = FakePagedUserRepository(testUsers)

    private val networkMonitor = FakeNetworkMonitor()

    private fun buildViewModel() = HomeViewModel(
        pagedUsers = pagedUsers,
        refreshVisibleUsers = RefreshVisibleUsersUseCase(
            syncStrategyFactoryOver(FakeUserRepository(testUsers)),
        ),
        networkMonitor = networkMonitor,
    )

    /**
     * Collects both halves of the screen, because both are `WhileSubscribed`-shaped and neither
     * runs without a subscriber.
     *
     * The second collector is the one that is easy to forget. `cachedIn` shares the paged stream
     * `Lazily`, so the `flatMapLatest` under it — and therefore every call to the repository —
     * starts on the *first collection of the pages*, not on the first collection of `state`. A
     * test that collected only `state` would assert against a repository that had never been
     * asked for anything.
     *
     * `state.value.users` rather than a collected state's: it is the same instance in every
     * emission, which is the point of the assertion in `the paged stream is one instance`.
     */
    private fun TestScope.buildSubscribedViewModel(): HomeViewModel {
        val viewModel = buildViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.value.users.collect { }
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

    @Test
    fun `the screen starts by asking for everything`() = runTest(mainDispatcher) {
        buildSubscribedViewModel()

        // The empty query is a query like any other — `likePattern("")` is `%%` — which is why
        // there is no separate "unfiltered" path anywhere below this.
        assertEquals(listOf(""), pagedUsers.queries)
    }

    @Test
    fun `nothing is asked for until the pages are collected`() = runTest(mainDispatcher) {
        val viewModel = buildViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { }
        }
        runCurrent()

        // `cachedIn` shares Lazily. A screen that renders the search field but never collects
        // the pages opens no database query, which is the behaviour that makes the two-pane
        // layout's collapsed pane free.
        assertEquals(emptyList<String>(), pagedUsers.queries)
    }

    @Test
    fun `a search query reaches the repository`() = runTest(mainDispatcher) {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "alice")

        // The filter is a parameter of the query the pages are made of, not a predicate applied
        // to them: an in-memory filter over a `PagingData` sees only the pages already loaded.
        assertEquals(listOf("", "alice"), pagedUsers.queries)
    }

    @Test
    fun `surrounding whitespace is not a different search`() = runTest(mainDispatcher) {
        val viewModel = buildSubscribedViewModel()

        enterQuery(viewModel, "alice")
        enterQuery(viewModel, "alice ")

        // A soft keyboard adds the trailing space after a word. Under paging, taking it for a
        // new query would tear down a `Pager` and build another to run the same SQL.
        assertEquals(listOf("", "alice"), pagedUsers.queries)
    }

    @Test
    fun `keystrokes typed within the debounce window produce a single search`() =
        runTest(mainDispatcher) {
            val viewModel = buildSubscribedViewModel()

            listOf("a", "al", "ali", "alic", "alice").forEach { keystroke ->
                viewModel.onEvent(HomeUiEvent.SearchQueryChanged(keystroke))
                advanceTimeBy(TYPING_GAP)
            }
            advanceTimeBy(SETTLE)
            runCurrent()

            // Undebounced this is five `Pager`s, four of which exist only long enough to run an
            // initial load of three pages against the database and be cancelled.
            assertEquals(listOf("", "alice"), pagedUsers.queries)
        }

    @Test
    fun `clearing the query is not held back by the debounce`() = runTest(mainDispatcher) {
        val viewModel = buildSubscribedViewModel()
        enterQuery(viewModel, "alice")
        val clearedAt = currentTime

        viewModel.onEvent(HomeUiEvent.SearchQueryChanged(""))
        runCurrent()

        // "Show me everything again" needs no rate limiting, and delaying it would put a
        // debounce-length flash of an empty list in front of the restored list.
        assertEquals(listOf("", "alice", ""), pagedUsers.queries)
        assertEquals(clearedAt, currentTime)
    }

    @Test
    fun `the search query in the state is every keystroke, without waiting for the debounce`() =
        runTest(mainDispatcher) {
            val viewModel = buildSubscribedViewModel()

            viewModel.onEvent(HomeUiEvent.SearchQueryChanged("ali"))
            runCurrent()

            // The text field is bound to this. Debouncing it would make typing feel broken.
            assertEquals("ali", viewModel.state.value.searchQuery)
            assertEquals(listOf(""), pagedUsers.queries)
        }

    @Test
    fun `the paged stream is one instance across every state the screen renders`() =
        runTest(mainDispatcher) {
            val viewModel = buildSubscribedViewModel()
            val first = viewModel.state.value.users

            viewModel.onEvent(HomeUiEvent.SearchQueryChanged("ali"))
            runCurrent()
            networkMonitor.emit(NetworkStatus.Offline)
            runCurrent()

            // What `HomeUiState`'s `@Immutable` promises, and what stops the screen rebuilding
            // its `LazyPagingItems` — restarting paging at page one — every time an unrelated
            // field moves. A `Flow` has no `equals`, so identity is the whole of it.
            assertSame(first, viewModel.state.value.users)
        }

    @Test
    fun `a user is rendered as its display name over its email`() {
        val item = testUsers[0].toHomeItem()

        assertEquals(HomeItem(id = "1", title = "Alice Johnson", description = "alice@example.com"), item)
    }

    @Test
    fun `isOffline stays false with no subscriber rather than reporting a stale offline`() {
        // Nothing is collected, so the monitor is never subscribed and the platform callback
        // is never registered. The initial value is what a caller reading .value would see.
        assertEquals(false, buildViewModel().state.value.isOffline)
    }

    @Test
    fun `isOffline follows the monitor in both directions while subscribed`() =
        runTest(mainDispatcher) {
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
    fun `a captive portal counts as online because a retry cannot fix it`() =
        runTest(mainDispatcher) {
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
