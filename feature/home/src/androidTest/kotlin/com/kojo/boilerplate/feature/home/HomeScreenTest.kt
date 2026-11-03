package com.kojo.boilerplate.feature.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.kojo.boilerplate.core.ui.theme.BoilerplateTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the home screen's list region renders for a given page of users.
 *
 * ### What this can and cannot set up
 *
 * A `LazyPagingItems` is a presenter, so a test builds one the way the screen does: from a
 * `Flow<PagingData<…>>`, through `collectAsLazyPagingItems()`. [pagedItems] does that over
 * `PagingData.from`, which produces a fully-loaded generation — `NotLoading` on every end.
 *
 * That covers the rows, the empty states and the item click, and it deliberately does not cover
 * the load-state branches: the spinner, the append footer and the error content. Driving those
 * means constructing a `PagingData` with chosen `LoadStates`, and the assertions worth making
 * about them — that a *refresh* error with cached pages leaves the pages on screen, that an
 * *append* error does not — are about how Paging transitions between states rather than about
 * how one state renders. `androidx.paging:paging-testing` is the tool for that and it is not on
 * this project's classpath yet.
 *
 * Both gaps stay open. What changed with the emulator matrix is only that the rest of this file
 * is now executed: `androidTest` compiles and runs on three API levels per PR, so the load-state
 * branches are a gap in the coverage rather than a gap in the pipeline.
 *
 * ### The three icon assertions
 *
 * `RefreshAction` and `SearchBar` put their affordances in an `Icon`'s `contentDescription`,
 * which `onNodeWithText` never matches. Three tests here were written against `onNodeWithText`
 * and so asserted that something was absent which was never going to be present under that
 * query — two of them under names promising the opposite. They are `onNodeWithContentDescription`
 * below. Nothing about the components changed; the queries did.
 */
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val alice = HomeItem(id = "1", title = "Alice", description = "alice@example.com")
    private val bob = HomeItem(id = "2", title = "Bob", description = "bob@example.com")

    @Composable
    private fun pagedItems(items: List<HomeItem>): LazyPagingItems<HomeItem> =
        remember(items) { flowOf(PagingData.from(items)) }.collectAsLazyPagingItems()

    @Composable
    private fun HomeBodyUnderTest(
        items: List<HomeItem>,
        searchQuery: String = "",
        listState: LazyListState = rememberLazyListState(),
        onItemClick: (HomeItem) -> Unit = {},
    ) {
        HomeBody(
            users = pagedItems(items),
            greeting = "Boilerplate Android",
            searchQuery = searchQuery,
            listState = listState,
            onItemClick = onItemClick,
        )
    }

    @Test
    fun homeBody_whenUsers_showsGreeting() {
        composeRule.setContent {
            BoilerplateTheme { HomeBodyUnderTest(items = listOf(alice)) }
        }
        composeRule.onNodeWithText("Boilerplate Android").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenUsers_showsItemTitle() {
        composeRule.setContent {
            BoilerplateTheme { HomeBodyUnderTest(items = listOf(alice)) }
        }
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenUsers_showsItemDescription() {
        composeRule.setContent {
            BoilerplateTheme { HomeBodyUnderTest(items = listOf(alice)) }
        }
        composeRule.onNodeWithText("alice@example.com").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenSeveralUsers_showsAllItems() {
        composeRule.setContent {
            BoilerplateTheme { HomeBodyUnderTest(items = listOf(alice, bob)) }
        }
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenUsers_itemClickInvokesCallback() {
        val clicked = mutableListOf<HomeItem>()
        composeRule.setContent {
            BoilerplateTheme {
                HomeBodyUnderTest(items = listOf(alice), onItemClick = { clicked.add(it) })
            }
        }
        composeRule.onNodeWithText("Alice").performClick()
        assertTrue(clicked.isNotEmpty())
        assertTrue(clicked.first().id == "1")
    }

    @Test
    fun homeBody_whenNoUsersAndNoSearch_saysTheListIsEmpty() {
        composeRule.setContent {
            BoilerplateTheme { HomeBodyUnderTest(items = emptyList()) }
        }
        composeRule.onNodeWithText("No users yet").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenNoUsersUnderASearch_saysTheSearchCoveredDownloadedUsers() {
        composeRule.setContent {
            BoilerplateTheme { HomeBodyUnderTest(items = emptyList(), searchQuery = "zoe") }
        }
        // The distinction is the user-visible half of "a search does not drive remote loading":
        // an empty result means nobody downloaded matches, not that nobody matches.
        composeRule
            .onNodeWithText("No downloaded users match “zoe”. Clear the search to load more.")
            .assertIsDisplayed()
    }

    @Test
    fun homeBody_whenUsers_showsNoEmptyMessage() {
        composeRule.setContent {
            BoilerplateTheme { HomeBodyUnderTest(items = listOf(alice)) }
        }
        composeRule.onNodeWithText("No users yet").assertDoesNotExist()
    }

    @Test
    fun refreshAction_whenNotInProgress_showsAClickableButton() {
        var refreshes = 0
        composeRule.setContent {
            BoilerplateTheme { RefreshAction(inProgress = false, onRefresh = { refreshes++ }) }
        }

        composeRule.onNodeWithContentDescription("Refresh users").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Refresh users").assertHasClickAction()
        composeRule.onNodeWithContentDescription("Refresh users").performClick()
        assertEquals(1, refreshes)
    }

    /**
     * The swap is the announcement, which is the whole reason the spinner carries a
     * `contentDescription` and a live region at all: replacing the button with a bare
     * `CircularProgressIndicator` would leave the app bar's action slot with nothing a screen
     * reader could name, and the refresh would be silent from the tap until the snackbar.
     *
     * Both halves asserted, because either one alone passes against a spinner that kept the
     * button's description or a button that kept the spinner's.
     */
    @Test
    fun refreshAction_whenInProgress_replacesTheButtonWithAnAnnouncedSpinner() {
        composeRule.setContent {
            BoilerplateTheme { RefreshAction(inProgress = true, onRefresh = {}) }
        }

        composeRule.onNodeWithContentDescription("Refreshing users").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Refresh users").assertDoesNotExist()
    }

    @Test
    fun offlineBanner_saysTheListMayBeStale() {
        composeRule.setContent {
            BoilerplateTheme { OfflineBanner() }
        }
        composeRule
            .onNodeWithText("You are offline. Showing the last data loaded.")
            .assertIsDisplayed()
    }

    @Test
    fun searchBar_showsPlaceholderText() {
        composeRule.setContent {
            BoilerplateTheme {
                SearchBar(query = "", onQueryChange = {})
            }
        }
        composeRule.onNodeWithText("Search users…").assertIsDisplayed()
    }

    @Test
    fun searchBar_whenQueryNotEmpty_showsClearIcon() {
        composeRule.setContent {
            BoilerplateTheme {
                SearchBar(query = "alice", onQueryChange = {})
            }
        }
        composeRule.onNodeWithContentDescription("Clear search").assertIsDisplayed()
    }

    /**
     * Clearing is what the icon does, so it is asserted here rather than left to the presence
     * test above: a trailing icon that appeared and did nothing is the failure a reader would
     * meet, and it is invisible to a query that only looks for the icon.
     */
    @Test
    fun searchBar_whenQueryNotEmpty_clearIconEmptiesTheQuery() {
        val queries = mutableListOf<String>()
        composeRule.setContent {
            BoilerplateTheme {
                SearchBar(query = "alice", onQueryChange = { queries.add(it) })
            }
        }

        composeRule.onNodeWithContentDescription("Clear search").performClick()

        assertEquals(listOf(""), queries)
    }

    @Test
    fun searchBar_whenQueryEmpty_doesNotShowClearIcon() {
        composeRule.setContent {
            BoilerplateTheme {
                SearchBar(query = "", onQueryChange = {})
            }
        }
        composeRule.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }
}
