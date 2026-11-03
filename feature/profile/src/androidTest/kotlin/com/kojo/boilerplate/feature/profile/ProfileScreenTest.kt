package com.kojo.boilerplate.feature.profile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kojo.boilerplate.core.ui.theme.BoilerplateTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What `ProfileContent` renders for each of its three states.
 *
 * Written before any emulator existed to run it, and two things in it were wrong in ways only a
 * run could show: `assertDoesNotExist` was used without being imported, so the file did not
 * compile, and one test asserted the monogram's initial is readable as text when the component
 * deliberately removes itself from the semantics tree. Both are fixed below, the second by
 * asserting the contract the component actually has rather than by deleting the test.
 */
class ProfileScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleProfile = ProfileData(
        userId = "user-123",
        displayName = "Alice Johnson",
        email = "alice@example.com",
        avatarUrl = null,
    )

    @Test
    fun profileContent_whenLoading_showsNoTextContent() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Loading,
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("Failed to load profile").assertDoesNotExist()
        composeRule.onNodeWithText("Alice Johnson").assertDoesNotExist()
    }

    @Test
    fun profileContent_whenSuccess_showsDisplayName() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Success(sampleProfile),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("Alice Johnson").assertIsDisplayed()
    }

    @Test
    fun profileContent_whenSuccess_showsEmail() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Success(sampleProfile),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("alice@example.com").assertIsDisplayed()
    }

    @Test
    fun profileContent_whenSuccess_showsEmailLabel() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Success(sampleProfile),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("Email").assertIsDisplayed()
    }

    @Test
    fun profileContent_whenSuccess_showsUserIdLabel() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Success(sampleProfile),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("User ID").assertIsDisplayed()
    }

    @Test
    fun profileContent_whenSuccess_showsUserId() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Success(sampleProfile),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("user-123").assertIsDisplayed()
    }

    /**
     * The monogram is a decoration, and `UserMonogram` says so in the one way the platform reads:
     * `clearAndSetSemantics {}` with nothing in it, which drops the circle and the letter inside
     * it from the semantics tree entirely. The name beside it already carries the whole string, so
     * the failure that decision prevents is a screen reader announcing "A" and then
     * "Alice Johnson" — the initial twice, once as a letter.
     *
     * This test used to read `onNodeWithText("A").assertIsDisplayed()`, which is the opposite
     * claim and would have failed on the first emulator that ran it. Asserted as the pair rather
     * than deleted: the letter is not announced, *and* the name is announced exactly once. Either
     * half alone passes against a regression that breaks the other.
     */
    @Test
    fun profileContent_whenSuccess_announcesTheNameOnceAndNotItsInitial() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Success(sampleProfile),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("A").assertDoesNotExist()
        composeRule.onAllNodesWithText("Alice Johnson").assertCountEquals(1)
    }

    @Test
    fun profileContent_whenError_showsErrorTitle() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Error("User not found"),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("Failed to load profile").assertIsDisplayed()
    }

    @Test
    fun profileContent_whenError_showsErrorMessage() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Error("User not found"),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("User not found").assertIsDisplayed()
    }

    @Test
    fun profileContent_whenError_showsRetryButton() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Error("Connection error"),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun profileContent_whenError_retryButtonInvokesCallback() {
        var retried = false
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Error("Connection error"),
                    onRetry = { retried = true },
                )
            }
        }
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }
}
