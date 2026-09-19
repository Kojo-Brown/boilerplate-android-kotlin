package com.kojo.boilerplate.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kojo.boilerplate.core.ui.transition.SharedElementTransition
import com.kojo.boilerplate.core.ui.udf.rememberEventSink

/**
 * @param transition the shared-element transition this screen is the destination half of, or
 *   `null` when it is not part of one. No default, for the reason `HomeScreen`'s equivalent
 *   parameter has none: it is a fact about where the screen is drawn, and `AppNavHost` is the one
 *   place that knows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateUp: () -> Unit,
    transition: SharedElementTransition?,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onEvent = rememberEventSink(viewModel)

    Scaffold(
        topBar = {
            TopAppBar(
                // A screen title is a heading, and `TopAppBar` does not say so: it draws
                // the slot with a type style and no semantics. Without this the title is
                // one more `Text` among the screen's others, and heading navigation —
                // TalkBack's swipe-by-heading, the fastest way past a screen a reader has
                // already heard — has nothing to land on.
                title = { Text("Profile", modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate up",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        ProfileContent(
            uiState = state,
            onRetry = { onEvent(ProfileUiEvent.RetryClicked) },
            modifier = Modifier.padding(innerPadding),
            transition = transition,
        )
    }
}

@Composable
internal fun ProfileContent(
    uiState: ProfileUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    transition: SharedElementTransition? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (uiState) {
            is ProfileUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is ProfileUiState.Success -> {
                ProfileSuccessContent(profile = uiState.profile, transition = transition)
            }
            is ProfileUiState.Error -> {
                ProfileErrorContent(
                    message = uiState.message,
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun ProfileSuccessContent(
    profile: ProfileData,
    transition: SharedElementTransition?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProfileIdentity(profile = profile, transition = transition)
        ProfileFieldCard(label = "Email", value = profile.email)
        ProfileFieldCard(label = "User ID", value = profile.userId)
    }
}

@Composable
private fun ProfileErrorContent(
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
