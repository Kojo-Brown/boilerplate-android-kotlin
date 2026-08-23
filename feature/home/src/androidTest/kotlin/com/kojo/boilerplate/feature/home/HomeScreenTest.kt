package com.kojo.boilerplate.feature.home

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kojo.boilerplate.core.ui.theme.BoilerplateTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeBody_whenLoading_showsNoTextContent() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Loading,
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Something went wrong").assertDoesNotExist()
        composeRule.onNodeWithText("No results found").assertDoesNotExist()
    }

    @Test
    fun homeBody_whenError_showsErrorTitle() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Error("Network unavailable"),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Something went wrong").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenError_showsErrorMessage() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Error("Request timed out"),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Request timed out").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenError_showsRetryButton() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Error("Network unavailable"),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenError_retryButtonInvokesCallback() {
        var retried = false
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Error("Network unavailable"),
                    onRetry = { retried = true },
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun homeBody_whenUsers_showsGreeting() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Users(
                        items = persistentListOf(
                            HomeItem(id = "1", title = "Alice", description = "alice@example.com"),
                        ),
                        greeting = "Boilerplate Android",
                    ),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Boilerplate Android").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenUsers_showsItemTitle() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Users(
                        items = persistentListOf(
                            HomeItem(id = "1", title = "Alice", description = "alice@example.com"),
                        ),
                        greeting = "Boilerplate Android",
                    ),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenUsers_showsItemDescription() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Users(
                        items = persistentListOf(
                            HomeItem(id = "1", title = "Alice", description = "alice@example.com"),
                        ),
                        greeting = "Boilerplate Android",
                    ),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("alice@example.com").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenSeveralUsers_showsAllItems() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Users(
                        items = persistentListOf(
                            HomeItem(id = "1", title = "Alice", description = "alice@example.com"),
                            HomeItem(id = "2", title = "Bob", description = "bob@example.com"),
                        ),
                        greeting = "Boilerplate Android",
                    ),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test
    fun homeBody_whenUsers_itemClickInvokesCallback() {
        val clicked = mutableListOf<HomeItem>()
        val item = HomeItem(id = "1", title = "Alice", description = "alice@example.com")
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Users(
                        items = persistentListOf(item),
                        greeting = "Boilerplate Android",
                    ),
                    onRetry = {},
                    onItemClick = { clicked.add(it) },
                )
            }
        }
        composeRule.onNodeWithText("Alice").performClick()
        assertTrue(clicked.isNotEmpty())
        assertTrue(clicked.first().id == "1")
    }

    @Test
    fun homeBody_whenNoUsers_showsEmptyMessage() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeBody(
                    content = HomeContent.Users(
                        items = persistentListOf(),
                        greeting = "Boilerplate Android",
                    ),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("No results found").assertIsDisplayed()
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
        composeRule.onNodeWithText("Clear search").assertDoesNotExist()
    }

    @Test
    fun searchBar_whenQueryEmpty_doesNotShowClearIcon() {
        composeRule.setContent {
            BoilerplateTheme {
                SearchBar(query = "", onQueryChange = {})
            }
        }
        composeRule.onNodeWithText("Clear search").assertDoesNotExist()
    }
}
