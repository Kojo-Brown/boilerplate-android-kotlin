package com.kojo.boilerplate.feature.profile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kojo.boilerplate.ui.theme.BoilerplateTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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

    @Test
    fun profileContent_whenSuccess_showsAvatarInitial() {
        composeRule.setContent {
            BoilerplateTheme {
                ProfileContent(
                    uiState = ProfileUiState.Success(sampleProfile),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("A").assertIsDisplayed()
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
