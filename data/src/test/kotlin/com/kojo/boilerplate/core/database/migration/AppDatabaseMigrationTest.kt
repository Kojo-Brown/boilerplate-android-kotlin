package com.kojo.boilerplate.core.database.migration

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.kojo.boilerplate.core.database.AppDatabase
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The migration suite.
 *
 * ## Why this is a unit test and not an instrumented one
 *
 * The canonical home for a Room migration test is `androidTest`, driven by
 * `androidx.room.testing.MigrationTestHelper`. Nothing in `androidTest` runs in this
 * repository — there is no emulator in CI, and adding one is Phase 12's own spec item — so a
 * suite written that way would be a gate that never fires, which is the failure mode Phase 0
 * exists to close. Robolectric gives `src/test` a real SQLite implementation and a real
 * `Context`, so this runs under `testDebugUnitTest`, a gate CI already enforces.
 *
 * ## What actually validates a migration
 *
 * Room does, and that is why every test opens the real [AppDatabase] rather than inspecting the
 * upgraded file directly. `RoomOpenHelper.onUpgrade` runs the migrations and then compares the
 * resulting tables against the schema *compiled into* `AppDatabase_Impl`, throwing if they
 * differ — so `openHelper.writableDatabase` returning at all is the assertion that the
 * migration lands where the entities say it should. The comparison against a fresh install in
 * [everyExportedVersionMigratesToTheCurrentSchema] is a second, independent check, and it
 * covers what Room's own validator does not look at: column defaults, and indices Room did not
 * declare.
 *
 * `@Config(sdk = …)` is pinned rather than left to Robolectric's default so that a `compileSdk`
 * bump is a deliberate change here too — SQLite's behaviour is a property of the Android
 * version, and this suite is about exactly that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppDatabaseMigrationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val schemaDirectory: File by lazy { locateSchemaDirectory() }

    // The app's own list, not a copy of it: a migration registered in `DatabaseModule` and
    // forgotten here — or the reverse — would leave the suite testing a database the app does
    // not build.
    private val migrations: List<Migration> = AppDatabase.ALL_MIGRATIONS

    @Before
    fun deleteAnyLeftoverDatabase() {
        deleteDatabaseFiles()
    }

    @After
    fun removeDatabase() {
        deleteDatabaseFiles()
    }

    @Test
    fun exportedSchemasAreContiguousAndReachTheCurrentVersion() {
        val exported = ExportedSchema.versionsIn(schemaDirectory)
        assertTrue(
            "No exported schema at all under ${schemaDirectory.absolutePath}",
            exported.isNotEmpty(),
        )
        assertEquals(
            "Exported schema versions must run 1..N with no gaps",
            (1..exported.size).toList(),
            exported,
        )
        assertEquals(
            "The newest exported schema must be the version @Database declares. Room only " +
                "writes the current one, so a mismatch means a version bump was compiled and " +
                "its schema never committed.",
            exported.last(),
            currentDatabaseVersion(),
        )
    }

    @Test
    fun everyVersionStepHasAMigration() {
        val steps = ExportedSchema.versionsIn(schemaDirectory).zipWithNext()
        val declared = migrations.map { it.startVersion to it.endVersion }.toSet()
        val missing = steps.filterNot { it in declared }
        assertTrue("No Migration declared for version step(s) $missing", missing.isEmpty())
    }

    @Test
    fun everyExportedVersionMigratesToTheCurrentSchema() {
        val current = currentDatabaseVersion()
        val fresh = freshInstallSnapshot()

        ExportedSchema.versionsIn(schemaDirectory).filter { it < current }.forEach { version ->
            deleteDatabaseFiles()
            createDatabaseAt(version)
            val migrated = withMigratedDatabase { SchemaSnapshot.of(it.openHelper.writableDatabase) }
            assertEquals(
                "Migrating from version $version does not produce what a fresh install creates",
                fresh,
                migrated,
            )
        }
    }

    @Test
    fun migratingFromVersion1KeepsRowsAndFillsTheColumnsItAdds() = runTest {
        createDatabaseAt(1) { db ->
            db.execSQL(
                "INSERT INTO users (id, displayName, email, avatarUrl) VALUES " +
                    "('u1', 'Ada', 'ada@example.com', NULL)",
            )
        }

        val stored = requireNotNull(withMigratedDatabase { it.userDao().findById("u1") }) {
            "The row written at version 1 did not survive the upgrade"
        }

        assertEquals("Ada", stored.displayName)
        // Deliberately pessimistic: a version-1 row carries no record of which server version it
        // came from, so `MIGRATION_1_2` treats it as older than anything the server can assign.
        assertEquals(0L, stored.version)
        assertEquals(emptySet<UserField>(), stored.locallyChanged)
    }

    @Test
    fun migratingFromVersion2KeepsTheConflictBookkeeping() = runTest {
        createDatabaseAt(VERSION_WITH_CONFLICT_COLUMNS) { db ->
            db.execSQL(
                "INSERT INTO users (id, displayName, email, avatarUrl, version, locallyChanged) " +
                    "VALUES ('u2', 'Grace', 'grace@example.com', NULL, $SEEDED_SERVER_VERSION, 'DISPLAY_NAME')",
            )
        }

        val stored = requireNotNull(withMigratedDatabase { it.userDao().findById("u2") })

        assertEquals(SEEDED_SERVER_VERSION, stored.version)
        assertEquals(
            "An unpushed local edit must survive the upgrade — it is what stops the next " +
                "background sync silently overwriting it",
            setOf(UserField.DISPLAY_NAME),
            stored.locallyChanged,
        )
    }

    @Test
    fun migratingFromVersion2LeavesThePagingCursorEmpty() = runTest {
        createDatabaseAt(VERSION_WITH_CONFLICT_COLUMNS)

        val cursor = withMigratedDatabase { it.userPagingDao().pageKey() }

        // "No page has been fetched yet", which is true of every database upgrading from 2 and
        // is what makes the first REFRESH start at page one rather than resuming from nothing.
        assertNull(cursor)
    }

    /** Builds the database file at [version] from the schema Room exported for it. */
    private fun createDatabaseAt(version: Int, seed: (SupportSQLiteDatabase) -> Unit = {}) {
        val schema = ExportedSchema.read(schemaDirectory, version)
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                schema.allQueries.forEach { db.execSQL(it) }
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("A database created at $version was asked to upgrade to $newVersion")
            }
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DATABASE_NAME)
            .callback(callback)
            .build()

        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            seed(helper.writableDatabase)
        }
    }

    /**
     * Opens the real database over whatever file is on disk, running the migrations — and
     * therefore Room's own post-migration schema validation — before [block] sees it, and
     * closing it afterwards so the next open starts from the file rather than from a cache.
     */
    private inline fun <T> withMigratedDatabase(block: (AppDatabase) -> T): T {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .allowMainThreadQueries()
        migrations.forEach { builder.addMigrations(it) }
        val database = builder.build()
        return try {
            database.openHelper.writableDatabase
            block(database)
        } finally {
            database.close()
        }
    }

    private fun freshInstallSnapshot(): SchemaSnapshot {
        deleteDatabaseFiles()
        return withMigratedDatabase { SchemaSnapshot.of(it.openHelper.writableDatabase) }
    }

    /** The version a fresh install creates, which is the one `@Database` declares. */
    private fun currentDatabaseVersion(): Int {
        deleteDatabaseFiles()
        return withMigratedDatabase { it.openHelper.writableDatabase.version }
    }

    private fun deleteDatabaseFiles() {
        val database = context.getDatabasePath(DATABASE_NAME)
        // The write-ahead log and its shared-memory file outlive a plain delete of the database
        // and are read back on the next open, so a leftover `-wal` would carry rows into the
        // next test — as a row that appeared from nowhere, in whichever test ran next.
        listOf(database, File("${database.path}-wal"), File("${database.path}-shm"))
            .forEach { it.delete() }
    }

    /**
     * Finds `data/schemas/<database class>`, tolerating either the module directory or the
     * repository root as the working directory: Gradle uses the former, an IDE run
     * configuration sometimes the latter.
     */
    private fun locateSchemaDirectory(): File {
        val roots = listOf(File("schemas"), File("data/schemas"))
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("No schema directory; tried ${roots.map { it.absolutePath }}")
        return root.walkTopDown()
            .firstOrNull { it.isDirectory && ExportedSchema.versionsIn(it).isNotEmpty() }
            ?: error("No exported schema JSON anywhere under ${root.absolutePath}")
    }

    companion object {
        private const val DATABASE_NAME = "migration-test.db"

        /** The version that introduced `users.version` and `users.locallyChanged`. */
        private const val VERSION_WITH_CONFLICT_COLUMNS = 2

        private const val SEEDED_SERVER_VERSION = 7L
    }
}
