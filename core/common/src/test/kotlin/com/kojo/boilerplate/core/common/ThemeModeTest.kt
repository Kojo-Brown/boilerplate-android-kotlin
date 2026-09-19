package com.kojo.boilerplate.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Six cases, because there are exactly six: three modes against the two things the platform can
 * report. Trivial to read and the reason it is here anyway — this function is the single point
 * where the colour scheme and the system bar icons are made to agree, so a regression in it is a
 * screen whose status bar disappears rather than a wrong branch somewhere.
 */
class ThemeModeTest {

    @Test
    fun `System follows the platform`() {
        assertTrue(ThemeMode.System.isDark(systemInDarkTheme = true))
        assertFalse(ThemeMode.System.isDark(systemInDarkTheme = false))
    }

    @Test
    fun `Light stays light on a device in dark mode`() {
        assertFalse(ThemeMode.Light.isDark(systemInDarkTheme = true))
        assertFalse(ThemeMode.Light.isDark(systemInDarkTheme = false))
    }

    @Test
    fun `Dark stays dark on a device in light mode`() {
        assertTrue(ThemeMode.Dark.isDark(systemInDarkTheme = true))
        assertTrue(ThemeMode.Dark.isDark(systemInDarkTheme = false))
    }

    /**
     * An explicit choice is an override, and the only mode whose answer may depend on the
     * platform is [ThemeMode.System]. Stated as a property over the whole enum so that a fourth
     * mode has to decide which side of it it is on.
     */
    @Test
    fun `only System changes its answer with the platform`() {
        val platformSensitive = ThemeMode.entries
            .filter { it.isDark(systemInDarkTheme = true) != it.isDark(systemInDarkTheme = false) }

        assertEquals(
            listOf(ThemeMode.System),
            platformSensitive,
            "A mode other than System changed its answer with the device configuration. An " +
                "explicit choice overrides the platform; if a new mode is a second kind of " +
                "deferral, say so here.",
        )
    }
}
