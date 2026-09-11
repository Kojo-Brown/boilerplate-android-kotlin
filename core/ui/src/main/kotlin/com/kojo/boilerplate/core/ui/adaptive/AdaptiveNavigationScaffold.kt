package com.kojo.boilerplate.core.ui.adaptive

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.collections.immutable.ImmutableList

/**
 * [items] is an [ImmutableList] so this composable is skippable. It sits above every screen in
 * the app, so a recomposition it cannot skip recomposes the whole navigation surface.
 *
 * Skippable is only half of it, because skipping is not free: `ImmutableList` is in the Compose
 * compiler's known-stable set, which puts [items] on *structural* equality, and an
 * `AdaptiveNavItem` compares an `ImageVector` — the whole path tree, per icon, per item. A caller
 * that rebuilds a structurally identical list each pass pays that comparison to learn nothing.
 * `MainNavScaffold` therefore remembers its list against the two values it is built from, which
 * turns the comparison into an identity hit.
 */
@Composable
fun AdaptiveNavigationScaffold(
    items: ImmutableList<AdaptiveNavItem>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            items.forEach { navItem ->
                item(
                    selected = navItem.selected,
                    onClick = navItem.onClick,
                    label = { Text(text = navItem.label) },
                    icon = {
                        Icon(
                            imageVector = if (navItem.selected) navItem.selectedIcon else navItem.icon,
                            contentDescription = navItem.label,
                        )
                    },
                )
            }
        },
        modifier = modifier,
        content = content,
    )
}
