package com.kojo.boilerplate.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.entity.UserEntity

@Database(
    entities = [UserEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
}
