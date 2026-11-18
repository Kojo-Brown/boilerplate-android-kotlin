package com.kojo.boilerplate.testing

import android.content.Context
import androidx.room.Room
import com.kojo.boilerplate.core.database.AppDatabase
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.dao.UserPagingDao
import com.kojo.boilerplate.core.di.DatabaseModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces [DatabaseModule] with a Room database that lives in memory and dies with the test.
 *
 * ## Why the fake is a real database
 *
 * What makes the production binding unusable in a test is the *file*: `boilerplate.db` survives
 * the test that wrote it, so the order the suite happens to run in decides what each test sees,
 * and a failure is reproducible only after an uninstall. Nothing about the SQL is wrong. So this
 * replaces the file and keeps everything else — the same `@Database`, the same entities, the same
 * `UserFieldSetConverter`, the same generated DAO implementations running the same statements.
 *
 * A hand-written fake `UserDao` would be the other option and is the wrong one here, for a
 * reason the DAOs state about themselves: `upsertResolving` and `commitPage` are `@Transaction`
 * methods whose whole contribution is atomicity, and a fake backed by a `MutableMap` has no
 * transaction to serialise — it accepts every interleaving, including the one the method exists
 * to rule out. `:data`'s unit tests already
 * use `FakeUserDao` where the subject is the caller's logic; this is the configuration in which
 * the subject is the caller *plus* the statement it issues. The repository keeps both.
 *
 * ## `allowMainThreadQueries` is deliberately absent
 *
 * It is the first line people add to a Room test and it would hide a real regression here. Every
 * method on [UserDao] and [UserPagingDao] is `suspend` or returns a `Flow`, so Room already runs
 * every query off the main thread; a blocking DAO method added later would throw on a device, and
 * allowing main-thread queries in the test rig is exactly how it would stop throwing in the one
 * place that could have caught it.
 *
 * ## No migrations, and that is not a gap
 *
 * `DatabaseModule` registers `AppDatabase.ALL_MIGRATIONS` because the app opens a database that
 * may have been written by an older version. An in-memory database is created at the current
 * version every time, so a migration could not run even if it were registered. Migrations are
 * executed by `AppDatabaseMigrationTest`, against `MigrationTestHelper` and the exported schemas,
 * which is the only thing in this repository that ever runs one.
 *
 * ## Lifetime
 *
 * `@Singleton` here is a *test's* singleton: Hilt builds a fresh component for each test method,
 * so each gets its own empty database. `tearDown` in the suite closes it, which is what keeps the
 * number of open in-memory databases from tracking the number of test methods.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {

    @Provides
    @Singleton
    fun provideInMemoryDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()

    @Provides
    fun provideUserPagingDao(db: AppDatabase): UserPagingDao = db.userPagingDao()
}
