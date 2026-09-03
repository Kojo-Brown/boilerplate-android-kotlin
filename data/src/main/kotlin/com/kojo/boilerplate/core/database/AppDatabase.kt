package com.kojo.boilerplate.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kojo.boilerplate.core.database.converter.UserFieldSetConverter
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.dao.UserPagingDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.database.entity.UserPageKeyEntity

@Database(
    entities = [UserEntity::class, UserPageKeyEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(UserFieldSetConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao

    abstract fun userPagingDao(): UserPagingDao

    companion object {

        /**
         * Every migration this database knows about, in one place.
         *
         * There is exactly one list because there are two callers — `DatabaseModule`, which
         * builds the database the app runs on, and `AppDatabaseMigrationTest`, which is the
         * only thing that ever executes a migration. Two lists would let the test pass while
         * the app shipped without the migration it was testing, which is the one arrangement
         * worse than having no test at all.
         *
         * `everyVersionStepHasAMigration` checks this against the exported schema versions, so
         * a `Migration` written and left unregistered fails the build rather than the upgrade.
         *
         * A `List` rather than the `Array` `addMigrations` takes as a vararg, so that neither
         * caller needs a spread operator — which copies the array at every call site and which
         * detekt's `SpreadOperator` rule flags for that reason.
         */
        val ALL_MIGRATIONS: List<Migration>
            get() = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /**
         * Adds the two columns conflict resolution needs, without touching the rows.
         *
         * Both are `NOT NULL DEFAULT`, which is what lets this be two `ALTER TABLE`s rather
         * than the create-copy-drop-rename dance SQLite otherwise requires: adding a nullable
         * column or one with a default is the one schema change SQLite does in place.
         *
         * The defaults are also the semantically correct values for rows that pre-date the
         * columns, which is why this migration writes no data. A row from version 1 was
         * written by an unconditional upsert of whatever the network last returned, so it is
         * the server's copy, unedited — `locallyChanged` empty is exactly true of it. Version
         * `0` is a claim about the past that is deliberately pessimistic: those rows carry no
         * record of which server version they came from, so they are treated as older than
         * anything the server can now assign, and the first fetch after upgrading overwrites
         * them. That costs one redundant write per row and is the only reading that cannot
         * lose a change.
         *
         * The defaults must stay in step with the `@ColumnInfo(defaultValue = …)` on
         * `UserEntity`: Room compares the schema it generates against the one the database
         * actually has when it opens, and a default declared in only one of the two places
         * fails that comparison at runtime rather than at build time.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN version INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE users ADD COLUMN locallyChanged TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Adds the paged list's cursor table. No data, and none needed: an empty
         * `user_page_keys` is exactly "no page has been fetched yet", which is true of every
         * database upgrading from version 2, and the first `REFRESH` writes the row.
         *
         * The `users` table is untouched on purpose. Rows already cached by `syncUser` or
         * `syncCurrentUser` are the same rows the paged list serves — Room is the single source
         * of truth for both — so an upgrade neither loses them nor has to re-fetch them; the
         * mediator fills in around them.
         *
         * The column list has to match what Room generates for `UserPageKeyEntity` exactly,
         * down to nullability: Room compares its own idea of the schema against the database it
         * opens and throws on any difference. `id` is `INTEGER NOT NULL` because the property
         * is a non-null `Int`; `nextPage` is a bare `INTEGER` because it is `Int?`, and that
         * nullability is load-bearing — it is what tells the mediator the server has run out of
         * pages.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_page_keys` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`nextPage` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        /**
         * Adds the idempotency key that names a row's unsent edit, and — unlike the two
         * migrations above — writes data, because leaving these rows alone would be wrong.
         *
         * ## Why the backfill is not optional
         *
         * `UserEntity.pendingChangeKey` is non-null exactly when `locallyChanged` is non-empty,
         * and a version-3 database can hold rows that are already dirty: `saveUser` has marked
         * fields since version 2 and nothing has ever pushed them. Adding a bare NULL column
         * would create rows breaking that invariant on the first launch after an upgrade, and
         * the push would have to choose between skipping them — an edit stranded forever — and
         * minting a key at send time, which is the one thing a key must never be, because a
         * key minted per attempt names a different mutation on every retry.
         *
         * ## Why any key is the right key for them
         *
         * These edits have never been sent. There was no code that could send them, so no
         * server has seen a mutation under any name, and there is nothing for a key to
         * collide with or to have to match. The first name assigned is therefore free, and
         * assigning it here — once, at upgrade — is what makes it stable across the retries
         * that follow.
         *
         * `hex(randomblob(16))` is SQLite's own 128 random bits, the same width as the UUID
         * `UuidIdempotencyKeyGenerator` produces at runtime. It is deliberately *not* derived
         * from the row (`'migrated-' || id` and the like): a device that upgrades, pushes, is
         * restored from a backup taken before the upgrade, and upgrades again would mint the
         * same key for what is by then a different edit, and the server would recognise the
         * name and drop the change.
         *
         * The `WHERE` matters as much as the `SET`. Applying a key to every row would give
         * clean rows one too, and `findPendingChanges` selects on `locallyChanged` rather than
         * on the key — so nothing would break loudly, and the invariant the rest of the code
         * reasons from would simply be false.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN pendingChangeKey TEXT")
                db.execSQL(
                    "UPDATE users SET pendingChangeKey = lower(hex(randomblob(16))) " +
                        "WHERE locallyChanged != ''",
                )
            }
        }
    }
}
