package com.kojo.boilerplate.core.ui.components

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kojo.boilerplate.core.ui.theme.BoilerplateTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryButton_showsLabel() {
        composeRule.setContent {
            BoilerplateTheme {
                AppButton(text = "Submit", onClick = {})
            }
        }
        composeRule.onNodeWithText("Submit").assertIsDisplayed()
    }

    @Test
    fun primaryButton_triggersOnClick() {
        var clicked = false
        composeRule.setContent {
            BoilerplateTheme {
                AppButton(text = "Submit", onClick = { clicked = true })
            }
        }
        composeRule.onNodeWithText("Submit").performClick()
        assertTrue(clicked)
    }

    /**
     * Both halves, because the name promises the second one and the test only ever made the
     * first: `clicked` was declared, assigned from a lambda and never read, so a disabled button
     * that still invoked its callback would have passed. A tap is injected as a touch at the
     * node's centre whether or not it carries a click action, which is what makes the negative
     * assertion possible at all.
     */
    @Test
    fun button_whenDisabled_doesNotTriggerOnClick() {
        var clicked = false
        composeRule.setContent {
            BoilerplateTheme {
                AppButton(text = "Submit", onClick = { clicked = true }, enabled = false)
            }
        }
        composeRule.onNodeWithText("Submit").assertIsNotEnabled()
        composeRule.onNodeWithText("Submit").performClick()
        assertFalse(clicked)
    }

    /**
     * `useUnmergedTree` on the spinner, and it is not optional. `AppButton`'s spinner sits inside
     * a Material button, whose clickable modifier merges its descendants into one semantics node
     * — and a test tag is not one of the properties that merge, so in the merged tree the
     * indicator is neither a node of its own nor a tag on the button above it. The label needs no
     * such flag: when `isLoading` is true the `Text` is not composed at all, so it is absent from
     * both trees.
     */
    @Test
    fun button_whenLoading_showsIndicatorAndHidesLabel() {
        composeRule.setContent {
            BoilerplateTheme {
                AppButton(text = "Submit", onClick = {}, isLoading = true)
            }
        }
        composeRule.onNodeWithText("Submit").assertDoesNotExist()
        composeRule
            .onNodeWithTag("AppButtonLoadingIndicator", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun outlinedButton_showsLabel() {
        composeRule.setContent {
            BoilerplateTheme {
                AppButton(
                    text = "Cancel",
                    onClick = {},
                    variant = AppButtonVariant.Outlined,
                )
            }
        }
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun outlinedButton_triggersOnClick() {
        var clicked = false
        composeRule.setContent {
            BoilerplateTheme {
                AppButton(
                    text = "Cancel",
                    onClick = { clicked = true },
                    variant = AppButtonVariant.Outlined,
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(clicked)
    }
}
