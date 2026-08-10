package com.kojo.boilerplate.core.ui.adaptive

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One destination in [AdaptiveNavigationScaffold]'s navigation surface.
 *
 * Lives in its own file because it is the only class-like declaration that was in
 * AdaptiveNavigationScaffold.kt, which detekt's MatchingDeclarationName flags — a file
 * holding a single type should be named after it.
 *
 * [onClick] does not cost the class its stability: the Compose compiler treats function
 * types as stable and memoizes the lambda at the call site. What it does not do is inspect
 * what the lambda *captures*, so a nav item built inside a composable from an unstable
 * value is still an unstable input in practice — see `docs/immutability.md`.
 */
@Immutable
data class AdaptiveNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val selected: Boolean,
    val onClick: () -> Unit,
)
