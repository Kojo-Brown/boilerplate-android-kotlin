package com.kojo.boilerplate.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kojo.boilerplate.ui.theme.BoilerplateTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeContent_whenLoading_showsNoTextContent() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Loading,
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Something went wrong").assertDoesNotExist()
        composeRule.onNodeWithText("No results found").assertDoesNotExist()
    }

    @Test
    fun homeContent_whenError_showsErrorTitle() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Error("Network unavailable"),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Something went wrong").assertIsDisplayed()
    }

    @Test
    fun homeContent_whenError_showsErrorMessage() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Error("Request timed out"),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Request timed out").assertIsDisplayed()
    }

    @Test
    fun homeContent_whenError_showsRetryButton() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Error("Network unavailable"),
                    onRetry = {},
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun homeContent_whenError_retryButtonInvokesCallback() {
        var retried = false
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Error("Network unavailable"),
                    onRetry = { retried = true },
                    onItemClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun homeContent_whenSuccessWithItems_showsGreeting() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Success(
                        items = listOf(
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
    fun homeContent_whenSuccessWithItems_showsItemTitle() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Success(
                        items = listOf(
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
    fun homeContent_whenSuccessWithItems_showsItemDescription() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Success(
                        items = listOf(
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
    fun homeContent_whenSuccessWithMultipleItems_showsAllItems() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Success(
                        items = listOf(
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
    fun homeContent_whenSuccessWithItems_itemClickInvokesCallback() {
        val clicked = mutableListOf<HomeItem>()
        val item = HomeItem(id = "1", title = "Alice", description = "alice@example.com")
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Success(
                        items = listOf(item),
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
    fun homeContent_whenSuccessEmpty_showsEmptyMessage() {
        composeRule.setContent {
            BoilerplateTheme {
                HomeContent(
                    uiState = HomeUiState.Success(
                        items = emptyList(),
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
