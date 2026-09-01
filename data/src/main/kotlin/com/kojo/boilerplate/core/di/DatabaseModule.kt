package com.kojo.boilerplate.core.di

import android.content.Context
import androidx.room.Room
import com.kojo.boilerplate.core.database.AppDatabase
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.dao.UserPagingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Migrations are declared and no fallback is. `fallbackToDestructiveMigration()` is the
     * one line that would make a missing migration invisible: the database is dropped and
     * rebuilt, the app starts, and every row the user had is gone — including, here, the local
     * edits the conflict resolver exists to protect. Without it a schema change that nobody
     * wrote a migration for throws on open, which is a crash on the developer's machine
     * instead of data loss on a user's.
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        val builder = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "boilerplate.db",
        )
        // `AppDatabase.ALL_MIGRATIONS` rather than the two constants written out here: it is the
        // same list `AppDatabaseMigrationTest` runs, and the point of there being one list is
        // that the app cannot ship without a migration the suite proved works.
        AppDatabase.ALL_MIGRATIONS.forEach { builder.addMigrations(it) }
        return builder.build()
    }

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()

    @Provides
    fun provideUserPagingDao(db: AppDatabase): UserPagingDao = db.userPagingDao()
}
