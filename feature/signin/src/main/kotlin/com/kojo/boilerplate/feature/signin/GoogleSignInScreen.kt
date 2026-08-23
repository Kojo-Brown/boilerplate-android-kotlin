package com.kojo.boilerplate.feature.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kojo.boilerplate.core.auth.GoogleUser
import com.kojo.boilerplate.core.ui.event.ObserveAsEvents
import kotlinx.coroutines.launch

@Composable
fun GoogleSignInScreen(
    onSignedIn: (GoogleUser) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GoogleSignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Navigation and the snackbar are driven by the effect stream, not by the state. Keyed on
    // a state, a LaunchedEffect runs again every time the composition is rebuilt — which is
    // once per rotation — so the same sign-in navigated twice and the same failure was
    // announced once per configuration change.
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            is GoogleSignInUiEffect.SignedIn -> onSignedIn(effect.user)
            // Launched rather than awaited: the handler returns immediately so a second effect
            // is not held up behind a snackbar, and SnackbarHostState queues the messages.
            is GoogleSignInUiEffect.SignInFailed -> scope.launch {
                snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = state) {
                is GoogleSignInUiState.Loading -> {
                    CircularProgressIndicator()
                }

                is GoogleSignInUiState.Success -> {
                    SignedInContent(
                        user = current.user,
                        onSignOut = {
                            viewModel.onEvent(GoogleSignInUiEvent.SignOutClicked)
                        },
                    )
                }

                is GoogleSignInUiState.Idle -> {
                    SignInContent(
                        onSignInClick = {
                            viewModel.onEvent(GoogleSignInUiEvent.SignInClicked(context))
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SignInContent(
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Welcome",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Sign in with your Google account to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onSignInClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign in with Google")
        }
    }
}

@Composable
internal fun SignedInContent(
    user: GoogleUser,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Signed in as",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = user.displayName.ifEmpty { user.email },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = user.email,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign out")
        }
    }
}
