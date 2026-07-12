package com.kojo.boilerplate.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class AppButtonVariant { Primary, Outlined }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    val isEnabled = enabled && !isLoading
    val loadingDescription = if (isLoading) "Loading" else null

    when (variant) {
        AppButtonVariant.Primary -> Button(
            onClick = onClick,
            modifier = modifier.applyLoadingSemantics(loadingDescription),
            enabled = isEnabled,
            contentPadding = contentPadding,
        ) {
            AppButtonContent(text = text, isLoading = isLoading, indicatorColor = MaterialTheme.colorScheme.onPrimary)
        }

        AppButtonVariant.Outlined -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.applyLoadingSemantics(loadingDescription),
            enabled = isEnabled,
            contentPadding = contentPadding,
        ) {
            AppButtonContent(text = text, isLoading = isLoading, indicatorColor = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AppButtonContent(
    text: String,
    isLoading: Boolean,
    indicatorColor: Color,
) {
    Box(contentAlignment = Alignment.Center) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .testTag("AppButtonLoadingIndicator"),
                color = indicatorColor,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text = text)
        }
    }
}

private fun Modifier.applyLoadingSemantics(description: String?): Modifier =
    if (description != null) {
        this.semantics { contentDescription = description }
    } else {
        this
    }
