package com.kojo.boilerplate.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kojo.boilerplate.core.ui.theme.BoilerplateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppTextFieldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun textField_showsLabel() {
        composeRule.setContent {
            BoilerplateTheme {
                AppTextField(value = "", onValueChange = {}, label = "Email")
            }
        }
        composeRule.onNodeWithText("Email").assertIsDisplayed()
    }

    /**
     * The placeholder is Material 3's behaviour rather than this component's, and it is
     * conditional: with a label declared, an empty unfocused field shows the *label* in the input
     * row and the placeholder is composed but not visible. It appears once the label has floated
     * up to the border, which is to say on focus — otherwise the two would be drawn on top of
     * each other.
     *
     * This test asserted the placeholder was displayed on an untouched field, and the first
     * emulator to run it answered "The component is not displayed!" on a node it had found. Both
     * states are asserted now, because the pair is the contract: without the unfocused half, a
     * field that showed the label and the placeholder at the same time would pass just as well.
     */
    @Test
    fun textField_placeholderAppearsOnFocusAndNotBefore() {
        composeRule.setContent {
            BoilerplateTheme {
                AppTextField(
                    value = "",
                    onValueChange = {},
                    label = "Email",
                    placeholder = "Enter your email",
                )
            }
        }

        composeRule.onNodeWithText("Enter your email").assertIsNotDisplayed()

        composeRule.onNodeWithText("Email").performClick()

        composeRule.onNodeWithText("Enter your email").assertIsDisplayed()
    }

    @Test
    fun textField_showsSupportingText() {
        composeRule.setContent {
            BoilerplateTheme {
                AppTextField(
                    value = "",
                    onValueChange = {},
                    label = "Email",
                    supportingText = "We'll never share your email.",
                )
            }
        }
        composeRule.onNodeWithText("We'll never share your email.").assertIsDisplayed()
    }

    @Test
    fun textField_updatesValueOnInput() {
        var text by mutableStateOf("")
        composeRule.setContent {
            BoilerplateTheme {
                AppTextField(value = text, onValueChange = { text = it }, label = "Name")
            }
        }
        composeRule.onNodeWithText("Name").performTextInput("Alice")
        assertEquals("Alice", text)
    }

    @Test
    fun textField_whenDisabled_isNotEnabled() {
        composeRule.setContent {
            BoilerplateTheme {
                AppTextField(
                    value = "",
                    onValueChange = {},
                    label = "Email",
                    enabled = false,
                )
            }
        }
        composeRule.onNodeWithText("Email").assertIsNotEnabled()
    }

    @Test
    fun textField_whenEnabled_isEnabled() {
        composeRule.setContent {
            BoilerplateTheme {
                AppTextField(value = "", onValueChange = {}, label = "Email")
            }
        }
        composeRule.onNodeWithText("Email").assertIsEnabled()
    }
}
