package com.kojo.boilerplate.core.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kojo.boilerplate.ui.theme.BoilerplateTheme
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

    @Test
    fun button_whenDisabled_doesNotTriggerOnClick() {
        var clicked = false
        composeRule.setContent {
            BoilerplateTheme {
                AppButton(text = "Submit", onClick = { clicked = true }, enabled = false)
            }
        }
        composeRule.onNodeWithText("Submit").assertIsNotEnabled()
    }

    @Test
    fun button_whenLoading_showsIndicatorAndHidesLabel() {
        composeRule.setContent {
            BoilerplateTheme {
                AppButton(text = "Submit", onClick = {}, isLoading = true)
            }
        }
        composeRule.onNodeWithText("Submit").assertDoesNotExist()
        composeRule.onNodeWithTag("AppButtonLoadingIndicator").assertIsDisplayed()
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
