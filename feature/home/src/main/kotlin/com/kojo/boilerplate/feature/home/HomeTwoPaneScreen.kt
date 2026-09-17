package com.kojo.boilerplate.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kojo.boilerplate.core.ui.transition.predictiveBackDismiss
import com.kojo.boilerplate.core.ui.transition.rememberPredictiveBackDismiss

/**
 * The list and a profile side by side, and the one screen in this app where back is not a
 * navigation.
 *
 * ### Why back needs handling here at all
 *
 * Both panes live inside a single `composable<Home>` entry, so selecting a user does not push
 * anything: [selectedUserId] is state. `NavHost` therefore has nothing to pop, and back with a
 * profile on screen leaves Home altogether — past the screen the user was reading, in one step.
 * Clearing the selection first is what makes back mean what it means everywhere else, and it is
 * what the Material list-detail pattern does.
 *
 * `rememberPredictiveBackDismiss` rather than a `BackHandler` because a `BackHandler` would take
 * the gesture and give nothing back: no preview of where back leads, and no way to abandon the
 * gesture once started. Its KDoc has the argument; the visible difference is that the pane here
 * shrinks under the finger and springs back if the user changes their mind.
 *
 * ### Why there are no shared elements in this layout
 *
 * `HomeScreen` is given `transition = null` below, and that is not an omission. A shared element
 * is one key with a half on each side of a transition, and in this layout the row and the profile
 * are on screen *at the same time* — so every key would have both halves visible at once inside
 * one `SharedTransitionScope`, which has no defined winner. There is also nothing to animate:
 * neither pane is arriving or leaving. `docs/shared-elements.md` records this as the reason the
 * transition is a parameter rather than an ambient — with a `CompositionLocal` this layout would
 * have inherited it silently.
 *
 * @param detailPane what fills the right-hand pane for the selected user. A slot rather than a
 *   direct call to `ProfileDetailPane`, because this module must not depend on
 *   `:feature:profile`: features are siblings, and one importing another is how a feature graph
 *   turns back into a single module that happens to have directories. The navigation layer owns
 *   which screens exist and is the only place that may know about both, so it supplies this —
 *   see `AppNavHost`. It also makes the layout previewable and testable on its own, with any
 *   composable at all on the right.
 */
@Composable
fun HomeTwoPaneScreen(
    selectedUserId: String?,
    onUserSelected: (String?) -> Unit,
    onNavigateToBarcodeScanner: () -> Unit,
    onNavigateToTextRecognition: () -> Unit,
    detailPane: @Composable (userId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Enabled only while there is a selection to clear. With the panes empty the gesture goes
    // straight back to the system, which is what keeps the back-to-home animation intact.
    val backDismiss = rememberPredictiveBackDismiss(
        enabled = selectedUserId != null,
        onDismissRequest = { onUserSelected(null) },
    )

    Row(modifier = modifier.fillMaxSize()) {
        HomeScreen(
            onNavigateToProfile = { userId -> onUserSelected(userId) },
            onNavigateToBarcodeScanner = onNavigateToBarcodeScanner,
            onNavigateToTextRecognition = onNavigateToTextRecognition,
            transition = null,
            modifier = Modifier.weight(0.4f),
        )

        VerticalDivider(modifier = Modifier.fillMaxHeight())

        Box(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight(),
        ) {
            if (selectedUserId != null) {
                // The gesture transform goes on a wrapper inside this branch rather than on the
                // `Box` above, so the empty state is not scaled along with it: what the user is
                // dismissing is the profile, and what is revealed is the placeholder at full
                // size. The wrapper is also what keeps `detailPane` a plain slot — a caller
                // supplying a detail pane should not have to know it might be animated.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .predictiveBackDismiss(backDismiss),
                ) {
                    detailPane(selectedUserId)
                }
            } else {
                HomeTwoPaneEmptyDetail(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun HomeTwoPaneEmptyDetail(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.PersonSearch,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Select a user to view their profile",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
