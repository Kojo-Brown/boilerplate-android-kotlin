package com.kojo.boilerplate.core.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kojo.boilerplate.core.common.ThemeMode
import com.kojo.boilerplate.core.ui.components.AppTextField
import org.junit.Rule
import org.junit.Test

/**
 * `AppTextField` in the states a form puts it in.
 *
 * The error state is the one worth having a golden for. `isError` moves four things at once in
 * Material 3 — the outline, the label, the supporting text and the trailing icon slot — and it
 * does it by reading the colour scheme, so "the field turns red" is a claim about `Color.kt` and
 * about `OutlinedTextField`'s own defaults rather than about this file. Neither a unit test nor a
 * semantics assertion can see any of it.
 *
 * Every case is given an explicit width. A text field is `fillMaxWidth` at almost every call
 * site, and under SHRINK rendering an unconstrained one would be as wide as the device.
 */
class AppTextFieldScreenshotTest {

    @get:Rule
    val paparazzi = screenshotRule()

    @Test
    fun emptyWithPlaceholder() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            AppTextField(
                value = "",
                onValueChange = {},
                label = "Email",
                placeholder = "you@example.com",
                modifier = Modifier.width(FieldWidth),
            )
        }
    }

    @Test
    fun filled() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            AppTextField(
                value = "ada.lovelace@example.com",
                onValueChange = {},
                label = "Email",
                modifier = Modifier.width(FieldWidth),
            )
        }
    }

    @Test
    fun error() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            AppTextField(
                value = "not-an-email",
                onValueChange = {},
                label = "Email",
                supportingText = "Enter a valid email address",
                isError = true,
                modifier = Modifier.width(FieldWidth),
            )
        }
    }

    @Test
    fun disabled() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Light) {
            AppTextField(
                value = "ada.lovelace@example.com",
                onValueChange = {},
                label = "Email",
                enabled = false,
                modifier = Modifier.width(FieldWidth),
            )
        }
    }

    /**
     * The dark palette's `error` is a pale pink and its `onError` a dark maroon — the inverse of
     * the light one. An error field that reads in one and not the other is a palette bug that
     * only this pair of images can show.
     */
    @Test
    fun errorDark() = paparazzi.snapshot {
        ScreenshotHost(ThemeMode.Dark) {
            AppTextField(
                value = "not-an-email",
                onValueChange = {},
                label = "Email",
                supportingText = "Enter a valid email address",
                isError = true,
                modifier = Modifier.width(FieldWidth),
            )
        }
    }
}

/** Narrow enough to keep the golden small, wide enough that the supporting text does not wrap. */
private val FieldWidth = 280.dp
