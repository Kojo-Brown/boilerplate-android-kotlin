package com.kojo.boilerplate.core.ui.components

/**
 * Visual treatment for [AppButton].
 *
 * Lives in its own file because it is the only class-like declaration that was in
 * AppButton.kt, which detekt's MatchingDeclarationName flags — a file holding a single
 * type should be named after it.
 */
enum class AppButtonVariant { Primary, Outlined }
