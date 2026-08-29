package com.kojo.boilerplate.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kojo.boilerplate.core.database.converter.UserFieldSetConverter
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.UserEntity

@Database(
    entities = [UserEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(UserFieldSetConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao

    companion object {

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
    }
}
