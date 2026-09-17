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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.kojo.boilerplate.core.ui.components.UserMonogram
import com.kojo.boilerplate.core.ui.event.ObserveAsEvents
import com.kojo.boilerplate.core.ui.transition.SharedElementKey
import com.kojo.boilerplate.core.ui.transition.SharedElementTransition
import com.kojo.boilerplate.core.ui.transition.sharedBoundsTransition
import com.kojo.boilerplate.core.ui.transition.sharedElementTransition
import com.kojo.boilerplate.core.ui.udf.rememberEventSink
import kotlinx.coroutines.launch

/**
 * @param transition the shared-element transition this screen is taking part in, or `null` when
 *   it is not taking part in one. There is no default, because which it is depends on where the
 *   screen is drawn rather than on the screen: full-width in the nav graph it is the source half
 *   of a transition into the profile, and inside [HomeTwoPaneScreen] it is not — that layout has
 *   the row and the profile on screen simultaneously, so both halves of every key would be
 *   visible at once, which has no defined behaviour. `AppNavHost` is the one place that knows
 *   which branch it is drawing and states it there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToProfile: (userId: String) -> Unit,
    onNavigateToBarcodeScanner: () -> Unit,
    onNavigateToTextRecognition: () -> Unit,
    transition: SharedElementTransition?,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // One stable reference for every callback below, so that none of them captures the view
    // model. Whether that capture costs a recomposition depends on a compiler default rather
    // than on this file — see `rememberEventSink` and `docs/recomposition.md`.
    val onEvent = rememberEventSink(viewModel)
    // The presenter for the paged stream, and the reason this screen has one at all: which pages
    // are live depends on where the reader has scrolled, so it belongs to the composition rather
    // than to the view model. `state.users` is the same `Flow` instance in every state the view
    // model emits — see `HomeUiState` — so this is remembered across recompositions and paging
    // does not restart when the search text or the offline flag changes.
    val users = state.users.collectAsLazyPagingItems()
    // Hoisted rather than left to `LazyColumn`'s default because the refresh action reads it:
    // under paging, "the users currently on screen" is a fact about the layout, and this is
    // where the layout keeps it.
    val listState = rememberLazyListState()
    // The app bar's action slot is its own recompose scope and the only thing it needs out of
    // `state` is one Boolean — while `state` itself is replaced on every keystroke, because the
    // text field is bound to `searchQuery` undebounced. Reading `state.isRefreshing` there makes
    // the slot a reader of the whole object, so typing invalidates it and it re-runs to discover
    // that the flag it renders has not moved.
    //
    // `derivedStateOf` is what narrows the dependency: a keystroke recomputes this one field
    // read, the result compares equal, and the slot is not invalidated at all. It is the only
    // place in the app where the trade pays — see `docs/derived-state.md` for the three
    // candidates that look like this one and are not.
    val isRefreshing by remember { derivedStateOf { state.isRefreshing } }
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
                        inProgress = isRefreshing,
                        // Read at the instant of the tap, which is when "what I am looking at"
                        // is defined. Reading `layoutInfo` during composition instead would
                        // subscribe this slot to every scrolled pixel.
                        onRefresh = {
                            onEvent(HomeUiEvent.RefreshClicked(listState.visibleUserIds()))
                        },
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
                    onEvent(HomeUiEvent.SearchQueryChanged(query))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HomeBody(
                users = users,
                greeting = state.greeting,
                searchQuery = state.searchQuery,
                listState = listState,
                onItemClick = { item -> onNavigateToProfile(item.id) },
                transition = transition,
            )
        }
    }
}

/**
 * The ids of the rows the reader can actually see, in view order.
 *
 * Each laid-out item carries the `key` its slot declared, so this is the list's own identity
 * read back rather than a second copy of it kept in step by hand. The `as? String` is what
 * separates the two kinds of key in this list: a user row is keyed on [HomeItem.id], and the
 * load-state footer on a [HomeListSlot] — so the footer, and a placeholder key were placeholders
 * ever enabled, drop out without a name having to be matched.
 *
 * Not a `@Composable`, and never read during composition: a `layoutInfo` read in a composition
 * subscribes it to every frame of a scroll.
 */
