package com.kojo.boilerplate.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kojo.boilerplate.ui.theme.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class ThemePreferencesRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val tempDir: File = Files.createTempDirectory("theme_datastore_test").toFile()

    private fun createDataStore() = PreferenceDataStoreFactory.create(
        scope = testScope,
        produceFile = { File(tempDir, "test_theme_preferences.preferences_pb") },
    )

    private lateinit var repository: ThemePreferencesRepository

    @Before
    fun setUp() {
        repository = ThemePreferencesRepository(dataStore = createDataStore())
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `initial theme mode defaults to System`() = testScope.runTest {
        val mode = repository.themeMode.first()
        assertEquals(ThemeMode.System, mode)
    }

    @Test
    fun `setThemeMode to Light persists Light`() = testScope.runTest {
        repository.setThemeMode(ThemeMode.Light)
        val mode = repository.themeMode.first()
        assertEquals(ThemeMode.Light, mode)
    }

    @Test
    fun `setThemeMode to Dark persists Dark`() = testScope.runTest {
        repository.setThemeMode(ThemeMode.Dark)
        val mode = repository.themeMode.first()
        assertEquals(ThemeMode.Dark, mode)
    }

    @Test
    fun `setThemeMode to System persists System`() = testScope.runTest {
        repository.setThemeMode(ThemeMode.Dark)
        repository.setThemeMode(ThemeMode.System)
        val mode = repository.themeMode.first()
        assertEquals(ThemeMode.System, mode)
    }

    @Test
    fun `setThemeMode overwrites previous selection`() = testScope.runTest {
        repository.setThemeMode(ThemeMode.Light)
        repository.setThemeMode(ThemeMode.Dark)
        val mode = repository.themeMode.first()
        assertEquals(ThemeMode.Dark, mode)
    }
}
