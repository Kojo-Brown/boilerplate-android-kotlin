package com.kojo.boilerplate.feature.signin

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kojo.boilerplate.core.auth.GoogleUser
import com.kojo.boilerplate.core.ui.theme.BoilerplateTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The two halves of the sign-in screen: the state before a credential exists and the state after.
 *
 * Every `onNodeWithText` here is also an implicit uniqueness assertion — it fails on two matching
 * nodes as loudly as on none — which is what the last test in this file turns into the subject.
 */
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

    /**
     * The name line falls back to the address — `user.displayName.ifEmpty { user.email }` — and
     * the address line below it is unconditional, so a user with no display name has their email
     * on screen twice.
     *
     * That is the assertion, and the count is the point rather than an inconvenience. This test
     * was written as `onNodeWithText("alice@gmail.com").assertIsDisplayed()`, which cannot pass:
     * `onNodeWithText` requires exactly one match and fails with "Expected exactly '1' node but
     * found '2'". `signedInContent_showsEmail` above is the same query against a user who *has* a
     * name and does find one node, so the two together are what pin the fallback — without this
     * one, a regression that dropped the name line entirely would leave both green.
     */
    @Test
    fun signedInContent_whenDisplayNameEmpty_showsEmailInTheNameLineToo() {
        val userWithEmptyName = sampleUser.copy(displayName = "")
        composeRule.setContent {
            BoilerplateTheme {
                SignedInContent(user = userWithEmptyName, onSignOut = {})
            }
        }
        composeRule.onAllNodesWithText("alice@gmail.com").assertCountEquals(2)
    }
}
