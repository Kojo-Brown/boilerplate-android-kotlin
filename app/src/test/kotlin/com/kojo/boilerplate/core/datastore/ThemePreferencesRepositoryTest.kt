package com.kojo.boilerplate.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kojo.boilerplate.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Like [DataStoreTokenProviderTest], this drives a real DataStore on real dispatchers.
 *
 * The previous version built the DataStore on the same TestScope it ran `runTest` on.
 * DataStore keeps a long-lived actor coroutine alive in whatever scope it is given, and
 * `runTest` will not finish while a coroutine in its scope is still running — so every
 * case here died with `UncompletedCoroutinesError` after the timeout, having asserted
 * nothing about theme persistence at all.
 *
 * Giving the DataStore its own scope, cancelled in tearDown, removes the conflict.
 * Nothing needs nudging for determinism: `setThemeMode` suspends until `DataStore.edit`
 * has committed, so the read that follows always observes the write.
 */
class ThemePreferencesRepositoryTest {

    private val tempDir: File = Files.createTempDirectory("theme_datastore_test").toFile()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var repository: ThemePreferencesRepository

    @Before
    fun setUp() {
        repository = ThemePreferencesRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(tempDir, "test_theme_preferences.preferences_pb") },
            ),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `initial theme mode defaults to System`() = runBlocking {
        assertEquals(ThemeMode.System, readThemeMode())
    }

    @Test
    fun `setThemeMode to Light persists Light`() = runBlocking {
        repository.setThemeMode(ThemeMode.Light)
        assertEquals(ThemeMode.Light, readThemeMode())
    }

    @Test
    fun `setThemeMode to Dark persists Dark`() = runBlocking {
        repository.setThemeMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, readThemeMode())
    }

    @Test
    fun `setThemeMode to System persists System`() = runBlocking {
        repository.setThemeMode(ThemeMode.Dark)
        repository.setThemeMode(ThemeMode.System)
        assertEquals(ThemeMode.System, readThemeMode())
    }

    @Test
    fun `setThemeMode overwrites previous selection`() = runBlocking {
        repository.setThemeMode(ThemeMode.Light)
        repository.setThemeMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, readThemeMode())
    }

    /** Bounded so a stuck DataStore fails this test rather than hanging the whole task. */
    private suspend fun readThemeMode(): ThemeMode =
        withTimeout(TIMEOUT_MS) { repository.themeMode.first() }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
