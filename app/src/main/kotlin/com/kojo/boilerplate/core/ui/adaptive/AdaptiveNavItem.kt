package com.kojo.boilerplate.core.ui.adaptive

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One destination in [AdaptiveNavigationScaffold]'s navigation surface.
 *
 * Lives in its own file because it is the only class-like declaration that was in
 * AdaptiveNavigationScaffold.kt, which detekt's MatchingDeclarationName flags — a file
 * holding a single type should be named after it.
 */
data class AdaptiveNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val selected: Boolean,
    val onClick: () -> Unit,
)
