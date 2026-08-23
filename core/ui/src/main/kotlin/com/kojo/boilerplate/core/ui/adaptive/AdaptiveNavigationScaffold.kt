package com.kojo.boilerplate.core.ui.adaptive

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.collections.immutable.ImmutableList

/**
 * [items] is an [ImmutableList] so this composable is skippable. It sits above every screen
 * in the app, so a recomposition it cannot skip recomposes the whole navigation surface —
 * and its caller rebuilds the item list on each pass to re-evaluate `selected`.
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
