package com.kojo.boilerplate.core.common

/**
 * What the reader asked for, which is not the same thing as what is drawn: [System] is a
 * *deferral*, and the value it defers to is only known where the configuration is.
 */
enum class ThemeMode {
    System,
    Light,
    Dark;

    /**
     * Whether the dark palette applies, given what the platform currently reports.
     *
     * ### Why this is a function on the enum rather than a `when` at each call site
     *
     * There are two of them, and they have to agree. The Compose theme picks a colour scheme from
     * it, and `MainActivity` picks the *system bar icon* colour from it — light glyphs over a dark
     * app, dark glyphs over a light one. Those are the two halves of one screen, and the second
     * used to be decided by `enableEdgeToEdge()`'s default, which reads the system's night mode
     * and knows nothing about [ThemeMode]. Choosing [Light] on a device in dark mode therefore put
     * white status-bar icons on a white app bar: invisible, on the one screen a user in that
     * configuration sees first.
     *
     * A shared `when` is the fix, and the enum is where it can live without either module
     * depending on the other — `:core:common` is the module everything may depend on.
     *
     * @param systemInDarkTheme what the platform reports for the current configuration:
     *   `isSystemInDarkTheme()` in a composition, `Configuration.UI_MODE_NIGHT_YES` outside one.
     *   Passed in rather than read here because this module carries no Android dependency and
     *   because a pure function of its inputs is the part worth testing.
     */
    fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        System -> systemInDarkTheme
        Light -> false
        Dark -> true
    }
}
