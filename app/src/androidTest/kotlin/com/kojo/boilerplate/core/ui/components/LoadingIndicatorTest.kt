package com.kojo.boilerplate.core.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.kojo.boilerplate.ui.theme.BoilerplateTheme
import org.junit.Rule
import org.junit.Test

class LoadingIndicatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingIndicator_isDisplayed() {
        composeRule.setContent {
            BoilerplateTheme {
                LoadingIndicator()
            }
        }
        composeRule.onNodeWithTag(LoadingIndicatorTestTag).assertIsDisplayed()
    }

    @Test
    fun loadingIndicator_withCustomSize_isDisplayed() {
        composeRule.setContent {
            BoilerplateTheme {
                LoadingIndicator(size = 64.dp)
            }
        }
        composeRule.onNodeWithTag(LoadingIndicatorTestTag).assertIsDisplayed()
    }
}
