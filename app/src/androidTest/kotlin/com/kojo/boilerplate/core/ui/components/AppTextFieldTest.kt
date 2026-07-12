package com.kojo.boilerplate.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.kojo.boilerplate.ui.theme.BoilerplateTheme
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

    @Test
    fun textField_showsPlaceholder() {
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
