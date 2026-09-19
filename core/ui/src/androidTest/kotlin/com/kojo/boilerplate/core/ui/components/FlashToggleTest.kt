package com.kojo.boilerplate.core.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.kojo.boilerplate.core.ui.theme.BoilerplateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The point of this control is what it says about itself, so that is what is asserted: a checked
 * state the platform can read, and a state description in the vocabulary of a torch rather than
 * of a checkbox. A test that only asserted it is displayed and clickable would pass just as well
 * against the plain `IconButton` this replaced, which is the thing it is not.
 */
class FlashToggleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun flashToggle_whenOff_reportsOff() {
        composeRule.setContent {
            BoilerplateTheme {
                FlashToggle(flashOn = false, onToggle = {})
            }
        }

        composeRule.onNodeWithContentDescription(LABEL).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(LABEL).assertIsOff()
        composeRule.onNodeWithContentDescription(LABEL).assert(hasStateDescription("Off"))
    }

    @Test
    fun flashToggle_whenOn_reportsOn() {
        composeRule.setContent {
            BoilerplateTheme {
                FlashToggle(flashOn = true, onToggle = {})
            }
        }

        composeRule.onNodeWithContentDescription(LABEL).assertIsOn()
        composeRule.onNodeWithContentDescription(LABEL).assert(hasStateDescription("On"))
    }

    /**
     * The state is hoisted, so a press raises the event and changes nothing by itself. Worth a
     * test of its own: a toggle that flipped its own appearance and then disagreed with the view
     * model is the failure this design rules out.
     */
    @Test
    fun flashToggle_whenPressed_raisesTheEventWithoutChangingItsOwnState() {
        var toggles = 0
        composeRule.setContent {
            BoilerplateTheme {
                FlashToggle(flashOn = false, onToggle = { toggles++ })
            }
        }

        composeRule.onNodeWithContentDescription(LABEL).performClick()

        assertEquals(1, toggles)
        composeRule.onNodeWithContentDescription(LABEL).assertIsOff()
    }

    private fun hasStateDescription(expected: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    private companion object {

        /** The control names the thing, not the action; see `FlashToggle`. */
        const val LABEL = "Flash"
    }
}