private fun LazyListState.visibleUserIds(): List<String> =
    layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }

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
 * The region the list fills, and the three things the *refresh* load state can say about it.
 *
 * Both full-screen branches are guarded on `itemCount == 0`, which is the whole difference
 * between this and the sealed `HomeContent` it replaced. A refresh that fails with pages already
 * cached must leave them on screen — the reader can still read them, and `docs/paging.md` makes
 * that a property of the design rather than a nicety — so an error replaces the list only when
 * there is no list to replace. The same guard is why a mediator refresh behind a populated cache
 * shows no spinner: the rows are already correct, and a spinner over them would say otherwise.
 */
@Composable
internal fun HomeBody(
    users: LazyPagingItems<HomeItem>,
    greeting: String,
    searchQuery: String,
    listState: LazyListState,
    onItemClick: (HomeItem) -> Unit,
    modifier: Modifier = Modifier,
    transition: SharedElementTransition? = null,
) {
    val refresh = users.loadState.refresh
    Box(modifier = modifier.fillMaxSize()) {
        when {
            refresh is LoadState.Loading && users.itemCount == 0 -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            refresh is LoadState.Error && users.itemCount == 0 -> {
                HomeErrorContent(
                    message = refresh.error.message ?: "Failed to load users",
                    onRetry = users::retry,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> {
                HomeUserList(
                    users = users,
                    greeting = greeting,
                    searchQuery = searchQuery,
                    listState = listState,
                    onItemClick = onItemClick,
                    transition = transition,
                )
            }
        }
    }
}

@Composable
private fun HomeUserList(
    users: LazyPagingItems<HomeItem>,
    greeting: String,
    searchQuery: String,
    listState: LazyListState,
    onItemClick: (HomeItem) -> Unit,
    transition: SharedElementTransition?,
) {
    Column {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (users.itemCount == 0) {
            EmptyUserList(searchQuery = searchQuery, modifier = Modifier.fillMaxSize())
        } else {
            // Keyed on the user id and typed by slot, which is the pair this list needs now that
            // it is not one shape any more. The key is what survives a reorder: the rows are
            // re-queried from the database on every write and sorted by display name, so under
            // the default index identity a rename would hand row 4's composition — and its
            // remembered state — to whoever moved into position 4. It is also what the refresh
            // action reads back out of `layoutInfo`.
            //
            // `contentType` was deliberately absent while the list emitted a single `items` call
            // over a single card. The load-state footer is a second shape, so the reuse pool is
            // now one a card can be handed a footer's slot table out of — which composes from
            // scratch and looks like nothing at all. `docs/lazy-lists.md` has the argument;
            // `LazyListContractTest` is what requires it of a list that emits more than one kind
            // of slot.
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = users.itemCount,
                    key = users.itemKey { it.id },
                    contentType = users.itemContentType { HomeListSlot.User },
                ) { index ->
                    // Nullable because `LazyPagingItems` hands back a placeholder for a row it
                    // has not loaded. This `Pager` disables placeholders — nothing knows how many
                    // users there are, so a placeholder list would be sized from a guess — so in
                    // practice this is never null, and the `?.let` is the type system's price for
                    // that being a configuration rather than a signature.
                    users[index]?.let { item ->
                        HomeItemCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            transition = transition,
                        )
                    }
                }

                // The footer, written inline rather than extracted to a `LazyListScope`
                // extension: `LazyListContractTest` attributes a slot to the lazy container that
                // lexically encloses it, so slots emitted from a helper would belong to no
                // container and stop being audited for keys and content types.
                when (val append = users.loadState.append) {
                    is LoadState.Loading -> {
                        item(key = HomeListSlot.Appending, contentType = HomeListSlot.Appending) {
                            AppendingFooter()
                        }
                    }
                    is LoadState.Error -> {
                        item(key = HomeListSlot.AppendFailed, contentType = HomeListSlot.AppendFailed) {
                            AppendFailedFooter(
                                message = append.error.message ?: "Could not load more users",
                                onRetry = users::retry,
                            )
                        }
                    }
                    is LoadState.NotLoading -> {
                        if (append.endOfPaginationReached) {
                            item(key = HomeListSlot.EndOfList, contentType = HomeListSlot.EndOfList) {
                                EndOfListFooter(searching = searchQuery.isNotBlank())
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The shapes this list puts in one `LazyColumn`, used as both the `key` and the `contentType` of
 * every slot that is not a user row.
 *
 * An `enum` rather than string constants for two reasons. A key is written to a `Bundle` when
 * saved item state is preserved across process death, and Compose's registry accepts a key that
 * is `Serializable`, which every enum entry is. And it is the narrower type: a typo in a string
 * key is a silent duplicate, and a duplicate key is an `IllegalArgumentException` out of the lazy
 * layout rather than a mis-render.
 *
 * [User] is declared even though the rows are keyed on their id, because the *content type* of a
 * row still has to be a value distinct from the footers'. Sharing a type with them is the exact
 * mistake `docs/lazy-lists.md` describes: the pool would offer a footer's slot table to a card.
 */
private enum class HomeListSlot {
    User,
    Appending,
    AppendFailed,
    EndOfList,
}

/**
 * Distinguishes "there are no users" from "none of the downloaded users match", because under
 * paging those are genuinely different and only one of them is the user's mistake.
 *
 * A search covers what has been cached rather than what the server holds — the endpoint takes no
 * query parameter, and `PagedUserRepositoryImpl` explains why letting a search drive remote
 * loading is a table scan rather than a search — so saying so here is the difference between a
 * reader scrolling further to find someone and a reader concluding they do not exist.
 */
@Composable
private fun EmptyUserList(searchQuery: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = if (searchQuery.isBlank()) {
                "No users yet"
            } else {
                "No downloaded users match “$searchQuery”. Clear the search to load more."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/** The next page is on its way. Sized so that arriving rows do not make the list jump. */
@Composable
private fun AppendingFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/**
 * An append failed, and this is where that is said: under the rows that did load, with a retry
 * beside it. `LazyPagingItems.retry()` re-runs the failed load only — the loaded pages, and the
 * reader's position in them, are untouched.
 */
@Composable
private fun AppendFailedFooter(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}

/**
 * Shown once there is nothing more to load. Worth a slot of its own: without it, a list that has
 * genuinely ended is indistinguishable from one whose next request is still in flight.
 *
 * [searching] changes the sentence because it changes what ended. With no search the pages come
 * from the `RemoteMediator` and the end is the server's; under a search the mediator is off, so
 * the end is the end of what has been downloaded — and "that's everyone" would be a claim about
 * the server that this screen is in no position to make.
 */
@Composable
private fun EndOfListFooter(searching: Boolean) {
    Text(
        text = if (searching) {
            "That's every downloaded user that matches."
        } else {
            "That's everyone."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    )
}

/**
 * A user row, and the source half of the transition into their profile.
 *
 * Two of the three things on this card are shared, and they take different modifiers for a reason
 * that is about what each one *is* rather than about how each one looks.
 *
 * The monogram is [UserMonogram] on both sides — one composable in `:core:ui`, drawn at 40dp here
 * and 80dp there — so the two are the same drawing at two magnifications and there is nothing to
 * cross-fade between them. That is the precondition for `sharedElement`, which lifts one node into
 * the transition's overlay and animates its rectangle.
 *
 * The name is the same string at two type scales, `titleMedium` here and `headlineSmall` on the
 * profile, so the two sides are *not* the same drawing. `sharedBounds` travels the box and
 * cross-fades the contents, which lets each side render at its own scale; `sharedElement` would
 * scale one glyph run into the other's box and show it at the wrong weight for the length of the
 * animation.
 *
 * The email is not shared at all. The profile does not draw it in a comparable place — it is a
 * labelled card down the page — so pinning the two together would drag the line across the screen
 * to land somewhere it does not belong. An element with no counterpart simply does not animate,
 * which is the right outcome and needs no code.
 */
@Composable
private fun HomeItemCard(
    item: HomeItem,
    onClick: () -> Unit,
    transition: SharedElementTransition?,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserMonogram(
                displayName = item.title,
                size = 40.dp,
                modifier = Modifier.sharedElementTransition(
                    key = SharedElementKey.UserAvatar(item.id),
                    transition = transition,
                ),
            )
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.sharedBoundsTransition(
                        key = SharedElementKey.UserName(item.id),
                        transition = transition,
                    ),
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
