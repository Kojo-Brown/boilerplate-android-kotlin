package com.kojo.boilerplate.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kojo.boilerplate.core.ui.udf.rememberEventSink

@Composable
fun ProfileDetailPane(
    userId: String,
    modifier: Modifier = Modifier,
    viewModel: ProfileDetailPaneViewModel =
        hiltViewModel<ProfileDetailPaneViewModel, ProfileDetailPaneViewModel.Factory>(
            key = userId,
            creationCallback = { factory -> factory.create(userId) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onEvent = rememberEventSink(viewModel)

    Box(modifier = modifier.fillMaxSize()) {
        // `when (val current = state)` rather than `when (state)`: the delegated property is
        // read through a getter, so smart-casting it is not possible and each branch would
        // otherwise have to cast the value it has already matched on.
        when (val current = state) {
            is ProfileUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is ProfileUiState.Success -> {
                ProfileDetailSuccessContent(profile = current.profile)
            }
            is ProfileUiState.Error -> {
                ProfileDetailErrorContent(
                    message = current.message,
                    onRetry = { onEvent(ProfileUiEvent.RetryClicked) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * `transition = null` on [ProfileIdentity], and that is the layout talking rather than an
 * omission: this pane is drawn *beside* the list it was selected from, so the row and this block
 * are visible at the same time and cannot be two halves of one transition. `HomeTwoPaneScreen`
 * has the argument.
 */
@Composable
private fun ProfileDetailSuccessContent(profile: ProfileData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProfileIdentity(profile = profile, transition = null)
        ProfileFieldCard(label = "Email", value = profile.email)
        ProfileFieldCard(label = "User ID", value = profile.userId)
    }
}

@Composable
private fun ProfileDetailErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Failed to load profile",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}
