package com.kojo.boilerplate.core.datastore

import androidx.datastore.core.DataStoreFactory
import com.kojo.boilerplate.core.common.preferences.SyncPolicy
import com.kojo.boilerplate.core.common.preferences.SyncPreferences
import com.kojo.boilerplate.core.common.preferences.UserPreferences
import com.kojo.boilerplate.core.datastore.proto.SyncPreferencesProto
import com.kojo.boilerplate.core.datastore.proto.UserPreferencesProto
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The data source over a real `DataStore` writing a real file, for the reason
 * [ThemePreferencesRepositoryTest] gives: the parts worth testing here — serialisation, the
 * corruption handler, and `updateData` serialising two writes — are DataStore's, and a fake
 * would be a reimplementation of them asserting against itself.
 *
 * Same scope discipline as that test, and for the same reason: DataStore keeps an actor
 * coroutine alive in the scope it is handed, and `runTest` refuses to finish while one is
 * running. The store gets its own scope, cancelled in [tearDown], and the cases use
 * `runBlocking` — every write here suspends until it has committed, so a read that follows one
 * always observes it and nothing needs a delay.
 *
 * One store per case, over a fresh directory. DataStore takes an exclusive lock per file and
 * throws on a second instance for the same path, which is also what the delegate in
 * `UserPreferencesDataStore.kt` exists to prevent in the app.
 */
class UserPreferencesDataSourceTest {

    private val tempDir: File = Files.createTempDirectory("user_preferences_test").toFile()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterEach
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `a device with no file reads the app's defaults, not the schema's zeroes`() = runBlocking {
        assertEquals(UserPreferences(), read(dataSource()))
    }

    @Test
    fun `setSyncPreferences writes every field of the nested message`() = runBlocking {
        val dataSource = dataSource()
        val sync = SyncPreferences(
            policy = SyncPolicy.AnyNetwork,
            onlyWhileCharging = true,
            lastSuccessfulSync = Instant.ofEpochMilli(SYNCED_AT_MILLIS),
        )

        dataSource.setSyncPreferences(sync)

        assertEquals(sync, read(dataSource).sync)
    }

    /**
     * The reason [UserPreferencesDataSource.recordSuccessfulSync] does its read inside
     * `updateData` instead of copying a value the caller observed earlier. Here the policy
     * change is the edit that would be lost, and it is lost silently: the write succeeds and
     * the setting goes back to what it was before the user touched it.
     */
    @Test
    fun `recordSuccessfulSync leaves the rest of the sync block alone`() = runBlocking {
        val dataSource = dataSource()
        dataSource.setSyncPreferences(
            SyncPreferences(policy = SyncPolicy.AnyNetwork, onlyWhileCharging = true),
        )

        dataSource.recordSuccessfulSync(Instant.ofEpochMilli(SYNCED_AT_MILLIS))

        assertEquals(
            SyncPreferences(
                policy = SyncPolicy.AnyNetwork,
                onlyWhileCharging = true,
                lastSuccessfulSync = Instant.ofEpochMilli(SYNCED_AT_MILLIS),
            ),
            read(dataSource).sync,
        )
    }

    @Test
    fun `markOnboardingComplete persists and leaves the sync block alone`() = runBlocking {
        val dataSource = dataSource()
        dataSource.setSyncPreferences(SyncPreferences(policy = SyncPolicy.AnyNetwork))

        dataSource.markOnboardingComplete()

        val preferences = read(dataSource)
        assertTrue(preferences.onboardingComplete)
        assertEquals(SyncPolicy.AnyNetwork, preferences.sync.policy)
    }

    /**
     * The schema's `0` for "never synced" has to arrive as an absence, because `0` is also a
     * real instant. Written through the public setter rather than by hand so that the round
     * trip is the one the app performs.
     */
    @Test
    fun `an epoch-zero timestamp reads back as never synced`() = runBlocking {
        val dataSource = dataSource()

        dataSource.recordSuccessfulSync(Instant.EPOCH)

        assertNull(read(dataSource).sync.lastSuccessfulSync)
    }

    /**
     * A policy constant this build does not know — written by a newer build on the same device,
     * which a downgrade or a beta channel makes ordinary rather than exotic. Protobuf hands it
     * over as `UNRECOGNIZED`, and the mapping has to resolve it rather than fail on it.
     */
    @Test
    fun `a policy from a newer schema reads as the default`() = runBlocking {
        val file = preferencesFile()
        file.parentFile.mkdirs()
        UserPreferencesProto.newBuilder()
            .setSync(SyncPreferencesProto.newBuilder().setPolicyValue(UNKNOWN_POLICY_NUMBER))
            .setOnboardingComplete(true)
            .build()
            .let { file.writeBytes(it.toByteArray()) }

        val preferences = read(dataSource())

        assertEquals(SyncPolicy.Default, preferences.sync.policy)
        // The rest of the file still decodes: an unknown enum constant is one unreadable field,
        // not an unreadable message, and treating it as corruption would throw the file away.
        assertTrue(preferences.onboardingComplete)
    }

    /**
     * The corruption handler the app installs, exercised over the app's serializer. A store
     * built without one would fail this by throwing, which is the whole point of asserting it
     * here rather than trusting that the argument was passed.
     */
    @Test
    fun `an unparseable file is replaced with the defaults`() = runBlocking {
        val file = preferencesFile()
        file.parentFile.mkdirs()
        file.writeBytes(TRUNCATED_VARINT)

        assertEquals(UserPreferences(), read(dataSource()))
    }

    private fun preferencesFile(): File = File(tempDir, "user_preferences.pb")

    private fun dataSource(): UserPreferencesDataSource = UserPreferencesDataSource(
        DataStoreFactory.create(
            serializer = UserPreferencesSerializer,
            corruptionHandler = userPreferencesCorruptionHandler,
            scope = scope,
            produceFile = ::preferencesFile,
        ),
    )

    /** Bounded so a stuck DataStore fails this test rather than hanging the whole task. */
    private suspend fun read(dataSource: UserPreferencesDataSource): UserPreferences =
        withTimeout(TIMEOUT_MS) { dataSource.preferences.first() }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SYNCED_AT_MILLIS = 1_730_000_000_000L

        /** Higher than any constant the schema declares, and it is meant to stay that way. */
        const val UNKNOWN_POLICY_NUMBER = 99

        /** See `UserPreferencesSerializerTest`: a tag followed by a varint that never ends. */
        val TRUNCATED_VARINT = byteArrayOf(0x08, 0xFF.toByte())
    }
}
