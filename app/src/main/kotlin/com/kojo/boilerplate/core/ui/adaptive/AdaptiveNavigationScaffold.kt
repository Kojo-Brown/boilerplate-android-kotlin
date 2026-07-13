package com.kojo.boilerplate.core.ui.adaptive

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

data class AdaptiveNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
fun AdaptiveNavigationScaffold(
    items: List<AdaptiveNavItem>,
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
