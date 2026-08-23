package com.kojo.boilerplate.core.di

import android.content.Context
import androidx.room.Room
import com.kojo.boilerplate.core.database.AppDatabase
import com.kojo.boilerplate.core.database.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "boilerplate.db",
        ).build()

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()
}
