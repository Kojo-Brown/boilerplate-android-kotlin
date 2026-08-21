package com.kojo.boilerplate.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kojo.boilerplate.core.ui.event.ObserveAsEvents
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToProfile: (userId: String) -> Unit,
    onNavigateToBarcodeScanner: () -> Unit,
    onNavigateToTextRecognition: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The three navigation callbacks are parameters and not view model events on purpose. A
    // tap that only ever means "go there" is not a decision anything below the composable
    // makes, and routing it through the view model would add a handler that can only forward.
    // It is also what lets `HomeTwoPaneScreen` reuse this screen with the same tap selecting a
    // pane instead of navigating. See `UiEffect` for where the line is.
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            // Launched rather than awaited: the handler returns immediately so a second
            // effect is not held up behind a snackbar, and SnackbarHostState queues them.
            is HomeUiEffect.RefreshIncomplete -> scope.launch {
                snackbarHostState.showSnackbar(
                    refreshFailureMessage(
                        refreshed = effect.refreshed,
                        failed = effect.failed,
                    ),
                )
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Home") },
                actions = {
                    RefreshAction(
                        inProgress = state.isRefreshing,
                        onRefresh = { viewModel.onEvent(HomeUiEvent.RefreshClicked) },
                    )
                },
            )
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = onNavigateToTextRecognition) {
                    Icon(
                        imageVector = Icons.Default.DocumentScanner,
                        contentDescription = "Recognize text",
                        modifier = Modifier.size(24.dp),
                    )
                }
                FloatingActionButton(onClick = onNavigateToBarcodeScanner) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "Scan barcode",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (state.isOffline) {
                OfflineBanner(modifier = Modifier.fillMaxWidth())
            }
            SearchBar(
                query = state.searchQuery,
                onQueryChange = { query ->
                    viewModel.onEvent(HomeUiEvent.SearchQueryChanged(query))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HomeBody(
                content = state.content,
                onRetry = { viewModel.onEvent(HomeUiEvent.RetryClicked) },
                onItemClick = { item -> onNavigateToProfile(item.id) },
            )
        }
    }
}

/**
 * The refresh control, which is a spinner while the fan-out is in flight and a button
 * otherwise.
 *
 * Swapping the button out rather than disabling it in place is what stops a second tap from
 * queueing behind the first — the view model rejects one anyway, but a button that looks
 * pressable and does nothing reads as a bug. The spinner keeps the app bar's slot width, so
 * the transition does not shift the title.
 */
@Composable
internal fun RefreshAction(
    inProgress: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (inProgress) {
        Box(
            // The IconButton's own minimum touch target, so swapping the two does not
            // resize the app bar's action slot mid-refresh.
            modifier = modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
    } else {
        IconButton(onClick = onRefresh, modifier = modifier) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh users",
            )
        }
    }
}

/**
 * Says which users are stale, not how many requests failed.
 *
 * "2 of 10 failed" describes the fan-out; the user is looking at a list and wants to know
 * how much of it to trust. The complete-failure case gets its own sentence because "0 users
 * updated" reads as a successful no-op rather than as a failure.
 *
 * A clean refresh produces no message at all, and that decision is the view model's — it does
 * not raise [HomeUiEffect.RefreshIncomplete] when nothing failed. The rows that changed are
 * already visible, and "everything worked" is a message the user has to dismiss to get their
 * screen back.
 */
private fun refreshFailureMessage(refreshed: Int, failed: Int): String = when (refreshed) {
    0 -> "Could not refresh. Showing the last data loaded."
    else -> "Refreshed $refreshed of ${refreshed + failed} users. The rest may be out of date."
}

/**
 * Shown while `HomeUiState.isOffline` is true, above the content rather than in place of it:
 * the list already loaded is still worth reading, it is just no longer guaranteed current.
 */
@Composable
internal fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = "You are offline. Showing the last data loaded.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Search users…") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                    )
                }
            }
        },
        singleLine = true,
    )
}

/**
 * The part of the screen that the mutually-exclusive [HomeContent] governs. Named for the
 * region it fills rather than for the type it renders, so it does not read as a constructor
 * call for the sealed interface it takes.
 */
@Composable
internal fun HomeBody(
    content: HomeContent,
    onRetry: () -> Unit,
    onItemClick: (HomeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (content) {
            is HomeContent.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is HomeContent.Users -> {
                HomeUserList(users = content, onItemClick = onItemClick)
            }
            is HomeContent.Error -> {
                HomeErrorContent(
                    message = content.message,
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun HomeUserList(
    users: HomeContent.Users,
    onItemClick: (HomeItem) -> Unit,
) {
    Column {
        Text(
            text = users.greeting,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (users.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No results found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(users.items, key = { it.id }) { item ->
                    HomeItemCard(item = item, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

@Composable
private fun HomeItemCard(
    item: HomeItem,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun HomeErrorContent(
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
            text = "Something went wrong",
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
