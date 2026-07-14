package com.kojo.boilerplate.feature.signin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kojo.boilerplate.core.auth.GoogleUser
import com.kojo.boilerplate.ui.theme.BoilerplateTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GoogleSignInScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleUser = GoogleUser(
        id = "google-id-123",
        email = "alice@gmail.com",
        displayName = "Alice Johnson",
        profilePictureUrl = null,
        idToken = "token-abc",
    )

    @Test
    fun signInContent_showsWelcomeText() {
        composeRule.setContent {
            BoilerplateTheme {
                SignInContent(onSignInClick = {})
            }
        }
        composeRule.onNodeWithText("Welcome").assertIsDisplayed()
    }

    @Test
    fun signInContent_showsSubtitleText() {
        composeRule.setContent {
            BoilerplateTheme {
                SignInContent(onSignInClick = {})
            }
        }
        composeRule.onNodeWithText("Sign in with your Google account to continue").assertIsDisplayed()
    }

    @Test
    fun signInContent_showsSignInButton() {
        composeRule.setContent {
            BoilerplateTheme {
                SignInContent(onSignInClick = {})
            }
        }
        composeRule.onNodeWithText("Sign in with Google").assertIsDisplayed()
    }

    @Test
    fun signInContent_signInButtonInvokesCallback() {
        var clicked = false
        composeRule.setContent {
            BoilerplateTheme {
                SignInContent(onSignInClick = { clicked = true })
            }
        }
        composeRule.onNodeWithText("Sign in with Google").performClick()
        assertTrue(clicked)
    }

    @Test
    fun signedInContent_showsSignedInAsLabel() {
        composeRule.setContent {
            BoilerplateTheme {
                SignedInContent(user = sampleUser, onSignOut = {})
            }
        }
        composeRule.onNodeWithText("Signed in as").assertIsDisplayed()
    }

    @Test
    fun signedInContent_showsDisplayName() {
        composeRule.setContent {
            BoilerplateTheme {
                SignedInContent(user = sampleUser, onSignOut = {})
            }
        }
        composeRule.onNodeWithText("Alice Johnson").assertIsDisplayed()
    }

    @Test
    fun signedInContent_showsEmail() {
        composeRule.setContent {
            BoilerplateTheme {
                SignedInContent(user = sampleUser, onSignOut = {})
            }
        }
        composeRule.onNodeWithText("alice@gmail.com").assertIsDisplayed()
    }

    @Test
    fun signedInContent_showsSignOutButton() {
        composeRule.setContent {
            BoilerplateTheme {
                SignedInContent(user = sampleUser, onSignOut = {})
            }
        }
        composeRule.onNodeWithText("Sign out").assertIsDisplayed()
    }

    @Test
    fun signedInContent_signOutButtonInvokesCallback() {
        var signedOut = false
        composeRule.setContent {
            BoilerplateTheme {
                SignedInContent(user = sampleUser, onSignOut = { signedOut = true })
            }
        }
        composeRule.onNodeWithText("Sign out").performClick()
        assertTrue(signedOut)
    }

    @Test
    fun signedInContent_whenDisplayNameEmpty_showsEmailInstead() {
        val userWithEmptyName = sampleUser.copy(displayName = "")
        composeRule.setContent {
            BoilerplateTheme {
                SignedInContent(user = userWithEmptyName, onSignOut = {})
            }
        }
        composeRule.onNodeWithText("alice@gmail.com").assertIsDisplayed()
    }
}
