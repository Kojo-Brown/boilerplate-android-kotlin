package com.kojo.boilerplate.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

const val LoadingIndicatorTestTag = "LoadingIndicator"

/**
 * @param contentDescription what a screen reader announces while this is on screen. An
 *   indeterminate [CircularProgressIndicator] carries no semantics of its own — it is a drawing,
 *   and `progressSemantics` only applies to the determinate overload — so without this the whole
 *   screen is silent for the entire time it is loading, which reads to a screen-reader user as an
 *   app that stopped responding. Overridable because "Loading" is rarely the most useful sentence
 *   available: a caller that knows what is loading should say so.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
    contentDescription: String = "Loading",
) {
    // Aliased rather than read straight from the parameter inside the `semantics` block: the
    // block's receiver carries an extension property of the same name, and one of the two
    // shadows the other depending on which scope the reader has in mind. `AppButton` avoids the
    // question by naming its parameter something else; this one keeps the name the Compose APIs
    // use and moves the read out instead.
    val announcement = contentDescription

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(size)
                .testTag(LoadingIndicatorTestTag)
                // Polite rather than assertive: the arrival of a spinner is worth saying, and
                // worth saying *after* whatever the user was being told, not over it.
                .semantics {
                    this.contentDescription = announcement
                    liveRegion = LiveRegionMode.Polite
                },
            color = color,
            strokeWidth = strokeWidth,
        )
    }
}
